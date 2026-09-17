package com.vcampus.server.database;

import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.server.config.DatabaseConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in SELECT-only verification against an existing MySQL schema. Never writes business data. */
@EnabledIfEnvironmentVariable(named = "VCAMPUS_SECONDHAND_MYSQL_READ_TEST", matches = "true")
class SecondhandAdminMysqlReadTest {
    @Test
    void adminQueriesExecuteOnRealMysql() throws Exception {
        var config = DatabaseConfig.fromEnvironment();
        assertTrue(config.url().startsWith("jdbc:mysql:"));
        var connections = new ConnectionFactory(config);
        try (var connection = connections.openConnection(); var statement = connection.createStatement()) {
            connection.setReadOnly(true);
            boolean backslashEscapes;
            try (var result = statement.executeQuery("SELECT @@SESSION.sql_mode")) {
                assertTrue(result.next());
                backslashEscapes = !result.getString(1).contains("NO_BACKSLASH_ESCAPES");
            }
            if (backslashEscapes) {
                var error = assertThrows(java.sql.SQLException.class,
                        () -> statement.executeQuery("SELECT 'text' LIKE '%' ESCAPE '\\'"));
                assertEquals("42000", error.getSQLState());
            }
            try (var result = statement.executeQuery("SELECT 'text' LIKE '%' ESCAPE '!'")) {
                assertTrue(result.next());
                assertTrue(result.getBoolean(1));
            }
        }
        var notifications = new NotificationRepository(connections);
        var bank = new BankRepository(connections, notifications);
        var orders = new SecondhandOrderRepository(connections, bank,
                new ShopAddressRepository(connections), notifications);
        var reviews = new SecondhandReviewRepository(connections);
        assertTrue(orders.searchAdmin("", ShopOrderStatus.SHIPPED, 1, 12).total() >= 0);
        assertTrue(reviews.searchAdmin("", null, 1, 12).total() >= 0);
        assertTrue(orders.searchAdmin("%_!", null, 1, 12).total() >= 0);
        assertTrue(reviews.searchAdmin("%_!", null, 1, 12).total() >= 0);
    }
}
