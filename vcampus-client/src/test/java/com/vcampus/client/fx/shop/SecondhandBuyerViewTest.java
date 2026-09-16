package com.vcampus.client.fx.shop;

import com.vcampus.common.model.SecondhandItemStatus;
import com.vcampus.common.model.SecondhandModerationStatus;
import com.vcampus.common.model.ShippingAddressChoice;
import com.vcampus.common.model.ShippingAddressValue;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SecondhandBuyerViewTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(15, TimeUnit.SECONDS)); Platform.setImplicitExit(false);
    }

    @Test
    void shopHeaderExposesSecondhandMarketWithoutInlineStyles() throws Exception {
        fx(() -> {
            boolean[] opened = {false};
            ShopView view = new ShopView(Set.of(UserRole.STUDENT), listener(() -> opened[0] = true));
            new Scene(view, 1000, 720); view.applyCss(); view.layout();
            Button button = (Button) view.lookup("#shop-tab-used");
            assertNotNull(button); assertEquals("二手市场", button.getText()); button.fire();
            assertTrue(opened[0]); assertTrue(view.getStylesheets().stream()
                    .anyMatch(value -> value.endsWith("secondhand.css")));
            assertNoInlineStyles(view); return null;
        });
    }

    @Test
    void ownListingHasNoBuyButtonAndOtherListingCanCheckout() throws Exception {
        fx(() -> {
            SecondhandDetailView view = new SecondhandDetailView(2L, item -> { });
            new Scene(view, 900, 700);
            view.show(listing(2L)); view.applyCss(); view.layout();
            assertNull(view.lookup("#used-buy"));
            view.show(listing(1L)); view.applyCss(); view.layout();
            assertNotNull(view.lookup("#used-buy")); return null;
        });
    }

    @Test
    void checkoutRequiresAddressAndInlineAddressIsNotSavedByDefault() throws Exception {
        fx(() -> {
            SecondhandCheckoutView view = new SecondhandCheckoutView(submission -> { }, () -> { });
            new Scene(view, 900, 700);
            view.show(listing(1L), List.of(), "00000000-0000-0000-0000-000000000001");
            assertTrue(view.submission().isEmpty());
            ((TextField) view.lookup("#shop-address-recipient")).setText("买家");
            ((TextField) view.lookup("#shop-address-phone")).setText("13800000000");
            ((TextField) view.lookup("#shop-address-text")).setText("江苏省南京市测试路1号");
            var submission = view.submission().orElseThrow();
            assertNotNull(submission.address().inline()); assertFalse(submission.saveAddress());
            assertEquals("00000000-0000-0000-0000-000000000001", submission.operationId());
            return null;
        });
    }

    @Test
    void orderShowsAddressSnapshotAndConfirmOnlyWhenShippedBuyer() throws Exception {
        fx(() -> {
            SecondhandDetailView view = new SecondhandDetailView(2L, item -> { });
            new Scene(view, 900, 700);
            view.showOrder(order(ShopOrderStatus.PAID)); view.applyCss(); view.layout();
            assertEquals("买家 · 13800000000 · 江苏省南京市测试路1号",
                    ((javafx.scene.control.Label) view.lookup("#used-order-address")).getText());
            assertNull(view.lookup("#used-confirm"));
            view.showOrder(order(ShopOrderStatus.SHIPPED)); view.applyCss(); view.layout();
            assertNotNull(view.lookup("#used-confirm")); return null;
        });
    }

    @Test
    void retryingFailedBuyKeepsTheSameOperationId() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        List<String> operations = new ArrayList<>();
        SecondhandGateway used = new SecondhandGateway() {
            private int attempts;
            public SecondhandData.ListingPage search(String keyword,int page){return new SecondhandData.ListingPage(List.of(listing(1L)),1,12,1);}
            public SecondhandData.Listing get(long itemId){return listing(1L);}
            public ShopData.ImageChunk imageChunk(long imageId,ShopData.ImageVariant variant,int chunkIndex){throw new UnsupportedOperationException();}
            public SecondhandData.Receipt buy(long itemId,String operationId,ShippingAddressChoice address)throws IOException{
                operations.add(operationId);if(attempts++==0)throw new IOException("connection reset");
                return new SecondhandData.Receipt(10L,"USED-10",ShopOrderStatus.PAID,false);
            }
            public SecondhandData.OrderPage orders(boolean selling,int page){return new SecondhandData.OrderPage(List.of(),1,12,0);}
            public SecondhandData.Order order(long orderId){return SecondhandBuyerViewTest.order(ShopOrderStatus.PAID);}
            public SecondhandData.Receipt cancel(long orderId){throw new UnsupportedOperationException();}
            public SecondhandData.Receipt confirm(long orderId){throw new UnsupportedOperationException();}
        };
        ShopGateway addresses = (ShopGateway) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ShopGateway.class}, (proxy, method, arguments) -> {
                    if(method.getName().equals("addresses")) return List.of();
                    if(method.getDeclaringClass()==Object.class) return method.invoke(this,arguments);
                    throw new UnsupportedOperationException(method.getName());
                });
        SecondhandController controller=fx(()->{
            SecondhandController value=new SecondhandController(used,addresses,2L,executor,()->{});
            new Scene(value.view(),900,700);value.open(7L);return value;
        });
        executor.runNext();flushFx();
        fx(()->{((Button)controller.view().lookup("#used-buy")).fire();return null;});
        executor.runNext();flushFx();
        fx(()->{
            ((TextField)controller.view().lookup("#shop-address-recipient")).setText("买家");
            ((TextField)controller.view().lookup("#shop-address-phone")).setText("13800000000");
            ((TextField)controller.view().lookup("#shop-address-text")).setText("江苏省南京市测试路1号");
            ((Button)controller.view().lookup("#used-checkout-confirm")).fire();return null;
        });
        executor.runNext();flushFx();
        fx(()->{((Button)controller.view().lookup("#used-checkout-confirm")).fire();return null;});
        executor.runNext();flushFx();
        assertEquals(2,operations.size());assertEquals(operations.get(0),operations.get(1));
        fx(()->{controller.close();return null;});
    }

    private static SecondhandData.Listing listing(long seller) {
        return new SecondhandData.Listing(7L, seller, "卖家", "旧教材", "含少量笔记", "BOOK",
                "九成新", new BigDecimal("12.00"), SecondhandItemStatus.AVAILABLE,
                SecondhandModerationStatus.VISIBLE, Instant.EPOCH, Instant.EPOCH);
    }

    private static SecondhandData.Order order(ShopOrderStatus status) {
        return new SecondhandData.Order(10L,"USED-10",7L,2L,1L,new BigDecimal("12.00"),status,
                SecondhandData.EscrowStatus.HELD,"旧教材","BOOK","九成新","卖家",
                new ShippingAddressValue("买家","13800000000","江苏省南京市测试路1号"),
                Instant.EPOCH,status==ShopOrderStatus.SHIPPED?Instant.EPOCH:null,null,null);
    }

    private static ShopView.Listener listener(Runnable used) { return new ShopView.Listener() {
        public void back(){} public void search(String k,com.vcampus.common.model.ShopCategory c,com.vcampus.common.model.ShopProductSort s,int p){}
        public void product(long id){} public void addToCart(long id,int q){}
        public void buyNow(ShopDetailView.BuyNowSelection selection){} public void used(){used.run();}
    }; }
    private static void assertNoInlineStyles(javafx.scene.Node node){assertTrue(node.getStyle()==null||node.getStyle().isBlank());if(node instanceof javafx.scene.Parent p)p.getChildrenUnmodifiable().forEach(SecondhandBuyerViewTest::assertNoInlineStyles);}
    private static <T>T fx(Callable<T> work)throws Exception{FutureTask<T> task=new FutureTask<>(work);Platform.runLater(task);return task.get(20,TimeUnit.SECONDS);}
    private static void flushFx()throws Exception{fx(()->null);}
    private static final class ManualExecutor implements Executor{
        private final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        public void execute(Runnable task){tasks.addLast(task);}
        void runNext(){assertFalse(tasks.isEmpty());tasks.removeFirst().run();}
    }
}
