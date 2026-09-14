package com.vcampus.server.database;

import com.vcampus.common.model.*;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.LibraryFeeStore.ReturnFeePreview;
import com.vcampus.server.database.LibraryFeeWriter.ClosedLoan;
import com.vcampus.server.database.LibraryFeeWriter.CreatedBill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LibraryFeeRepositoryTest {
    private static final Instant DUE = Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant CLOSED = Instant.parse("2026-09-03T08:00:00Z");
    private ConnectionFactory connections;
    private RecordingNotifications notifications;
    private LibraryFeeRepository repository;

    @BeforeEach void setUp() throws Exception {
        connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        createSchema(); seed(); notifications = new RecordingNotifications();
        repository = new LibraryFeeRepository(connections, notifications, ZoneId.of("Asia/Shanghai"));
    }

    @Test void previewsAndCreatesSnapshotBillWithNotification() throws Exception {
        var preview = repository.previewReturn(new ReturnFeePreview(
                "B000000001", CLOSED, LibraryReturnCondition.DAMAGED, LibraryDamageLevel.MAJOR));
        assertEquals(new BigDecimal("44.50"), preview.damageAmount());

        Optional<CreatedBill> result = LibraryTransactions.run(connections, c ->
                repository.createForReturn(c, new ClosedLoan(7, 11, 13, 17,
                        "Java 核心技术", "B000000001", DUE, CLOSED,
                        LibraryReturnCondition.DAMAGED, LibraryDamageLevel.MAJOR,
                        "封面及书脊严重破损", 3)));

        assertEquals("LFB000000001", result.orElseThrow().billNo());
        assertEquals(new BigDecimal("45.00"), result.orElseThrow().totalAmount());
        assertEquals(NotificationType.LIBRARY_FEE_CREATED, notifications.drafts.getFirst().type());
        var bill = repository.findForOwner(11, result.orElseThrow().billId()).orElseThrow();
        assertEquals(new BigDecimal("89.00"), bill.replacementPriceSnapshot());
        assertEquals(new BigDecimal("0.50"), bill.overdueAmount());
        assertEquals(new BigDecimal("0.5000"), bill.conditionRateSnapshot());
        try(Connection c=connection()){assertTrue(repository.hasPending(c, 11));}
        assertTrue(repository.findForOwner(12, bill.billId()).isEmpty());
    }

    @Test void searchesWithOwnershipBoundaryAndUpdatesPolicy() throws Exception {
        var damaged = new ClosedLoan(7, 11, 13, 17, "Java 核心技术", "B000000001",
                DUE, CLOSED, LibraryReturnCondition.DAMAGED, LibraryDamageLevel.MINOR, "封面破损", 3);
        LibraryTransactions.run(connections, c -> repository.createForReturn(c, damaged));
        assertEquals(1, repository.searchOwner(11, new LibraryFeeStore.FeeBillQuery("Java", LibraryFeeStatus.PENDING, 1, 10)).total());
        assertEquals(0, repository.searchOwner(12, new LibraryFeeStore.FeeBillQuery("", null, 1, 10)).total());
        assertEquals(1, repository.searchAll(new LibraryFeeStore.FeeBillQuery("student", null, 1, 10)).total());
        assertThrows(IllegalArgumentException.class, () -> repository.searchAll(
                new LibraryFeeStore.FeeBillQuery("", null, 0, 10)));
        var changed = repository.updatePolicy(3, new LibraryFeePolicy(2, new BigDecimal("1.00"),
                new BigDecimal("20.00"), new BigDecimal("0.1000"), new BigDecimal("0.6000"),
                new BigDecimal("1.0000")));
        assertEquals(2, changed.graceDays());
        assertEquals(new BigDecimal("1.00"), repository.currentPolicy().dailyOverdueFee());
    }

    @Test void zeroFeeIsSkippedAndCreationIsIdempotent() throws Exception {
        var normal = new ClosedLoan(8, 11, 13, 17, "Java 核心技术", "B000000001",
                CLOSED, CLOSED, LibraryReturnCondition.NORMAL, null, null, 3);
        assertTrue(LibraryTransactions.run(connections, c -> repository.createForReturn(c, normal)).isEmpty());
        var damaged = new ClosedLoan(7, 11, 13, 17, "Java 核心技术", "B000000001",
                DUE, CLOSED, LibraryReturnCondition.DAMAGED, LibraryDamageLevel.MINOR, "封面破损", 3);
        var first = LibraryTransactions.run(connections, c -> repository.createForReturn(c, damaged)).orElseThrow();
        var second = LibraryTransactions.run(connections, c -> repository.createForReturn(c, damaged)).orElseThrow();
        assertEquals(first.billId(), second.billId());
        assertEquals(1, scalar("SELECT COUNT(*) FROM library_fee_bills"));
        assertEquals(1, notifications.drafts.size());
    }

    @Test void damageOrLossWithoutPriceFailsButHistoricalNullCanBeRead() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE books SET replacement_price=NULL WHERE id=13");
        }
        assertThrows(IllegalArgumentException.class, () -> repository.previewReturn(new ReturnFeePreview(
                "B000000001", CLOSED, LibraryReturnCondition.LOST, null)));
    }

    private Connection connection() throws SQLException { return connections.openConnection(); }
    private int scalar(String sql) throws SQLException { try(Connection c=connection();Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getInt(1);} }

    private void createSchema() throws SQLException {
        try(Connection c=connection();Statement s=c.createStatement()) {
            s.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(50),display_name VARCHAR(80))");
            s.execute("CREATE TABLE books(id BIGINT PRIMARY KEY,title VARCHAR(200),replacement_price DECIMAL(10,2))");
            s.execute("CREATE TABLE book_copies(id BIGINT PRIMARY KEY,book_id BIGINT,barcode CHAR(10))");
            s.execute("CREATE TABLE library_loans(id BIGINT PRIMARY KEY,borrower_user_id BIGINT,copy_id BIGINT,due_at TIMESTAMP,returned_at TIMESTAMP)");
            s.execute("CREATE TABLE library_code_sequences(code_type VARCHAR(32) PRIMARY KEY,next_value BIGINT)");
            s.execute("CREATE TABLE library_fee_policy(id TINYINT PRIMARY KEY,grace_days INT,daily_overdue_fee DECIMAL(10,2),overdue_cap DECIMAL(10,2),minor_damage_rate DECIMAL(5,4),major_damage_rate DECIMAL(5,4),lost_rate DECIMAL(5,4),changed_by_user_id BIGINT,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE library_fee_bills(id BIGINT AUTO_INCREMENT PRIMARY KEY,bill_no CHAR(12) UNIQUE,loan_id BIGINT UNIQUE,borrower_user_id BIGINT,book_id BIGINT,copy_id BIGINT,status VARCHAR(16),return_condition VARCHAR(16),damage_level VARCHAR(16),due_at TIMESTAMP,closed_at TIMESTAMP,overdue_days INT,billable_days INT,replacement_price_snapshot DECIMAL(10,2),grace_days_snapshot INT,daily_overdue_fee_snapshot DECIMAL(10,2),overdue_cap_snapshot DECIMAL(10,2),condition_rate_snapshot DECIMAL(5,4),overdue_amount DECIMAL(10,2),damage_amount DECIMAL(10,2),lost_amount DECIMAL(10,2),total_amount DECIMAL(10,2),reason VARCHAR(255),created_by_user_id BIGINT,payment_reference_no VARCHAR(64),paid_at TIMESTAMP,waived_reason VARCHAR(255),waived_at TIMESTAMP,refund_reference_no VARCHAR(64),refunded_reason VARCHAR(255),refunded_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        }
    }
    private void seed() throws SQLException {
        try(Connection c=connection();Statement s=c.createStatement()) {
            s.executeUpdate("INSERT INTO users VALUES(3,'admin','管理员'),(11,'student','学生'),(12,'other','其他')");
            s.executeUpdate("INSERT INTO books VALUES(13,'Java 核心技术',89.00)");
            s.executeUpdate("INSERT INTO book_copies VALUES(17,13,'B000000001')");
            s.executeUpdate("INSERT INTO library_loans VALUES(7,11,17,TIMESTAMP '2026-09-01 16:00:00',NULL),(8,11,17,TIMESTAMP '2026-09-03 16:00:00',TIMESTAMP '2026-09-03 16:00:00')");
            s.executeUpdate("INSERT INTO library_code_sequences VALUES('LIBRARY_FEE_BILL',1)");
            s.executeUpdate("INSERT INTO library_fee_policy VALUES(1,1,0.50,30.00,0.2000,0.5000,1.0000,3,CURRENT_TIMESTAMP)");
        }
    }

    private static final class RecordingNotifications implements NotificationWriter {
        final List<NotificationDraft> drafts = new ArrayList<>();
        public void insert(Connection c, NotificationDraft d){drafts.add(d);}
        public void insertBatch(Connection c,List<NotificationDraft>d){drafts.addAll(d);}
    }
}
