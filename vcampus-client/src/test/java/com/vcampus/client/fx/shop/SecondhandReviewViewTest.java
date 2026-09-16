package com.vcampus.client.fx.shop;

import com.vcampus.common.model.ShopOrderStatus;
import javafx.application.Platform;
import javafx.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SecondhandReviewViewTest {
    @BeforeAll static void toolkit()throws Exception{CountDownLatch ready=new CountDownLatch(1);try{Platform.startup(ready::countDown);}catch(IllegalStateException started){ready.countDown();}assertTrue(ready.await(15,TimeUnit.SECONDS));Platform.setImplicitExit(false);}

    @Test void completedBuyerCanCreateOneReviewAndOtherStatesCannot()throws Exception{
        fx(()->{SecondhandReviewView view=new SecondhandReviewView(2L,draft->{},(id,enabled)->{});new Scene(view,900,700);
            view.showOrder(SecondhandSellerViewTest.order(2L,1L,ShopOrderStatus.COMPLETED),null);view.applyCss();view.layout();
            assertNotNull(view.lookup("#used-review-create"));
            view.showOrder(SecondhandSellerViewTest.order(2L,1L,ShopOrderStatus.PAID),null);view.applyCss();view.layout();
            assertNull(view.lookup("#used-review-create"));
            view.showOrder(SecondhandSellerViewTest.order(2L,1L,ShopOrderStatus.COMPLETED),44L);view.applyCss();view.layout();
            assertNull(view.lookup("#used-review-create"));return null;});
    }

    @Test void catalogDisplaysSellerRatingWithoutAddressData()throws Exception{
        fx(()->{SecondhandCatalogView view=new SecondhandCatalogView((k,p)->{},id->{});new Scene(view,900,700);
            view.show(new SecondhandData.ListingPage(List.of(SecondhandSellerViewTest.listing(1L,com.vcampus.common.model.SecondhandItemStatus.AVAILABLE,com.vcampus.common.model.SecondhandModerationStatus.VISIBLE)),1,12,1));
            view.showRating(7L,new BigDecimal("4.8"),12);view.applyCss();view.layout();
            var rating=(javafx.scene.control.Label)view.lookup("#used-rating-7");assertNotNull(rating);assertTrue(rating.getText().contains("4.8"));assertNull(view.lookup("#used-address"));return null;});
    }
    private static <T>T fx(Callable<T>work)throws Exception{FutureTask<T>task=new FutureTask<>(work);Platform.runLater(task);return task.get(20,TimeUnit.SECONDS);}
}
