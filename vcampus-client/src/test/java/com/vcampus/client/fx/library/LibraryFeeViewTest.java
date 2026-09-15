package com.vcampus.client.fx.library;

import com.vcampus.common.model.*;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LibraryFeeViewTest {
    @BeforeAll static void toolkit()throws Exception{LibraryAdminViewTest.toolkit();}

    @Test void listShowsItemizedAmountsAndChineseStatus()throws Exception{
        LibraryAdminViewTest.fx(()->{var view=new LibraryFeeView(new Listener());LibraryViewTest.scene(view,1000,760);
            view.showList(new LibraryData.Page<>(List.of(bill(LibraryFeeStatus.PENDING)),1,10,1));view.applyCss();view.layout();
            assertTrue(((Label)view.lookup("#library-fee-total-8")).getText().contains("¥104.00"));
            assertTrue(((Label)view.lookup("#library-fee-items-8")).getText().contains("逾期 ¥5.00"));
            assertTrue(((Label)view.lookup("#library-fee-items-8")).getText().contains("破损 ¥10.00"));
            assertTrue(((Label)view.lookup("#library-fee-items-8")).getText().contains("遗失 ¥89.00"));
            assertEquals("待缴费",((Label)view.lookup("#library-fee-state-8")).getText());return null;});
    }

    @Test void detailShowsCalculationReasonSnapshotsAndPaymentOnlyForPending()throws Exception{
        LibraryAdminViewTest.fx(()->{var view=new LibraryFeeView(new Listener());LibraryViewTest.scene(view,900,760);
            view.showDetail(detail(LibraryFeeStatus.PENDING));
            assertNotNull(view.lookup("#library-fee-pay-8"));assertTrue(((Label)view.lookup("#library-fee-calculation")).getText().contains("逾期 8 天，计费 6 天"));
            assertTrue(((Label)view.lookup("#library-fee-snapshot")).getText().contains("¥89.00"));assertTrue(((Label)view.lookup("#library-fee-reason")).getText().contains("严重水渍"));
            view.showDetail(detail(LibraryFeeStatus.PAID));assertNull(view.lookup("#library-fee-pay-8"));return null;});
    }

    @Test void paymentPreviewShowsBalanceAndWarningsAndBusyPreventsDuplicate()throws Exception{
        LibraryAdminViewTest.fx(()->{Listener listener=new Listener();var view=new LibraryFeeView(listener);LibraryViewTest.scene(view,900,760);
            view.showPaymentPreview(new LibraryData.PaymentPreview(detail(LibraryFeeStatus.PENDING),new BigDecimal("50.00"),BankAccountStatus.ACTIVE));
            assertTrue(((Label)view.lookup("#library-fee-payment-total")).getText().contains("¥104.00"));
            assertTrue(((Label)view.lookup("#library-fee-payment-balance")).getText().contains("¥50.00"));
            assertTrue(((Label)view.lookup("#library-fee-payment-warning")).getText().contains("余额不足"));
            Button confirm=(Button)view.lookup("#library-fee-payment-confirm");assertTrue(confirm.isDisabled());
            view.showPaymentPreview(new LibraryData.PaymentPreview(detail(LibraryFeeStatus.PENDING),new BigDecimal("200.00"),BankAccountStatus.FROZEN));
            assertTrue(((Label)view.lookup("#library-fee-payment-warning")).getText().contains("已冻结"));
            view.showPaymentPreview(new LibraryData.PaymentPreview(detail(LibraryFeeStatus.PENDING),new BigDecimal("200.00"),BankAccountStatus.ACTIVE));
            confirm=(Button)view.lookup("#library-fee-payment-confirm");assertFalse(confirm.isDisabled());view.busy(true);assertTrue(confirm.isDisabled());return null;});
    }

    @Test void serverBusinessMessagesRemainVisible()throws Exception{
        LibraryAdminViewTest.fx(()->{var view=new LibraryFeeView(new Listener());LibraryViewTest.scene(view,800,600);
            view.message("余额不足，账单仍待缴",true);assertEquals("余额不足，账单仍待缴",((Label)view.lookup("#library-fee-status")).getText());
            view.message("银行账户已冻结，暂时无法缴费",true);assertEquals("银行账户已冻结，暂时无法缴费",((Label)view.lookup("#library-fee-status")).getText());return null;});
    }

    private static LibraryData.FeeBill bill(LibraryFeeStatus status){return new LibraryData.FeeBill(8,"LF202609140008",18,"程序设计实践","BC0008",status,new BigDecimal("5.00"),new BigDecimal("10.00"),new BigDecimal("89.00"),new BigDecimal("104.00"),Instant.parse("2026-09-14T02:00:00Z"),status==LibraryFeeStatus.PAID?Instant.parse("2026-09-14T03:00:00Z"):null,"student","Student");}
    private static LibraryData.FeeDetail detail(LibraryFeeStatus status){return new LibraryData.FeeDetail(bill(status),8,6,LibraryReturnCondition.DAMAGED,LibraryDamageLevel.MAJOR,new BigDecimal("89.00"),"严重水渍","PAY-8","");}
    private static final class Listener implements LibraryFeeView.Listener{
        public void list(String status,int page){}public void detail(long billId){}public void requestPayment(long billId){}public void confirmPayment(long billId,String operationId){}
    }
}
