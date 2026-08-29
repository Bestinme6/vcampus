package com.vcampus.server.database;

import com.vcampus.common.model.BankAccountStatus;
import com.vcampus.common.model.BankLedgerType;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.BankStore.AccountQuery;
import com.vcampus.server.database.BankStore.LedgerQuery;
import com.vcampus.server.database.BankStore.StatusResult;
import com.vcampus.server.database.BankStore.TopUpResult;
import com.vcampus.server.model.BankAccountRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankRepositoryTest {
    private ConnectionFactory connections;
    private BankRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", ""));
        createSchema();
        seedUsers();
        repository = new BankRepository(connections, new NotificationRepository(connections));
    }

    @Test
    void firstAccessCreatesExactlyOneActiveZeroBalanceAccount() throws Exception {
        BankAccountRecord first = repository.account(1L);
        BankAccountRecord second = repository.account(1L);

        assertEquals(first.id(), second.id());
        assertEquals("student", first.username());
        assertEquals("张同学", first.displayName());
        assertEquals(new BigDecimal("0.00"), first.balance());
        assertEquals(BankAccountStatus.ACTIVE, first.status());
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM bank_accounts WHERE user_id=1"));
    }

    @Test
    void accountSearchFiltersByUserTextAndStatusWithStablePaging() throws Exception {
        repository.account(1L);
        repository.account(2L);
        execute("UPDATE bank_accounts SET status='FROZEN' WHERE user_id=2");

        var page = repository.searchAccounts(
                new AccountQuery("李", BankAccountStatus.FROZEN, 1, 10));

        assertEquals(1, page.total());
        assertEquals(1, page.rows().size());
        assertEquals("teacher", page.rows().getFirst().username());
        assertEquals(BankAccountStatus.FROZEN, page.rows().getFirst().status());
    }

    @Test
    void ledgerQueryReturnsOnlyTheRequestedAccountsRows() throws Exception {
        BankAccountRecord student = repository.account(1L);
        BankAccountRecord teacher = repository.account(2L);
        insertLedger(student.id(), "ADMIN_TOPUP", "CREDIT", "20.00", "20.00", "student-op");
        insertLedger(teacher.id(), "ADMIN_TOPUP", "CREDIT", "30.00", "30.00", "teacher-op");

        var page = repository.searchLedger(
                new LedgerQuery(1L, BankLedgerType.ADMIN_TOPUP, 1, 10));

        assertEquals(1, page.total());
        assertEquals("student-op", page.rows().getFirst().referenceNo());
        assertTrue(page.rows().stream().allMatch(row -> row.accountId() == student.id()));
    }

    @Test
    void topUpWritesBalanceLedgerAndNotificationAtomicallyAndIsIdempotent() throws Exception {
        String operationId = UUID.randomUUID().toString();

        TopUpResult first = repository.topUp(
                9L, 1L, new BigDecimal("50.00"), operationId);
        TopUpResult duplicate = repository.topUp(
                9L, 1L, new BigDecimal("50.00"), operationId);

        assertEquals(new BigDecimal("50.00"), first.balanceAfter());
        assertEquals(first.balanceAfter(), duplicate.balanceAfter());
        assertTrue(duplicate.duplicate());
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='ADMIN_TOPUP'"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM notifications WHERE notification_type='BANK_ACCOUNT_TOPPED_UP'"));
    }

    @Test
    void freezeAndUnfreezeNotifyOnlyWhenStatusChanges() throws Exception {
        repository.account(1L);

        StatusResult frozen = repository.setStatus(9L, 1L, BankAccountStatus.FROZEN);
        StatusResult unchanged = repository.setStatus(9L, 1L, BankAccountStatus.FROZEN);
        StatusResult active = repository.setStatus(9L, 1L, BankAccountStatus.ACTIVE);

        assertTrue(frozen.changed());
        assertEquals(BankAccountStatus.FROZEN, frozen.status());
        assertTrue(!unchanged.changed());
        assertTrue(active.changed());
        assertEquals(2, scalarInt("SELECT COUNT(*) FROM notifications WHERE notification_type='BANK_ACCOUNT_STATUS_CHANGED'"));
    }

    @Test
    void notificationFailureRollsBackTopUpBalanceAndLedger() throws Exception {
        repository.account(1L);
        BankRepository failing = new BankRepository(connections, failingNotifications());

        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> failing.topUp(9L, 1L, new BigDecimal("50.00"),
                        UUID.randomUUID().toString()));

        assertEquals(new BigDecimal("0.00"), repository.account(1L).balance());
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM bank_ledger_entries"));
    }

    private void createSchema() throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, username VARCHAR(64) UNIQUE NOT NULL, display_name VARCHAR(100) NOT NULL, enabled BOOLEAN NOT NULL)");
            statement.execute("CREATE TABLE bank_accounts (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNIQUE NOT NULL, balance DECIMAL(15,2) NOT NULL DEFAULT 0.00 CHECK (balance >= 0), status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','FROZEN')), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY (user_id) REFERENCES users(id))");
            statement.execute("CREATE TABLE bank_ledger_entries (id BIGINT AUTO_INCREMENT PRIMARY KEY, account_id BIGINT NOT NULL, entry_type VARCHAR(32) NOT NULL, direction VARCHAR(8) NOT NULL, amount DECIMAL(15,2) NOT NULL CHECK (amount > 0), balance_after DECIMAL(15,2) NOT NULL CHECK (balance_after >= 0), reference_no VARCHAR(64) NOT NULL, counterparty_user_id BIGINT, operator_user_id BIGINT, description VARCHAR(255) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(account_id, entry_type, reference_no), FOREIGN KEY(account_id) REFERENCES bank_accounts(id))");
            statement.execute("CREATE TABLE notifications (id BIGINT AUTO_INCREMENT PRIMARY KEY, recipient_user_id BIGINT NOT NULL, sender_user_id BIGINT, notification_type VARCHAR(40) NOT NULL, source_module VARCHAR(40) NOT NULL, title VARCHAR(160) NOT NULL, content VARCHAR(1000) NOT NULL, target VARCHAR(40) NOT NULL, related_entity_id BIGINT, is_read BOOLEAN DEFAULT FALSE, read_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    private void seedUsers() throws SQLException {
        execute("INSERT INTO users VALUES (1,'student','张同学',TRUE),(2,'teacher','李老师',TRUE),(9,'bankadmin','银行管理员',TRUE)");
    }

    private void insertLedger(long accountId, String type, String direction,
                              String amount, String balanceAfter, String reference) throws SQLException {
        execute("INSERT INTO bank_ledger_entries(account_id,entry_type,direction,amount,balance_after,reference_no,description) VALUES ("
                + accountId + ",'" + type + "','" + direction + "'," + amount + ","
                + balanceAfter + ",'" + reference + "','测试流水')");
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

    private NotificationWriter failingNotifications() {
        return new NotificationWriter() {
            @Override
            public void insert(Connection connection, NotificationDraft draft) throws SQLException {
                throw new SQLException("notification failed");
            }

            @Override
            public void insertBatch(Connection connection, List<NotificationDraft> drafts)
                    throws SQLException {
                throw new SQLException("notification failed");
            }
        };
    }
}
