package com.vcampus.server.service;

import com.vcampus.common.model.*;
import com.vcampus.common.protocol.*;
import com.vcampus.server.database.*;
import com.vcampus.server.database.LibraryFeeStore.*;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LibraryServiceTest {
    private final SessionManager sessions=new SessionManager(); private final Catalog catalog=new Catalog(); private final Loans loans=new Loans(); private final Audit audit=new Audit(); private LibraryService service;
    @BeforeEach void setUp(){service=new LibraryService(catalog,loans,sessions,audit,Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"),ZoneOffset.UTC));}

    @Test void superAdministratorCannotSelfBorrow(){var admin=session(1,UserRole.SUPER_ADMIN);var response=service.borrow(request(Actions.LIBRARY_LOAN_BORROW,admin.token(),Map.of("bookId","10")));assertFalse(response.success());assertEquals("没有执行该操作的权限",response.message());assertEquals(0,loans.borrowCalls);}
    @Test void catalogEncodingHasStableFieldOrder(){var student=session(2,UserRole.STUDENT);var response=service.searchCatalog(request(Actions.LIBRARY_CATALOG_SEARCH,student.token(),Map.of()));assertTrue(response.success());assertEquals(List.of("10","BK000000010","9787111565277","Java","作者","出版社","2026","计算机","简介","true","4","2"),RowCodec.decode(response.data().get("row.0")));}
    @Test void catalogAndDetailExposePriceAsMetadata(){var student=session(20,UserRole.STUDENT);var page=service.searchCatalog(request(Actions.LIBRARY_CATALOG_SEARCH,student.token(),Map.of()));assertEquals("89.00",page.data().get("row.0.replacementPrice"));var detail=service.getCatalogItem(request(Actions.LIBRARY_CATALOG_GET,student.token(),Map.of("bookId","10")));assertTrue(detail.success());assertEquals("89.00",detail.data().get("replacementPrice"));}
    @Test void createBookRequiresValidReplacementPrice(){var admin=session(21,UserRole.LIBRARY_ADMIN);for(String price:List.of("","0","-1","1.001")){var response=service.createBook(request(Actions.LIBRARY_ADMIN_BOOK_CREATE,admin.token(),Map.of("title","Java","authors","作者","publisher","出版社","category","计算机","replacementPrice",price)));assertFalse(response.success(),price);}}
    @Test void administratorCanBorrowForStudentAndAuditExactAction(){var admin=session(3,UserRole.LIBRARY_ADMIN);var response=service.adminBorrow(request(Actions.LIBRARY_ADMIN_LOAN_BORROW,admin.token(),Map.of("username","student","barcode","B000000128")));assertTrue(response.success());assertEquals(1,loans.borrowCalls);assertEquals(Actions.LIBRARY_ADMIN_LOAN_BORROW,audit.action);}
    @Test void administratorCanSubmitDamagedReturn(){var admin=session(4,UserRole.LIBRARY_ADMIN);var response=service.adminReturn(request(Actions.LIBRARY_ADMIN_LOAN_RETURN,admin.token(),Map.of("barcode","B000000128","condition","DAMAGED","damageLevel","MAJOR","reason","封面脱落")));assertTrue(response.success());assertEquals(LibraryReturnCondition.DAMAGED,loans.returnCommand.condition());assertEquals(LibraryDamageLevel.MAJOR,loans.returnCommand.damageLevel());assertEquals("封面脱落",loans.returnCommand.reason());assertTrue(loans.returnCommand.administrator());assertEquals(Actions.LIBRARY_ADMIN_LOAN_RETURN,audit.action);}
    @Test void readerCannotCallAdministratorReturnAction(){var student=session(5,UserRole.STUDENT);var response=service.adminReturn(request(Actions.LIBRARY_ADMIN_LOAN_RETURN,student.token(),Map.of("barcode","B000000128","condition","DAMAGED","reason","封面脱落")));assertFalse(response.success());assertEquals("没有执行该操作的权限",response.message());assertNull(loans.returnCommand);}
    @Test void selfReturnIgnoresInjectedDamagedConditionAndRemainsNormal(){var student=session(6,UserRole.STUDENT);var response=service.returnLoan(request(Actions.LIBRARY_LOAN_RETURN,student.token(),Map.of("loanId","501","condition","DAMAGED","reason","封面脱落")));assertTrue(response.success());assertEquals(LibraryReturnCondition.NORMAL,loans.returnCommand.condition());assertFalse(loans.returnCommand.administrator());assertNull(loans.returnCommand.reason());}

    @Test void reservationEndpointsUseSessionOwnerAndRejectTeacher() {
        Reservations reservations=new Reservations();
        service=new LibraryService(catalog,loans,reservations,sessions,audit,Clock.systemUTC());
        var student=session(42,UserRole.STUDENT);
        var created=service.createReservation(request(Actions.LIBRARY_RESERVATION_CREATE,student.token(),Map.of("bookId","10","borrowerUserId","99")));
        assertTrue(created.success());assertEquals("123",created.data().get("reservationId"));assertEquals(42,reservations.user);
        assertTrue(service.cancelReservation(request(Actions.LIBRARY_RESERVATION_CANCEL,student.token(),Map.of("reservationId","123","borrowerUserId","99"))).success());
        assertEquals(42,reservations.user);
        var page=service.myReservations(request(Actions.LIBRARY_RESERVATION_MY,student.token(),Map.of("status","WAITING")));
        assertTrue(page.success());assertEquals(7,RowCodec.decode(page.data().get("row.0")).size());
        for(String action:List.of(Actions.LIBRARY_RESERVATION_CREATE,Actions.LIBRARY_RESERVATION_CANCEL,Actions.LIBRARY_RESERVATION_MY)) {
            var r=request(action,session(43,UserRole.TEACHER).token(),Map.of("bookId","10","reservationId","123"));
            var response=switch(action){case Actions.LIBRARY_RESERVATION_CREATE->service.createReservation(r);case Actions.LIBRARY_RESERVATION_CANCEL->service.cancelReservation(r);default->service.myReservations(r);};
            assertFalse(response.success());
        }
    }

    @Test void catalogSortIsWhitelistedAndMetadataDoesNotChangeLegacyRows() {
        var student=session(2,UserRole.STUDENT);
        var good=service.searchCatalog(request(Actions.LIBRARY_CATALOG_SEARCH,student.token(),Map.of("sort","BORROW_COUNT_DESC")));
        assertTrue(good.success());assertEquals(12,RowCodec.decode(good.data().get("row.0")).size());
        assertEquals("1",good.data().get("row.0.onLoanCopies"));assertEquals("8",good.data().get("row.0.borrowCount"));
        assertFalse(service.searchCatalog(request(Actions.LIBRARY_CATALOG_SEARCH,student.token(),Map.of("sort","title; DELETE FROM books"))).success());
    }

    @Test void borrowerAndAdministratorFeePayloadsRespectRoles() {
        FakeFees feeStore=new FakeFees();service=new LibraryService(catalog,loans,null,feeStore,sessions,audit,Clock.systemUTC());
        var student=session(70,UserRole.STUDENT);
        var own=service.myFees(request(Actions.LIBRARY_FEE_MY,student.token(),Map.of()));
        assertTrue(own.success());assertEquals(14,RowCodec.decode(own.data().get("row.0")).size());
        assertEquals("",RowCodec.decode(own.data().get("row.0")).get(12));
        var preview=service.paymentPreview(request(Actions.LIBRARY_FEE_PAYMENT_PREVIEW,student.token(),Map.of("billId","1")));
        assertEquals("50.00",preview.data().get("balance"));
        var denied=service.searchFees(request(Actions.LIBRARY_ADMIN_FEE_SEARCH,student.token(),Map.of()));assertFalse(denied.success());
        var admin=session(71,UserRole.LIBRARY_ADMIN);var all=service.searchFees(request(Actions.LIBRARY_ADMIN_FEE_SEARCH,admin.token(),Map.of()));
        assertEquals("student",RowCodec.decode(all.data().get("row.0")).get(12));
        var detail=service.feeDetail(request(Actions.LIBRARY_FEE_GET,admin.token(),Map.of("billId","1")));
        assertTrue(detail.success());assertEquals("student",RowCodec.decode(detail.data().get("row")).get(12));
        assertEquals("0.50",detail.data().get("dailyOverdueFeeSnapshot"));assertEquals("0.2000",detail.data().get("conditionRateSnapshot"));
    }

    private static final class Reservations implements LibraryReservationStore {
        long user;
        public long create(long u,long b,Instant now){user=u;return 123;}
        public boolean cancel(long u,long id){user=u;return true;}
        public ReservationPage search(long u,LibraryReservationStatus status,int page,int size){user=u;return new ReservationPage(List.of(new ReservationRecord(123,10,"BK000000010","Java",LibraryReservationStatus.WAITING,Instant.EPOCH,null)),page,size,1);}
    }

    private SessionManager.UserSession session(long id,UserRole role){Set<UserRole> roles=role==UserRole.LIBRARY_ADMIN?Set.of(UserRole.STUDENT,role):Set.of(role);return sessions.create(new UserAccount(id,"u"+id,"h","s","用户",true,false,roles));}
    private RequestMessage request(String action,String token,Map<String,String>values){Map<String,String>p=new HashMap<>(values);p.put("sessionToken",token);return RequestMessage.create(action,p);}
    private static final class Audit implements AuditStore{String action;public void record(Long u,String a,String r,String c){action=a;}}
    private static final class Catalog implements LibraryCatalogStore{
        private CatalogItem row(){return new CatalogItem(10,"BK000000010","9787111565277","Java","作者","出版社",2026,"计算机","简介",true,4,2,1,8,new BigDecimal("89.00"));}public CatalogPage search(CatalogQuery q){return new CatalogPage(List.of(row()),q.page(),q.pageSize(),1);}public Optional<CatalogItem>findBook(long i){return i==10?Optional.of(row()):Optional.empty();}public CreatedBook createBook(BookCommand c){return new CreatedBook(1,"BK000000001");}public MutationResult updateBook(long i,BookCommand c){return MutationResult.CHANGED;}public MutationResult setBookEnabled(long i,boolean e){return MutationResult.CHANGED;}public CopyPage searchCopies(CopyQuery q){return new CopyPage(List.of(),q.page(),q.pageSize(),0);}public CreatedCopy createCopy(CreateCopy c){return new CreatedCopy(1,"B000000001");}public MutationResult setCopyStatus(long i,LibraryCopyStatus s,String r){return MutationResult.CHANGED;}
    }
    private static final class Loans implements LibraryLoanStore{
        int borrowCalls;ReturnCommand returnCommand;public Optional<Borrower>findBorrower(String u){return Optional.of(new Borrower(20,"student","学生",UserRole.STUDENT,true));}public CirculationPreview previewCirculation(long u,String b,LibraryCirculationOperation o,Instant n,int m){return new CirculationPreview(1,10,"Java",b,LibraryCopyStatus.AVAILABLE,null,0,m,false,true,"可以办理借阅");}public LoanPage searchBorrowerLoans(long u,LoanQuery q){return new LoanPage(List.of(),q.page(),q.pageSize(),0);}public LoanPage searchAllLoans(LoanQuery q){return new LoanPage(List.of(),q.page(),q.pageSize(),0);}public BorrowReceipt borrow(BorrowCommand c){borrowCalls++;return new BorrowReceipt(1,1,"B000000128","Java",c.dueAt());}public ReturnReceipt returnLoan(ReturnCommand c){returnCommand=c;return new ReturnReceipt(1,1,"B000000128",c.condition(),c.returnedAt());}public RenewReceipt renew(RenewCommand c){return new RenewReceipt(c.loanId(),c.now().plus(c.extension()),1);}
    }
    private static final class FakeFees implements LibraryFeeStore{
        private final FeeBill bill=new FeeBill(1,"LFB000000001",2,70,"student","学生",3,4,"Java","B000000001",LibraryFeeStatus.PENDING,LibraryReturnCondition.DAMAGED,LibraryDamageLevel.MINOR,Instant.EPOCH,Instant.EPOCH,2,1,new BigDecimal("89.00"),1,new BigDecimal("0.50"),new BigDecimal("30.00"),new BigDecimal("0.2000"),new BigDecimal("0.50"),new BigDecimal("17.80"),new BigDecimal("0.00"),new BigDecimal("18.30"),"破损",null,null,null,null,null,null,null,Instant.EPOCH);
        public boolean hasPending(java.sql.Connection c,long u){return true;}public Optional<LibraryFeeWriter.CreatedBill>createForReturn(java.sql.Connection c,LibraryFeeWriter.ClosedLoan l){return Optional.empty();}
        public LibraryFeePolicy currentPolicy(){return new LibraryFeePolicy(1,new BigDecimal("0.50"),new BigDecimal("30.00"),new BigDecimal("0.2000"),new BigDecimal("0.5000"),new BigDecimal("1.0000"));}public LibraryFeePolicy updatePolicy(long u,LibraryFeePolicy p){return p;}
        public LibraryFeeAssessment previewReturn(ReturnFeePreview c){return new LibraryFeeAssessment(2,1,new BigDecimal("89.00"),new BigDecimal("0.50"),new BigDecimal("17.80"),new BigDecimal("0.00"),new BigDecimal("18.30"));}
        public FeeBillPage searchOwner(long u,FeeBillQuery q){return new FeeBillPage(List.of(bill),q.page(),q.pageSize(),1);}public FeeBillPage searchAll(FeeBillQuery q){return new FeeBillPage(List.of(bill),q.page(),q.pageSize(),1);}public Optional<FeeBill> findForOwner(long u,long id){return u==70?Optional.of(bill):Optional.empty();}public Optional<FeeBill>find(long id){return Optional.of(bill);}
        public PaymentPreview paymentPreview(long u,long id){return new PaymentPreview(bill,new BigDecimal("50.00"),BankAccountStatus.ACTIVE);}public PaymentReceipt pay(long u,long id,String op,Instant n){return new PaymentReceipt(bill,9,new BigDecimal("31.70"),false);}public FeeBill waive(long u,long id,String r,Instant n){return bill;}public PaymentReceipt refund(long u,long id,String r,String op,Instant n){return new PaymentReceipt(bill,10,new BigDecimal("50.00"),false);}
    }
}
