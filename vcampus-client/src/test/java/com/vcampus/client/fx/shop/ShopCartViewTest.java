package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.Cart;
import com.vcampus.client.fx.shop.ShopData.CartItem;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ShopCartViewTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(15, TimeUnit.SECONDS)); Platform.setImplicitExit(false);
    }

    @Test void selectAllSkipsIneligibleCartItems() throws Exception {
        fx(() -> {
            RecordingListener listener = new RecordingListener();
            ShopCartView view = new ShopCartView(listener);
            new Scene(view, 1000, 720);
            CartItem enabled = item(1, true, 5, 2);
            CartItem disabled = item(2, false, 5, 1);
            CartItem outOfStock = item(3, true, 0, 1);
            CartItem overStock = item(4, true, 1, 2);
            view.show(cart(enabled, disabled, outOfStock, overStock));
            layout(view);
            view.selectAll();
            assertEquals(Set.of(enabled.productId()), view.selectedProductIds());
            assertEquals(enabled.subtotal(), view.selectedTotal());
            return null;
        });
    }

    @Test void quantityRemovalAndCheckoutReachListener() throws Exception {
        fx(() -> {
            RecordingListener listener = new RecordingListener();
            ShopCartView view = new ShopCartView(listener);
            new Scene(view, 1000, 720);
            view.show(cart(item(7, true, 5, 1)));
            layout(view);
            view.selectAll();
            view.changeQuantity(7, 3);
            assertEquals(7, listener.quantityProduct);
            assertEquals(3, listener.quantity);
            ((Button) view.lookup("#shop-cart-remove-7")).fire();
            assertEquals(7, listener.removed);
            view.checkoutSelected();
            assertEquals(Set.of(7L), listener.checkout);
            return null;
        });
    }

    @Test void thumbnailReplacesPlaceholderAndRemovedRowsIgnoreLateImages() throws Exception {
        fx(() -> {
            var view = new ShopCartView(new RecordingListener());
            new Scene(view,1000,720);
            view.show(cart(item(7,true,5,1)));
            byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
            view.showThumbnail(7,png); layout(view);
            var image = (javafx.scene.image.ImageView)view.lookup("#shop-cart-thumbnail-7");
            assertNotNull(image); assertFalse(image.getImage().isError());
            view.showThumbnail(7,new byte[]{1,2}); layout(view);
            assertNull(view.lookup("#shop-cart-thumbnail-7"));
            view.show(cart()); view.showThumbnail(7,png); layout(view);
            assertNull(view.lookup("#shop-cart-thumbnail-7"));
            return null;
        });
    }

    private static Cart cart(CartItem... items) {
        BigDecimal total = List.of(items).stream().map(CartItem::subtotal)
                .reduce(new BigDecimal("0.00"), BigDecimal::add);
        return new Cart(List.of(items), total);
    }

    private static CartItem item(long id, boolean enabled, int stock, int quantity) {
        BigDecimal price = new BigDecimal("10.00");
        return new CartItem(id, "SKU-" + id, "商品 " + id, "", price, quantity, stock, enabled,
                price.multiply(BigDecimal.valueOf(quantity)), Instant.parse("2026-09-01T00:00:00Z"));
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work); Platform.runLater(task); return task.get(20, TimeUnit.SECONDS);
    }
    private static void layout(javafx.scene.Parent root) { root.applyCss(); root.layout(); }

    private static final class RecordingListener implements ShopCartView.Listener {
        long quantityProduct, removed; int quantity; Set<Long> checkout = Set.of();
        @Override public void quantity(long productId, int quantity) { quantityProduct = productId; this.quantity = quantity; }
        @Override public void remove(long productId) { removed = productId; }
        @Override public void checkout(Set<Long> selectedProductIds) { checkout = Set.copyOf(selectedProductIds); }
    }
}
