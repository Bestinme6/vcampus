package com.vcampus.server.database;

import com.vcampus.common.model.LibraryCirculationOperation;
import com.vcampus.common.model.LibraryCopyStatus;
import com.vcampus.common.model.LibraryLoanChannel;
import com.vcampus.common.model.LibraryReturnCondition;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import com.vcampus.common.model.UserRole;
import com.vcampus.server.database.NotificationWriter.NotificationDraft;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class LibraryLoanRepository implements LibraryLoanStore {
    private final ConnectionFactory connectionFactory;
    private final NotificationWriter notifications;

    public LibraryLoanRepository(ConnectionFactory connectionFactory) {
        this(connectionFactory, new NotificationRepository(connectionFactory));
    }

    public LibraryLoanRepository(
            ConnectionFactory connectionFactory, NotificationWriter notifications) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
    }

    @Override
    public Optional<Borrower> findBorrower(String username) throws SQLException {
        String sql = """
                SELECT u.id, u.username, u.display_name, u.enabled, r.role_code
                  FROM users u
                  JOIN user_roles ur ON ur.user_id=u.id
                  JOIN roles r ON r.id=ur.role_id
                 WHERE u.username=? AND u.enabled=TRUE AND r.role_code IN ('STUDENT','TEACHER')
                 ORDER BY CASE WHEN r.role_code='TEACHER' THEN 0 ELSE 1 END
                """;
        try (Connection c=connectionFactory.openConnection(); PreparedStatement s=c.prepareStatement(sql)) {
            s.setString(1, trim(username));
            try (ResultSet r=s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(new Borrower(r.getLong("id"), r.getString("username"),
                        r.getString("display_name"), UserRole.valueOf(r.getString("role_code")), true));
            }
        }
    }

    @Override
    public CirculationPreview previewCirculation(long borrowerUserId, String barcode,
            LibraryCirculationOperation operation, Instant now, int maxActiveLoans) throws SQLException {
        Objects.requireNonNull(operation, "operation");
        int active;
        boolean overdue;
        try (Connection c=connectionFactory.openConnection()) {
            active=countActive(c, borrowerUserId);
            overdue=hasOverdue(c, borrowerUserId, now);
            String sql="""
                    SELECT c.id copy_id,c.book_id,b.title,c.barcode,c.status,l.id active_loan_id
                      FROM book_copies c JOIN books b ON b.id=c.book_id
                      LEFT JOIN library_loans l ON l.copy_id=c.id AND l.returned_at IS NULL
                     WHERE c.barcode=?
                    """;
            try(PreparedStatement s=c.prepareStatement(sql)){
                s.setString(1, barcode==null?"":barcode.trim());
                try(ResultSet r=s.executeQuery()){
                    if(!r.next()) throw new LibraryRuleException("未找到该馆藏");
                    LibraryCopyStatus status=parseCopyStatus(r.getString("status"));
                    long value=r.getLong("active_loan_id"); Long loanId=r.wasNull()?null:value;
                    boolean allowed;
                    String message;
                    if(operation==LibraryCirculationOperation.BORROW){
                        allowed=status==LibraryCopyStatus.AVAILABLE && loanId==null && !overdue && active<maxActiveLoans;
                        message=allowed?"可以办理借阅":borrowMessage(status,overdue,active,maxActiveLoans);
                    } else {
                        allowed=loanId!=null;
                        message=allowed?"可以办理归还":"该馆藏没有活动借阅";
                    }
                    return new CirculationPreview(r.getLong("copy_id"),r.getLong("book_id"),
                            r.getString("title"),r.getString("barcode"),status,loanId,
                            active,maxActiveLoans,overdue,allowed,message);
                }
            }
        }
    }

    @Override
    public LoanPage searchBorrowerLoans(long borrowerUserId, LoanQuery query) throws SQLException {
        return searchLoans(borrowerUserId, query);
    }

    @Override
    public LoanPage searchAllLoans(LoanQuery query) throws SQLException {
        return searchLoans(null, query);
    }

    @Override
    public BorrowReceipt borrow(BorrowCommand command) throws SQLException {
        Objects.requireNonNull(command,"command");
        return inTransaction(c -> {
            lockBorrower(c,command.borrowerUserId());
            int active=countActiveForUpdate(c,command.borrowerUserId());
            if(hasOverdue(c,command.borrowerUserId(),command.borrowedAt()))
                throw new LibraryRuleException("存在逾期图书，请先归还");
            if(active>=command.maxActiveLoans()) throw new LibraryRuleException("借阅数量已达上限");
            ensureBookBorrowable(c, command.bookId(), command.barcode());
            if(!hasAvailableCopy(c, command.bookId(), command.barcode())) {
                if(hasRecentBorrow(c, command.bookId(), command.barcode(),
                        command.borrowedAt().minusSeconds(10)))
                    throw new LibraryRuleException("该馆藏刚刚被其他用户借出");
                throw new LibraryRuleException("当前没有可借馆藏");
            }
            LockedCopy copy=lockAvailableCopy(c,command.bookId(),command.barcode());
            if(copy==null) throw new LibraryRuleException("该馆藏刚刚被其他用户借出");
            long loanId;
            String insert="""
                    INSERT INTO library_loans(copy_id,borrower_user_id,borrowed_at,initial_due_at,due_at,
                        renewal_count,channel,checkout_operator_user_id)
                    VALUES(?,?,?,?,?,0,?,?)
                    """;
            try(PreparedStatement s=c.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS)){
                s.setLong(1,copy.copyId);s.setLong(2,command.borrowerUserId());
                s.setTimestamp(3,Timestamp.from(command.borrowedAt()));s.setTimestamp(4,Timestamp.from(command.dueAt()));
                s.setTimestamp(5,Timestamp.from(command.dueAt()));s.setString(6,command.channel().name());
                s.setLong(7,command.operatorUserId());s.executeUpdate();
                try(ResultSet keys=s.getGeneratedKeys()){if(!keys.next())throw new SQLException("数据库未返回借阅ID");loanId=keys.getLong(1);}
            }
            try(PreparedStatement s=c.prepareStatement("UPDATE book_copies SET status='ON_LOAN',status_reason=NULL WHERE id=? AND status='AVAILABLE'")){
                s.setLong(1,copy.copyId); if(s.executeUpdate()!=1)throw new LibraryRuleException("该馆藏刚刚被其他用户借出");
            }
            notify(c, command.borrowerUserId(), command.operatorUserId(),
                    NotificationType.LIBRARY_BORROWED, "借阅成功",
                    "《" + copy.title + "》（" + copy.barcode + "）借阅成功，应还时间：" + command.dueAt(),
                    loanId);
            return new BorrowReceipt(loanId,copy.copyId,copy.barcode,copy.title,command.dueAt());
        });
    }

    @Override
    public ReturnReceipt returnLoan(ReturnCommand command) throws SQLException {
        Objects.requireNonNull(command,"command");
        if(command.condition()==LibraryReturnCondition.LOST && !command.administrator())
            throw new LibraryRuleException("只有管理员可以登记遗失");
        if(command.condition()==LibraryReturnCondition.DAMAGED && !command.administrator())
            throw new LibraryRuleException("只有管理员可以登记破损归还");
        String reason=trim(command.reason());
        if(command.condition()==LibraryReturnCondition.LOST && reason.isBlank())
            throw new IllegalArgumentException("遗失关闭必须填写原因");
        if(command.condition()==LibraryReturnCondition.DAMAGED && reason.isBlank())
            throw new IllegalArgumentException("破损归还必须填写原因");
        return inTransaction(c -> {
            LockedLoan loan=lockReturnLoan(c,command.loanId(),command.barcode());
            if(loan==null)throw new LibraryRuleException("未找到活动借阅记录");
            if(!command.administrator() && loan.borrowerId!=command.borrowerUserId())
                throw new LibraryRuleException("当前记录不属于该用户");
            try(PreparedStatement s=c.prepareStatement("UPDATE library_loans SET returned_at=?,return_condition=?,return_operator_user_id=? WHERE id=? AND returned_at IS NULL")){
                s.setTimestamp(1,Timestamp.from(command.returnedAt()));s.setString(2,command.condition().name());
                s.setLong(3,command.operatorUserId());s.setLong(4,loan.loanId);s.executeUpdate();
            }
            String copyStatus=switch(command.condition()){
                case NORMAL -> "AVAILABLE";
                case LOST -> "LOST";
                case DAMAGED -> "DAMAGED";
            };
            try(PreparedStatement s=c.prepareStatement("UPDATE book_copies SET status=?,status_reason=? WHERE id=?")){
                s.setString(1,copyStatus);if(command.condition()!=LibraryReturnCondition.NORMAL)s.setString(2,reason);else s.setNull(2,Types.VARCHAR);
                s.setLong(3,loan.copyId);s.executeUpdate();
            }
            boolean lost = command.condition() == LibraryReturnCondition.LOST;
            boolean damaged = command.condition() == LibraryReturnCondition.DAMAGED;
            notify(c, loan.borrowerId, command.operatorUserId(),
                    lost ? NotificationType.LIBRARY_LOST : NotificationType.LIBRARY_RETURNED,
                    lost ? "图书已登记遗失" : damaged ? "图书已破损归还" : "归还成功",
                    lost
                            ? "《" + loan.title + "》（" + loan.barcode + "）已登记遗失。原因：" + reason
                            : damaged
                            ? "《" + loan.title + "》（" + loan.barcode + "）已登记破损归还并暂停流通。原因：" + reason
                            : "《" + loan.title + "》（" + loan.barcode + "）已正常归还，归还时间：" + command.returnedAt(),
                    loan.loanId);
            return new ReturnReceipt(loan.loanId,loan.copyId,loan.barcode,command.condition(),command.returnedAt());
        });
    }

    @Override
    public RenewReceipt renew(RenewCommand command) throws SQLException {
        Objects.requireNonNull(command,"command");
        return inTransaction(c -> {
            lockBorrower(c,command.borrowerUserId());
            String sql="""
                    SELECT l.borrower_user_id,l.due_at,l.renewal_count,c.barcode,b.title
                      FROM library_loans l
                      JOIN book_copies c ON c.id=l.copy_id
                      JOIN books b ON b.id=c.book_id
                     WHERE l.id=? AND l.returned_at IS NULL
                     FOR UPDATE
                    """;
            try(PreparedStatement s=c.prepareStatement(sql)){
                s.setLong(1,command.loanId());
                try(ResultSet r=s.executeQuery()){
                    if(!r.next())throw new LibraryRuleException("未找到活动借阅记录");
                    if(r.getLong("borrower_user_id")!=command.borrowerUserId())throw new LibraryRuleException("当前记录不属于该用户");
                    if(hasOverdue(c,command.borrowerUserId(),command.now()))throw new LibraryRuleException("存在逾期图书，请先归还");
                    if(r.getInt("renewal_count")>=1)throw new LibraryRuleException("该借阅已经续借过一次");
                    Instant newDue=r.getTimestamp("due_at").toInstant().plus(command.extension());
                    try(PreparedStatement u=c.prepareStatement("UPDATE library_loans SET due_at=?,renewal_count=renewal_count+1,due_notice_sent_at=NULL WHERE id=? AND returned_at IS NULL")){
                        u.setTimestamp(1,Timestamp.from(newDue));u.setLong(2,command.loanId());u.executeUpdate();
                    }
                    notify(c, command.borrowerUserId(), command.borrowerUserId(),
                            NotificationType.LIBRARY_RENEWED, "续借成功",
                            "《" + r.getString("title") + "》（" + r.getString("barcode")
                                    + "）续借成功，新的应还时间：" + newDue,
                            command.loanId());
                    return new RenewReceipt(command.loanId(),newDue,r.getInt("renewal_count")+1);
                }
            }
        });
    }

    private LoanPage searchLoans(Long borrowerId, LoanQuery query) throws SQLException {
        Objects.requireNonNull(query,"query"); int page=Math.max(1,query.page()),size=Math.max(1,Math.min(100,query.pageSize()));
        StringBuilder where=new StringBuilder(" WHERE 1=1");List<Object> p=new ArrayList<>();
        if(borrowerId!=null){where.append(" AND l.borrower_user_id=?");p.add(borrowerId);}
        String keyword=trim(query.keyword());if(!keyword.isBlank()){where.append(" AND (b.title LIKE ? OR c.barcode LIKE ? OR u.username LIKE ? OR u.display_name LIKE ?)");String like="%"+keyword+"%";for(int i=0;i<4;i++)p.add(like);}
        if(query.active()!=null){where.append(query.active()?" AND l.returned_at IS NULL":" AND l.returned_at IS NOT NULL");}
        if(query.overdue()!=null){where.append(query.overdue()?" AND l.returned_at IS NULL AND l.due_at<CURRENT_TIMESTAMP":" AND (l.returned_at IS NOT NULL OR l.due_at>=CURRENT_TIMESTAMP)");}
        String from=" FROM library_loans l JOIN book_copies c ON c.id=l.copy_id JOIN books b ON b.id=c.book_id JOIN users u ON u.id=l.borrower_user_id";
        try(Connection c=connectionFactory.openConnection()){
            int total;try(PreparedStatement s=c.prepareStatement("SELECT COUNT(*)"+from+where)){bind(s,p);try(ResultSet r=s.executeQuery()){r.next();total=r.getInt(1);}}
            String sql="SELECT l.id loan_id,b.id book_id,b.isbn,b.title,c.id copy_id,c.barcode,u.username,u.display_name,l.borrowed_at,l.due_at,l.renewal_count,l.returned_at,l.return_condition,l.channel"+from+where+" ORDER BY l.borrowed_at DESC,l.id DESC LIMIT ? OFFSET ?";
            List<Object> pp=new ArrayList<>(p);pp.add(size);pp.add((page-1)*size);List<LoanRecord> rows=new ArrayList<>();
            try(PreparedStatement s=c.prepareStatement(sql)){bind(s,pp);try(ResultSet r=s.executeQuery()){while(r.next())rows.add(readLoan(r));}}
            return new LoanPage(rows,page,size,total);
        }
    }

    private LoanRecord readLoan(ResultSet r)throws SQLException{
        Timestamp returned=r.getTimestamp("returned_at");Instant due=r.getTimestamp("due_at").toInstant();int renew=r.getInt("renewal_count");
        String condition=r.getString("return_condition");
        return new LoanRecord(r.getLong("loan_id"),r.getLong("book_id"),r.getString("isbn"),r.getString("title"),r.getLong("copy_id"),r.getString("barcode"),r.getString("username"),r.getString("display_name"),r.getTimestamp("borrowed_at").toInstant(),due,renew,returned==null?null:returned.toInstant(),condition==null?null:LibraryReturnCondition.valueOf(condition),LibraryLoanChannel.valueOf(r.getString("channel")),returned==null&&due.isBefore(Instant.now()),returned==null&&!due.isBefore(Instant.now())&&renew==0);
    }

    private void lockBorrower(Connection c,long id)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT enabled FROM users WHERE id=? FOR UPDATE")){s.setLong(1,id);try(ResultSet r=s.executeQuery()){if(!r.next()||!r.getBoolean(1))throw new LibraryRuleException("借阅人账号不可用");}}}
    private int countActive(Connection c,long id)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM library_loans WHERE borrower_user_id=? AND returned_at IS NULL")){s.setLong(1,id);try(ResultSet r=s.executeQuery()){r.next();return r.getInt(1);}}}
    private int countActiveForUpdate(Connection c,long id)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT id FROM library_loans WHERE borrower_user_id=? AND returned_at IS NULL FOR UPDATE")){s.setLong(1,id);int count=0;try(ResultSet r=s.executeQuery()){while(r.next())count++;}return count;}}
    private boolean hasOverdue(Connection c,long id,Instant now)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT 1 FROM library_loans WHERE borrower_user_id=? AND returned_at IS NULL AND due_at<? LIMIT 1")){s.setLong(1,id);s.setTimestamp(2,Timestamp.from(now));try(ResultSet r=s.executeQuery()){return r.next();}}}
    private void ensureBookBorrowable(Connection c,Long bookId,String barcode)throws SQLException{
        String sql;
        if(barcode!=null&&!barcode.isBlank()){
            sql="SELECT b.enabled FROM book_copies c JOIN books b ON b.id=c.book_id WHERE c.barcode=? FOR UPDATE";
        }else if(bookId!=null){
            sql="SELECT enabled FROM books WHERE id=? FOR UPDATE";
        }else{
            throw new IllegalArgumentException("必须指定书目或馆藏条码");
        }
        try(PreparedStatement s=c.prepareStatement(sql)){
            if(barcode!=null&&!barcode.isBlank())s.setString(1,barcode.trim());else s.setLong(1,bookId);
            try(ResultSet r=s.executeQuery()){
                if(!r.next())throw new LibraryRuleException("书目或馆藏不存在");
                if(!r.getBoolean(1))throw new LibraryRuleException("该书目已停用，暂不可借阅");
            }
        }
    }
    private boolean hasAvailableCopy(Connection c,Long bookId,String barcode)throws SQLException{
        String sql=barcode!=null&&!barcode.isBlank()
                ? "SELECT 1 FROM book_copies WHERE barcode=? AND status='AVAILABLE'"
                : "SELECT 1 FROM book_copies WHERE book_id=? AND status='AVAILABLE' LIMIT 1";
        try(PreparedStatement s=c.prepareStatement(sql)){
            if(barcode!=null&&!barcode.isBlank())s.setString(1,barcode.trim());else s.setLong(1,bookId);
            try(ResultSet r=s.executeQuery()){return r.next();}
        }
    }
    private boolean hasRecentBorrow(Connection c,Long bookId,String barcode,Instant since)throws SQLException{
        StringBuilder sql=new StringBuilder("""
                SELECT 1 FROM library_loans l
                JOIN book_copies c ON c.id=l.copy_id
                WHERE l.returned_at IS NULL AND l.borrowed_at>=?
                """);
        if(barcode!=null&&!barcode.isBlank())sql.append(" AND c.barcode=?");
        else sql.append(" AND c.book_id=?");
        sql.append(" LIMIT 1 FOR UPDATE");
        try(PreparedStatement s=c.prepareStatement(sql.toString())){
            s.setTimestamp(1,Timestamp.from(since));
            if(barcode!=null&&!barcode.isBlank())s.setString(2,barcode.trim());else s.setLong(2,bookId);
            try(ResultSet r=s.executeQuery()){return r.next();}
        }
    }
    private LockedCopy lockAvailableCopy(Connection c,Long bookId,String barcode)throws SQLException{
        String sql;if(barcode!=null&&!barcode.isBlank())sql="SELECT c.id,c.barcode,b.title FROM book_copies c JOIN books b ON b.id=c.book_id WHERE c.barcode=? AND c.status='AVAILABLE' AND b.enabled=TRUE FOR UPDATE";else sql="SELECT c.id,c.barcode,b.title FROM book_copies c JOIN books b ON b.id=c.book_id WHERE b.id=? AND c.status='AVAILABLE' AND b.enabled=TRUE ORDER BY c.id LIMIT 1 FOR UPDATE SKIP LOCKED";
        try(PreparedStatement s=c.prepareStatement(sql)){if(barcode!=null&&!barcode.isBlank())s.setString(1,barcode.trim());else if(bookId!=null)s.setLong(1,bookId);else throw new IllegalArgumentException("必须指定书目或馆藏条码");try(ResultSet r=s.executeQuery()){return r.next()?new LockedCopy(r.getLong(1),r.getString(2),r.getString(3)):null;}}
    }
    private LockedLoan lockReturnLoan(Connection c,Long loanId,String barcode)throws SQLException{String select="SELECT l.id,l.copy_id,l.borrower_user_id,c.barcode,b.title FROM library_loans l JOIN book_copies c ON c.id=l.copy_id JOIN books b ON b.id=c.book_id ";String sql=loanId!=null?select+"WHERE l.id=? AND l.returned_at IS NULL FOR UPDATE":select+"WHERE c.barcode=? AND l.returned_at IS NULL FOR UPDATE";try(PreparedStatement s=c.prepareStatement(sql)){if(loanId!=null)s.setLong(1,loanId);else s.setString(1,trim(barcode));try(ResultSet r=s.executeQuery()){return r.next()?new LockedLoan(r.getLong(1),r.getLong(2),r.getLong(3),r.getString(4),r.getString(5)):null;}}}
    private void notify(Connection connection,long recipientUserId,long senderUserId,NotificationType type,String title,String content,long loanId)throws SQLException{notifications.insert(connection,new NotificationDraft(recipientUserId,senderUserId,type,NotificationSource.LIBRARY,title,content,NotificationTarget.LIBRARY_LOANS,loanId));}
    private String borrowMessage(LibraryCopyStatus s,boolean overdue,int active,int max){if(overdue)return "存在逾期图书，请先归还";if(active>=max)return "借阅数量已达上限";return s==LibraryCopyStatus.AVAILABLE?"该馆藏已有活动借阅":"该馆藏状态不允许借阅";}
    private LibraryCopyStatus parseCopyStatus(String s)throws SQLException{try{return LibraryCopyStatus.valueOf(s);}catch(Exception e){throw new SQLException("未知馆藏状态",e);}}
    private void bind(PreparedStatement s,List<Object> p)throws SQLException{for(int i=0;i<p.size();i++)s.setObject(i+1,p.get(i));}
    private String trim(String s){return s==null?"":s.trim();}
    private <T>T inTransaction(Work<T>w)throws SQLException{try(Connection c=connectionFactory.openConnection()){boolean ac=c.getAutoCommit();c.setAutoCommit(false);try{T v=w.run(c);c.commit();return v;}catch(SQLException|RuntimeException e){c.rollback();throw e;}finally{c.setAutoCommit(ac);}}}
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException;}
    private record LockedCopy(long copyId,String barcode,String title){}
    private record LockedLoan(long loanId,long copyId,long borrowerId,String barcode,String title){}
}
