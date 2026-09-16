package com.vcampus.client.fx.shop;

import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SecondhandAdminViewTest {
    @BeforeAll static void toolkit()throws Exception{CountDownLatch ready=new CountDownLatch(1);try{Platform.startup(ready::countDown);}catch(IllegalStateException started){ready.countDown();}assertTrue(ready.await(15,TimeUnit.SECONDS));Platform.setImplicitExit(false);}

    @Test void adminViewRejectsOrdinaryUsers()throws Exception{
        assertThrows(java.util.concurrent.ExecutionException.class,()->fx(()->new SecondhandAdminView(Set.of(UserRole.STUDENT),listener())));
    }

    @Test void shippedOrderResolutionRequiresOneChoiceReasonAndExplicitConfirmation()throws Exception{
        fx(()->{SecondhandAdminView view=new SecondhandAdminView(Set.of(UserRole.SHOP_ADMIN),listener());new Scene(view,900,700);
            view.showOrder(SecondhandSellerViewTest.order(1L,2L,ShopOrderStatus.SHIPPED));view.applyCss();view.layout();
            RadioButton refund=(RadioButton)view.lookup("#used-admin-refund");RadioButton settle=(RadioButton)view.lookup("#used-admin-settle");
            var apply=(javafx.scene.control.Button)view.lookup("#used-admin-resolve");assertNotNull(refund);assertNotNull(settle);assertTrue(apply.isDisabled());
            refund.setSelected(true);((TextArea)view.lookup("#used-admin-reason")).setText("协商退款");assertTrue(apply.isDisabled());
            ((CheckBox)view.lookup("#used-admin-confirm")).setSelected(true);assertFalse(apply.isDisabled());
            settle.setSelected(true);assertFalse(refund.isSelected());return null;});
    }

    private static SecondhandAdminView.Listener listener(){return new SecondhandAdminView.Listener(){public void moderateItem(long id,boolean hide,String reason){}public void resolve(long id,SecondhandData.AdminResolution resolution,String operationId,String reason){}public void moderateReview(long id,boolean hide,String reason){}};}
    private static <T>T fx(Callable<T>work)throws Exception{FutureTask<T>task=new FutureTask<>(work);Platform.runLater(task);return task.get(20,TimeUnit.SECONDS);}
}
