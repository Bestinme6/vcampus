package com.vcampus.server.service;

import com.vcampus.common.model.*;
import com.vcampus.common.protocol.*;
import com.vcampus.server.database.*;
import com.vcampus.server.database.LibraryFeeStore.*;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RequestRouterLibraryFeeTest {
    @Test void allFeeActionsReachTheirMatchingStoreOperation() {
        SessionManager sessions=new SessionManager();var student=session(sessions,11,UserRole.STUDENT);var admin=session(sessions,3,UserRole.LIBRARY_ADMIN);
        CountingFees fees=new CountingFees();
        LibraryCatalogStore catalog=proxy(LibraryCatalogStore.class);LibraryLoanStore loans=proxy(LibraryLoanStore.class);
        LibraryService library=new LibraryService(catalog,loans,null,fees,sessions,(u,a,r,c)->{},Clock.systemUTC());
        RequestRouter router=new RequestRouter(null,null,null,null,null,null,library,null,sessions);
        List<String> owner=List.of(Actions.LIBRARY_FEE_MY,Actions.LIBRARY_FEE_GET,Actions.LIBRARY_FEE_PAYMENT_PREVIEW,Actions.LIBRARY_FEE_PAY);
        List<String> staff=List.of(Actions.LIBRARY_ADMIN_FEE_SEARCH,Actions.LIBRARY_ADMIN_FEE_PREVIEW,Actions.LIBRARY_ADMIN_FEE_WAIVE,Actions.LIBRARY_ADMIN_FEE_REFUND,Actions.LIBRARY_ADMIN_FEE_POLICY_GET,Actions.LIBRARY_ADMIN_FEE_POLICY_UPDATE);
        for(String action:owner)assertTrue(router.route(request(action,student.token()),"local").success(),action);
        for(String action:staff)assertTrue(router.route(request(action,admin.token()),"local").success(),action);
        assertEquals(10,fees.calls);
    }

    private RequestMessage request(String action,String token){Map<String,String>p=new HashMap<>();p.put("sessionToken",token);p.put("billId","1");p.put("operationId",UUID.randomUUID().toString());p.put("reason","原因");p.put("barcode","B000000001");p.put("condition","NORMAL");p.put("graceDays","1");p.put("dailyOverdueFee","0.50");p.put("overdueCap","30.00");p.put("minorDamageRate","0.2000");p.put("majorDamageRate","0.5000");p.put("lostRate","1.0000");return RequestMessage.create(action,p);}
    private static SessionManager.UserSession session(SessionManager s,long id,UserRole role){Set<UserRole> roles=role==UserRole.LIBRARY_ADMIN?Set.of(UserRole.STUDENT,role):Set.of(role);return s.create(new UserAccount(id,"u"+id,"h","s","用户",true,false,roles));}
    @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> type){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(p,m,a)->null);}

    private static final class CountingFees implements LibraryFeeStore {
        int calls;private final FeeBill bill=new FeeBill(1,"LFB000000001",2,11,"student","学生",3,4,"Java","B000000001",LibraryFeeStatus.PENDING,LibraryReturnCondition.NORMAL,null,Instant.EPOCH,Instant.EPOCH,1,0,new BigDecimal("89.00"),1,new BigDecimal("0.50"),new BigDecimal("30.00"),null,new BigDecimal("0.00"),new BigDecimal("0.00"),new BigDecimal("5.00"),new BigDecimal("5.00"),null,null,null,null,null,null,null,null,Instant.EPOCH);
        private LibraryFeePolicy policy(){return new LibraryFeePolicy(1,new BigDecimal("0.50"),new BigDecimal("30.00"),new BigDecimal("0.2000"),new BigDecimal("0.5000"),new BigDecimal("1.0000"));}
        public boolean hasPending(java.sql.Connection c,long u){return false;}public Optional<LibraryFeeWriter.CreatedBill>createForReturn(java.sql.Connection c,LibraryFeeWriter.ClosedLoan l){return Optional.empty();}
        public LibraryFeePolicy currentPolicy(){calls++;return policy();}public LibraryFeePolicy updatePolicy(long u,LibraryFeePolicy p){calls++;return p;}public LibraryFeeAssessment previewReturn(ReturnFeePreview p){calls++;return new LibraryFeeAssessment(1,0,new BigDecimal("89.00"),new BigDecimal("0.00"),new BigDecimal("0.00"),new BigDecimal("5.00"),new BigDecimal("5.00"));}
        public FeeBillPage searchOwner(long u,FeeBillQuery q){calls++;return new FeeBillPage(List.of(bill),1,10,1);}public FeeBillPage searchAll(FeeBillQuery q){calls++;return new FeeBillPage(List.of(bill),1,10,1);}public Optional<FeeBill>findForOwner(long u,long i){calls++;return Optional.of(bill);}public Optional<FeeBill>find(long i){calls++;return Optional.of(bill);}
        public PaymentPreview paymentPreview(long u,long i){calls++;return new PaymentPreview(bill,new BigDecimal("50.00"),BankAccountStatus.ACTIVE);}public PaymentReceipt pay(long u,long i,String o,Instant n){calls++;return new PaymentReceipt(bill,1,new BigDecimal("45.00"),false);}public FeeBill waive(long u,long i,String r,Instant n){calls++;return bill;}public PaymentReceipt refund(long u,long i,String r,String o,Instant n){calls++;return new PaymentReceipt(bill,2,new BigDecimal("50.00"),false);}
    }
}
