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
import com.vcampus.server.database.ShopReviewStore.AdminReviewQuery;
import com.vcampus.server.database.ShopReviewStore.ReviewQuery;
import com.vcampus.common.model.ShopReviewStatus;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
        assertEquals(first.review().id(), repository.findCreated(7L, OP1, 31L)
                .orElseThrow().review().id());
    }

    @Test
    void concurrentSameOperationReturnsOneReviewToBothCallers() throws Exception {
        var command = new CreateReview(OP1, 31L, 5, "并发提交仍然只创建一条评价", List.of());
        CompletableFuture<ShopReviewStore.ReviewMutationResult> first = CompletableFuture.supplyAsync(
                () -> createUnchecked(7L, command));
        CompletableFuture<ShopReviewStore.ReviewMutationResult> second = CompletableFuture.supplyAsync(
                () -> createUnchecked(7L, command));

        var results = List.of(first.join(), second.join());
        assertEquals(1, results.stream().map(result -> result.review().id()).distinct().count());
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM shop_product_reviews"));
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

    @Test
    void aggregatesOnlyNormalReviewsAndIncludesExplicitEmptySummaries() throws Exception {
        repository.create(7L, new CreateReview(OP1, 31L, 5,
                "五星评价内容足够完整", List.of()));
        repository.create(7L, new CreateReview(OP2, 32L, 3,
                "三星评价内容足够完整", List.of()));
        insertReview("hidden-op", 35, 11, 9, 1, "隐藏评价不计入平均分", "HIDDEN");
        insertReview("deleted-op", 36, 11, 10, 1, "删除评价不计入平均分", "DELETED");

        var summary = repository.summary(11L);
        assertEquals(new BigDecimal("4.0"), summary.average());
        assertEquals(2, summary.total());
        assertEquals(List.of(0, 0, 1, 0, 1), summary.starCounts());

        var summaries = repository.summaries(Set.of(11L, 12L));
        assertEquals(summary, summaries.get(11L));
        assertEquals(0, summaries.get(12L).total());
        assertEquals(null, summaries.get(12L).average());
        assertEquals(List.of(0, 0, 0, 0, 0), summaries.get(12L).starCounts());
    }

    @Test
    void likesAreIdempotentRejectSelfAndDriveStablePublicOrder() throws Exception {
        long first = repository.create(7L, new CreateReview(OP1, 31L, 5,
                "第一条评价用于排序测试", List.of())).review().id();
        long second = repository.create(8L, new CreateReview(OP2, 34L, 4,
                "第二条评价用于排序测试", List.of())).review().id();
        long third = repository.create(9L, new CreateReview(OP3, 35L, 3,
                "第三条评价用于排序测试", List.of())).review().id();
        execute("UPDATE shop_product_reviews SET created_at=TIMESTAMP '2026-09-05 10:00:00' WHERE id=" + first);
        execute("UPDATE shop_product_reviews SET created_at=TIMESTAMP '2026-09-06 10:00:00' WHERE id IN (" + second + "," + third + ")");

        assertThrows(ShopRuleException.class, () -> repository.setLike(7L, first, true));
        assertTrue(repository.setLike(8L, first, true).changed());
        assertFalse(repository.setLike(8L, first, true).changed());
        repository.setLike(9L, first, true);
        repository.setLike(7L, second, true);
        repository.setLike(7L, third, true);

        var page = repository.list(new ReviewQuery(11L, 8L, 1, 10));
        assertEquals(List.of(first, third, second),
                page.rows().stream().map(row -> row.id()).toList());
        assertTrue(page.rows().getFirst().likedByViewer());
        assertEquals(2, page.rows().getFirst().likeCount());
        assertTrue(repository.setLike(8L, first, false).changed());
        assertFalse(repository.setLike(8L, first, false).changed());
    }

    @Test
    void concurrentLikesRemainUnique() throws Exception {
        long reviewId = repository.create(7L, new CreateReview(OP1, 31L, 5,
                "并发点赞评价内容足够完整", List.of())).review().id();
        CompletableFuture<?> first = CompletableFuture.runAsync(() -> likeUnchecked(8L, reviewId));
        CompletableFuture<?> second = CompletableFuture.runAsync(() -> likeUnchecked(9L, reviewId));
        CompletableFuture.allOf(first, second).join();

        assertEquals(2, repository.list(new ReviewQuery(11L, 10L, 1, 10))
                .rows().getFirst().likeCount());
    }

    @Test
    void moderationIsStateAwareAndControlsPublicVisibility() throws Exception {
        long reviewId = repository.create(7L, new CreateReview(OP1, 31L, 5,
                "等待管理员审核的评价内容", List.of())).review().id();
        assertTrue(repository.hide(10L, reviewId, "含有不适宜内容"));
        assertFalse(repository.hide(10L, reviewId, "重复隐藏不应记录"));
        assertEquals(0, repository.summary(11L).total());
        assertTrue(repository.list(new ReviewQuery(11L, 8L, 1, 10)).rows().isEmpty());
        assertEquals(1, repository.searchAdmin(
                new AdminReviewQuery("等待", ShopReviewStatus.HIDDEN, 1, 10)).total());
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM shop_review_moderation_logs"));

        assertTrue(repository.restore(10L, reviewId, "复核后恢复展示"));
        assertFalse(repository.restore(10L, reviewId, "重复恢复不应记录"));
        assertEquals(1, repository.summary(11L).total());
        assertEquals(2, scalarInt("SELECT COUNT(*) FROM shop_review_moderation_logs"));
    }

    @Test
    void adminSearchMatchesProductNameAndReviewId() throws Exception {
        long reviewId = repository.create(7L, new CreateReview(OP1, 31L, 5,
                "这条内容不包含商品名称", List.of())).review().id();

        assertEquals(1, repository.searchAdmin(
                new AdminReviewQuery("校园笔记本", null, 1, 10)).total());
        assertEquals(1, repository.searchAdmin(
                new AdminReviewQuery(Long.toString(reviewId), null, 1, 10)).total());
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

    private void likeUnchecked(long userId, long reviewId) {
        try {
            repository.setLike(userId, reviewId, true);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private ShopReviewStore.ReviewMutationResult createUnchecked(long userId, CreateReview command) {
        try {
            return repository.create(userId, command);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private void insertReview(String operationId, long itemId, long productId, long authorId,
                              int rating, String content, String status) throws Exception {
        execute("INSERT INTO shop_product_reviews(operation_id,order_item_id,product_id,author_user_id,"
                + "rating,content,repeat_purchase,status) VALUES('" + operationId + "'," + itemId
                + "," + productId + "," + authorId + "," + rating + ",'" + content
                + "',FALSE,'" + status + "')");
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int scalarInt(String sql) throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
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
            statement.execute("CREATE TABLE shop_products(id BIGINT PRIMARY KEY,name VARCHAR(200) NOT NULL)");
            statement.execute("CREATE TABLE shop_orders(id BIGINT PRIMARY KEY,buyer_user_id BIGINT NOT NULL,"
                    + "status VARCHAR(16) NOT NULL,completed_at TIMESTAMP NULL)");
            statement.execute("CREATE TABLE shop_order_items(id BIGINT PRIMARY KEY,order_id BIGINT NOT NULL,product_id BIGINT NOT NULL)");
            String migration = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "..", "database", "migrations", "024_shop_reviews.sql"));
            for (String table : List.of("shop_product_reviews", "shop_review_images",
                    "shop_review_likes", "shop_review_moderation_logs")) {
                statement.execute(extractCreateTable(migration, table));
            }
            statement.execute("INSERT INTO users VALUES(7,'小林'),(8,'小周'),(9,'小陈'),(10,'管理员')");
            statement.execute("INSERT INTO shop_products VALUES(11,'校园笔记本'),(12,'校园水杯')");
            statement.execute("INSERT INTO shop_orders VALUES"
                    + "(21,7,'COMPLETED',TIMESTAMP '2026-09-01 10:00:00'),"
                    + "(22,7,'COMPLETED',TIMESTAMP '2026-09-02 10:00:00'),"
                    + "(23,7,'SHIPPED',NULL),(24,8,'COMPLETED',TIMESTAMP '2026-09-03 10:00:00'),"
                    + "(25,9,'COMPLETED',TIMESTAMP '2026-09-04 10:00:00'),"
                    + "(26,10,'COMPLETED',TIMESTAMP '2026-09-05 10:00:00')");
            statement.execute("INSERT INTO shop_order_items VALUES"
                    + "(31,21,11),(32,22,11),(33,23,11),(34,24,11),(35,25,11),(36,26,11)");
        }
    }

    private String extractCreateTable(String sql, String table) {
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS " + table);
        int end = sql.indexOf(';', start);
        return sql.substring(start, end + 1);
    }
}
