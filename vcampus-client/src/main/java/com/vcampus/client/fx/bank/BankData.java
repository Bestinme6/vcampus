package com.vcampus.client.fx.bank;

import com.vcampus.common.model.*;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Strict boundary between wire strings and the bank UI. */
public final class BankData {
    private BankData() { }
    public record Account(long id, String username, String displayName, BigDecimal balance,
                          BankAccountStatus status, Instant updatedAt) { }
    public record Recipient(String username, String displayName, boolean self) { }
    public record Entry(long id, long accountId, BankLedgerType type, BankLedgerDirection direction,
                        BigDecimal amount, BigDecimal balanceAfter, String reference,
                        Long counterpartyId, Long operatorId, String description, Instant createdAt) { }
    public record Page<T>(List<T> rows, int page, int pageSize, int total) {
        public Page { rows = List.copyOf(rows); }
        public int pages() { return Math.max(1, (int) (((long) total + pageSize - 1) / pageSize)); }
    }
    public record Ledger(Page<Entry> entries, BigDecimal income, BigDecimal expense) { }
    public record Receipt(BigDecimal balanceAfter, String reference, boolean duplicate) { }
    public record Query(boolean administrative, String username, BankLedgerType type,
                        String keyword, LocalDate from, LocalDate to, String reference, int page) {
        public Query {
            username = Objects.toString(username, "").trim();
            keyword = Objects.toString(keyword, "").trim();
            reference = Objects.toString(reference, "").trim();
            if (page < 1 || keyword.length() > 100 || reference.length() > 64
                    || from != null && to != null && from.isAfter(to))
                throw new IllegalArgumentException("请检查日期范围与查询条件");
        }
        public static Query mine() { return new Query(false, "", null, "", null, null, "", 1); }
    }
    public static class Rejected extends RuntimeException {
        public Rejected(String message) { super(message); }
    }
    public static void require(ResponseMessage r) {
        if (r == null) throw new IllegalArgumentException("银行响应数据无效");
        if (!r.success()) {
            // A transaction commit can succeed before a connection reports an error.
            if (r.message().contains("数据库"))
                throw new IllegalArgumentException("交易结果需要核对，请勿重复创建操作");
            throw new Rejected(r.message());
        }
    }
    public static Account account(ResponseMessage r) {
        require(r); var d = r.data();
        return new Account(id(get(d,"accountId")), get(d,"username"), get(d,"displayName"),
                money(get(d,"balance")), BankAccountStatus.valueOf(get(d,"status")), Instant.parse(get(d,"updatedAt")));
    }
    public static Recipient recipient(ResponseMessage r) {
        require(r); return new Recipient(get(r.data(),"username"), get(r.data(),"displayName"), bool(get(r.data(),"self")));
    }
    public static Receipt receipt(ResponseMessage r) {
        require(r); return new Receipt(money(get(r.data(),"balanceAfter")),
                get(r.data(),"referenceNo"), bool(get(r.data(),"duplicate")));
    }
    public static Page<Account> accounts(ResponseMessage r) {
        require(r); var d = r.data(); int[] h = header(d); List<Account> rows = new ArrayList<>();
        for (int i=0; i<h[3]; i++) {
            var a = fields(d,i,8);
            id(a.get(1)); Instant.parse(a.get(6));
            rows.add(new Account(id(a.get(0)), a.get(2), a.get(3), money(a.get(4)),
                    BankAccountStatus.valueOf(a.get(5)), Instant.parse(a.get(7))));
        }
        return new Page<>(rows,h[0],h[1],h[2]);
    }
    public static Ledger ledger(ResponseMessage r) {
        require(r); var d = r.data(); int[] h = header(d); List<Entry> rows = new ArrayList<>();
        for (int i=0; i<h[3]; i++) {
            var a=fields(d,i,11);
            rows.add(new Entry(id(a.get(0)),id(a.get(1)),BankLedgerType.valueOf(a.get(2)),
                    BankLedgerDirection.valueOf(a.get(3)),MoneyPolicy.parsePositive(a.get(4)),
                    money(a.get(5)),a.get(6),optionalId(a.get(7)),optionalId(a.get(8)),a.get(9),Instant.parse(a.get(10))));
        }
        return new Ledger(new Page<>(rows,h[0],h[1],h[2]),aggregate(get(d,"income")),aggregate(get(d,"expense")));
    }
    private static List<String> fields(Map<String,String> d,int index,int size) {
        var f=RowCodec.decode(get(d,"row."+index));
        if(f.size()!=size) throw new IllegalArgumentException("银行行数据无效"); return f;
    }
    private static int[] header(Map<String,String> d) {
        int page=Integer.parseInt(get(d,"page")), size=Integer.parseInt(get(d,"pageSize")),
                total=Integer.parseInt(get(d,"total")), count=Integer.parseInt(get(d,"count"));
        if(page<1||size<1||size>100||total<0||count<0||count>size||count>total)
            throw new IllegalArgumentException("银行分页数据无效");
        return new int[]{page,size,total,count};
    }
    public static BigDecimal money(String value) {
        if(value==null||!value.matches("[0-9]+(\\.[0-9]{1,2})?")||value.length()>16)
            throw new IllegalArgumentException("银行金额数据无效");
        BigDecimal amount=new BigDecimal(value).setScale(2);
        if(amount.compareTo(new BigDecimal("9999999999999.99"))>0)
            throw new IllegalArgumentException("银行金额数据无效");
        return amount;
    }
    private static BigDecimal aggregate(String value) {
        if(value==null||value.length()>40||!value.matches("[0-9]+(\\.[0-9]{1,2})?"))
            throw new IllegalArgumentException("流水汇总数据无效");
        return new BigDecimal(value).setScale(2);
    }
    private static String get(Map<String,String> d,String key) {
        String value=d.get(key); if(value==null) throw new IllegalArgumentException("银行响应缺少 "+key);
        return value;
    }
    public static long id(String value) {
        long id=Long.parseLong(value); if(id<1)throw new IllegalArgumentException("编号无效");return id;
    }
    private static Long optionalId(String value) { return value.isBlank()?null:id(value); }
    private static boolean bool(String value) {
        if(!value.equals("true")&&!value.equals("false")) throw new IllegalArgumentException("银行状态数据无效");
        return Boolean.parseBoolean(value);
    }
}
