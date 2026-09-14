package com.vcampus.client.fx.library;

import com.vcampus.common.model.*;
import com.vcampus.common.protocol.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class LibraryFeeAdminViewTest {
    @BeforeAll static void toolkit()throws Exception{LibraryAdminViewTest.toolkit();}

    @Test void feeWorkspaceLoadsSearchStatusDetailAndPolicy()throws Exception{
        Gateway gateway=new Gateway();Executor executor=Runnable::run;
        LibraryFeeAdminView view=LibraryAdminViewTest.fx(()->{var v=new LibraryFeeAdminView(gateway,executor,()->{});new Scene(v,1000,720);v.activate();return v;});
        LibraryAdminViewTest.await(()->{view.applyCss();view.layout();return view.lookup("#library-fee-admin-search")!=null&&!view.isDisabled();});
        LibraryAdminViewTest.fx(()->{assertNotNull(view.lookup("#library-fee-admin-status-filter"));assertEquals(1,((TableView<?>)view.lookup("#library-fee-admin-table")).getItems().size());((TableView<?>)view.lookup("#library-fee-admin-table")).getSelectionModel().selectFirst();((Button)view.lookup("#library-fee-admin-detail")).fire();return null;});
        LibraryAdminViewTest.await(()->{view.applyCss();view.layout();return view.lookup("#library-fee-admin-detail-card")!=null;});
        LibraryAdminViewTest.fx(()->{assertNotNull(view.lookup("#library-fee-admin-waive"));((Button)view.lookup("#library-fee-admin-policy-tab")).fire();return null;});
        LibraryAdminViewTest.await(()->{view.applyCss();view.layout();return view.lookup("#library-fee-policy-save")!=null;});
        LibraryAdminViewTest.fx(()->{assertNotNull(view.lookup("#library-fee-policy-daily"));view.close();return null;});
    }

    @Test void policyAndReasonValidationAreStrict(){
        assertEquals("0.5000",LibraryFeeAdminView.normalizeRate("0.5"));
        assertEquals("0.50",LibraryFeeAdminView.normalizeMoney("0.5"));
        assertEquals("复核退款",LibraryFeeAdminView.requireReason("  复核退款  "));
        for(String value:List.of("","0","-1","1.001"))assertThrows(IllegalArgumentException.class,()->LibraryFeeAdminView.normalizeMoney(value));
        for(String value:List.of("","0","1.0001","abc"))assertThrows(IllegalArgumentException.class,()->LibraryFeeAdminView.normalizeRate(value));
        assertThrows(IllegalArgumentException.class,()->LibraryFeeAdminView.requireReason(" "));
    }

    private static final class Gateway implements LibraryFeeAdminView.Gateway{
        public ResponseMessage search(String keyword,String status,int page){String row=RowCodec.encode("8","LF8","18","程序设计","COPY-1","PENDING","0.50","44.50","0.00","45.00","2026-09-14T00:00:00Z","","student","测试同学");return ok(Map.of("page","1","pageSize","10","total","1","count","1","row.0",row));}
        public ResponseMessage detail(long billId){var data=new HashMap<String,String>(search("","",1).data());data.put("row",data.remove("row.0"));data.put("overdueDays","2");data.put("billableDays","1");data.put("returnCondition","DAMAGED");data.put("damageLevel","MAJOR");data.put("replacementPrice","89.00");data.put("reason","水渍");data.put("paymentReference","");data.put("refundReference","");return ok(data);}
        public ResponseMessage waive(long billId,String reason){return ok(Map.of());}public ResponseMessage refund(long billId,String reason,String operationId){return ok(Map.of());}
        public ResponseMessage getPolicy(){return ok(Map.of("graceDays","1","dailyOverdueFee","0.50","overdueCap","20.00","minorDamageRate","0.2000","majorDamageRate","0.5000","lostRate","1.0000"));}
        public ResponseMessage updatePolicy(Map<String,String> values){return ok(values);}
        private ResponseMessage ok(Map<String,String> data){return ResponseMessage.success(UUID.randomUUID().toString(),"ok",data);}
    }
}
