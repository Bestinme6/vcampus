package com.vcampus.server.service;

import com.vcampus.common.model.*;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.*;
import com.vcampus.server.database.LibraryCatalogStore.*;
import com.vcampus.server.database.LibraryLoanStore.*;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

public final class LibraryService {
    private static final int PAGE_SIZE = 10;
    private final LibraryCatalogStore catalog;
    private final LibraryLoanStore loans;
    private final SessionManager sessions;
    private final AuditStore audit;
    private final Clock clock;

    public LibraryService(LibraryCatalogStore catalog, LibraryLoanStore loans,
                          SessionManager sessions, AuditStore audit, Clock clock) {
        this.catalog = Objects.requireNonNull(catalog);
        this.loans = Objects.requireNonNull(loans);
        this.sessions = Objects.requireNonNull(sessions);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    public ResponseMessage searchCatalog(RequestMessage r) { return handle(r, false, s -> { boolean manage=LibraryAccessPolicy.canManage(s.roles()); boolean include=manage&&Boolean.TRUE.equals(bool(r,"includeDisabled")); boolean newest=manage&&Boolean.TRUE.equals(bool(r,"newestFirst")); return catalogPage(r,catalog.search(new CatalogQuery(p(r,"keyword"),p(r,"category"),include,newest,page(r),PAGE_SIZE))); }); }
    public ResponseMessage getCatalogItem(RequestMessage r) { return handle(r, false, s -> catalog.findBook(id(r,"bookId")).map(x -> ok(r,"查询成功",Map.of("row",catalogRow(x)))).orElseGet(() -> fail(r,"书目不存在"))); }
    public ResponseMessage myLoans(RequestMessage r) { return handle(r, true, s -> { var rule=LibraryLoanPolicy.ruleFor(s.roles()); var q=new LoanQuery(p(r,"keyword"),bool(r,"active"),bool(r,"overdue"),page(r),PAGE_SIZE); var data=loanPage(loans.searchBorrowerLoans(s.userId(),q)); data.put("maxLoans",Integer.toString(rule.maxLoans())); data.put("initialLoanDays",Long.toString(rule.initialLoanDuration().toDays())); data.put("renewalDays",Long.toString(rule.renewalDuration().toDays())); return ok(r,"查询成功",data); }); }
    public ResponseMessage borrow(RequestMessage r) { return handle(r, true, s -> { var rule=LibraryLoanPolicy.ruleFor(s.roles()); Instant now=clock.instant(); var x=loans.borrow(new BorrowCommand(s.userId(),id(r,"bookId"),null,s.userId(),LibraryLoanChannel.SELF_SERVICE,now,now.plus(rule.initialLoanDuration()),rule.maxLoans())); audited(s,r); return ok(r,"借阅成功",borrowData(x)); }); }
    public ResponseMessage returnLoan(RequestMessage r) { return handle(r, true, s -> { var x=loans.returnLoan(new ReturnCommand(s.userId(),id(r,"loanId"),null,s.userId(),LibraryReturnCondition.NORMAL,null,clock.instant(),false)); audited(s,r); return ok(r,"归还成功",returnData(x)); }); }
    public ResponseMessage renew(RequestMessage r) { return handle(r, true, s -> { var rule=LibraryLoanPolicy.ruleFor(s.roles()); var x=loans.renew(new RenewCommand(s.userId(),id(r,"loanId"),clock.instant(),rule.renewalDuration())); audited(s,r); return ok(r,"续借成功",Map.of("loanId",Long.toString(x.loanId()),"dueAt",x.dueAt().toString(),"renewalCount",Integer.toString(x.renewalCount()))); }); }

    public ResponseMessage createBook(RequestMessage r) { return handleManage(r,s -> { var x=catalog.createBook(book(r)); audited(s,r); return ok(r,"创建成功",Map.of("bookId",Long.toString(x.bookId()),"catalogCode",x.catalogCode())); }); }
    public ResponseMessage updateBook(RequestMessage r) { return handleManage(r,s -> mutation(r,catalog.updateBook(id(r,"bookId"),book(r)),s)); }
    public ResponseMessage setBookEnabled(RequestMessage r) { return handleManage(r,s -> mutation(r,catalog.setBookEnabled(id(r,"bookId"),requiredBool(r,"enabled")),s)); }
    public ResponseMessage searchCopies(RequestMessage r) { return handleManage(r,s -> copyPage(r,catalog.searchCopies(new CopyQuery(optionalId(r,"bookId"),p(r,"keyword"),enumValue(r,"status",LibraryCopyStatus.class),Boolean.TRUE.equals(bool(r,"newestFirst")),page(r),PAGE_SIZE)))); }
    public ResponseMessage createCopy(RequestMessage r) { return handleManage(r,s -> { var x=catalog.createCopy(new CreateCopy(id(r,"bookId"),p(r,"barcode"),p(r,"shelfLocation"))); audited(s,r); return ok(r,"创建成功",Map.of("copyId",Long.toString(x.copyId()),"barcode",x.barcode())); }); }
    public ResponseMessage setCopyStatus(RequestMessage r) { return handleManage(r,s -> mutation(r,catalog.setCopyStatus(id(r,"copyId"),requiredEnum(r,"status",LibraryCopyStatus.class),p(r,"reason")),s)); }
    public ResponseMessage searchLoans(RequestMessage r) { return handleManage(r,s -> ok(r,"查询成功",loanPage(loans.searchAllLoans(new LoanQuery(p(r,"keyword"),bool(r,"active"),bool(r,"overdue"),page(r),PAGE_SIZE))))); }
    public ResponseMessage previewCirculation(RequestMessage r) { return handleManage(r,s -> { Borrower b=borrower(r); var rule=LibraryLoanPolicy.ruleFor(Set.of(b.baseIdentity())); var x=loans.previewCirculation(b.userId(),LibraryCodePolicy.requireValidBarcode(p(r,"barcode")),requiredEnum(r,"operation",LibraryCirculationOperation.class),clock.instant(),rule.maxLoans()); return ok(r,"查询成功",previewData(b,x)); }); }
    public ResponseMessage adminBorrow(RequestMessage r) { return handleManage(r,s -> { Borrower b=borrower(r); var rule=LibraryLoanPolicy.ruleFor(Set.of(b.baseIdentity())); Instant now=clock.instant(); var x=loans.borrow(new BorrowCommand(b.userId(),null,LibraryCodePolicy.requireValidBarcode(p(r,"barcode")),s.userId(),LibraryLoanChannel.ADMIN_DESK,now,now.plus(rule.initialLoanDuration()),rule.maxLoans())); audited(s,r); return ok(r,"借阅成功",borrowData(x)); }); }
    public ResponseMessage adminReturn(RequestMessage r) { return handleManage(r,s -> { var x=loans.returnLoan(new ReturnCommand(0,null,LibraryCodePolicy.requireValidBarcode(p(r,"barcode")),s.userId(),requiredEnum(r,"condition",LibraryReturnCondition.class),p(r,"reason"),clock.instant(),true)); audited(s,r); return ok(r,"归还成功",returnData(x)); }); }

    private ResponseMessage handleManage(RequestMessage r, Work w) { return handle(r,false,s -> { if(!LibraryAccessPolicy.canManage(s.roles())) return fail(r,"没有执行该操作的权限"); return w.run(s); }); }
    private ResponseMessage handle(RequestMessage r, boolean borrow, Work w) {
        Optional<UserSession> found=sessions.find(p(r,"sessionToken")); if(found.isEmpty()) return fail(r,"登录已过期，请重新登录");
        if(borrow&&!LibraryAccessPolicy.canBorrow(found.get().roles())) return fail(r,"没有执行该操作的权限");
        try { return w.run(found.get()); }
        catch (IllegalArgumentException|LibraryRuleException e) { return fail(r,e.getMessage()==null?"请求参数无效":e.getMessage()); }
        catch (SQLException e) { System.err.println("Library database operation failed: "+e.getMessage()); return fail(r,"图书馆数据暂时不可用，请稍后重试"); }
    }
    private Borrower borrower(RequestMessage r)throws SQLException{return loans.findBorrower(p(r,"username")).orElseThrow(()->new IllegalArgumentException("借阅人不存在或账号不可用"));}
    private BookCommand book(RequestMessage r){String isbn=p(r,"isbn");return new BookCommand(isbn.isBlank()?null:LibraryCodePolicy.normalizeIsbn(isbn),required(r,"title"),required(r,"authors"),p(r,"publisher"),integer(r,"publishYear"),p(r,"category"),p(r,"description"));}
    private ResponseMessage mutation(RequestMessage r,MutationResult m,UserSession s){if(m==MutationResult.NOT_FOUND)return fail(r,"记录不存在");if(m==MutationResult.CONFLICT)return fail(r,"当前状态不允许该操作");if(m==MutationResult.CHANGED)audited(s,r);return ok(r,m==MutationResult.UNCHANGED?"无需更新":"更新成功",Map.of("changed",Boolean.toString(m==MutationResult.CHANGED)));}
    private ResponseMessage catalogPage(RequestMessage r,CatalogPage x){Map<String,String>d=pageData(x.page(),x.pageSize(),x.total(),x.rows().size());for(int i=0;i<x.rows().size();i++)d.put("row."+i,catalogRow(x.rows().get(i)));return ok(r,"查询成功",d);}
    private ResponseMessage copyPage(RequestMessage r,CopyPage x){Map<String,String>d=pageData(x.page(),x.pageSize(),x.total(),x.rows().size());for(int i=0;i<x.rows().size();i++)d.put("row."+i,copyRow(x.rows().get(i)));return ok(r,"查询成功",d);}
    private Map<String,String> pageData(int page,int size,int total,int count){Map<String,String>d=new LinkedHashMap<>();d.put("page",Integer.toString(page));d.put("pageSize",Integer.toString(size));d.put("total",Integer.toString(total));d.put("count",Integer.toString(count));return d;}
    private Map<String,String> loanPage(LoanPage x){Map<String,String>d=new LinkedHashMap<>();d.put("page",Integer.toString(x.page()));d.put("pageSize",Integer.toString(x.pageSize()));d.put("total",Integer.toString(x.total()));d.put("count",Integer.toString(x.rows().size()));for(int i=0;i<x.rows().size();i++)d.put("row."+i,loanRow(x.rows().get(i)));return d;}
    private String catalogRow(CatalogItem x){return RowCodec.encode(Long.toString(x.bookId()),x.catalogCode(),str(x.isbn()),x.title(),x.authors(),x.publisher(),x.publishYear()==null?"":x.publishYear().toString(),x.category(),x.description(),Boolean.toString(x.enabled()),Integer.toString(x.totalCopies()),Integer.toString(x.availableCopies()));}
    private String copyRow(CopyRecord x){return RowCodec.encode(Long.toString(x.copyId()),Long.toString(x.bookId()),x.barcode(),x.title(),x.shelfLocation(),x.status().name(),x.statusReason(),x.updatedAt().toString());}
    private String loanRow(LoanRecord x){return RowCodec.encode(Long.toString(x.loanId()),Long.toString(x.bookId()),x.isbn(),x.title(),Long.toString(x.copyId()),x.barcode(),x.borrowerUsername(),x.borrowerDisplayName(),x.borrowedAt().toString(),x.dueAt().toString(),Integer.toString(x.renewalCount()),str(x.returnedAt()),x.returnCondition()==null?"":x.returnCondition().name(),x.channel().name(),Boolean.toString(x.overdue()),Boolean.toString(x.renewable()));}
    private Map<String,String> borrowData(BorrowReceipt x){return Map.of("loanId",Long.toString(x.loanId()),"copyId",Long.toString(x.copyId()),"barcode",x.barcode(),"title",x.title(),"dueAt",x.dueAt().toString());}
    private Map<String,String> returnData(ReturnReceipt x){return Map.of("loanId",Long.toString(x.loanId()),"copyId",Long.toString(x.copyId()),"barcode",x.barcode(),"condition",x.condition().name(),"returnedAt",x.returnedAt().toString());}
    private Map<String,String> previewData(Borrower b,CirculationPreview x){Map<String,String>d=new LinkedHashMap<>();d.put("borrowerUserId",Long.toString(b.userId()));d.put("username",b.username());d.put("displayName",b.displayName());d.put("baseIdentity",b.baseIdentity().name());d.put("copyId",Long.toString(x.copyId()));d.put("bookId",Long.toString(x.bookId()));d.put("title",x.title());d.put("barcode",x.barcode());d.put("copyStatus",x.copyStatus().name());d.put("activeLoanId",x.activeLoanId()==null?"":x.activeLoanId().toString());d.put("activeLoans",Integer.toString(x.activeLoans()));d.put("maxLoans",Integer.toString(x.maxLoans()));d.put("overdue",Boolean.toString(x.overdue()));d.put("allowed",Boolean.toString(x.allowed()));d.put("message",x.message());return d;}
    private void audited(UserSession s,RequestMessage r){audit.record(s.userId(),r.action(),"SUCCESS",null);}
    private String p(RequestMessage r,String k){return r.parameters().getOrDefault(k,"");} private String required(RequestMessage r,String k){String v=p(r,k).trim();if(v.isEmpty())throw new IllegalArgumentException(k+"不能为空");return v;}
    private long id(RequestMessage r,String k){long v=Long.parseLong(required(r,k));if(v<1)throw new IllegalArgumentException(k+"无效");return v;} private Long optionalId(RequestMessage r,String k){return p(r,k).isBlank()?null:id(r,k);}
    private int page(RequestMessage r){String v=p(r,"page");int n=v.isBlank()?1:Integer.parseInt(v);if(n<1)throw new IllegalArgumentException("页码无效");return n;} private Integer integer(RequestMessage r,String k){return p(r,k).isBlank()?null:Integer.valueOf(p(r,k));}
    private Boolean bool(RequestMessage r,String k){String v=p(r,k);if(v.isBlank())return null;if("true".equalsIgnoreCase(v))return true;if("false".equalsIgnoreCase(v))return false;throw new IllegalArgumentException(k+"无效");} private boolean requiredBool(RequestMessage r,String k){Boolean b=bool(r,k);if(b==null)throw new IllegalArgumentException(k+"不能为空");return b;}
    private <E extends Enum<E>>E enumValue(RequestMessage r,String k,Class<E> c){String v=p(r,k);return v.isBlank()?null:Enum.valueOf(c,v.trim());} private <E extends Enum<E>>E requiredEnum(RequestMessage r,String k,Class<E> c){E e=enumValue(r,k,c);if(e==null)throw new IllegalArgumentException(k+"不能为空");return e;}
    private String str(Object x){return x==null?"":x.toString();} private ResponseMessage ok(RequestMessage r,String m,Map<String,String>d){return ResponseMessage.success(r.requestId(),m,d);} private ResponseMessage fail(RequestMessage r,String m){return ResponseMessage.failure(r.requestId(),m);}
    @FunctionalInterface private interface Work{ResponseMessage run(UserSession s)throws SQLException;}
}
