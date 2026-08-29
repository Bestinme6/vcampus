package com.vcampus.server.database;

import com.vcampus.common.model.*;
import com.vcampus.server.database.NotificationWriter.NotificationDraft;

import java.sql.*;
import java.time.Instant;
import java.util.Objects;

public final class LibraryNoticeRepository implements LibraryNoticeStore {
    private final ConnectionFactory connections;
    private final NotificationWriter notifications;

    public LibraryNoticeRepository(ConnectionFactory connections, NotificationWriter notifications) {
        this.connections = Objects.requireNonNull(connections);
        this.notifications = Objects.requireNonNull(notifications);
    }

    @Override public int sendDueSoon(Instant now,Instant deadline,int batchSize)throws SQLException{
        return scan(now,deadline,batchSize,false);
    }
    @Override public int sendOverdue(Instant now,int batchSize)throws SQLException{
        return scan(now,null,batchSize,true);
    }

    private int scan(Instant now,Instant deadline,int batchSize,boolean overdue)throws SQLException{
        if(batchSize<1)throw new IllegalArgumentException("batchSize must be positive");
        try(Connection c=connections.openConnection()){
            boolean auto=c.getAutoCommit();c.setAutoCommit(false);
            try{
                String marker=overdue?"overdue_notice_sent_at":"due_notice_sent_at";
                String range=overdue?"l.due_at < ?":"l.due_at > ? AND l.due_at <= ?";
                String sql="SELECT l.id,l.borrower_user_id,l.due_at,b.title,c.barcode FROM library_loans l JOIN book_copies c ON c.id=l.copy_id JOIN books b ON b.id=c.book_id WHERE l.returned_at IS NULL AND l."+marker+" IS NULL AND "+range+" ORDER BY l.due_at,l.id LIMIT ? FOR UPDATE SKIP LOCKED";
                int sent=0;
                try(PreparedStatement s=c.prepareStatement(sql)){
                    int i=1;s.setTimestamp(i++,Timestamp.from(now));if(!overdue)s.setTimestamp(i++,Timestamp.from(deadline));s.setInt(i,batchSize);
                    try(ResultSet r=s.executeQuery()){
                        while(r.next()){
                            long loanId=r.getLong("id");String title=r.getString("title");Instant due=r.getTimestamp("due_at").toInstant();
                            NotificationType type=overdue?NotificationType.LIBRARY_OVERDUE:NotificationType.LIBRARY_DUE_SOON;
                            String heading=overdue?"图书已逾期":"图书即将到期";
                            String content=title+"（"+r.getString("barcode")+"）应还日期："+due;
                            notifications.insert(c,new NotificationDraft(r.getLong("borrower_user_id"),null,type,NotificationSource.LIBRARY,heading,content,NotificationTarget.LIBRARY_LOANS,loanId));
                            try(PreparedStatement u=c.prepareStatement("UPDATE library_loans SET "+marker+"=? WHERE id=? AND "+marker+" IS NULL")){u.setTimestamp(1,Timestamp.from(now));u.setLong(2,loanId);if(u.executeUpdate()==1)sent++;}
                        }
                    }
                }
                c.commit();return sent;
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}finally{c.setAutoCommit(auto);}
        }
    }
}
