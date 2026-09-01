package com.vcampus.server.database;

import com.vcampus.common.model.*;
import com.vcampus.server.database.NotificationWriter.NotificationDraft;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Return reminders only: no queue position, copy hold, or priority lending right. */
public final class LibraryReservationRepository implements LibraryReservationStore {
    private final ConnectionFactory connections;
    public LibraryReservationRepository(ConnectionFactory connections) { this.connections=Objects.requireNonNull(connections); }

    @Override public long create(long userId,long bookId,Instant now)throws SQLException {
        return LibraryTransactions.run(connections,c -> {
            if(!LibraryTransactions.lockBook(c,bookId)) throw new LibraryRuleException("该书目已停用，不能预约");
            requireStudent(c,userId);
            if(exists(c,"SELECT 1 FROM library_loans l JOIN book_copies cp ON cp.id=l.copy_id WHERE cp.book_id=? AND l.borrower_user_id=? AND l.returned_at IS NULL",bookId,userId))
                throw new LibraryRuleException("已借有该书目的图书，不能预约");
            if(!exists(c,"SELECT 1 FROM book_copies WHERE book_id=? AND status='ON_LOAN'",bookId))
                throw new LibraryRuleException("该书目没有已借出的馆藏，不能预约");
            if(exists(c,"SELECT 1 FROM library_reservations WHERE book_id=? AND borrower_user_id=? AND status='WAITING'",bookId,userId))
                throw new LibraryRuleException("您已预约该书目的归还提醒");
            try(PreparedStatement s=c.prepareStatement("INSERT INTO library_reservations(book_id,borrower_user_id,status,created_at) VALUES(?,?,'WAITING',?)",Statement.RETURN_GENERATED_KEYS)) {
                s.setLong(1,bookId);s.setLong(2,userId);s.setTimestamp(3,Timestamp.from(now));s.executeUpdate();
                try(ResultSet r=s.getGeneratedKeys()){if(!r.next())throw new SQLException("数据库未返回预约ID");return r.getLong(1);}
            }
        });
    }

    @Override public boolean cancel(long userId,long reservationId)throws SQLException {
        return LibraryTransactions.run(connections,c -> {
            long bookId;
            try(PreparedStatement s=c.prepareStatement("SELECT book_id FROM library_reservations WHERE id=? AND borrower_user_id=?")) {
                s.setLong(1,reservationId);s.setLong(2,userId);
                try(ResultSet r=s.executeQuery()){if(!r.next())throw new LibraryRuleException("预约不存在或不属于当前用户");bookId=r.getLong(1);}
            }
            LibraryTransactions.lockBook(c,bookId);
            requireStudent(c,userId);
            try(PreparedStatement s=c.prepareStatement("UPDATE library_reservations SET status='CANCELLED' WHERE id=? AND borrower_user_id=? AND status='WAITING'")) {
                s.setLong(1,reservationId);s.setLong(2,userId);return s.executeUpdate()==1;
            }
        });
    }

    @Override public ReservationPage search(long userId,LibraryReservationStatus status,int page,int pageSize)throws SQLException {
        int p=Math.max(1,page),size=Math.max(1,Math.min(100,pageSize));
        String where=" WHERE r.borrower_user_id=?"+(status==null?"":" AND r.status=?");
        try(Connection c=connections.openConnection()) {
            requireStudent(c,userId);int total;
            try(PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM library_reservations r"+where)) {
                bindFilter(s,userId,status);try(ResultSet r=s.executeQuery()){r.next();total=r.getInt(1);}
            }
            List<ReservationRecord> rows=new ArrayList<>();
            try(PreparedStatement s=c.prepareStatement("SELECT r.id,r.book_id,b.catalog_code,b.title,r.status,r.created_at,r.notified_at FROM library_reservations r JOIN books b ON b.id=r.book_id"+where+" ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?")) {
                int index=bindFilter(s,userId,status);s.setInt(index++,size);s.setLong(index,(long)(p-1)*size);
                try(ResultSet r=s.executeQuery()){while(r.next()) {Timestamp notified=r.getTimestamp("notified_at");rows.add(new ReservationRecord(r.getLong("id"),r.getLong("book_id"),r.getString("catalog_code"),r.getString("title"),LibraryReservationStatus.valueOf(r.getString("status")),r.getTimestamp("created_at").toInstant(),notified==null?null:notified.toInstant()));}}
            }
            return new ReservationPage(rows,p,size,total);
        }
    }

    /** Caller holds the book lock and owns the transaction. */
    static void cancelWaitingAfterBorrow(Connection c,long userId,long bookId)throws SQLException {
        try(PreparedStatement s=c.prepareStatement("UPDATE library_reservations SET status='CANCELLED' WHERE book_id=? AND borrower_user_id=? AND status='WAITING'")) {
            s.setLong(1,bookId);s.setLong(2,userId);s.executeUpdate();
        }
    }

    /** All notification inserts and status changes roll back together with the normal return. */
    static void notifyWaiting(Connection c,NotificationWriter writer,long bookId,String title,Instant now)throws SQLException {
        List<long[]> waiting=new ArrayList<>();
        try(PreparedStatement s=c.prepareStatement("SELECT id,borrower_user_id FROM library_reservations WHERE book_id=? AND status='WAITING' ORDER BY id FOR UPDATE")) {
            s.setLong(1,bookId);try(ResultSet r=s.executeQuery()){while(r.next())waiting.add(new long[]{r.getLong(1),r.getLong(2)});}
        }
        for(long[] reservation:waiting) {
            writer.insert(c,new NotificationDraft(reservation[1],null,NotificationType.LIBRARY_RESERVATION_AVAILABLE,
                    NotificationSource.LIBRARY,"预约图书已归还","《"+title+"》已有馆藏正常归还，可前往图书馆查询并借阅。本提醒不保留馆藏，也不提供优先借阅权。",NotificationTarget.LIBRARY_CATALOG,bookId));
            try(PreparedStatement s=c.prepareStatement("UPDATE library_reservations SET status='NOTIFIED',notified_at=? WHERE id=? AND status='WAITING'")) {
                s.setTimestamp(1,Timestamp.from(now));s.setLong(2,reservation[0]);
                if(s.executeUpdate()!=1)throw new SQLException("预约状态在归还事务中发生冲突");
            }
        }
    }

    private static void requireStudent(Connection c,long userId)throws SQLException {
        if(!exists(c,"SELECT 1 FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE u.id=? AND u.enabled=TRUE AND r.role_code='STUDENT'",userId))
            throw new LibraryRuleException("仅学生可以使用图书预约提醒");
    }
    private static boolean exists(Connection c,String sql,long... values)throws SQLException {
        try(PreparedStatement s=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)s.setLong(i+1,values[i]);try(ResultSet r=s.executeQuery()){return r.next();}}
    }
    private static int bindFilter(PreparedStatement s,long userId,LibraryReservationStatus status)throws SQLException {
        s.setLong(1,userId);if(status!=null){s.setString(2,status.name());return 3;}return 2;
    }
}
