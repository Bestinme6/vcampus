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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SecondhandMigrationTest {
    private static final List<Path> SOURCES = List.of(
            Path.of("..", "database", "migrations", "026_secondhand_market.sql"),
            Path.of("..", "database", "schema.sql"));
    private static final List<String> TABLES = List.of(
            "secondhand_items", "secondhand_item_images", "secondhand_orders",
            "secondhand_escrows", "secondhand_listing_moderation_logs",
            "secondhand_order_resolution_logs", "secondhand_reviews",
            "secondhand_review_images", "secondhand_review_likes",
            "secondhand_review_moderation_logs");

    @Test
    void migrationAndFreshSchemaDefineTheSameIndependentSecondhandTables() throws Exception {
        for (Path source : SOURCES) {
            String sql = Files.readString(source);
            String url = "jdbc:h2:mem:secondhand_schema_" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
            try (Connection connection = DriverManager.getConnection(url, "sa", "");
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
                for (String table : TABLES) {
                    statement.execute(extractCreateTable(sql, table));
                }
                Set<String> actual = tableNames(connection);
                assertTrue(actual.containsAll(TABLES.stream()
                        .map(String::toUpperCase).collect(Collectors.toSet())));
            }
        }
    }

    @Test
    void itemPriceAndSellerForeignKeyAreDatabaseConstraints() throws Exception {
        for (Path source : SOURCES) {
            String sql = Files.readString(source);
            String url = "jdbc:h2:mem:secondhand_constraints_" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
            try (Connection connection = DriverManager.getConnection(url, "sa", "");
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
                statement.execute(extractCreateTable(sql, "secondhand_items"));
                statement.execute("INSERT INTO users VALUES(1)");
                statement.execute("INSERT INTO secondhand_items"
                        + "(seller_user_id,title,description,category,condition_text,price)"
                        + " VALUES(1,'旧教材','九成新，含笔记','BOOK','九成新',12.00)");
                assertThrows(SQLException.class, () -> statement.execute(
                        "INSERT INTO secondhand_items"
                                + "(seller_user_id,title,description,category,condition_text,price)"
                                + " VALUES(1,'无效价格','说明文字','OTHER','一般',0.00)"));
                assertThrows(SQLException.class, () -> statement.execute(
                        "INSERT INTO secondhand_items"
                                + "(seller_user_id,title,description,category,condition_text,price)"
                                + " VALUES(99,'无效卖家','说明文字','OTHER','一般',1.00)"));
                assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM secondhand_items"));
            }
        }
    }

    @Test
    void numberedMigrationUsesIdempotentCreateTableStatements() throws Exception {
        String sql = Files.readString(SOURCES.getFirst());
        for (String table : TABLES) {
            assertEquals(1, sql.split("CREATE TABLE IF NOT EXISTS " + table, -1).length - 1,
                    table);
        }
    }

    @Test
    void numberedMigrationUpgradesSecondhandNotificationChecks() throws Exception {
        String sql = Files.readString(SOURCES.getFirst());

        for (String value : List.of(
                "SECONDHAND_ORDER_PAID", "SECONDHAND_ORDER_SHIPPED",
                "SECONDHAND_ORDER_COMPLETED", "SECONDHAND_ORDER_REFUNDED",
                "SECONDHAND_ORDERS")) {
            assertTrue(sql.contains("'" + value + "'"), value);
        }
    }

    private static Set<String> tableNames(Connection connection) throws SQLException {
        try (var rows = connection.getMetaData().getTables(null, null, null,
                new String[]{"TABLE"})) {
            var names = new java.util.HashSet<String>();
            while (rows.next()) names.add(rows.getString("TABLE_NAME"));
            return names;
        }
    }

    private static int scalar(Statement statement, String sql) throws SQLException {
        try (var row = statement.executeQuery(sql)) {
            row.next();
            return row.getInt(1);
        }
    }

    private static String extractCreateTable(String sql, String table) {
        String marker = "CREATE TABLE IF NOT EXISTS " + table;
        int start = sql.indexOf(marker);
        if (start < 0) throw new IllegalArgumentException("Missing table: " + table);
        int end = sql.indexOf(';', start);
        if (end < 0) throw new IllegalArgumentException("Unterminated table: " + table);
        return sql.substring(start, end + 1);
    }
}
