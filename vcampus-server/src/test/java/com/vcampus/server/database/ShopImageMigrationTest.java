package com.vcampus.server.database;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopImageMigrationTest {
    private static final Path SHOP_MIGRATION =
            Path.of("..", "database", "migrations", "008_shop.sql");
    private static final Path IMAGE_MIGRATION =
            Path.of("..", "database", "migrations", "013_shop_images_javafx.sql");
    private static final Path FRESH_SCHEMA = Path.of("..", "database", "schema.sql");

    @Test
    void upgradePreservesProductsBackfillsOtherAndIsIdempotent() throws Exception {
        String shopSql = Files.readString(SHOP_MIGRATION);
        String imageSql = Files.readString(IMAGE_MIGRATION);
        try (Connection connection = connection("shop_image_upgrade");
             Statement statement = connection.createStatement()) {
            statement.execute(extractCreateTable(shopSql, "shop_products"));
            statement.execute("INSERT INTO shop_products"
                    + "(sku,name,description,price,stock,enabled)"
                    + " VALUES('SKU-OLD','旧商品','保留我',12.50,3,TRUE)");

            executeUpgrade(connection, imageSql);

            assertEquals(1, scalarInt(connection,
                    "SELECT COUNT(*) FROM shop_products WHERE sku='SKU-OLD'"));
            assertEquals("OTHER", scalarString(connection,
                    "SELECT category FROM shop_products WHERE sku='SKU-OLD'"));
            assertTrue(tableExists(connection, "SHOP_PRODUCT_IMAGES"));

            statement.executeUpdate("UPDATE shop_products SET category='DIGITAL_ACCESSORIES'"
                    + " WHERE sku='SKU-OLD'");
            executeUpgrade(connection, imageSql);

            assertEquals("DIGITAL_ACCESSORIES", scalarString(connection,
                    "SELECT category FROM shop_products WHERE sku='SKU-OLD'"));
            assertEquals(1, scalarInt(connection,
                    "SELECT COUNT(*) FROM shop_products WHERE sku='SKU-OLD'"));
        }
    }

    @Test
    void upgradeAndFreshSchemaEnforceImageMetadataConstraints() throws Exception {
        verifyImageConstraints("upgrade", false);
        verifyImageConstraints("fresh", true);
    }

    @Test
    void migrationRetainsTheRequiredIdempotentMysqlContract() throws Exception {
        String sql = Files.readString(IMAGE_MIGRATION);

        assertTrue(sql.contains("information_schema.columns"));
        assertTrue(sql.contains("PREPARE vcampus_shop_upgrade"));
        assertTrue(sql.contains("EXECUTE vcampus_shop_upgrade"));
        assertTrue(sql.contains("MODIFY COLUMN category VARCHAR(32) NOT NULL DEFAULT ''OTHER''"));
        assertTrue(sql.contains("WHERE category IS NULL"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS shop_product_images"));
        assertTrue(sql.contains("cover_product_id BIGINT GENERATED ALWAYS AS"));
        assertTrue(sql.contains("UNIQUE KEY uk_shop_image_cover_product (cover_product_id)"));
    }

    private void verifyImageConstraints(String databaseName, boolean fresh) throws Exception {
        String shopSql = Files.readString(SHOP_MIGRATION);
        String imageSql = Files.readString(IMAGE_MIGRATION);
        String schemaSql = Files.readString(FRESH_SCHEMA);
        try (Connection connection = connection("shop_image_" + databaseName);
             Statement statement = connection.createStatement()) {
            if (fresh) {
                statement.execute(extractCreateTable(schemaSql, "shop_products"));
                statement.execute(extractH2CreateTable(schemaSql, "shop_product_images"));
            } else {
                statement.execute(extractCreateTable(shopSql, "shop_products"));
                executeUpgrade(connection, imageSql);
            }
            statement.execute("INSERT INTO shop_products"
                    + "(sku,name,description,category,price,stock,enabled)"
                    + " VALUES('SKU-1','教材','说明','LEARNING_STATIONERY',20.00,2,TRUE)");
            statement.execute("INSERT INTO shop_product_images"
                    + "(product_id,storage_key,thumbnail_storage_key,mime_type,byte_size,sha256,"
                    + "sort_order,is_cover) VALUES"
                    + "(1,'image-a','thumb-a','image/jpeg',128,'"
                    + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',0,TRUE)");

            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO shop_product_images"
                            + "(product_id,storage_key,thumbnail_storage_key,mime_type,byte_size,"
                            + "sha256,sort_order,is_cover) VALUES"
                            + "(1,'image-cover-b','thumb-cover-b','image/png',64,'"
                            + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',"
                            + "1,TRUE)"));
            statement.execute("INSERT INTO shop_product_images"
                    + "(product_id,storage_key,thumbnail_storage_key,mime_type,byte_size,sha256,"
                    + "sort_order,is_cover) VALUES"
                    + "(1,'image-false-b','thumb-false-b','image/png',64,'"
                    + "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',"
                    + "1,FALSE),"
                    + "(1,'image-false-c','thumb-false-c','image/png',64,'"
                    + "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',"
                    + "2,FALSE)");
            assertEquals(2, scalarInt(connection,
                    "SELECT COUNT(*) FROM shop_product_images WHERE product_id=1 AND is_cover=FALSE"));

            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO shop_product_images"
                            + "(product_id,storage_key,thumbnail_storage_key,mime_type,byte_size,"
                            + "sha256,sort_order,is_cover) VALUES"
                            + "(1,'image-b','thumb-b','image/png',64,'"
                            + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',"
                            + "0,FALSE)"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO shop_product_images"
                            + "(product_id,storage_key,thumbnail_storage_key,mime_type,byte_size,"
                            + "sha256,sort_order,is_cover) VALUES"
                            + "(1,'image-a','thumb-c','image/jpeg',64,'"
                            + "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',"
                            + "1,FALSE)"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO shop_product_images"
                            + "(product_id,storage_key,thumbnail_storage_key,mime_type,byte_size,"
                            + "sha256,sort_order,is_cover) VALUES"
                            + "(1,'image-d','thumb-d','image/jpeg',0,'"
                            + "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',"
                            + "1,FALSE)"));

            statement.execute("DELETE FROM shop_products WHERE id=1");
            assertEquals(0, scalarInt(connection, "SELECT COUNT(*) FROM shop_product_images"));
        }
    }

    private Connection connection(String label) throws SQLException {
        return DriverManager.getConnection("jdbc:h2:mem:" + label + "_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    }

    private void executeUpgrade(Connection connection, String sql) throws SQLException {
        if (!columnExists(connection, "SHOP_PRODUCTS", "CATEGORY")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(extractQuotedDdl(sql,
                        "ALTER TABLE shop_products ADD COLUMN category"));
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE shop_products SET category='OTHER'"
                    + " WHERE category IS NULL");
            statement.execute(extractH2CreateTable(sql, "shop_product_images"));
        }
    }

    private boolean columnExists(Connection connection, String table, String column)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(null, null, table, column)) {
            return columns.next();
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(
                null, null, table, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private int scalarInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private String scalarString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private String extractQuotedDdl(String sql, String marker) {
        int markerStart = sql.indexOf(marker);
        if (markerStart < 0) throw new IllegalArgumentException("Missing DDL: " + marker);
        int start = sql.lastIndexOf('\'', markerStart) + 1;
        if (start < 1) throw new IllegalArgumentException("Unquoted DDL: " + marker);
        StringBuilder ddl = new StringBuilder();
        for (int index = start; index < sql.length(); index++) {
            char character = sql.charAt(index);
            if (character != '\'') {
                ddl.append(character);
            } else if (index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                ddl.append('\'');
                index++;
            } else {
                return ddl.toString();
            }
        }
        throw new IllegalArgumentException("Unterminated DDL: " + marker);
    }

    private String extractCreateTable(String sql, String table) {
        String marker = "CREATE TABLE IF NOT EXISTS " + table;
        int start = sql.indexOf(marker);
        if (start < 0) throw new IllegalArgumentException("Missing table: " + table);
        int end = sql.indexOf(';', start);
        if (end < 0) throw new IllegalArgumentException("Unterminated table: " + table);
        return sql.substring(start, end + 1);
    }

    private String extractH2CreateTable(String sql, String table) {
        return extractCreateTable(sql, table).replace(") STORED", ")");
    }
}
