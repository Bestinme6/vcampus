package com.vcampus.server.database;

import com.vcampus.common.model.*;
import com.vcampus.server.config.DatabaseConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class LibraryReservationRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");
    private ConnectionFactory connections;
    private LibraryReservationRepository reservations;
    private LibraryLoanRepository loans;

    @BeforeEach void setUp() throws SQLException {
        connections = new ConnectionFactory(new DatabaseConfig("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", ""));
        execute("CREATE TABLE users(id BIGINT PRIMARY KEY, enabled BOOLEAN)",
                "CREATE TABLE roles(id BIGINT PRIMARY KEY, role_code VARCHAR(40))",
                "CREATE TABLE user_roles(user_id BIGINT, role_id BIGINT)",
                "INSERT INTO users VALUES(1,TRUE),(2,TRUE),(3,TRUE),(4,TRUE),(9,TRUE)",
                "INSERT INTO roles VALUES(1,'STUDENT'),(2,'TEACHER')",
                "INSERT INTO user_roles VALUES(1,1),(2,1),(3,1),(4,2),(9,1)",
                "CREATE TABLE books(id BIGINT PRIMARY KEY,catalog_code VARCHAR(20),title VARCHAR(200),enabled BOOLEAN)",
                "INSERT INTO books VALUES(10,'BK000000010','Java',TRUE),(11,'BK000000011','Other',TRUE)",
                "CREATE TABLE book_copies(id BIGINT PRIMARY KEY, book_id BIGINT,barcode VARCHAR(20),status VARCHAR(20),status_reason VARCHAR(255))",
                "INSERT INTO book_copies VALUES(100,10,'B000000100','ON_LOAN',NULL),(101,10,'B000000101','AVAILABLE',NULL),(102,11,'B000000102','DAMAGED','damage')",
                "CREATE TABLE library_loans(id BIGINT AUTO_INCREMENT PRIMARY KEY,copy_id BIGINT,borrower_user_id BIGINT,borrowed_at TIMESTAMP,initial_due_at TIMESTAMP,due_at TIMESTAMP,renewal_count INT,returned_at TIMESTAMP,return_condition VARCHAR(20),channel VARCHAR(20),checkout_operator_user_id BIGINT,return_operator_user_id BIGINT,due_notice_sent_at TIMESTAMP,overdue_notice_sent_at TIMESTAMP)",
                "INSERT INTO library_loans VALUES(500,100,1,TIMESTAMP '2026-08-20 00:00:00',TIMESTAMP '2026-09-20 00:00:00',TIMESTAMP '2026-09-20 00:00:00',0,NULL,NULL,'SELF_SERVICE',1,NULL,NULL,NULL)",
                "CREATE TABLE library_reservations(id BIGINT AUTO_INCREMENT PRIMARY KEY,book_id BIGINT NOT NULL,borrower_user_id BIGINT NOT NULL,status VARCHAR(16) NOT NULL,created_at TIMESTAMP NOT NULL,notified_at TIMESTAMP,active_book_id BIGINT GENERATED ALWAYS AS (CASE WHEN status='WAITING' THEN book_id ELSE NULL END),UNIQUE(borrower_user_id,active_book_id))",
                "CREATE TABLE notifications(id BIGINT AUTO_INCREMENT PRIMARY KEY,recipient_user_id BIGINT,sender_user_id BIGINT,notification_type VARCHAR(40),source_module VARCHAR(40),title VARCHAR(160),content VARCHAR(1000),target VARCHAR(40),related_entity_id BIGINT,is_read BOOLEAN DEFAULT FALSE,read_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        reservations = new LibraryReservationRepository(connections);
        loans = new LibraryLoanRepository(connections);
    }

    @Test void onlyOnLoanAllowsReservationEvenWhenAnotherCopyAvailable() throws SQLException {
        assertTrue(reservations.create(2,10,NOW)>0);
        for (String state : List.of("DAMAGED","LOST","WITHDRAWN","AVAILABLE")) {
            execute("UPDATE book_copies SET status='"+state+"' WHERE id=102");
            assertThrows(LibraryRuleException.class,()->reservations.create(3,11,NOW));
        }
        assertThrows(LibraryRuleException.class,()->reservations.create(1,10,NOW));
        assertThrows(LibraryRuleException.class,()->reservations.create(4,10,NOW));
        execute("UPDATE books SET enabled=FALSE WHERE id=10");
        assertThrows(LibraryRuleException.class,()->reservations.create(3,10,NOW));
    }

    @Test void duplicateAndOwnershipChecksAllowRecreationAfterCancellation() throws SQLException {
        long id = reservations.create(2,10,NOW);
        assertThrows(LibraryRuleException.class,()->reservations.create(2,10,NOW));
        assertThrows(LibraryRuleException.class,()->reservations.cancel(3,id));
        assertTrue(reservations.cancel(2,id));
        assertFalse(reservations.cancel(2,id));
        assertTrue(reservations.create(2,10,NOW)>id);
        assertEquals(1,reservations.search(2,LibraryReservationStatus.WAITING,1,10).total());
        assertEquals(0,reservations.search(3,null,1,10).total());
    }

    @Test void normalReturnNotifiesEveryWaitingStudentExactlyOnce() throws SQLException {
        reservations.create(2,10,NOW); reservations.create(3,10,NOW);
        long cancelled=reservations.create(9,10,NOW); reservations.cancel(9,cancelled);
        loans.returnLoan(normalReturn());
        assertEquals(2,count("SELECT COUNT(*) FROM notifications WHERE notification_type='LIBRARY_RESERVATION_AVAILABLE' AND target='LIBRARY_CATALOG' AND related_entity_id=10"));
        assertEquals(2,count("SELECT COUNT(*) FROM library_reservations WHERE status='NOTIFIED' AND notified_at IS NOT NULL"));
        assertThrows(LibraryRuleException.class,()->loans.returnLoan(normalReturn()));
        assertEquals(2,count("SELECT COUNT(*) FROM notifications WHERE notification_type='LIBRARY_RESERVATION_AVAILABLE'"));
    }

    @Test void damagedAndLostReturnsDoNotNotifyWaitingStudents() throws SQLException {
        reservations.create(2,10,NOW);
        loans.returnLoan(new LibraryLoanStore.ReturnCommand(0,500L,null,9,LibraryReturnCondition.DAMAGED,"damage",NOW,true));
        assertEquals(1,count("SELECT COUNT(*) FROM library_reservations WHERE status='WAITING'"));
        assertEquals(0,count("SELECT COUNT(*) FROM notifications WHERE notification_type='LIBRARY_RESERVATION_AVAILABLE'"));
        execute("UPDATE library_loans SET returned_at=NULL,return_condition=NULL WHERE id=500", "UPDATE book_copies SET status='ON_LOAN' WHERE id=100");
        loans.returnLoan(new LibraryLoanStore.ReturnCommand(0,500L,null,9,LibraryReturnCondition.LOST,"lost",NOW,true));
        assertEquals(0,count("SELECT COUNT(*) FROM notifications WHERE notification_type='LIBRARY_RESERVATION_AVAILABLE'"));
    }

    @Test void notificationFailureRollsBackReturnAndAllReminderState() throws SQLException {
        reservations.create(2,10,NOW); reservations.create(3,10,NOW);
        NotificationRepository writer = new NotificationRepository(connections);
        var broken = new LibraryLoanRepository(connections,new NotificationWriter() {
            public void insert(Connection c,NotificationDraft d)throws SQLException {
                if(d.type()==NotificationType.LIBRARY_RESERVATION_AVAILABLE && d.recipientUserId()==3) throw new SQLException("failure");
                writer.insert(c,d);
            }
            public void insertBatch(Connection c,List<NotificationDraft> drafts)throws SQLException {
                for(var draft:drafts)insert(c,draft);
            }
        });
        assertThrows(SQLException.class,()->broken.returnLoan(normalReturn()));
        assertEquals(0,count("SELECT COUNT(*) FROM notifications"));
        assertEquals(2,count("SELECT COUNT(*) FROM library_reservations WHERE status='WAITING'"));
        assertEquals(1,count("SELECT COUNT(*) FROM library_loans WHERE id=500 AND returned_at IS NULL"));
        assertEquals(1,count("SELECT COUNT(*) FROM book_copies WHERE id=100 AND status='ON_LOAN'"));
    }

    @Test void successfulBorrowCancelsOwnReminderAndRenewalDoesNotAddLoan() throws SQLException {
        reservations.create(2,10,NOW);
        var receipt=loans.borrow(new LibraryLoanStore.BorrowCommand(2,10L,null,2,LibraryLoanChannel.SELF_SERVICE,NOW,NOW.plus(Duration.ofDays(30)),5));
        assertEquals(1,count("SELECT COUNT(*) FROM library_reservations WHERE borrower_user_id=2 AND status='CANCELLED'"));
        loans.renew(new LibraryLoanStore.RenewCommand(2,receipt.loanId(),NOW,Duration.ofDays(30)));
        assertEquals(2,count("SELECT COUNT(*) FROM library_loans"));
    }

    @Test void simultaneousDuplicateReservationsHaveOneWinner() throws Exception {
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task=()-> {start.await();try {reservations.create(2,10,NOW);return true;}catch(LibraryRuleException e){return false;}};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();
            assertNotEquals(a.get(),b.get());
            assertEquals(1,count("SELECT COUNT(*) FROM library_reservations WHERE status='WAITING'"));
        }
    }

    @Test void reservationAndLastReturnRaceNeverLeavesAnUnnotifiedAcceptedReminder() throws Exception {
        for(int i=0;i<10;i++) {
            execute("DELETE FROM library_reservations","DELETE FROM notifications","UPDATE library_loans SET returned_at=NULL,return_condition=NULL WHERE id=500","UPDATE book_copies SET status='ON_LOAN' WHERE id=100");
            CountDownLatch start=new CountDownLatch(1);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var a=pool.submit(()-> {start.await();try{return reservations.create(2,10,NOW)>0;}catch(LibraryRuleException e){return false;}});
                var b=pool.submit(()-> {start.await();return loans.returnLoan(normalReturn());});
                start.countDown();boolean accepted=a.get();b.get();
                assertEquals(0,count("SELECT COUNT(*) FROM library_reservations WHERE status='WAITING'"));
                assertEquals(accepted?1:0,count("SELECT COUNT(*) FROM notifications WHERE notification_type='LIBRARY_RESERVATION_AVAILABLE'"));
            }
        }
    }

    private LibraryLoanStore.ReturnCommand normalReturn(){return new LibraryLoanStore.ReturnCommand(1,500L,null,1,LibraryReturnCondition.NORMAL,null,NOW,false);}
    private void execute(String... queries)throws SQLException {try(Connection c=connections.openConnection();Statement s=c.createStatement()){for(String q:queries)s.execute(q);}}
    private long count(String q)throws SQLException {try(Connection c=connections.openConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery(q)){r.next();return r.getLong(1);}}
}
