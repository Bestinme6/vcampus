package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.Order;
import com.vcampus.client.fx.shop.ShopData.OrderDetail;
import com.vcampus.client.fx.shop.ShopData.OrderItem;
import com.vcampus.common.model.ShopOrderStatus;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ShopOrdersViewTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(15, TimeUnit.SECONDS)); Platform.setImplicitExit(false);
    }

    @Test void orderActionsFollowServerStatusVocabulary() throws Exception {
        fx(() -> {
            ShopOrderDetailView view = new ShopOrderDetailView(new NoopListener());
            new Scene(view, 900, 700);
            view.show(detail(ShopOrderStatus.PAID));
            layout(view);
            assertFalse(((Button) view.lookup("#shop-order-cancel")).isDisabled());
            assertTrue(((Button) view.lookup("#shop-order-confirm")).isDisabled());
            view.show(detail(ShopOrderStatus.SHIPPED));
            layout(view);
            assertTrue(((Button) view.lookup("#shop-order-cancel")).isDisabled());
            assertFalse(((Button) view.lookup("#shop-order-confirm")).isDisabled());
            assertEquals("下单时商品名称", ((Label) view.lookup("#shop-order-item-91")).getText());
            return null;
        });
    }

    @Test void orderPagingAndDetailCallbackAreExposed() throws Exception {
        fx(() -> {
            long[] opened = {0};
            ShopOrdersView view = new ShopOrdersView((id) -> opened[0] = id, page -> { });
            new Scene(view, 900, 700);
            view.show(new ShopData.OrderPage(List.of(order(ShopOrderStatus.PAID)), 1, 12, 1));
            layout(view);
            ((Button) view.lookup("#shop-order-open-31")).fire();
            assertEquals(31, opened[0]);
            return null;
        });
    }

    private static OrderDetail detail(ShopOrderStatus status) {
        Order order = order(status);
        return new OrderDetail(order, List.of(new OrderItem(91, 8, "SKU-8", "下单时商品名称",
                new BigDecimal("20.00"), 1, new BigDecimal("20.00"))));
    }

    private static Order order(ShopOrderStatus status) {
        Instant created = Instant.parse("2026-09-01T00:00:00Z");
        return new Order(31, "ORDER-31", "student", "演示同学", new BigDecimal("20.00"), status,
                created, status == ShopOrderStatus.SHIPPED ? created.plusSeconds(60) : null,
                null, null);
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work); Platform.runLater(task); return task.get(20, TimeUnit.SECONDS);
    }
    private static void layout(javafx.scene.Parent root) { root.applyCss(); root.layout(); }

    private static final class NoopListener implements ShopOrderDetailView.Listener {
        @Override public void back() { }
        @Override public void cancel(long orderId) { }
        @Override public void confirm(long orderId) { }
    }
}
