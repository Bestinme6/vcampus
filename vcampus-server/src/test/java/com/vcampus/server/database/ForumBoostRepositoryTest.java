package com.vcampus.server.database;

import com.vcampus.common.model.ForumBoostTier;
import com.vcampus.server.config.DatabaseConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForumBoostRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-15T02:00:00Z");
    private static final Instant DAY_START = Instant.parse("2026-09-14T16:00:00Z");
    private static final Instant DAY_END = Instant.parse("2026-09-15T16:00:00Z");

    private ConnectionFactory connections;
    private BankRepository bank;
    private ForumBoostRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
                "sa", ""));
        createSchema();
        seedForum();
        var notifications = new NotificationRepository(connections);
        bank = new BankRepository(connections, notifications);
        repository = new ForumBoostRepository(connections, bank, notifications);
    }

    @Test
    void createAtomicallyDebitsPersistsBoostAndSendsBankNotification() throws Exception {
        fund(2L, "50.00");
        UUID operationId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        var receipt = repository.create(11L, 2L, ForumBoostTier.FIVE, operationId,
                DAY_START, DAY_END, NOW);

        assertAll(
                () -> assertEquals(new BigDecimal("5.00"), receipt.amount()),
                () -> assertEquals(50, receipt.initialHeat()),
                () -> assertEquals(new BigDecimal("45.00"), receipt.balanceAfter()),
                () -> assertEquals("FB-" + operationId, receipt.bankReference()),
                () -> assertFalse(receipt.duplicate()),
                () -> assertEquals(1, scalarInt(
                        "SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='FORUM_BOOST'")),
                () -> assertEquals("DEBIT", scalarString(
                        "SELECT direction FROM bank_ledger_entries WHERE entry_type='FORUM_BOOST'")),
                () -> assertEquals(1, scalarInt(
                        "SELECT COUNT(*) FROM forum_post_boosts WHERE post_id=11 AND initial_heat=50")),
                () -> assertEquals(48L, Duration.between(NOW, scalarInstant(
                        "SELECT expires_at FROM forum_post_boosts WHERE post_id=11")).toHours()),
                () -> assertEquals(1, scalarInt(
                        "SELECT COUNT(*) FROM notifications WHERE notification_type=" +
                                "'BANK_FORUM_BOOST_PAID' AND source_module='BANK' " +
                                "AND target='BANK_LEDGER'")));
        String notice = scalarString(
                "SELECT content FROM notifications WHERE notification_type='BANK_FORUM_BOOST_PAID'");
        assertTrue(notice.contains("示例帖子"));
        assertTrue(notice.contains("5.00 元"));
        assertTrue(notice.contains("50 初始热度"));
        assertTrue(notice.contains("45.00 元"));
        assertTrue(notice.contains("FB-" + operationId));
    }

    @Test
    void identicalOperationReturnsOriginalReceiptWithoutRepeatingSideEffects() throws Exception {
        fund(2L, "50.00");
        UUID operationId = UUID.fromString("550e8400-e29b-41d4-a716-446655440010");

        var first = repository.create(11L, 2L, ForumBoostTier.TEN, operationId,
                DAY_START, DAY_END, NOW);
        var duplicate = repository.create(11L, 2L, ForumBoostTier.TEN, operationId,
                DAY_START, DAY_END, NOW.plusSeconds(60));

        assertAll(
                () -> assertTrue(duplicate.duplicate()),
                () -> assertEquals(first.boostId(), duplicate.boostId()),
                () -> assertEquals(first.bankLedgerId(), duplicate.bankLedgerId()),
                () -> assertEquals(first.balanceAfter(), duplicate.balanceAfter()),
                () -> assertEquals(1, scalarInt(
                        "SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='FORUM_BOOST'")),
                () -> assertEquals(1, scalarInt("SELECT COUNT(*) FROM forum_post_boosts")),
                () -> assertEquals(1, scalarInt(
                        "SELECT COUNT(*) FROM notifications WHERE notification_type='BANK_FORUM_BOOST_PAID'")));
    }

    @Test
    void operationUuidCannotBeReusedForAnotherTier() throws Exception {
        fund(2L, "50.00");
        UUID operationId = UUID.fromString("550e8400-e29b-41d4-a716-446655440020");
        repository.create(11L, 2L, ForumBoostTier.FIVE, operationId,
                DAY_START, DAY_END, NOW);

        ForumBoostRuleException failure = assertThrows(ForumBoostRuleException.class,
                () -> repository.create(11L, 2L, ForumBoostTier.FIFTEEN, operationId,
                        DAY_START, DAY_END, NOW));

        assertTrue(failure.getMessage().contains("操作号"));
        assertEquals(new BigDecimal("45.00"), bank.account(2L).balance());
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM forum_post_boosts"));
    }

    @Test
    void stateAndCreateEnforceThreeOperationsAndThirtyYuanPerUserPostDay() throws Exception {
        fund(2L, "100.00");
        repository.create(11L, 2L, ForumBoostTier.TEN, UUID.randomUUID(), DAY_START, DAY_END, NOW);
        repository.create(11L, 2L, ForumBoostTier.TEN, UUID.randomUUID(), DAY_START, DAY_END, NOW);
        repository.create(11L, 2L, ForumBoostTier.TEN, UUID.randomUUID(), DAY_START, DAY_END, NOW);

        var state = repository.state(11L, 2L, DAY_START, DAY_END, NOW);
        assertAll(
                () -> assertFalse(state.eligible()),
                () -> assertEquals(3, state.userOperationsUsed()),
                () -> assertEquals(new BigDecimal("30.00"), state.userAmountUsed()),
                () -> assertTrue(state.availableTiers().isEmpty()));
        assertThrows(ForumBoostRuleException.class, () -> repository.create(
                11L, 2L, ForumBoostTier.FIVE, UUID.randomUUID(), DAY_START, DAY_END, NOW));
    }

    @Test
    void thirtyYuanLimitCanBeReachedBeforeOperationLimit() throws Exception {
        fund(2L, "100.00");
        repository.create(11L, 2L, ForumBoostTier.FIFTEEN, UUID.randomUUID(), DAY_START, DAY_END, NOW);
        repository.create(11L, 2L, ForumBoostTier.FIFTEEN, UUID.randomUUID(), DAY_START, DAY_END, NOW);

        assertThrows(ForumBoostRuleException.class, () -> repository.create(
                11L, 2L, ForumBoostTier.FIVE, UUID.randomUUID(), DAY_START, DAY_END, NOW));
        assertEquals(2, scalarInt("SELECT COUNT(*) FROM forum_post_boosts"));
    }

    @Test
    void postDailyLimitAllowsExactlyTwoHundredAndRejectsMore() throws Exception {
        seedPostBoostTotal("195.00");
        fund(2L, "20.00");
        fund(3L, "20.00");

        repository.create(11L, 2L, ForumBoostTier.FIVE, UUID.randomUUID(), DAY_START, DAY_END, NOW);
        ForumBoostRuleException failure = assertThrows(ForumBoostRuleException.class,
                () -> repository.create(11L, 3L, ForumBoostTier.FIVE, UUID.randomUUID(),
                        DAY_START, DAY_END, NOW));

        assertTrue(failure.getMessage().contains("帖子"));
        assertEquals(new BigDecimal("200.00"), scalarDecimal(
                "SELECT SUM(amount) FROM forum_post_boosts WHERE post_id=11"));
    }

    @Test
    void ineligiblePostStatesAndSelfBoostAreRejectedBeforeDebit() throws Exception {
        fund(1L, "50.00");
        fund(2L, "50.00");
        for (long postId : new long[]{11L, 12L, 13L, 14L, 15L, 999L}) {
            long actor = postId == 11L ? 1L : 2L;
            assertThrows(ForumBoostRuleException.class, () -> repository.create(
                    postId, actor, ForumBoostTier.FIVE, UUID.randomUUID(),
                    DAY_START, DAY_END, NOW));
        }
        assertEquals(0, scalarInt(
                "SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='FORUM_BOOST'"));
    }

    @Test
    void bankFailuresAndNotificationFailureRollbackEveryBoostSideEffect() throws Exception {
        fund(2L, "5.00");
        assertThrows(BankRuleException.class, () -> repository.create(
                11L, 2L, ForumBoostTier.TEN, UUID.randomUUID(), DAY_START, DAY_END, NOW));
        bank.setStatus(9L, "sponsor", com.vcampus.common.model.BankAccountStatus.FROZEN);
        assertThrows(BankRuleException.class, () -> repository.create(
                11L, 2L, ForumBoostTier.FIVE, UUID.randomUUID(), DAY_START, DAY_END, NOW));
        bank.setStatus(9L, "sponsor", com.vcampus.common.model.BankAccountStatus.ACTIVE);

        NotificationWriter failing = new NotificationWriter() {
            @Override public void insert(Connection connection, NotificationDraft draft) throws SQLException {
                throw new SQLException("notification failed");
            }
            @Override public void insertBatch(Connection connection, java.util.List<NotificationDraft> drafts)
                    throws SQLException { throw new SQLException("notification failed"); }
        };
        ForumBoostRepository failingRepository = new ForumBoostRepository(connections, bank, failing);
        assertThrows(SQLException.class, () -> failingRepository.create(
                11L, 2L, ForumBoostTier.FIVE, UUID.randomUUID(), DAY_START, DAY_END, NOW));

        assertEquals(new BigDecimal("5.00"), bank.account(2L).balance());
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM forum_post_boosts"));
        assertEquals(0, scalarInt(
                "SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='FORUM_BOOST'"));
        assertEquals(0, scalarInt(
                "SELECT COUNT(*) FROM notifications WHERE notification_type='BANK_FORUM_BOOST_PAID'"));
    }

    @Test
    void boostInsertFailureRollsBackThePrecedingBankDebit() throws Exception {
        fund(2L, "20.00");
        execute("ALTER TABLE forum_post_boosts ADD CONSTRAINT test_reject_sponsor CHECK(sponsor_user_id<>2)");

        assertThrows(SQLException.class, () -> repository.create(
                11L, 2L, ForumBoostTier.FIVE, UUID.randomUUID(), DAY_START, DAY_END, NOW));

        assertEquals(new BigDecimal("20.00"), bank.account(2L).balance());
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM forum_post_boosts"));
        assertEquals(0, scalarInt(
                "SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='FORUM_BOOST'"));
        assertEquals(0, scalarInt(
                "SELECT COUNT(*) FROM notifications WHERE notification_type='BANK_FORUM_BOOST_PAID'"));
    }

    @Test
    void concurrentRequestsCannotCrossThePostDailyLimit() throws Exception {
        seedPostBoostTotal("195.00");
        fund(2L, "20.00");
        fund(3L, "20.00");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createAfterBarrier(2L, ready, start));
            var second = executor.submit(() -> createAfterBarrier(3L, ready, start));
            ready.await();
            start.countDown();
            int successes = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertEquals(1, successes);
        }
        assertEquals(new BigDecimal("200.00"), scalarDecimal(
                "SELECT SUM(amount) FROM forum_post_boosts WHERE post_id=11"));
    }

    @Test
    void concurrentUuidReuseAcrossPostsCreatesOnlyOneBusinessOperation() throws Exception {
        fund(2L, "20.00");
        UUID operationId = UUID.fromString("550e8400-e29b-41d4-a716-446655440030");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createWithOperationAfterBarrier(
                    11L, 2L, operationId, ready, start));
            var second = executor.submit(() -> createWithOperationAfterBarrier(
                    16L, 2L, operationId, ready, start));
            ready.await();
            start.countDown();
            int successes = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertEquals(1, successes);
        }
        assertEquals(new BigDecimal("15.00"), bank.account(2L).balance());
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM forum_post_boosts"));
        assertEquals(1, scalarInt(
                "SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='FORUM_BOOST'"));
        assertEquals(1, scalarInt(
                "SELECT COUNT(*) FROM notifications WHERE notification_type='BANK_FORUM_BOOST_PAID'"));
    }

    private void fund(long userId, String amount) throws Exception {
        bank.topUp(9L, username(userId), new BigDecimal(amount), UUID.randomUUID().toString());
    }

    private String username(long userId) {
        return switch ((int) userId) {
            case 1 -> "author";
            case 2 -> "sponsor";
            case 3 -> "other";
            default -> throw new IllegalArgumentException("unknown user");
        };
    }

    private void createSchema() throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE," +
                    "display_name VARCHAR(100),enabled BOOLEAN NOT NULL)");
            statement.execute("CREATE TABLE forum_sections(id BIGINT PRIMARY KEY,enabled BOOLEAN NOT NULL)");
            statement.execute("CREATE TABLE forum_posts(id BIGINT PRIMARY KEY,section_id BIGINT NOT NULL," +
                    "author_user_id BIGINT NOT NULL,title VARCHAR(160) NOT NULL,status VARCHAR(16) NOT NULL," +
                    "pinned BOOLEAN NOT NULL,is_announcement BOOLEAN NOT NULL," +
                    "FOREIGN KEY(section_id) REFERENCES forum_sections(id)," +
                    "FOREIGN KEY(author_user_id) REFERENCES users(id))");
            statement.execute("CREATE TABLE bank_accounts(id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "user_id BIGINT UNIQUE NOT NULL,balance DECIMAL(15,2) NOT NULL DEFAULT 0.00," +
                    "status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,FOREIGN KEY(user_id) REFERENCES users(id))");
            statement.execute("CREATE TABLE bank_ledger_entries(id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "account_id BIGINT NOT NULL,entry_type VARCHAR(32) NOT NULL,direction VARCHAR(8) NOT NULL," +
                    "amount DECIMAL(15,2) NOT NULL,balance_after DECIMAL(15,2) NOT NULL," +
                    "reference_no VARCHAR(64) NOT NULL,counterparty_user_id BIGINT,operator_user_id BIGINT," +
                    "description VARCHAR(255) NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "UNIQUE(account_id,entry_type,reference_no),FOREIGN KEY(account_id) REFERENCES bank_accounts(id))");
            statement.execute("CREATE TABLE notifications(id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "recipient_user_id BIGINT NOT NULL,sender_user_id BIGINT,notification_type VARCHAR(40) NOT NULL," +
                    "source_module VARCHAR(40) NOT NULL,title VARCHAR(160) NOT NULL,content VARCHAR(1000) NOT NULL," +
                    "target VARCHAR(40) NOT NULL,related_entity_id BIGINT,is_read BOOLEAN DEFAULT FALSE," +
                    "read_at TIMESTAMP,deleted_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE forum_post_boosts(id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "post_id BIGINT NOT NULL,sponsor_user_id BIGINT NOT NULL,tier VARCHAR(16) NOT NULL," +
                    "amount DECIMAL(15,2) NOT NULL,initial_heat INT NOT NULL,operation_id CHAR(36) NOT NULL," +
                    "bank_ledger_id BIGINT NOT NULL,created_at TIMESTAMP NOT NULL,expires_at TIMESTAMP NOT NULL," +
                    "UNIQUE(sponsor_user_id,operation_id),UNIQUE(bank_ledger_id)," +
                    "FOREIGN KEY(post_id) REFERENCES forum_posts(id),FOREIGN KEY(sponsor_user_id) REFERENCES users(id)," +
                    "FOREIGN KEY(bank_ledger_id) REFERENCES bank_ledger_entries(id))");
        }
    }

    private void seedForum() throws SQLException {
        execute("INSERT INTO users VALUES" +
                "(1,'author','发帖人',TRUE),(2,'sponsor','助力人',TRUE)," +
                "(3,'other','其他同学',TRUE),(9,'bankadmin','银行管理员',TRUE)");
        execute("INSERT INTO forum_sections VALUES(1,TRUE)");
        execute("INSERT INTO forum_sections VALUES(2,FALSE)");
        execute("INSERT INTO forum_posts VALUES" +
                "(11,1,1,'示例帖子','NORMAL',FALSE,FALSE)," +
                "(12,1,1,'隐藏帖子','HIDDEN',FALSE,FALSE)," +
                "(13,2,1,'停用板块帖子','NORMAL',FALSE,FALSE)," +
                "(14,1,1,'置顶帖子','NORMAL',TRUE,FALSE)," +
                "(15,1,1,'公告帖子','NORMAL',FALSE,TRUE)," +
                "(16,1,1,'另一个帖子','NORMAL',FALSE,FALSE)");
    }

    private void seedPostBoostTotal(String amount) throws SQLException {
        execute("INSERT INTO users VALUES(100,'seed','种子用户',TRUE)");
        execute("INSERT INTO bank_accounts(id,user_id,balance,status) VALUES(100,100,0.00,'ACTIVE')");
        execute("INSERT INTO bank_ledger_entries(id,account_id,entry_type,direction,amount," +
                "balance_after,reference_no,description) VALUES(100,100,'FORUM_BOOST','DEBIT'," +
                amount + ",0.00,'FB-SEED','种子助力')");
        execute("INSERT INTO forum_post_boosts(post_id,sponsor_user_id,tier,amount,initial_heat," +
                "operation_id,bank_ledger_id,created_at,expires_at) VALUES(11,100,'FIFTEEN'," +
                amount + ",1950,'550e8400-e29b-41d4-a716-446655449999',100," +
                "TIMESTAMP '2026-09-15 01:00:00',TIMESTAMP '2026-09-17 01:00:00')");
    }

    private boolean createAfterBarrier(long userId, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        start.await();
        try {
            repository.create(11L, userId, ForumBoostTier.FIVE, UUID.randomUUID(),
                    DAY_START, DAY_END, NOW);
            return true;
        } catch (ForumBoostRuleException expected) {
            return false;
        }
    }

    private boolean createWithOperationAfterBarrier(
            long postId,
            long userId,
            UUID operationId,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            repository.create(postId, userId, ForumBoostTier.FIVE, operationId,
                    DAY_START, DAY_END, NOW);
            return true;
        } catch (ForumBoostRuleException expected) {
            return false;
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

    private String scalarString(String sql) throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private Instant scalarInstant(String sql) throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getTimestamp(1).toInstant();
        }
    }

    private BigDecimal scalarDecimal(String sql) throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getBigDecimal(1).setScale(2);
        }
    }
}
