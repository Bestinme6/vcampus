package com.vcampus.server.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.ShopReviewStore.CreateReview;
import com.vcampus.server.database.ShopReviewStore.ImagePlan;
import com.vcampus.server.database.ShopReviewStore.ImagePlanItem;
import com.vcampus.server.database.ShopReviewStore.PendingImage;
import com.vcampus.server.database.ShopReviewStore.UpdateReview;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShopReviewRepositoryTest {
    private static final String OP1 = "00000000-0000-0000-0000-000000000001";
    private static final String OP2 = "00000000-0000-0000-0000-000000000002";
    private static final String OP3 = "00000000-0000-0000-0000-000000000003";
    private static final String OP4 = "00000000-0000-0000-0000-000000000004";
    private ConnectionFactory connections;
    private ShopReviewRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:review_repo_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        connections = new ConnectionFactory(new DatabaseConfig(url, "sa", ""));
        createSchema(url);
        repository = new ShopReviewRepository(connections);
    }

    @Test
    void createRequiresCompletedOwnedItemAndIsIdempotentAndMarksRepeatPurchase() throws Exception {
        var first = repository.create(7L, new CreateReview(OP1, 31L, 5,
                "第一次购买，纸张质量很好", List.of()));
        var duplicate = repository.create(7L, new CreateReview(OP1, 31L, 5,
                "第一次购买，纸张质量很好", List.of()));
        var repeat = repository.create(7L, new CreateReview(OP2, 32L, 4,
                "第二次购买，装订依然牢固", List.of()));

        assertFalse(first.review().repeatPurchase());
        assertTrue(duplicate.duplicate());
        assertEquals(first.review().id(), duplicate.review().id());
        assertTrue(repeat.review().repeatPurchase());
        assertThrows(ShopRuleException.class, () -> repository.create(8L,
                new CreateReview(OP3, 31L, 5, "不能评价他人订单商品", List.of())));
        assertThrows(ShopRuleException.class, () -> repository.create(7L,
                new CreateReview(OP4, 33L, 5, "订单尚未完成不能评价", List.of())));
        assertThrows(ShopRuleException.class, () -> repository.create(7L,
                new CreateReview(OP3, 31L, 5, "同一订单商品不能重复评价", List.of())));
        assertEquals(first.review().id(), repository.reviewIdsByOrderItems(Set.of(31L)).get(31L));
    }

    @Test
    void authorCanUpdateImagePlanAndSoftDeleteButOthersCannot() throws Exception {
        PendingImage firstImage = image("original-a", "thumb-a");
        PendingImage secondImage = image("original-b", "thumb-b");
        var created = repository.create(7L, new CreateReview(OP1, 31L, 5,
                "初次评价内容足够完整", List.of(firstImage, secondImage)));
        long firstImageId = created.review().id() == 0 ? 0 : imageId("original-a");

        assertThrows(ShopRuleException.class, () -> repository.update(8L,
                new UpdateReview(created.review().id(), 4, "他人不能修改这条评价",
                        new ImagePlan(List.of()))));
        var updated = repository.update(7L, new UpdateReview(created.review().id(), 4,
                "修改后的评价内容同样完整", new ImagePlan(List.of(
                        new ImagePlanItem(firstImageId, null),
                        new ImagePlanItem(null, image("original-c", "thumb-c"))))));

        assertEquals(created.review().id(), updated.review().id());
        assertEquals(4, updated.review().rating());
        assertEquals(Set.of("original-a", "thumb-a", "original-c", "thumb-c"),
                updated.keptKeys());
        assertEquals(Set.of("original-b", "thumb-b"), updated.deletedKeys());
        assertThrows(ShopRuleException.class,
                () -> repository.delete(8L, created.review().id()));
        assertTrue(repository.delete(7L, created.review().id()));
        assertFalse(repository.delete(7L, created.review().id()));
        assertEquals("DELETED", scalarString(
                "SELECT status FROM shop_product_reviews WHERE id=" + created.review().id()));
        assertEquals(created.review().id(), repository.reviewIdsByOrderItems(Set.of(31L)).get(31L));
    }

    @Test
    void validatesRatingContentAndImageCountBeforeWriting() {
        assertThrows(IllegalArgumentException.class, () -> repository.create(7L,
                new CreateReview(OP1, 31L, 0, "评分太低但文字足够", List.of())));
        assertThrows(IllegalArgumentException.class, () -> repository.create(7L,
                new CreateReview(OP1, 31L, 5, "短评", List.of())));
        assertThrows(IllegalArgumentException.class, () -> repository.create(7L,
                new CreateReview(OP1, 31L, 5, "图片数量超过允许范围",
                        List.of(image("a", "ta"), image("b", "tb"),
                                image("c", "tc"), image("d", "td")))));
    }

    private PendingImage image(String storageKey, String thumbnailKey) {
        return new PendingImage(storageKey, thumbnailKey, "image/jpeg", 128,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 12, 10);
    }

    private long imageId(String storageKey) throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT id FROM shop_review_images WHERE storage_key='" + storageKey + "'")) {
            result.next();
            return result.getLong(1);
        }
    }

    private String scalarString(String sql) throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private void createSchema(String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,display_name VARCHAR(100) NOT NULL)");
            statement.execute("CREATE TABLE shop_products(id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE shop_orders(id BIGINT PRIMARY KEY,buyer_user_id BIGINT NOT NULL,"
                    + "status VARCHAR(16) NOT NULL,completed_at TIMESTAMP NULL)");
            statement.execute("CREATE TABLE shop_order_items(id BIGINT PRIMARY KEY,order_id BIGINT NOT NULL,product_id BIGINT NOT NULL)");
            String migration = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "..", "database", "migrations", "024_shop_reviews.sql"));
            for (String table : List.of("shop_product_reviews", "shop_review_images",
                    "shop_review_likes", "shop_review_moderation_logs")) {
                statement.execute(extractCreateTable(migration, table));
            }
            statement.execute("INSERT INTO users VALUES(7,'小林'),(8,'小周')");
            statement.execute("INSERT INTO shop_products VALUES(11)");
            statement.execute("INSERT INTO shop_orders VALUES"
                    + "(21,7,'COMPLETED',TIMESTAMP '2026-09-01 10:00:00'),"
                    + "(22,7,'COMPLETED',TIMESTAMP '2026-09-02 10:00:00'),"
                    + "(23,7,'SHIPPED',NULL),(24,8,'COMPLETED',TIMESTAMP '2026-09-03 10:00:00')");
            statement.execute("INSERT INTO shop_order_items VALUES"
                    + "(31,21,11),(32,22,11),(33,23,11),(34,24,11)");
        }
    }

    private String extractCreateTable(String sql, String table) {
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS " + table);
        int end = sql.indexOf(';', start);
        return sql.substring(start, end + 1);
    }
}
