package com.vcampus.server.database;

import com.vcampus.server.config.DatabaseConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.time.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LibraryNoticeRepositoryTest {
    private static final Instant NOW=Instant.parse("2026-08-27T00:00:00Z");
    private ConnectionFactory connections;

    @BeforeEach void setUp()throws Exception{
        connections=new ConnectionFactory(new DatabaseConfig("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        try(Connection c=connections.openConnection();Statement s=c.createStatement()){
            s.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");s.execute("INSERT INTO users VALUES(1),(2),(3)");
            s.execute("CREATE TABLE books(id BIGINT PRIMARY KEY,title VARCHAR(200))");s.execute("INSERT INTO books VALUES(10,'到期书'),(11,'逾期书'),(12,'已归还')");
            s.execute("CREATE TABLE book_copies(id BIGINT PRIMARY KEY,book_id BIGINT,barcode VARCHAR(10))");s.execute("INSERT INTO book_copies VALUES(101,10,'B000000101'),(102,11,'B000000102'),(103,12,'B000000103')");
            s.execute("CREATE TABLE library_loans(id BIGINT PRIMARY KEY,copy_id BIGINT,borrower_user_id BIGINT,due_at TIMESTAMP,returned_at TIMESTAMP,due_notice_sent_at TIMESTAMP,overdue_notice_sent_at TIMESTAMP)");
            s.execute("CREATE TABLE notifications(id BIGINT AUTO_INCREMENT PRIMARY KEY,recipient_user_id BIGINT,sender_user_id BIGINT,notification_type VARCHAR(40),source_module VARCHAR(40),title VARCHAR(160),content VARCHAR(1000),target VARCHAR(40),related_entity_id BIGINT,is_read BOOLEAN DEFAULT FALSE,read_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        }
        loan(501,101,1,NOW.plus(Duration.ofDays(2)),null);loan(502,102,2,NOW.minus(Duration.ofHours(1)),null);loan(503,103,3,NOW.minus(Duration.ofDays(1)),NOW);
    }

    @Test void scansAreIdempotentAndIgnoreReturnedLoans()throws Exception{
        var repo=new LibraryNoticeRepository(connections,new NotificationRepository(connections));
        assertEquals(1,repo.sendDueSoon(NOW,NOW.plus(Duration.ofDays(3)),100));
        assertEquals(0,repo.sendDueSoon(NOW,NOW.plus(Duration.ofDays(3)),100));
        assertEquals(1,repo.sendOverdue(NOW,100));assertEquals(0,repo.sendOverdue(NOW,100));
        assertEquals(2,count("SELECT COUNT(*) FROM notifications"));
        assertEquals(0,count("SELECT COUNT(*) FROM notifications WHERE related_entity_id=503"));
        assertEquals(1,count("SELECT COUNT(*) FROM library_loans WHERE id=501 AND due_notice_sent_at IS NOT NULL"));
        assertEquals(1,count("SELECT COUNT(*) FROM library_loans WHERE id=502 AND overdue_notice_sent_at IS NOT NULL"));
    }

    @Test void writerFailureRollsBackMarker()throws Exception{
        NotificationWriter failing=new NotificationWriter(){public void insert(Connection c,NotificationDraft d)throws SQLException{throw new SQLException("boom");}public void insertBatch(Connection c,java.util.List<NotificationDraft>d)throws SQLException{throw new SQLException("boom");}};
        var repo=new LibraryNoticeRepository(connections,failing);
        assertThrows(SQLException.class,()->repo.sendDueSoon(NOW,NOW.plus(Duration.ofDays(3)),100));
        assertEquals(0,count("SELECT COUNT(*) FROM library_loans WHERE id=501 AND due_notice_sent_at IS NOT NULL"));
        assertEquals(0,count("SELECT COUNT(*) FROM notifications"));
    }

    private void loan(long id,long copy,long user,Instant due,Instant returned)throws Exception{try(Connection c=connections.openConnection();PreparedStatement s=c.prepareStatement("INSERT INTO library_loans VALUES(?,?,?,?,?,NULL,NULL)")){s.setLong(1,id);s.setLong(2,copy);s.setLong(3,user);s.setTimestamp(4,Timestamp.from(due));if(returned==null)s.setNull(5,Types.TIMESTAMP);else s.setTimestamp(5,Timestamp.from(returned));s.executeUpdate();}}
    private int count(String sql)throws Exception{try(Connection c=connections.openConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getInt(1);}}
}
