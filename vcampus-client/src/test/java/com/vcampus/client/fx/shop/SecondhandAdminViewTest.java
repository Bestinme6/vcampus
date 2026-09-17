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

    @Test void listsModerateSelectedRowsAndPreserveSearchWhenPaging() throws Exception {
        fx(()->{
            var calls = new java.util.ArrayList<String>();
            var view = new SecondhandAdminView(Set.of(UserRole.SHOP_ADMIN),new SecondhandAdminView.Listener(){
                public void moderateItem(long id,boolean hide,String reason){calls.add("item:"+id+":"+hide+":"+reason);}
                public void moderateReview(long id,boolean hide,String reason){calls.add("review:"+id+":"+hide+":"+reason);}
                public void resolve(long id,SecondhandData.AdminResolution resolution,String operationId,String reason){}
                public void items(String keyword,int page){calls.add("items:"+keyword+":"+page);}
            });
            new Scene(view,900,700);
            var item = new SecondhandData.Listing(7,1,"卖家","旧教材","有笔记","BOOK","九成新",new java.math.BigDecimal("12.00"),
                    com.vcampus.common.model.SecondhandItemStatus.AVAILABLE,com.vcampus.common.model.SecondhandModerationStatus.HIDDEN,
                    java.time.Instant.EPOCH,java.time.Instant.EPOCH);
            view.showList(true,"教材",1);
            view.showItems(new SecondhandData.ListingPage(java.util.List.of(item),1,12,13));
            view.applyCss();view.layout();
            var action = (javafx.scene.control.Button)view.lookup("#used-admin-item-7-apply");
            assertEquals("恢复",action.getText());assertTrue(action.isDisabled());
            ((TextArea)view.lookup("#used-admin-item-7-reason")).setText("复核通过");action.fire();
            assertEquals("item:7:false:复核通过",calls.getLast());
            ((javafx.scene.control.TextField)view.lookup("#used-admin-keyword")).setText("未提交搜索");
            ((javafx.scene.control.Button)view.lookup("#used-admin-next")).fire();
            assertEquals("items:教材:2",calls.getLast());
            var review = new SecondhandData.Review(44,10,7,1,2,"买家",5,"描述准确",com.vcampus.common.model.ShopReviewStatus.NORMAL,
                    0,false,java.time.Instant.EPOCH,java.time.Instant.EPOCH,java.util.List.of());
            view.showList(false,"",1);
            view.showReviews(new SecondhandData.ReviewPage(java.util.List.of(review),1,12,1,
                    new SecondhandData.RatingSummary(null,0,java.util.List.of())),java.util.Map.of(7L,item));
            view.applyCss();view.layout();
            var hide = (javafx.scene.control.Button)view.lookup("#used-admin-review-44-apply");
            assertEquals("隐藏",hide.getText());assertTrue(hide.isDisabled());
            ((TextArea)view.lookup("#used-admin-review-44-reason")).setText("违规内容");hide.fire();
            assertEquals("review:44:true:违规内容",calls.getLast());
            assertTrue(((javafx.scene.control.Button)view.lookup("#used-admin-next")).isDisabled());
            assertTrue(view.lookupAll(".label").stream().map(node->((javafx.scene.control.Label)node).getText()).anyMatch(text->text.contains("旧教材")));
            return null;
        });
    }

    private static SecondhandAdminView.Listener listener(){return new SecondhandAdminView.Listener(){public void moderateItem(long id,boolean hide,String reason){}public void resolve(long id,SecondhandData.AdminResolution resolution,String operationId,String reason){}public void moderateReview(long id,boolean hide,String reason){}};}
    private static <T>T fx(Callable<T>work)throws Exception{FutureTask<T>task=new FutureTask<>(work);Platform.runLater(task);return task.get(20,TimeUnit.SECONDS);}
}
