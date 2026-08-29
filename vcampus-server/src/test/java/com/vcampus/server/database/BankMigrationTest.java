package com.vcampus.server.database;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankMigrationTest {
    @Test
    void migrationCreatesBankTablesWithEnforcedBalanceLedgerAndIdempotencyConstraints()
            throws Exception {
        Path migration = Path.of("..", "database", "migrations", "007_bank.sql");
        assertTrue(Files.exists(migration));
        String sql = Files.readString(migration);

        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:bank_migration;MODE=MySQL;DB_CLOSE_DELAY=-1");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO users(id) VALUES (1), (2)");
            statement.execute(extractCreateTable(sql, "bank_accounts"));
            statement.execute(extractCreateTable(sql, "bank_ledger_entries"));

            statement.execute("INSERT INTO bank_accounts(user_id) VALUES (1)");
            assertThrows(SQLException.class,
                    () -> statement.execute("INSERT INTO bank_accounts(user_id) VALUES (1)"));
            assertThrows(SQLException.class,
                    () -> statement.execute("UPDATE bank_accounts SET balance=-0.01 WHERE user_id=1"));

            String ledger = "INSERT INTO bank_ledger_entries(account_id,entry_type,direction,"
                    + "amount,balance_after,reference_no,description) "
                    + "VALUES (1,'ADMIN_TOPUP','CREDIT',10.00,10.00,'op-1','充值')";
            assertDoesNotThrow(() -> statement.execute(ledger));
            assertThrows(SQLException.class, () -> statement.execute(ledger));
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO bank_ledger_entries(account_id,entry_type,direction,amount,"
                            + "balance_after,reference_no,description) "
                            + "VALUES (1,'ADMIN_TOPUP','CREDIT',0,10.00,'op-2','非法')"));
        }
    }

    @Test
    void migrationAndFreshSchemaExtendNotificationChecksForBankEvents() throws Exception {
        Path migrationPath = Path.of("..", "database", "migrations", "007_bank.sql");
        assertTrue(Files.exists(migrationPath));
        String migration = Files.readString(migrationPath);
        String schema = Files.readString(Path.of("..", "database", "schema.sql"));
        for (String literal : new String[]{"BANK_TRANSFER_RECEIVED", "BANK_ACCOUNT_TOPPED_UP",
                "BANK_ACCOUNT_STATUS_CHANGED", "BANK", "BANK_LEDGER"}) {
            assertTrue(migration.contains("'" + literal + "'"));
            assertTrue(schema.contains("'" + literal + "'"));
        }
    }

    private String extractCreateTable(String sql, String table) {
        String marker = "CREATE TABLE IF NOT EXISTS " + table;
        int start = sql.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException("Missing table: " + table);
        }
        int end = sql.indexOf(';', start);
        if (end < 0) {
            throw new IllegalArgumentException("Unterminated table: " + table);
        }
        return sql.substring(start, end + 1);
    }
}
