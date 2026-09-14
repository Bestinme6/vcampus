package com.vcampus.server.database;

import com.vcampus.common.model.*;
import com.vcampus.server.config.DatabaseConfig;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LibraryFeeCloseoutTransactionTest {
    @Test void feeFailureRollsBackLoanAndCopy() throws Exception {
        var connections=new ConnectionFactory(new DatabaseConfig("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        try(Connection c=connections.openConnection();Statement s=c.createStatement()){
            s.execute("CREATE TABLE books(id BIGINT PRIMARY KEY,enabled BOOLEAN,title VARCHAR(200))");
            s.execute("CREATE TABLE book_copies(id BIGINT PRIMARY KEY,book_id BIGINT,barcode CHAR(10),status VARCHAR(16),status_reason VARCHAR(255))");
            s.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,enabled BOOLEAN)");
            s.execute("CREATE TABLE library_loans(id BIGINT PRIMARY KEY,copy_id BIGINT,borrower_user_id BIGINT,due_at TIMESTAMP,returned_at TIMESTAMP,return_condition VARCHAR(16),return_operator_user_id BIGINT)");
            s.execute("CREATE TABLE library_reservations(id BIGINT PRIMARY KEY,book_id BIGINT,borrower_user_id BIGINT,status VARCHAR(16),created_at TIMESTAMP,notified_at TIMESTAMP)");
            s.execute("INSERT INTO books VALUES(10,TRUE,'Java')");s.execute("INSERT INTO book_copies VALUES(20,10,'B000000020','ON_LOAN',NULL)");
            s.execute("INSERT INTO users VALUES(30,TRUE),(90,TRUE)");s.execute("INSERT INTO library_loans VALUES(40,20,30,CURRENT_TIMESTAMP,NULL,NULL,NULL)");
        }
        NotificationWriter notifications=new NotificationWriter(){public void insert(Connection c,NotificationDraft d){}public void insertBatch(Connection c,java.util.List<NotificationDraft>d){}};
        LibraryFeeWriter failing=new LibraryFeeWriter(){public boolean hasPending(Connection c,long u){return false;}public java.util.Optional<CreatedBill> createForReturn(Connection c,ClosedLoan l)throws SQLException{throw new SQLException("notification failed");}};
        var repo=new LibraryLoanRepository(connections,notifications,failing);
        assertThrows(SQLException.class,()->repo.returnLoan(new LibraryLoanStore.ReturnCommand(30,40L,null,90,
                LibraryReturnCondition.DAMAGED,LibraryDamageLevel.MINOR,"破损",Instant.now(),true)));
        try(Connection c=connections.openConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT l.returned_at,c.status FROM library_loans l JOIN book_copies c ON c.id=l.copy_id")){r.next();assertNull(r.getTimestamp(1));assertEquals("ON_LOAN",r.getString(2));}
    }
}
