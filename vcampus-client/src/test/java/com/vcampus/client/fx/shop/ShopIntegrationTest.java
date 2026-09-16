package com.vcampus.client.fx.shop;

import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ShopIntegrationTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(15, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }

    @Test void campusRoutesResolveToNativeShopPages() {
        assertEquals("catalog", ShopRoute.fromCampus("shop"));
        assertEquals("cart", ShopRoute.fromCampus("shop-cart"));
        assertEquals("orders", ShopRoute.fromCampus("shop-orders"));
        assertEquals("addresses", ShopRoute.fromCampus("shop-addresses"));
        assertEquals("order/42", ShopRoute.fromCampus("shop-order/42"));
        assertNull(ShopRoute.fromCampus("bank"));
        assertNull(ShopRoute.fromCampus("shop-order/not-a-number"));
        assertNull(ShopRoute.fromCampus("shop-order/0"));
    }

    @Test void addressManagerIsReachableAndUsesTheExternalShopStylesheet() throws Exception {
        fx(() -> {
            boolean[] opened = {false};
            ShopView view = new ShopView(Set.of(UserRole.STUDENT), new ShopView.Listener() {
                @Override public void back() { }
                @Override public void search(String keyword,
                                             com.vcampus.common.model.ShopCategory category,
                                             com.vcampus.common.model.ShopProductSort sort,
                                             int page) { }
                @Override public void product(long productId) { }
                @Override public void addToCart(long productId, int quantity) { }
                @Override public void buyNow(ShopDetailView.BuyNowSelection selection) { }
                @Override public void addresses() { opened[0] = true; }
            });
            new Scene(view, 900, 700);
            view.applyCss();
            view.layout();

            assertTrue(view.getStylesheets().stream()
                    .anyMatch(stylesheet -> stylesheet.endsWith("shop-address.css")));
            ((javafx.scene.control.Button) view.lookup("#shop-tab-addresses")).fire();
            assertTrue(opened[0]);
            assertNoInlineStyles(view);
            return null;
        });
    }

    private static void assertNoInlineStyles(Node node) {
        assertTrue(node.getStyle() == null || node.getStyle().isBlank(),
                () -> "新商店地址节点不应使用内联样式: " + node.getId());
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(ShopIntegrationTest::assertNoInlineStyles);
        }
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }
}
