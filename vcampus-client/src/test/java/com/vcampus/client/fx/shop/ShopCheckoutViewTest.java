package com.vcampus.client.fx.shop;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.vcampus.common.model.ShippingAddressValue;
import com.vcampus.common.model.ShopAddress;
import javafx.scene.control.TextField;

import static org.junit.jupiter.api.Assertions.*;

class ShopCheckoutViewTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(15, TimeUnit.SECONDS)); Platform.setImplicitExit(false);
    }

    @Test void displayDoesNotPayAndDoubleClickIsSuppressed() throws Exception {
        fx(() -> {
            AtomicInteger confirms = new AtomicInteger();
            ShopCheckoutView view = new ShopCheckoutView(
                    submission -> confirms.incrementAndGet(), () -> { });
            new Scene(view, 900, 700);
            ShopCheckoutView.CheckoutDraft draft = directDraft("operation-1", 1);
            view.show(draft, List.of(new ShopAddress(1, "v1",
                    new ShippingAddressValue("张三", "13800000000", "校内一号楼 101"))));
            layout(view);
            assertEquals(0, confirms.get());
            Button confirm = (Button) view.lookup("#shop-checkout-confirm");
            confirm.fire(); confirm.fire();
            assertEquals(1, confirms.get());
            assertTrue(confirm.isDisabled());
            return null;
        });
    }

    @Test void failureRetainsOperationIdAndQuantityChangeCreatesNewDraft() throws Exception {
        fx(() -> {
            ShopCheckoutView view = new ShopCheckoutView(submission -> { }, () -> { });
            new Scene(view, 900, 700);
            view.show(directDraft("operation-1", 1), List.of(new ShopAddress(1, "v1",
                    new ShippingAddressValue("张三", "13800000000", "校内一号楼 101"))));
            layout(view);
            view.failure("网络连接中断");
            assertFalse(((Button) view.lookup("#shop-checkout-confirm")).isDisabled());
            assertEquals("operation-1", view.draft().operationId());
            view.show(directDraft("operation-2", 2), List.of(new ShopAddress(1, "v1",
                    new ShippingAddressValue("张三", "13800000000", "校内一号楼 101"))));
            assertEquals("operation-2", view.draft().operationId());
            assertNotEquals("operation-1", view.draft().operationId());
            return null;
        });
    }

    @Test void checkoutIsDisabledUntilAnAddressChoiceIsComplete() throws Exception {
        fx(() -> {
            ShopCheckoutView view = new ShopCheckoutView(submission -> { }, () -> { });
            new Scene(view, 900, 700);
            view.show(directDraft("operation-3", 1), List.of());
            layout(view);
            Button confirm = (Button) view.lookup("#shop-checkout-confirm");
            assertTrue(confirm.isDisabled());

            ((TextField) view.lookup("#shop-address-recipient")).setText("张三");
            ((TextField) view.lookup("#shop-address-phone")).setText("13800000000");
            ((TextField) view.lookup("#shop-address-text")).setText("校内一号楼 101");

            assertFalse(confirm.isDisabled());
            return null;
        });
    }

    private static ShopCheckoutView.CheckoutDraft directDraft(String operationId, int quantity) {
        ShopCheckoutView.CheckoutLine line = new ShopCheckoutView.CheckoutLine(8, "帆布包",
                new BigDecimal("20.00"), quantity, 5, true);
        return new ShopCheckoutView.CheckoutDraft(ShopCheckoutView.Source.DIRECT, List.of(line),
                line.subtotal(), new BigDecimal("100.00"), operationId);
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work); Platform.runLater(task); return task.get(20, TimeUnit.SECONDS);
    }
    private static void layout(javafx.scene.Parent root) { root.applyCss(); root.layout(); }
}
