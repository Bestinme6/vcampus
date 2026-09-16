package com.vcampus.server.database;

import com.vcampus.common.model.BankAccountStatus;
import com.vcampus.server.config.DatabaseConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecondhandPaymentWriterTest {
    private ConnectionFactory connections;
    private BankRepository bank;
    private SecondhandPaymentWriter payments;

    @BeforeEach
    void setUp() throws SQLException {
        connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", ""));
        createSchema();
        execute("INSERT INTO users VALUES (1,'buyer','买家',TRUE),(2,'seller','卖家',TRUE),(9,'admin','管理员',TRUE)");
        bank = new BankRepository(connections, new NotificationRepository(connections));
        payments = bank;
        bank.topUp(9L, "buyer", new BigDecimal("50.00"), UUID.randomUUID().toString());
    }

    @Test
    void holdDebitsBuyerExactlyOnceAndRejectsMismatchedDuplicate() throws Exception {
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            var first = payments.hold(connection, 1L, new BigDecimal("12.00"), "USED-1");
            var duplicate = payments.hold(connection, 1L, new BigDecimal("12.00"), "USED-1");

            assertEquals(new BigDecimal("38.00"), first.balanceAfter());
            assertTrue(duplicate.duplicate());
            assertThrows(BankRuleException.class, () -> payments.hold(
                    connection, 1L, new BigDecimal("13.00"), "USED-1"));
            connection.commit();
        }

        assertEquals(new BigDecimal("38.00"), bank.account(1L).balance());
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM bank_ledger_entries "
                + "WHERE entry_type='SECONDHAND_HOLD' AND reference_no='USED-1'"));
    }

    @Test
    void refundCreditsFrozenBuyerExactlyOnce() throws Exception {
        bank.setStatus(9L, "buyer", BankAccountStatus.FROZEN);
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            var first = payments.refund(connection, 1L, new BigDecimal("12.00"), "USED-2");
            var duplicate = payments.refund(connection, 1L, new BigDecimal("12.00"), "USED-2");
            assertEquals(new BigDecimal("62.00"), first.balanceAfter());
            assertTrue(duplicate.duplicate());
            connection.commit();
        }
    }

    @Test
    void payoutCreditsFrozenSellerExactlyOnce() throws Exception {
        bank.account(2L);
        bank.setStatus(9L, "seller", BankAccountStatus.FROZEN);
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            var first = payments.payout(connection, 2L, new BigDecimal("12.00"), "USED-3");
            var duplicate = payments.payout(connection, 2L, new BigDecimal("12.00"), "USED-3");
            assertEquals(new BigDecimal("12.00"), first.balanceAfter());
            assertTrue(duplicate.duplicate());
            connection.commit();
        }
        assertEquals(new BigDecimal("12.00"), bank.account(2L).balance());
    }

    @Test
    void frozenBuyerCannotStartHold() throws Exception {
        bank.setStatus(9L, "buyer", BankAccountStatus.FROZEN);
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            assertThrows(BankRuleException.class, () -> payments.hold(
                    connection, 1L, new BigDecimal("12.00"), "USED-4"));
            connection.rollback();
        }
    }

    private void createSchema() throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, username VARCHAR(64) UNIQUE NOT NULL, display_name VARCHAR(100) NOT NULL, enabled BOOLEAN NOT NULL)");
            statement.execute("CREATE TABLE bank_accounts (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNIQUE NOT NULL, balance DECIMAL(15,2) NOT NULL DEFAULT 0.00 CHECK (balance >= 0), status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','FROZEN')), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY (user_id) REFERENCES users(id))");
            statement.execute("CREATE TABLE bank_ledger_entries (id BIGINT AUTO_INCREMENT PRIMARY KEY, account_id BIGINT NOT NULL, entry_type VARCHAR(32) NOT NULL CHECK (entry_type IN ('ADMIN_TOPUP','SECONDHAND_HOLD','SECONDHAND_REFUND','SECONDHAND_PAYOUT')), direction VARCHAR(8) NOT NULL, amount DECIMAL(15,2) NOT NULL CHECK (amount > 0), balance_after DECIMAL(15,2) NOT NULL CHECK (balance_after >= 0), reference_no VARCHAR(64) NOT NULL, counterparty_user_id BIGINT, operator_user_id BIGINT, description VARCHAR(255) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(account_id, entry_type, reference_no), FOREIGN KEY(account_id) REFERENCES bank_accounts(id))");
            statement.execute("CREATE TABLE notifications (id BIGINT AUTO_INCREMENT PRIMARY KEY, recipient_user_id BIGINT NOT NULL, sender_user_id BIGINT, notification_type VARCHAR(40) NOT NULL, source_module VARCHAR(40) NOT NULL, title VARCHAR(160) NOT NULL, content VARCHAR(1000) NOT NULL, target VARCHAR(40) NOT NULL, related_entity_id BIGINT, is_read BOOLEAN DEFAULT FALSE, read_at TIMESTAMP, deleted_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private int scalarInt(String sql) throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }
}
