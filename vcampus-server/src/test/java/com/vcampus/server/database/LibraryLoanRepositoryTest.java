package com.vcampus.server.database;

import com.vcampus.common.model.LibraryCirculationOperation;
import com.vcampus.common.model.LibraryLoanChannel;
import com.vcampus.common.model.LibraryReturnCondition;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.LibraryLoanStore.BorrowCommand;
import com.vcampus.server.database.LibraryLoanStore.BorrowReceipt;
import com.vcampus.server.database.LibraryLoanStore.LoanQuery;
import com.vcampus.server.database.LibraryLoanStore.RenewCommand;
import com.vcampus.server.database.LibraryLoanStore.RenewReceipt;
import com.vcampus.server.database.LibraryLoanStore.ReturnCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryLoanRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");
    private ConnectionFactory connections;
    private LibraryLoanRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
                "sa", ""));
        createSchema();
        seed();
        repository = new LibraryLoanRepository(connections);
    }

    @Test
    void previewAndQueriesExposeStateWithoutMutation() throws SQLException {
        var borrower = repository.findBorrower("teacher1").orElseThrow();
        var preview = repository.previewCirculation(
                borrower.userId(), "B000000109", LibraryCirculationOperation.BORROW, NOW, 10);

        assertEquals("教师一", borrower.displayName());
        assertTrue(preview.allowed());
        assertEquals(0, preview.activeLoans());
        assertEquals("AVAILABLE", scalarString("SELECT status FROM book_copies WHERE id=109"));
        assertEquals(1, repository.searchBorrowerLoans(
                201L, new LoanQuery("并发", true, false, 1, 10)).total());
        assertTrue(repository.searchAllLoans(
                new LoanQuery("student2", true, null, 1, 10)).total() >= 1);
    }

    @Test
    void borrowCreatesActiveLoanAndMarksCopyOnLoan() throws SQLException {
        BorrowReceipt receipt = repository.borrow(new BorrowCommand(
                204L, 10L, null, 204L, LibraryLoanChannel.SELF_SERVICE,
                NOW, NOW.plus(Duration.ofDays(60)), 10));

        assertEquals("ON_LOAN", scalarString(
                "SELECT status FROM book_copies WHERE id=" + receipt.copyId()));
        assertEquals(1L, scalarLong(
                "SELECT COUNT(*) FROM library_loans WHERE id=" + receipt.loanId() + " AND returned_at IS NULL"));
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM notifications"));
        assertEquals("204|204|LIBRARY_BORROWED|LIBRARY|LIBRARY_LOANS|" + receipt.loanId(),
                scalarString("SELECT CONCAT(recipient_user_id,'|',sender_user_id,'|',notification_type,'|',source_module,'|',target,'|',related_entity_id) FROM notifications"));
        assertTrue(scalarString("SELECT content FROM notifications").contains(receipt.barcode()));
    }

    @Test
    void limitAndOverdueBlockBorrowing() {
        LibraryRuleException limit = assertThrows(LibraryRuleException.class,
                () -> repository.borrow(new BorrowCommand(
                        205L, 10L, null, 205L, LibraryLoanChannel.SELF_SERVICE,
                        NOW, NOW.plus(Duration.ofDays(30)), 5)));
        assertEquals("借阅数量已达上限", limit.getMessage());

        LibraryRuleException overdue = assertThrows(LibraryRuleException.class,
                () -> repository.borrow(new BorrowCommand(
                        203L, 10L, null, 203L, LibraryLoanChannel.SELF_SERVICE,
                        NOW, NOW.plus(Duration.ofDays(30)), 5)));
        assertEquals("存在逾期图书，请先归还", overdue.getMessage());
    }

    @Test
    void renewalExtendsExistingDueAtOnceAndClearsNoticeMarker() throws SQLException {
        RenewReceipt receipt = repository.renew(new RenewCommand(
                201L, 501L, NOW, Duration.ofDays(15)));

        assertEquals(Instant.parse("2026-09-26T00:00:00Z"), receipt.dueAt());
        assertEquals(1, receipt.renewalCount());
        assertNull(timestamp("SELECT due_notice_sent_at FROM library_loans WHERE id=501"));
        assertEquals(1L, scalarLong(
                "SELECT COUNT(*) FROM notifications WHERE related_entity_id=501"));
        assertEquals("LIBRARY_RENEWED", scalarString(
                "SELECT notification_type FROM notifications WHERE related_entity_id=501"));
        assertTrue(scalarString(
                "SELECT content FROM notifications WHERE related_entity_id=501").contains("2026-09-26"));
        assertThrows(LibraryRuleException.class,
                () -> repository.renew(new RenewCommand(201L, 501L, NOW, Duration.ofDays(15))));
        assertThrows(LibraryRuleException.class,
                () -> repository.renew(new RenewCommand(203L, 503L, NOW, Duration.ofDays(15))));
    }

    @Test
    void selfReturnEnforcesOwnershipAndAdministratorCanCloseLostCopy() throws SQLException {
        assertThrows(LibraryRuleException.class, () -> repository.returnLoan(new ReturnCommand(
                201L, 502L, null, 201L, LibraryReturnCondition.NORMAL, null, NOW, false)));

        var receipt = repository.returnLoan(new ReturnCommand(
                202L, null, "B000000102", 900L, LibraryReturnCondition.LOST,
                "读者报失", NOW, true));

        assertEquals(LibraryReturnCondition.LOST, receipt.condition());
        assertEquals("LOST", scalarString("SELECT status FROM book_copies WHERE id=102"));
        assertEquals("读者报失", scalarString("SELECT status_reason FROM book_copies WHERE id=102"));
        assertEquals("LOST", scalarString("SELECT return_condition FROM library_loans WHERE id=502"));
        assertEquals(1L, scalarLong(
                "SELECT COUNT(*) FROM notifications WHERE related_entity_id=502"));
        assertEquals("202|900|LIBRARY_LOST", scalarString(
                "SELECT CONCAT(recipient_user_id,'|',sender_user_id,'|',notification_type) FROM notifications WHERE related_entity_id=502"));
        assertTrue(scalarString(
                "SELECT content FROM notifications WHERE related_entity_id=502").contains("读者报失"));
    }

    @Test
    void normalReturnNotifiesBorrower() throws SQLException {
        repository.returnLoan(new ReturnCommand(
                201L, 501L, null, 201L, LibraryReturnCondition.NORMAL,
                null, NOW, false));

        assertEquals(1L, scalarLong(
                "SELECT COUNT(*) FROM notifications WHERE related_entity_id=501"));
        assertEquals("201|201|LIBRARY_RETURNED", scalarString(
                "SELECT CONCAT(recipient_user_id,'|',sender_user_id,'|',notification_type) FROM notifications WHERE related_entity_id=501"));
        assertTrue(scalarString(
                "SELECT content FROM notifications WHERE related_entity_id=501").contains("B000000101"));
    }

    @Test
    void notificationFailureRollsBackBorrowAndCopyState() throws SQLException {
        try (Connection c = connections.openConnection(); Statement s = c.createStatement()) {
            s.execute("ALTER TABLE notifications ADD CONSTRAINT reject_library_receipts CHECK (notification_type NOT LIKE 'LIBRARY_%')");
        }

        assertThrows(SQLException.class, () -> repository.borrow(new BorrowCommand(
                204L, 11L, null, 204L, LibraryLoanChannel.SELF_SERVICE,
                NOW, NOW.plus(Duration.ofDays(60)), 10)));

        assertEquals(0L, scalarLong(
                "SELECT COUNT(*) FROM library_loans WHERE copy_id=111 AND returned_at IS NULL"));
        assertEquals("AVAILABLE", scalarString("SELECT status FROM book_copies WHERE id=111"));
    }

    @Test
    void twoBorrowersRacingForLastCopyYieldExactlyOneSuccess() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> raceBorrow(start, 204L));
            var second = executor.submit(() -> raceBorrow(start, 206L));
            start.countDown();
            List<Object> outcomes = List.of(first.get(), second.get());

            assertEquals(1, outcomes.stream().filter(BorrowReceipt.class::isInstance).count());
            assertEquals(1, outcomes.stream().filter(LibraryRuleException.class::isInstance).count());
            assertInstanceOf(LibraryRuleException.class,
                    outcomes.stream().filter(Throwable.class::isInstance).findFirst().orElseThrow());
            assertEquals(1L, scalarLong("SELECT COUNT(*) FROM library_loans WHERE copy_id=111 AND returned_at IS NULL"));
            assertEquals("ON_LOAN", scalarString("SELECT status FROM book_copies WHERE id=111"));
        }
    }

    private Object raceBorrow(CountDownLatch start, long borrowerId) {
        try {
            start.await();
            return repository.borrow(new BorrowCommand(
                    borrowerId, 11L, null, borrowerId, LibraryLoanChannel.SELF_SERVICE,
                    NOW, NOW.plus(Duration.ofDays(60)), 10));
        } catch (Throwable throwable) {
            return throwable;
        }
    }

    private void createSchema() throws SQLException {
        try (Connection c = connections.openConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, username VARCHAR(64) UNIQUE, display_name VARCHAR(100), enabled BOOLEAN)");
            s.execute("CREATE TABLE roles(id BIGINT PRIMARY KEY, role_code VARCHAR(64) UNIQUE)");
            s.execute("CREATE TABLE user_roles(user_id BIGINT, role_id BIGINT, PRIMARY KEY(user_id,role_id))");
            s.execute("CREATE TABLE books(id BIGINT PRIMARY KEY, isbn VARCHAR(20), title VARCHAR(200), enabled BOOLEAN)");
            s.execute("CREATE TABLE book_copies(id BIGINT PRIMARY KEY, book_id BIGINT, barcode CHAR(10) UNIQUE, shelf_location VARCHAR(80), status VARCHAR(16), status_reason VARCHAR(255), updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE library_loans(id BIGINT AUTO_INCREMENT PRIMARY KEY, copy_id BIGINT, borrower_user_id BIGINT, borrowed_at TIMESTAMP, initial_due_at TIMESTAMP, due_at TIMESTAMP, renewal_count INT, returned_at TIMESTAMP, return_condition VARCHAR(16), channel VARCHAR(16), checkout_operator_user_id BIGINT, return_operator_user_id BIGINT, due_notice_sent_at TIMESTAMP, overdue_notice_sent_at TIMESTAMP)");
            s.execute("CREATE TABLE notifications(id BIGINT AUTO_INCREMENT PRIMARY KEY, recipient_user_id BIGINT NOT NULL, sender_user_id BIGINT, notification_type VARCHAR(40) NOT NULL, source_module VARCHAR(40) NOT NULL, title VARCHAR(160) NOT NULL, content VARCHAR(1000) NOT NULL, target VARCHAR(40) NOT NULL, related_entity_id BIGINT, is_read BOOLEAN DEFAULT FALSE, read_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    private void seed() throws SQLException {
        try (Connection c = connections.openConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO roles VALUES(1,'STUDENT'),(2,'TEACHER'),(3,'LIBRARY_ADMIN')");
            s.executeUpdate("INSERT INTO users VALUES(201,'student1','学生一',TRUE),(202,'student2','学生二',TRUE),(203,'overdue','逾期学生',TRUE),(204,'teacher1','教师一',TRUE),(205,'full','满额学生',TRUE),(206,'teacher2','教师二',TRUE),(900,'admin','管理员',TRUE)");
            s.executeUpdate("INSERT INTO user_roles VALUES(201,1),(202,1),(203,1),(204,2),(205,1),(206,2),(900,3)");
            s.executeUpdate("INSERT INTO books VALUES(10,'9787111565277','并发编程',TRUE),(11,'9780134685991','Effective Java',TRUE)");
            List<String> copies = new ArrayList<>();
            for (int id=101; id<=111; id++) {
                int book=id==111?11:10;
                String status=(id<=108)?"ON_LOAN":"AVAILABLE";
                copies.add("("+id+","+book+",'B"+String.format("%09d",id)+"','A-01','"+status+"',NULL)");
            }
            s.executeUpdate("INSERT INTO book_copies(id,book_id,barcode,shelf_location,status,status_reason) VALUES"+String.join(",",copies));
            insertLoan(c,501,101,201,"2026-08-01T00:00:00Z","2026-09-11T00:00:00Z",0,true);
            insertLoan(c,502,102,202,"2026-08-01T00:00:00Z","2026-09-01T00:00:00Z",0,false);
            insertLoan(c,503,103,203,"2026-07-01T00:00:00Z","2026-08-01T00:00:00Z",0,false);
            for(int i=0;i<5;i++) insertLoan(c,510+i,104+i,205,"2026-08-01T00:00:00Z","2026-09-15T00:00:00Z",0,false);
        }
    }

    private void insertLoan(Connection connection,long id,long copy,long borrower,String borrowed,String due,int renew,boolean marker) throws SQLException {
        String sql = "INSERT INTO library_loans(id,copy_id,borrower_user_id,borrowed_at,initial_due_at,due_at,renewal_count,channel,checkout_operator_user_id,due_notice_sent_at) VALUES(?,?,?,?,?,?,?,?,?,?)";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.setLong(2, copy);
            statement.setLong(3, borrower);
            statement.setTimestamp(4, Timestamp.from(Instant.parse(borrowed)));
            statement.setTimestamp(5, Timestamp.from(Instant.parse(due)));
            statement.setTimestamp(6, Timestamp.from(Instant.parse(due)));
            statement.setInt(7, renew);
            statement.setString(8, "SELF_SERVICE");
            statement.setLong(9, borrower);
            if (marker) statement.setTimestamp(10, Timestamp.from(NOW));
            else statement.setNull(10, java.sql.Types.TIMESTAMP);
            statement.executeUpdate();
        }
    }

    private long scalarLong(String sql) throws SQLException { try(Connection c=connections.openConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getLong(1);} }
    private String scalarString(String sql) throws SQLException { try(Connection c=connections.openConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getString(1);} }
    private Instant timestamp(String sql) throws SQLException { try(Connection c=connections.openConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){r.next();Timestamp t=r.getTimestamp(1);return t==null?null:t.toInstant();} }
}
