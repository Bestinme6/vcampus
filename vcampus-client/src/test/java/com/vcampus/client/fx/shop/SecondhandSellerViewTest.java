package com.vcampus.client.fx.shop;

import com.vcampus.common.model.SecondhandItemStatus;
import com.vcampus.common.model.SecondhandModerationStatus;
import com.vcampus.common.model.ShippingAddressValue;
import com.vcampus.common.model.ShopOrderStatus;
import javafx.application.Platform;
import javafx.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SecondhandSellerViewTest {
    @BeforeAll static void toolkit() throws Exception {CountDownLatch ready=new CountDownLatch(1);try{Platform.startup(ready::countDown);}catch(IllegalStateException started){ready.countDown();}assertTrue(ready.await(15,TimeUnit.SECONDS));Platform.setImplicitExit(false);}

    @Test void sellerCanShipOnlyOwnPaidOrderAndOnlyParticipantsSeeAddress()throws Exception{
        fx(()->{SecondhandSellerView view=new SecondhandSellerView(2L,id->{});new Scene(view,900,700);
            view.showOrder(order(1L,2L,ShopOrderStatus.PAID));view.applyCss();view.layout();
            assertNotNull(view.lookup("#used-ship"));assertNotNull(view.lookup("#used-address"));
            view.showOrder(order(1L,3L,ShopOrderStatus.PAID));view.applyCss();view.layout();
            assertNull(view.lookup("#used-ship"));assertNull(view.lookup("#used-address"));return null;});
    }

    @Test void itemEditorAllowsOnlyOwnerToChangeAvailableVisibleItem()throws Exception{
        fx(()->{SecondhandItemEditorView view=new SecondhandItemEditorView(2L,draft->{},id->{},()->{});new Scene(view,900,700);
            view.show(listing(2L,SecondhandItemStatus.AVAILABLE,SecondhandModerationStatus.VISIBLE));view.applyCss();view.layout();
            assertNotNull(view.lookup("#used-item-save"));assertNotNull(view.lookup("#used-item-withdraw"));
            view.show(listing(3L,SecondhandItemStatus.AVAILABLE,SecondhandModerationStatus.VISIBLE));view.applyCss();view.layout();
            assertNull(view.lookup("#used-item-save"));assertNull(view.lookup("#used-item-withdraw"));return null;});
    }

    static SecondhandData.Listing listing(long seller,SecondhandItemStatus status,SecondhandModerationStatus moderation){return new SecondhandData.Listing(7L,seller,"卖家","旧教材","含少量笔记","BOOK","九成新",new BigDecimal("12.00"),status,moderation,Instant.EPOCH,Instant.EPOCH);}
    static SecondhandData.Order order(long buyer,long seller,ShopOrderStatus status){return new SecondhandData.Order(10L,"USED-10",7L,buyer,seller,new BigDecimal("12.00"),status,SecondhandData.EscrowStatus.HELD,"旧教材","BOOK","九成新","卖家",new ShippingAddressValue("买家","13800000000","江苏省南京市测试路1号"),Instant.EPOCH,status==ShopOrderStatus.SHIPPED?Instant.EPOCH:null,null,null);}
    private static <T>T fx(Callable<T>work)throws Exception{FutureTask<T>task=new FutureTask<>(work);Platform.runLater(task);return task.get(20,TimeUnit.SECONDS);}
}
