package com.vcampus.server.database;

import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.SecondhandReviewStore.PendingImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecondhandReviewRepositoryTest {
    private ConnectionFactory connections;
    private SecondhandReviewRepository reviews;

    @BeforeEach
    void setUp() throws Exception {
        connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        createSchema();
        reviews = new SecondhandReviewRepository(connections);
    }

    @Test
    void onlyCompletedOrderBuyerCanReviewAndOrderRemainsUniqueAfterDelete() throws Exception {
        assertThrows(SecondhandRuleException.class, () -> reviews.create(
                3L, UUID.randomUUID().toString(), 10L, 5, "物品与描述一致", List.of()));
        execute("UPDATE secondhand_orders SET status='SHIPPED' WHERE id=10");
        assertThrows(SecondhandRuleException.class, () -> reviews.create(
                2L, UUID.randomUUID().toString(), 10L, 5, "物品与描述一致", List.of()));
        execute("UPDATE secondhand_orders SET status='COMPLETED' WHERE id=10");

        var created = reviews.create(2L, UUID.randomUUID().toString(), 10L,
                5, "物品与描述一致", List.of());
        assertTrue(reviews.delete(2L, created.review().id()));
        assertThrows(SecondhandRuleException.class, () -> reviews.create(
                2L, UUID.randomUUID().toString(), 10L, 4, "再次提交评价内容", List.of()));
    }

    @Test
    void summaryExcludesHiddenAndDeletedAndModerationIsAudited() throws Exception {
        var first = reviews.create(2L, UUID.randomUUID().toString(), 10L,
                5, "物品与描述一致", List.of());
        assertEquals(1, reviews.summary(1L).count());
        assertEquals("5.0", reviews.summary(1L).average().toPlainString());

        assertTrue(reviews.moderate(9L, first.review().id(), true, "评价包含违规内容"));
        assertEquals(0, reviews.summary(1L).count());
        assertTrue(reviews.moderate(9L, first.review().id(), false, "复核后恢复展示"));
        assertEquals(1, reviews.summary(1L).count());
        assertEquals(2, scalar("SELECT COUNT(*) FROM secondhand_review_moderation_logs"));
    }

    @Test
    void ownerCanEditDeleteAndNoOneCanLikeOwnReview() throws Exception {
        var created = reviews.create(2L, UUID.randomUUID().toString(), 10L,
                4, "物品成色总体不错", List.of());
        assertThrows(SecondhandRuleException.class,
                () -> reviews.update(3L, created.review().id(), 1, "恶意修改他人评价"));
        assertEquals(5, reviews.update(2L, created.review().id(), 5,
                "补充说明：卖家发货很快").review().rating());
        assertThrows(SecondhandRuleException.class,
                () -> reviews.setLike(2L, created.review().id(), true));
        assertTrue(reviews.setLike(3L, created.review().id(), true).enabled());
        assertFalse(reviews.setLike(3L, created.review().id(), false).enabled());
    }

    @Test
    void createSupportsAtMostThreeValidatedImagesAndRetryIsIdempotent() throws Exception {
        String operation = UUID.randomUUID().toString();
        var image = image("a");
        var first = reviews.create(2L, operation, 10L, 5,
                "物品与描述一致", List.of(image));
        var retry = reviews.create(2L, operation, 10L, 5,
                "重试内容不会覆盖", List.of());

        assertTrue(retry.duplicate());
        assertEquals(first.review().id(), retry.review().id());
        assertEquals(1, scalar("SELECT COUNT(*) FROM secondhand_review_images"));
        assertThrows(IllegalArgumentException.class, () -> reviews.create(2L,
                UUID.randomUUID().toString(), 11L, 5, "图片数量超过限制",
                List.of(image("b"), image("c"), image("d"), image("e"))));
    }

    @Test
    void adminSearchSupportsEmptyKeywordStatusPagingAndLiteralWildcards() throws Exception {
        var first = reviews.create(2L, UUID.randomUUID().toString(), 10L, 5,
                "成色100%_!\\ 与描述一致", List.of());
        reviews.create(2L, UUID.randomUUID().toString(), 11L, 4,"物品描述非常准确", List.of());
        assertEquals(2, reviews.searchAdmin("", null, 1, 12).total());
        assertEquals(2, reviews.searchAdmin("买家", null, 1, 12).total());
        assertEquals(1, reviews.searchAdmin("", null, 2, 1).rows().size());
        for (String keyword : List.of("%", "_", "!", "\\", "100%_!\\")) {
            var page = reviews.searchAdmin(keyword, null, 1, 12);
            assertEquals(1, page.total(), keyword);
            assertEquals(first.review().id(), page.rows().getFirst().id());
        }
        reviews.moderate(9L, first.review().id(), true, "违规评价");
        assertEquals(1, reviews.searchAdmin("", com.vcampus.common.model.ShopReviewStatus.HIDDEN, 1, 12).total());
        assertEquals(0, reviews.searchAdmin("不存在", null, 1, 12).total());
    }

    private static PendingImage image(String key) {
        return new PendingImage(key, key + "-thumb", "image/png", 100,
                "a".repeat(64), 10, 10);
    }

    private void createSchema() throws SQLException {
        try (Connection c=connections.openConnection(); Statement s=c.createStatement()) {
            s.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,display_name VARCHAR(100))");
            s.execute("CREATE TABLE secondhand_items(id BIGINT PRIMARY KEY,seller_user_id BIGINT)");
            s.execute("CREATE TABLE secondhand_orders(id BIGINT PRIMARY KEY,item_id BIGINT,buyer_user_id BIGINT,seller_user_id BIGINT,status VARCHAR(16))");
            s.execute("CREATE TABLE secondhand_reviews(id BIGINT AUTO_INCREMENT PRIMARY KEY,operation_id VARCHAR(64) UNIQUE,order_id BIGINT UNIQUE,item_id BIGINT,seller_user_id BIGINT,author_user_id BIGINT,rating INT,content VARCHAR(1000),status VARCHAR(16) DEFAULT 'NORMAL',created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,deleted_at TIMESTAMP)");
            s.execute("CREATE TABLE secondhand_review_images(id BIGINT AUTO_INCREMENT PRIMARY KEY,review_id BIGINT,position INT,storage_key VARCHAR(160) UNIQUE,thumbnail_storage_key VARCHAR(160) UNIQUE,mime_type VARCHAR(32),byte_size BIGINT,sha256 VARCHAR(64),width INT,height INT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(review_id,position))");
            s.execute("CREATE TABLE secondhand_review_likes(review_id BIGINT,user_id BIGINT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(review_id,user_id))");
            s.execute("CREATE TABLE secondhand_review_moderation_logs(id BIGINT AUTO_INCREMENT PRIMARY KEY,review_id BIGINT,operator_user_id BIGINT,action VARCHAR(16),reason VARCHAR(500),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("INSERT INTO users VALUES(1,'卖家'),(2,'买家'),(3,'其他用户'),(9,'管理员')");
            s.execute("INSERT INTO secondhand_items VALUES(7,1),(8,1)");
            s.execute("INSERT INTO secondhand_orders VALUES(10,7,2,1,'COMPLETED'),(11,8,2,1,'COMPLETED')");
        }
    }

    private void execute(String sql)throws SQLException{try(Connection c=connections.openConnection();Statement s=c.createStatement()){s.executeUpdate(sql);}}
    private int scalar(String sql)throws SQLException{try(Connection c=connections.openConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getInt(1);}}
}
