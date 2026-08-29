package com.vcampus.server.database;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopMigrationTest {
    @Test
    void migrationCreatesFiveShopTablesAndEnforcesCoreConstraints() throws Exception {
        String sql = Files.readString(Path.of("..", "database", "migrations", "008_shop.sql"));
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:shop_migration;MODE=MySQL;DB_CLOSE_DELAY=-1");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO users(id) VALUES (1), (9)");
            for (String table : List.of("shop_products", "shop_cart_items", "shop_orders",
                    "shop_order_items", "shop_inventory_movements")) {
                statement.execute(extractCreateTable(sql, table));
            }

            assertDoesNotThrow(() -> statement.execute("INSERT INTO shop_products"
                    + "(sku,name,description,price,stock,enabled)"
                    + " VALUES('SKU-1','教材','课程用品',20.00,2,TRUE)"));
            assertThrows(SQLException.class, () -> statement.execute("INSERT INTO shop_products"
                    + "(sku,name,description,price,stock,enabled)"
                    + " VALUES('SKU-1','重复','',10.00,0,TRUE)"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE shop_products SET stock=-1 WHERE id=1"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO shop_cart_items(user_id,product_id,quantity) VALUES(1,1,0)"));
            statement.execute("INSERT INTO shop_orders(order_no,buyer_user_id,checkout_operation_id,"
                    + "total_amount,status) VALUES('SO1',1,'op-1',20.00,'PAID')");
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO shop_orders(order_no,buyer_user_id,checkout_operation_id,"
                            + "total_amount,status) VALUES('SO2',1,'op-1',20.00,'PAID')"));
        }
    }

    @Test
    void freshAndUpgradeScriptsContainShopContract() throws Exception {
        String schema = Files.readString(Path.of("..", "database", "schema.sql"));
        String migration = Files.readString(
                Path.of("..", "database", "migrations", "008_shop.sql"));
        for (String table : List.of("shop_products", "shop_cart_items", "shop_orders",
                "shop_order_items", "shop_inventory_movements")) {
            assertTrue(schema.contains(table));
            assertTrue(migration.contains(table));
        }
        for (String literal : List.of("SHOP_ORDER_SHIPPED", "SHOP", "SHOP_ORDERS")) {
            assertTrue(schema.contains("'" + literal + "'"));
            assertTrue(migration.contains("'" + literal + "'"));
        }
        assertTrue(schema.contains("checkout_operation_id VARCHAR(64) NOT NULL UNIQUE"));
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
