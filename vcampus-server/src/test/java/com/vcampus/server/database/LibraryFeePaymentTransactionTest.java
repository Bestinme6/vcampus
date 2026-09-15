package com.vcampus.server.database;

import com.vcampus.common.model.*;
import com.vcampus.server.config.DatabaseConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LibraryFeePaymentTransactionTest {
    private ConnectionFactory connections; private BankRepository bank; private LibraryFeeRepository fees;
    @BeforeEach void setUp()throws Exception{
        connections=new ConnectionFactory(new DatabaseConfig("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        schema();seed();var notifications=new NotificationRepository(connections);bank=new BankRepository(connections,notifications);
        fees=new LibraryFeeRepository(connections,notifications,ZoneId.of("Asia/Shanghai"),bank);
    }

    @Test void paymentIsAtomicAndOperationRetryIsIdempotent()throws Exception{
        String op=UUID.randomUUID().toString();
        var preview=fees.paymentPreview(11,1);assertEquals(new BigDecimal("50.00"),preview.balance());
        var first=fees.pay(11,1,op,Instant.parse("2026-09-04T00:00:00Z"));
        assertEquals(LibraryFeeStatus.PAID,first.bill().status());assertEquals(new BigDecimal("32.20"),first.balanceAfter());
        var retry=fees.pay(11,1,op,Instant.parse("2026-09-04T00:01:00Z"));assertTrue(retry.duplicate());assertEquals(first.ledgerId(),retry.ledgerId());
        assertThrows(LibraryRuleException.class,()->fees.pay(11,1,UUID.randomUUID().toString(),Instant.now()));
        assertEquals(1,scalar("SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='LIBRARY_FEE'"));
        assertEquals(1,scalar("SELECT COUNT(*) FROM notifications WHERE notification_type='LIBRARY_FEE_PAID'"));
    }

    @Test void waiverAndRefundChangeOnlyAllowedStates()throws Exception{
        var waived=fees.waive(3,2,"特殊情况",Instant.now());assertEquals(LibraryFeeStatus.WAIVED,waived.status());
        assertThrows(LibraryRuleException.class,()->fees.waive(3,2,"再次免除",Instant.now()));
        fees.pay(11,1,UUID.randomUUID().toString(),Instant.now());
        var refunded=fees.refund(3,1,"复核退费",UUID.randomUUID().toString(),Instant.now());
        assertEquals(LibraryFeeStatus.REFUNDED,refunded.bill().status());assertEquals(new BigDecimal("50.00"),refunded.balanceAfter());
        assertEquals(1,scalar("SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='LIBRARY_REFUND'"));
    }

    @Test void notificationFailureRollsBackPaymentAndLedger()throws Exception{
        NotificationWriter failing=new NotificationWriter(){public void insert(Connection c,NotificationDraft d)throws SQLException{throw new SQLException("notify failed");}public void insertBatch(Connection c,List<NotificationDraft>d)throws SQLException{throw new SQLException("notify failed");}};
        var broken=new LibraryFeeRepository(connections,failing,ZoneId.of("Asia/Shanghai"),bank);
        assertThrows(SQLException.class,()->broken.pay(11,3,UUID.randomUUID().toString(),Instant.now()));
        assertEquals("PENDING",text("SELECT status FROM library_fee_bills WHERE id=3"));
        assertEquals(new BigDecimal("50.00"),bank.paymentAccount(11).balance());
        assertEquals(0,scalar("SELECT COUNT(*) FROM bank_ledger_entries WHERE reference_no='LIBRARY-FEE-LFB000000003'"));
    }

    private void schema()throws SQLException{try(Connection c=connections.openConnection();Statement s=c.createStatement()){
        s.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64),display_name VARCHAR(100),enabled BOOLEAN)");
        s.execute("CREATE TABLE books(id BIGINT PRIMARY KEY,title VARCHAR(200),replacement_price DECIMAL(10,2))");s.execute("CREATE TABLE book_copies(id BIGINT PRIMARY KEY,book_id BIGINT,barcode CHAR(10))");s.execute("CREATE TABLE library_loans(id BIGINT PRIMARY KEY,borrower_user_id BIGINT,copy_id BIGINT,due_at TIMESTAMP,returned_at TIMESTAMP)");
        s.execute("CREATE TABLE bank_accounts(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT UNIQUE,balance DECIMAL(15,2) DEFAULT 0.00,status VARCHAR(16) DEFAULT 'ACTIVE',created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        s.execute("CREATE TABLE bank_ledger_entries(id BIGINT AUTO_INCREMENT PRIMARY KEY,account_id BIGINT,entry_type VARCHAR(32),direction VARCHAR(8),amount DECIMAL(15,2),balance_after DECIMAL(15,2),reference_no VARCHAR(64),counterparty_user_id BIGINT,operator_user_id BIGINT,description VARCHAR(255),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(account_id,entry_type,reference_no))");
        s.execute("CREATE TABLE notifications(id BIGINT AUTO_INCREMENT PRIMARY KEY,recipient_user_id BIGINT,sender_user_id BIGINT,notification_type VARCHAR(40),source_module VARCHAR(40),title VARCHAR(160),content VARCHAR(1000),target VARCHAR(40),related_entity_id BIGINT,is_read BOOLEAN DEFAULT FALSE,read_at TIMESTAMP,deleted_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        s.execute("CREATE TABLE library_fee_bills(id BIGINT PRIMARY KEY,bill_no CHAR(12) UNIQUE,loan_id BIGINT UNIQUE,borrower_user_id BIGINT,book_id BIGINT,copy_id BIGINT,status VARCHAR(16),return_condition VARCHAR(16),damage_level VARCHAR(16),due_at TIMESTAMP,closed_at TIMESTAMP,overdue_days INT,billable_days INT,replacement_price_snapshot DECIMAL(10,2),grace_days_snapshot INT,daily_overdue_fee_snapshot DECIMAL(10,2),overdue_cap_snapshot DECIMAL(10,2),condition_rate_snapshot DECIMAL(5,4),overdue_amount DECIMAL(10,2),damage_amount DECIMAL(10,2),lost_amount DECIMAL(10,2),total_amount DECIMAL(10,2),reason VARCHAR(255),created_by_user_id BIGINT,payment_operation_id CHAR(36),payment_reference_no VARCHAR(64),paid_at TIMESTAMP,waived_by_user_id BIGINT,waived_reason VARCHAR(255),waived_at TIMESTAMP,refund_operation_id CHAR(36),refund_reference_no VARCHAR(64),refunded_by_user_id BIGINT,refunded_reason VARCHAR(255),refunded_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
    }}
    private void seed()throws SQLException{try(Connection c=connections.openConnection();Statement s=c.createStatement()){
        s.execute("INSERT INTO users VALUES(3,'admin','管理员',TRUE),(11,'student','学生',TRUE)");s.execute("INSERT INTO books VALUES(13,'Java',89.00)");s.execute("INSERT INTO book_copies VALUES(17,13,'B000000001')");
        s.execute("INSERT INTO bank_accounts(id,user_id,balance,status) VALUES(1,11,50.00,'ACTIVE')");
        s.execute("INSERT INTO library_fee_bills(id,bill_no,loan_id,borrower_user_id,book_id,copy_id,status,return_condition,due_at,closed_at,overdue_days,billable_days,grace_days_snapshot,daily_overdue_fee_snapshot,overdue_cap_snapshot,overdue_amount,damage_amount,lost_amount,total_amount,created_by_user_id) VALUES(1,'LFB000000001',1,11,13,17,'PENDING','NORMAL',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,3,2,1,0.50,30.00,1.00,16.80,0.00,17.80,3),(2,'LFB000000002',2,11,13,17,'PENDING','NORMAL',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1,0,1,0.50,30.00,0.00,5.00,0.00,5.00,3),(3,'LFB000000003',3,11,13,17,'PENDING','NORMAL',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1,0,1,0.50,30.00,0.00,6.00,0.00,6.00,3)");
    }}
    private int scalar(String q)throws SQLException{try(Connection c=connections.openConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery(q)){r.next();return r.getInt(1);}}
    private String text(String q)throws SQLException{try(Connection c=connections.openConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery(q)){r.next();return r.getString(1);}}
}
