package com.vcampus.server.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShopReviewMigrationTest {
    private static final List<String> TABLES = List.of(
            "shop_product_reviews", "shop_review_images",
            "shop_review_likes", "shop_review_moderation_logs");

    @Test
    void migrationCreatesConstrainedReviewTables() throws Exception {
        String sql = Files.readString(Path.of("..", "database", "migrations", "024_shop_reviews.sql"));
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:shop_reviews_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE shop_products(id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE shop_orders(id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE shop_order_items(id BIGINT PRIMARY KEY,order_id BIGINT NOT NULL)");
            statement.execute("INSERT INTO users VALUES(7)");
            statement.execute("INSERT INTO shop_products VALUES(11)");
            statement.execute("INSERT INTO shop_orders VALUES(21)");
            statement.execute("INSERT INTO shop_order_items VALUES(31,21)");

            for (String table : TABLES) {
                statement.execute(extractCreateTable(sql, table));
            }
            statement.execute("INSERT INTO shop_product_reviews"
                    + "(operation_id,order_item_id,product_id,author_user_id,rating,content,repeat_purchase,status)"
                    + " VALUES('00000000-0000-0000-0000-000000000001',31,11,7,5,'纸张厚实，书写顺滑',FALSE,'NORMAL')");
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO shop_product_reviews(operation_id,order_item_id,product_id,author_user_id,rating,content,repeat_purchase,status)"
                            + " VALUES('00000000-0000-0000-0000-000000000002',31,11,7,4,'重复订单商品评价',FALSE,'NORMAL')"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE shop_product_reviews SET rating=6 WHERE id=1"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO shop_review_likes(review_id,user_id) VALUES(1,7),(1,7)"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO shop_review_images(review_id,position,storage_key,thumbnail_storage_key,mime_type,byte_size,sha256,width,height)"
                            + " VALUES(1,3,'a','b','image/jpeg',5,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',1,1)"));
            assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM shop_product_reviews"));
        }
    }

    @Test
    void migrationAndFreshSchemaContainReviewVocabulary() throws Exception {
        for (Path path : List.of(
                Path.of("..", "database", "migrations", "024_shop_reviews.sql"),
                Path.of("..", "database", "schema.sql"))) {
            String sql = Files.readString(path);
            for (String table : TABLES) assertTrue(sql.contains(table));
            for (String status : List.of("'NORMAL'", "'HIDDEN'", "'DELETED'")) {
                assertTrue(sql.contains(status));
            }
        }
    }

    @Test
    void reviewImagePixelCheckUsesMysqlCompatibleIntegerCast() throws Exception {
        for (Path path : List.of(
                Path.of("..", "database", "migrations", "024_shop_reviews.sql"),
                Path.of("..", "database", "schema.sql"))) {
            String sql = Files.readString(path);
            assertTrue(sql.contains("CAST(width AS SIGNED) * height <= 20000000"));
        }
    }

    private int scalar(Statement statement, String sql) throws SQLException {
        try (var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private String extractCreateTable(String sql, String table) {
        String marker = "CREATE TABLE IF NOT EXISTS " + table;
        int start = sql.indexOf(marker);
        if (start < 0) throw new IllegalArgumentException("Missing table: " + table);
        int end = sql.indexOf(';', start);
        if (end < 0) throw new IllegalArgumentException("Unterminated table: " + table);
        return sql.substring(start, end + 1);
    }
}
