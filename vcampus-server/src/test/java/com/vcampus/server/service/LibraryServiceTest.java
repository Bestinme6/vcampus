package com.vcampus.server.service;

import com.vcampus.common.model.*;
import com.vcampus.common.protocol.*;
import com.vcampus.server.database.*;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LibraryServiceTest {
    private final SessionManager sessions=new SessionManager(); private final Catalog catalog=new Catalog(); private final Loans loans=new Loans(); private final Audit audit=new Audit(); private LibraryService service;
    @BeforeEach void setUp(){service=new LibraryService(catalog,loans,sessions,audit,Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"),ZoneOffset.UTC));}

    @Test void superAdministratorCannotSelfBorrow(){var admin=session(1,UserRole.SUPER_ADMIN);var response=service.borrow(request(Actions.LIBRARY_LOAN_BORROW,admin.token(),Map.of("bookId","10")));assertFalse(response.success());assertEquals("没有执行该操作的权限",response.message());assertEquals(0,loans.borrowCalls);}
    @Test void catalogEncodingHasStableFieldOrder(){var student=session(2,UserRole.STUDENT);var response=service.searchCatalog(request(Actions.LIBRARY_CATALOG_SEARCH,student.token(),Map.of()));assertTrue(response.success());assertEquals(List.of("10","9787111565277","Java","作者","出版社","2026","计算机","简介","true","4","2"),RowCodec.decode(response.data().get("row.0")));}
    @Test void administratorCanBorrowForStudentAndAuditExactAction(){var admin=session(3,UserRole.LIBRARY_ADMIN);var response=service.adminBorrow(request(Actions.LIBRARY_ADMIN_LOAN_BORROW,admin.token(),Map.of("username","student","barcode","B000000128")));assertTrue(response.success());assertEquals(1,loans.borrowCalls);assertEquals(Actions.LIBRARY_ADMIN_LOAN_BORROW,audit.action);}

    private SessionManager.UserSession session(long id,UserRole role){Set<UserRole> roles=role==UserRole.LIBRARY_ADMIN?Set.of(UserRole.STUDENT,role):Set.of(role);return sessions.create(new UserAccount(id,"u"+id,"h","s","用户",true,false,roles));}
    private RequestMessage request(String action,String token,Map<String,String>values){Map<String,String>p=new HashMap<>(values);p.put("sessionToken",token);return RequestMessage.create(action,p);}
    private static final class Audit implements AuditStore{String action;public void record(Long u,String a,String r,String c){action=a;}}
    private static final class Catalog implements LibraryCatalogStore{
        public CatalogPage search(CatalogQuery q){return new CatalogPage(List.of(new CatalogItem(10,"9787111565277","Java","作者","出版社",2026,"计算机","简介",true,4,2)),q.page(),q.pageSize(),1);}public Optional<CatalogItem>findBook(long i){return Optional.empty();}public long createBook(BookCommand c){return 1;}public MutationResult updateBook(long i,BookCommand c){return MutationResult.CHANGED;}public MutationResult setBookEnabled(long i,boolean e){return MutationResult.CHANGED;}public CopyPage searchCopies(CopyQuery q){return new CopyPage(List.of(),q.page(),q.pageSize(),0);}public long createCopy(CreateCopy c){return 1;}public MutationResult setCopyStatus(long i,LibraryCopyStatus s,String r){return MutationResult.CHANGED;}
    }
    private static final class Loans implements LibraryLoanStore{
        int borrowCalls;public Optional<Borrower>findBorrower(String u){return Optional.of(new Borrower(20,"student","学生",UserRole.STUDENT,true));}public CirculationPreview previewCirculation(long u,String b,LibraryCirculationOperation o,Instant n,int m){return new CirculationPreview(1,10,"Java",b,LibraryCopyStatus.AVAILABLE,null,0,m,false,true,"可以办理借阅");}public LoanPage searchBorrowerLoans(long u,LoanQuery q){return new LoanPage(List.of(),q.page(),q.pageSize(),0);}public LoanPage searchAllLoans(LoanQuery q){return new LoanPage(List.of(),q.page(),q.pageSize(),0);}public BorrowReceipt borrow(BorrowCommand c){borrowCalls++;return new BorrowReceipt(1,1,"B000000128","Java",c.dueAt());}public ReturnReceipt returnLoan(ReturnCommand c){return new ReturnReceipt(1,1,"B000000128",c.condition(),c.returnedAt());}public RenewReceipt renew(RenewCommand c){return new RenewReceipt(c.loanId(),c.now().plus(c.extension()),1);}
    }
}
