package com.vcampus.client.fx.library;

import com.vcampus.client.ui.LibraryViewData;
import com.vcampus.common.model.*;
import com.vcampus.common.protocol.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

public final class LibraryData {
    private LibraryData() {}
    public record Book(LibraryViewData.CatalogRow book,int onLoanCopies,long borrowCount,BigDecimal replacementPrice) {
        public Book(LibraryViewData.CatalogRow book,int onLoanCopies,long borrowCount){this(book,onLoanCopies,borrowCount,null);}
    }
    public record Page<T>(List<T> rows,int page,int pageSize,int total) {
        public Page {rows=List.copyOf(rows);if(page<1||pageSize<1||total<0||rows.size()>pageSize||rows.size()>total)throw new IllegalArgumentException("分页数据无效");}
        public int pages(){return Math.max(1,(int)((total+(long)pageSize-1)/pageSize));}
    }
    public record Reservation(long id,long bookId,String catalogCode,String title,String status,Instant createdAt,Instant notifiedAt) {}
    public record FeeBill(long billId,String billNo,long loanId,String title,String barcode,
                          LibraryFeeStatus status,BigDecimal overdueAmount,BigDecimal damageAmount,
                          BigDecimal lostAmount,BigDecimal totalAmount,Instant createdAt,Instant paidAt,
                          String borrowerUsername,String borrowerDisplayName) {}
    public record FeeDetail(FeeBill bill,int overdueDays,int billableDays,
                            LibraryReturnCondition condition,LibraryDamageLevel damageLevel,
                            BigDecimal replacementPrice,Integer graceDays,BigDecimal dailyOverdueFee,
                            BigDecimal overdueCap,BigDecimal conditionRate,String reason,
                            String paymentReference,String refundReference) {
        public FeeDetail(FeeBill bill,int overdueDays,int billableDays,LibraryReturnCondition condition,LibraryDamageLevel damageLevel,BigDecimal replacementPrice,String reason,String paymentReference,String refundReference){this(bill,overdueDays,billableDays,condition,damageLevel,replacementPrice,null,null,null,null,reason,paymentReference,refundReference);}
    }
    public record FeePreview(LibraryFeeAssessment assessment,String title,String barcode) {}
    public record PaymentPreview(FeeDetail detail,BigDecimal balance,BankAccountStatus accountStatus) {}
    public record FeePolicy(int graceDays,BigDecimal dailyOverdueFee,BigDecimal overdueCap,
                            BigDecimal minorDamageRate,BigDecimal majorDamageRate,BigDecimal lostRate) {}
    public static Page<Book> books(ResponseMessage response) {
        require(response);
        var legacy=LibraryViewData.catalogPage(response); var rows=new ArrayList<Book>();
        for(int i=0;i<legacy.rows().size();i++) rows.add(book(legacy.rows().get(i),response.data(),"row."+i+"."));
        return new Page<>(rows,legacy.page(),legacy.pageSize(),legacy.total());
    }
    public static Book detail(ResponseMessage response) {
        require(response); var data=new HashMap<>(response.data());
        data.put("row.0",Objects.requireNonNull(data.get("row"),"缺少书目详情"));
        data.putAll(Map.of("page","1","pageSize","1","total","1","count","1"));
        var legacy=LibraryViewData.catalogPage(ResponseMessage.success(response.requestId(),response.message(),data));
        return book(legacy.rows().getFirst(),response.data(),"");
    }
    private static Book book(LibraryViewData.CatalogRow row,Map<String,String> data,String prefix) {
        long loans=metadata(data,prefix+"onLoanCopies"), count=metadata(data,prefix+"borrowCount");
        if(loans>Integer.MAX_VALUE||loans>row.totalCopies()||row.availableCopies()<0||row.availableCopies()>row.totalCopies())
            throw new IllegalArgumentException("馆藏统计数据无效");
        return new Book(row,(int)loans,count,nullableMoney(data.get(prefix+"replacementPrice")));
    }
    private static long metadata(Map<String,String> data,String key) {
        if(!data.containsKey(key))return -1;
        long value=Long.parseLong(data.get(key));if(value<0)throw new IllegalArgumentException("馆藏统计数据无效");return value;
    }
    public static Page<Reservation> reservations(ResponseMessage response) {
        require(response); var d=response.data();int count=Integer.parseInt(d.get("count"));
        if(count<0||count>100)throw new IllegalArgumentException("预约数据无效");
        var rows=new ArrayList<Reservation>();
        for(int i=0;i<count;i++) {
            var f=RowCodec.decode(Objects.requireNonNull(d.get("row."+i)));
            if(f.size()!=7||!Set.of("WAITING","NOTIFIED","CANCELLED").contains(f.get(4)))throw new IllegalArgumentException("预约数据无效");
            long id=Long.parseLong(f.get(0)),bookId=Long.parseLong(f.get(1));
            if(id<1||bookId<1)throw new IllegalArgumentException("预约编号无效");
            rows.add(new Reservation(id,bookId,f.get(2),f.get(3),f.get(4),Instant.parse(f.get(5)),f.get(6).isBlank()?null:Instant.parse(f.get(6))));
        }
        return new Page<>(rows,Integer.parseInt(d.get("page")),Integer.parseInt(d.get("pageSize")),Integer.parseInt(d.get("total")));
    }
    public static Page<FeeBill> fees(ResponseMessage response){require(response);Map<String,String>d=response.data();int count=integer(d,"count");if(count<0||count>100)throw new IllegalArgumentException("费用数据无效");List<FeeBill>rows=new ArrayList<>();for(int i=0;i<count;i++)rows.add(feeRow(Objects.requireNonNull(d.get("row."+i),"缺少费用行")));return new Page<>(rows,integer(d,"page"),integer(d,"pageSize"),integer(d,"total"));}
    public static FeeDetail feeDetail(ResponseMessage response){require(response);return detail(response.data());}
    public static PaymentPreview paymentPreview(ResponseMessage response){require(response);Map<String,String>d=response.data();return new PaymentPreview(detail(d),money(d.get("balance"),false),enumValue(BankAccountStatus.class,d.get("accountStatus"),false));}
    public static FeePreview feePreview(ResponseMessage response){require(response);Map<String,String>d=response.data();int overdue=nonNegative(integer(d,"overdueDays")),billable=nonNegative(integer(d,"billableDays"));if(billable>overdue)throw new IllegalArgumentException("费用天数无效");BigDecimal price=nullableMoney(d.get("replacementPrice"));BigDecimal overdueAmount=money(d.get("overdueAmount"),true),damage=money(d.get("damageAmount"),true),lost=money(d.get("lostAmount"),true),total=money(d.get("totalAmount"),true);if(total.compareTo(overdueAmount.add(damage).add(lost))!=0)throw new IllegalArgumentException("费用合计无效");return new FeePreview(new LibraryFeeAssessment(overdue,billable,price,overdueAmount,damage,lost,total),d.getOrDefault("title",""),d.getOrDefault("barcode",""));}
    public static FeePolicy feePolicy(ResponseMessage response){require(response);Map<String,String>d=response.data();int grace=nonNegative(integer(d,"graceDays"));return new FeePolicy(grace,money(d.get("dailyOverdueFee"),false),money(d.get("overdueCap"),false),rate(d.get("minorDamageRate")),rate(d.get("majorDamageRate")),rate(d.get("lostRate")));}
    private static FeeDetail detail(Map<String,String>d){FeeBill bill=feeRow(Objects.requireNonNull(d.get("row"),"缺少费用详情"));int overdue=nonNegative(integer(d,"overdueDays")),billable=nonNegative(integer(d,"billableDays"));if(billable>overdue)throw new IllegalArgumentException("费用天数无效");Integer grace=d.containsKey("graceDaysSnapshot")?nonNegative(integer(d,"graceDaysSnapshot")):null;return new FeeDetail(bill,overdue,billable,enumValue(LibraryReturnCondition.class,d.get("returnCondition"),false),enumValue(LibraryDamageLevel.class,d.get("damageLevel"),true),nullableMoney(d.get("replacementPrice")),grace,nullableMoney(d.get("dailyOverdueFeeSnapshot")),nullableMoney(d.get("overdueCapSnapshot")),nullableRate(d.get("conditionRateSnapshot")),d.getOrDefault("reason",""),d.getOrDefault("paymentReference",""),d.getOrDefault("refundReference",""));}
    private static FeeBill feeRow(String encoded){List<String>f=RowCodec.decode(encoded);if(f.size()!=14)throw new IllegalArgumentException("费用行格式无效");long billId=positiveLong(f.get(0)),loanId=positiveLong(f.get(2));BigDecimal overdue=money(f.get(6),true),damage=money(f.get(7),true),lost=money(f.get(8),true),total=money(f.get(9),false);if(total.compareTo(overdue.add(damage).add(lost))!=0)throw new IllegalArgumentException("费用合计无效");return new FeeBill(billId,f.get(1),loanId,f.get(3),f.get(4),enumValue(LibraryFeeStatus.class,f.get(5),false),overdue,damage,lost,total,Instant.parse(f.get(10)),f.get(11).isBlank()?null:Instant.parse(f.get(11)),f.get(12),f.get(13));}
    private static int integer(Map<String,String>d,String key){try{return Integer.parseInt(Objects.requireNonNull(d.get(key),"缺少"+key));}catch(NumberFormatException e){throw new IllegalArgumentException(key+"格式无效",e);}}
    private static int nonNegative(int n){if(n<0)throw new IllegalArgumentException("数值不能为负数");return n;}
    private static long positiveLong(String v){try{long n=Long.parseLong(v);if(n<1)throw new IllegalArgumentException("编号无效");return n;}catch(NumberFormatException e){throw new IllegalArgumentException("编号无效",e);}}
    private static BigDecimal nullableMoney(String v){return v==null||v.isBlank()?null:money(v,false);}
    private static BigDecimal money(String v,boolean zeroAllowed){try{BigDecimal x=new BigDecimal(Objects.requireNonNull(v,"缺少金额")).setScale(2,RoundingMode.UNNECESSARY);if(x.signum()<(zeroAllowed?0:1))throw new IllegalArgumentException("金额无效");return x;}catch(ArithmeticException|NumberFormatException e){throw new IllegalArgumentException("金额格式无效",e);}}
    private static BigDecimal rate(String v){try{BigDecimal x=new BigDecimal(Objects.requireNonNull(v)).setScale(4,RoundingMode.UNNECESSARY);if(x.signum()<=0||x.compareTo(BigDecimal.ONE)>0)throw new IllegalArgumentException("比例无效");return x;}catch(ArithmeticException|NumberFormatException e){throw new IllegalArgumentException("比例格式无效",e);}}
    private static BigDecimal nullableRate(String v){return v==null||v.isBlank()?null:rate(v);}
    private static <E extends Enum<E>>E enumValue(Class<E>type,String value,boolean nullable){if(nullable&&(value==null||value.isBlank()))return null;try{return Enum.valueOf(type,Objects.requireNonNull(value));}catch(Exception e){throw new IllegalArgumentException("状态无效",e);}}
    public static void require(ResponseMessage response) {
        if(response==null)throw new IllegalArgumentException("服务器未返回数据");
        if(!response.success())throw new IllegalArgumentException(response.message());
    }
}
