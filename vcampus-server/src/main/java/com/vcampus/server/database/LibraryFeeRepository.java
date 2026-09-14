package com.vcampus.server.database;

import com.vcampus.common.model.*;
import com.vcampus.server.database.LibraryFeeWriter.ClosedLoan;
import com.vcampus.server.database.LibraryFeeWriter.CreatedBill;
import com.vcampus.server.database.NotificationWriter.NotificationDraft;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class LibraryFeeRepository implements LibraryFeeStore {
    private static final ZoneId PRODUCTION_ZONE = ZoneId.of("Asia/Shanghai");
    private final ConnectionFactory connections;
    private final NotificationWriter notifications;
    private final ZoneId zone;

    public LibraryFeeRepository(ConnectionFactory connections, NotificationWriter notifications) {
        this(connections, notifications, PRODUCTION_ZONE);
    }

    public LibraryFeeRepository(ConnectionFactory connections, NotificationWriter notifications, ZoneId zone) {
        this.connections = Objects.requireNonNull(connections);
        this.notifications = Objects.requireNonNull(notifications);
        this.zone = Objects.requireNonNull(zone);
    }

    @Override public boolean hasPending(Connection connection, long borrowerUserId) throws SQLException {
        try (PreparedStatement s=connection.prepareStatement("SELECT 1 FROM library_fee_bills WHERE borrower_user_id=? AND status='PENDING' LIMIT 1")) {
            s.setLong(1,borrowerUserId); try(ResultSet r=s.executeQuery()){return r.next();}
        }
    }

    @Override public Optional<CreatedBill> createForReturn(Connection c, ClosedLoan loan) throws SQLException {
        Objects.requireNonNull(c); Objects.requireNonNull(loan);
        Optional<CreatedBill> existing=findCreatedByLoan(c,loan.loanId());
        if(existing.isPresent()) return existing;
        LibraryFeePolicy policy=readPolicy(c);
        BigDecimal price=bookPrice(c,loan.bookId());
        LibraryFeeAssessment fee=policy.assess(date(loan.dueAt()),date(loan.closedAt()),
                loan.condition(),loan.damageLevel(),price);
        if(fee.totalAmount().signum()==0) return Optional.empty();
        long sequence=nextBillSequence(c); String billNo="LFB"+String.format(Locale.ROOT,"%09d",sequence);
        String sql="""
                INSERT INTO library_fee_bills
                (bill_no,loan_id,borrower_user_id,book_id,copy_id,status,return_condition,damage_level,
                 due_at,closed_at,overdue_days,billable_days,replacement_price_snapshot,grace_days_snapshot,
                 daily_overdue_fee_snapshot,overdue_cap_snapshot,condition_rate_snapshot,overdue_amount,
                 damage_amount,lost_amount,total_amount,reason,created_by_user_id)
                VALUES(?,?,?,?,?,'PENDING',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        long billId;
        try(PreparedStatement s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)) {
            int i=1;s.setString(i++,billNo);s.setLong(i++,loan.loanId());s.setLong(i++,loan.borrowerUserId());
            s.setLong(i++,loan.bookId());s.setLong(i++,loan.copyId());s.setString(i++,loan.condition().name());
            nullableString(s,i++,loan.damageLevel()==null?null:loan.damageLevel().name());
            s.setTimestamp(i++,Timestamp.from(loan.dueAt()));s.setTimestamp(i++,Timestamp.from(loan.closedAt()));
            s.setInt(i++,fee.overdueDays());s.setInt(i++,fee.billableDays());nullableDecimal(s,i++,fee.replacementPrice());
            s.setInt(i++,policy.graceDays());s.setBigDecimal(i++,policy.dailyOverdueFee());s.setBigDecimal(i++,policy.overdueCap());
            nullableDecimal(s,i++,conditionRate(policy,loan.condition(),loan.damageLevel()));
            s.setBigDecimal(i++,fee.overdueAmount());s.setBigDecimal(i++,fee.damageAmount());s.setBigDecimal(i++,fee.lostAmount());
            s.setBigDecimal(i++,fee.totalAmount());nullableString(s,i++,trimToNull(loan.reason()));s.setLong(i,loan.operatorUserId());
            try { s.executeUpdate(); }
            catch(SQLException error) {
                Optional<CreatedBill> winner=findCreatedByLoan(c,loan.loanId());
                if(winner.isPresent()) return winner;
                throw error;
            }
            try(ResultSet keys=s.getGeneratedKeys()){if(!keys.next())throw new SQLException("数据库未返回费用账单ID");billId=keys.getLong(1);}
        }
        notifications.insert(c,new NotificationDraft(loan.borrowerUserId(),loan.operatorUserId(),
                NotificationType.LIBRARY_FEE_CREATED,NotificationSource.LIBRARY,"产生图书费用",
                loan.title()+"（"+loan.barcode()+"）产生费用 "+fee.totalAmount().toPlainString()+" 元，请及时处理。",
                NotificationTarget.LIBRARY_FEES,billId));
        return Optional.of(new CreatedBill(billId,billNo,fee.totalAmount()));
    }

    @Override public LibraryFeePolicy currentPolicy() throws SQLException {
        try(Connection c=connections.openConnection()){return readPolicy(c);}
    }

    @Override public LibraryFeePolicy updatePolicy(long operatorUserId, LibraryFeePolicy p) throws SQLException {
        Objects.requireNonNull(p);
        try(Connection c=connections.openConnection();PreparedStatement s=c.prepareStatement("UPDATE library_fee_policy SET grace_days=?,daily_overdue_fee=?,overdue_cap=?,minor_damage_rate=?,major_damage_rate=?,lost_rate=?,changed_by_user_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=1")) {
            s.setInt(1,p.graceDays());s.setBigDecimal(2,p.dailyOverdueFee());s.setBigDecimal(3,p.overdueCap());
            s.setBigDecimal(4,p.minorDamageRate());s.setBigDecimal(5,p.majorDamageRate());s.setBigDecimal(6,p.lostRate());s.setLong(7,operatorUserId);
            if(s.executeUpdate()!=1)throw new SQLException("缺少图书费用规则");
        }
        return currentPolicy();
    }

    @Override public LibraryFeeAssessment previewReturn(ReturnFeePreview command) throws SQLException {
        Objects.requireNonNull(command); Objects.requireNonNull(command.closedAt()); Objects.requireNonNull(command.condition());
        String sql="SELECT l.due_at,b.replacement_price FROM library_loans l JOIN book_copies c ON c.id=l.copy_id JOIN books b ON b.id=c.book_id WHERE c.barcode=? AND l.returned_at IS NULL";
        try(Connection c=connections.openConnection();PreparedStatement s=c.prepareStatement(sql)) {
            s.setString(1,command.barcode());
            try(ResultSet r=s.executeQuery()) {
                if(!r.next())throw new IllegalArgumentException("未找到该条码的在借记录");
                return readPolicy(c).assess(date(r.getTimestamp(1).toInstant()),date(command.closedAt()),
                        command.condition(),command.damageLevel(),r.getBigDecimal(2));
            }
        }
    }

    @Override public FeeBillPage searchOwner(long userId, FeeBillQuery query) throws SQLException {return search(userId,query);}
    @Override public FeeBillPage searchAll(FeeBillQuery query) throws SQLException {return search(null,query);}
    @Override public Optional<FeeBill> findForOwner(long userId,long billId) throws SQLException {return findInternal(billId,userId);}
    @Override public Optional<FeeBill> find(long billId) throws SQLException {return findInternal(billId,null);}

    private FeeBillPage search(Long owner, FeeBillQuery q) throws SQLException {
        validateQuery(q); String keyword=Objects.requireNonNullElse(q.keyword(),"").trim();
        String where=" WHERE (? IS NULL OR f.borrower_user_id=?) AND (? IS NULL OR f.status=?) AND (?='' OR f.bill_no LIKE ? OR u.username LIKE ? OR u.display_name LIKE ? OR b.title LIKE ? OR c.barcode LIKE ?)";
        String from=" FROM library_fee_bills f JOIN users u ON u.id=f.borrower_user_id JOIN books b ON b.id=f.book_id JOIN book_copies c ON c.id=f.copy_id";
        try(Connection c=connections.openConnection()) {
            int total;try(PreparedStatement s=c.prepareStatement("SELECT COUNT(*)"+from+where)){bindSearch(s,owner,q.status(),keyword);try(ResultSet r=s.executeQuery()){r.next();total=r.getInt(1);}}
            List<FeeBill> rows=new ArrayList<>();
            try(PreparedStatement s=c.prepareStatement(select()+from+where+" ORDER BY f.created_at DESC,f.id DESC LIMIT ? OFFSET ?")) {
                int i=bindSearch(s,owner,q.status(),keyword);s.setInt(i++,q.pageSize());s.setInt(i,(q.page()-1)*q.pageSize());
                try(ResultSet r=s.executeQuery()){while(r.next())rows.add(readBill(r));}
            }
            return new FeeBillPage(rows,q.page(),q.pageSize(),total);
        }
    }

    private Optional<FeeBill> findInternal(long billId,Long owner) throws SQLException {
        String from=" FROM library_fee_bills f JOIN users u ON u.id=f.borrower_user_id JOIN books b ON b.id=f.book_id JOIN book_copies c ON c.id=f.copy_id";
        String sql=select()+from+" WHERE f.id=?"+(owner==null?"":" AND f.borrower_user_id=?");
        try(Connection c=connections.openConnection();PreparedStatement s=c.prepareStatement(sql)){s.setLong(1,billId);if(owner!=null)s.setLong(2,owner);try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(readBill(r)):Optional.empty();}}
    }

    private String select(){return "SELECT f.*,u.username,u.display_name,b.title,c.barcode";}
    private FeeBill readBill(ResultSet r)throws SQLException{return new FeeBill(r.getLong("id"),r.getString("bill_no"),r.getLong("loan_id"),r.getLong("borrower_user_id"),r.getString("username"),r.getString("display_name"),r.getLong("book_id"),r.getLong("copy_id"),r.getString("title"),r.getString("barcode"),LibraryFeeStatus.valueOf(r.getString("status")),LibraryReturnCondition.valueOf(r.getString("return_condition")),enumOrNull(LibraryDamageLevel.class,r.getString("damage_level")),instant(r,"due_at"),instant(r,"closed_at"),r.getInt("overdue_days"),r.getInt("billable_days"),r.getBigDecimal("replacement_price_snapshot"),r.getInt("grace_days_snapshot"),r.getBigDecimal("daily_overdue_fee_snapshot"),r.getBigDecimal("overdue_cap_snapshot"),r.getBigDecimal("condition_rate_snapshot"),r.getBigDecimal("overdue_amount"),r.getBigDecimal("damage_amount"),r.getBigDecimal("lost_amount"),r.getBigDecimal("total_amount"),r.getString("reason"),r.getString("payment_reference_no"),instant(r,"paid_at"),r.getString("waived_reason"),instant(r,"waived_at"),r.getString("refund_reference_no"),r.getString("refunded_reason"),instant(r,"refunded_at"),instant(r,"created_at"));}
    private LibraryFeePolicy readPolicy(Connection c)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT grace_days,daily_overdue_fee,overdue_cap,minor_damage_rate,major_damage_rate,lost_rate FROM library_fee_policy WHERE id=1");ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("缺少图书费用规则");return new LibraryFeePolicy(r.getInt(1),r.getBigDecimal(2),r.getBigDecimal(3),r.getBigDecimal(4),r.getBigDecimal(5),r.getBigDecimal(6));}}
    private BigDecimal bookPrice(Connection c,long id)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT replacement_price FROM books WHERE id=?")){s.setLong(1,id);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("书目不存在");return r.getBigDecimal(1);}}}
    private Optional<CreatedBill> findCreatedByLoan(Connection c,long loanId)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT id,bill_no,total_amount FROM library_fee_bills WHERE loan_id=?")){s.setLong(1,loanId);try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(new CreatedBill(r.getLong(1),r.getString(2),r.getBigDecimal(3))):Optional.empty();}}}
    private long nextBillSequence(Connection c)throws SQLException{long n;try(PreparedStatement s=c.prepareStatement("SELECT next_value FROM library_code_sequences WHERE code_type='LIBRARY_FEE_BILL' FOR UPDATE");ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("缺少费用账单编号序列");n=r.getLong(1);}try(PreparedStatement s=c.prepareStatement("UPDATE library_code_sequences SET next_value=? WHERE code_type='LIBRARY_FEE_BILL'")){s.setLong(1,n+1);s.executeUpdate();}return n;}
    private int bindSearch(PreparedStatement s,Long owner,LibraryFeeStatus status,String keyword)throws SQLException{int i=1;if(owner==null){s.setNull(i++,Types.BIGINT);s.setNull(i++,Types.BIGINT);}else{s.setLong(i++,owner);s.setLong(i++,owner);}if(status==null){s.setNull(i++,Types.VARCHAR);s.setNull(i++,Types.VARCHAR);}else{s.setString(i++,status.name());s.setString(i++,status.name());}s.setString(i++,keyword);String p="%"+keyword.replace("%","\\%").replace("_","\\_")+"%";for(int n=0;n<5;n++)s.setString(i++,p);return i;}
    private void validateQuery(FeeBillQuery q){Objects.requireNonNull(q);if(q.page()<1||q.page()>100000)throw new IllegalArgumentException("页码无效");if(q.pageSize()<1||q.pageSize()>100)throw new IllegalArgumentException("每页数量无效");}
    private LocalDate date(Instant i){return Objects.requireNonNull(i).atZone(zone).toLocalDate();}
    private static BigDecimal conditionRate(LibraryFeePolicy p,LibraryReturnCondition c,LibraryDamageLevel d){return switch(c){case NORMAL->null;case LOST->p.lostRate();case DAMAGED->d==LibraryDamageLevel.MINOR?p.minorDamageRate():p.majorDamageRate();};}
    private static <E extends Enum<E>> E enumOrNull(Class<E> c,String v){return v==null?null:Enum.valueOf(c,v);}
    private static Instant instant(ResultSet r,String name)throws SQLException{Timestamp t=r.getTimestamp(name);return t==null?null:t.toInstant();}
    private static String trimToNull(String v){if(v==null)return null;String x=v.trim();return x.isEmpty()?null:x;}
    private static void nullableString(PreparedStatement s,int i,String v)throws SQLException{if(v==null)s.setNull(i,Types.VARCHAR);else s.setString(i,v);}
    private static void nullableDecimal(PreparedStatement s,int i,BigDecimal v)throws SQLException{if(v==null)s.setNull(i,Types.DECIMAL);else s.setBigDecimal(i,v);}
}
