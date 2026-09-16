package com.vcampus.server.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShopAddressMigrationTest {
    private static final List<Path> SOURCES = List.of(
            Path.of("..", "database", "migrations", "025_shop_shipping_addresses.sql"),
            Path.of("..", "database", "schema.sql"));

    @Test
    void migrationAndFreshSchemaCreateTheSameConstrainedAddressTable() throws Exception {
        for (Path source : SOURCES) {
            String sql = Files.readString(source);
            String url = "jdbc:h2:mem:address_migration_" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
            try (Connection connection = DriverManager.getConnection(url, "sa", "");
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
                statement.execute(extractCreateTable(sql, "shop_shipping_addresses"));

                assertEquals(Set.of("USER_ID", "SLOT", "VERSION_TOKEN", "RECIPIENT", "PHONE",
                        "ADDRESS_TEXT", "CREATED_AT", "UPDATED_AT"), columnNames(connection));
                assertEquals(Set.of("USER_ID", "SLOT"), primaryKeyColumns(connection));
                assertEquals(Set.of("USER_ID"), importedKeyColumns(connection));

                statement.execute("INSERT INTO users VALUES(7)");
                statement.execute("INSERT INTO shop_shipping_addresses"
                        + "(user_id,slot,version_token,recipient,phone,address_text)"
                        + " VALUES(7,1,'v1','小林','13800138000','江苏省南京市文昌路 1 号')");
                assertThrows(SQLException.class, () -> statement.execute(
                        "INSERT INTO shop_shipping_addresses"
                                + "(user_id,slot,version_token,recipient,phone,address_text)"
                                + " VALUES(7,1,'v2','小周','13900139000','江苏省南京市文昌路 2 号')"));
                assertThrows(SQLException.class, () -> statement.execute(
                        "INSERT INTO shop_shipping_addresses"
                                + "(user_id,slot,version_token,recipient,phone,address_text)"
                                + " VALUES(7,4,'v4','小周','13900139000','江苏省南京市文昌路 2 号')"));
                assertThrows(SQLException.class, () -> statement.execute(
                        "INSERT INTO shop_shipping_addresses"
                                + "(user_id,slot,version_token,recipient,phone,address_text)"
                                + " VALUES(99,2,'v3','小周','13900139000','江苏省南京市文昌路 2 号')"));
                assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM shop_shipping_addresses"));
            }
        }
    }

    @Test
    void migrationUsesTheProjectIdempotentTableConvention() throws Exception {
        String sql = Files.readString(SOURCES.getFirst()).strip();
        assertTrue(sql.startsWith("CREATE TABLE IF NOT EXISTS shop_shipping_addresses"));
        assertEquals(1, sql.split("CREATE TABLE IF NOT EXISTS shop_shipping_addresses", -1).length - 1);
    }

    private Set<String> columnNames(Connection connection) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (var rows = connection.getMetaData().getColumns(null, null,
                "SHOP_SHIPPING_ADDRESSES", null)) {
            while (rows.next()) columns.add(rows.getString("COLUMN_NAME"));
        }
        return columns;
    }

    private Set<String> primaryKeyColumns(Connection connection) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (var rows = connection.getMetaData().getPrimaryKeys(null, null,
                "SHOP_SHIPPING_ADDRESSES")) {
            while (rows.next()) columns.add(rows.getString("COLUMN_NAME"));
        }
        return columns;
    }

    private Set<String> importedKeyColumns(Connection connection) throws SQLException {
        Set<String> columns = new HashSet<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (var rows = metadata.getImportedKeys(null, null, "SHOP_SHIPPING_ADDRESSES")) {
            while (rows.next()) columns.add(rows.getString("FKCOLUMN_NAME"));
        }
        return columns;
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
