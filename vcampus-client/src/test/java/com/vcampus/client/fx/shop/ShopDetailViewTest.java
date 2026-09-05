package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.ImageRef;
import com.vcampus.client.fx.shop.ShopData.Product;
import com.vcampus.client.fx.shop.ShopData.ProductDetail;
import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.ShopProductSort;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ShopDetailViewTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(15, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }

    @Test void buyNowUsesCurrentQuantityAndDoesNotMutateCart() throws Exception {
        fx(() -> {
            RecordingListener listener = new RecordingListener();
            ShopDetailView view = new ShopDetailView(listener);
            new Scene(view, 1050, 760);
            view.show(detail(4, 5));
            layout(view);
            @SuppressWarnings("unchecked") Spinner<Integer> quantity =
                    (Spinner<Integer>) view.lookup("#shop-detail-quantity");
            quantity.getValueFactory().setValue(3);
            ((Button) view.lookup("#shop-buy-now")).fire();
            assertEquals(new ShopDetailView.BuyNowSelection(23, 3), listener.buyNow);
            assertEquals(0, listener.cartCalls);
            return null;
        });
    }

    @Test void supportsZeroToFiveImagesLongTextAndQuantityBounds() throws Exception {
        fx(() -> {
            RecordingListener listener = new RecordingListener();
            ShopDetailView view = new ShopDetailView(listener);
            new Scene(view, 900, 720);
            view.show(detail(1200, 0));
            layout(view);
            assertNotNull(view.lookup("#shop-detail-image-placeholder"));
            @SuppressWarnings("unchecked") Spinner<Integer> quantity =
                    (Spinner<Integer>) view.lookup("#shop-detail-quantity");
            assertEquals(999, ((javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory)
                    quantity.getValueFactory()).getMax());
            Label description = (Label) view.lookup("#shop-detail-description");
            assertTrue(description.isWrapText());

            view.show(detail(2, 5));
            layout(view);
            assertEquals(5, view.thumbnailCount());
            assertEquals(2, ((javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory)
                    quantity.getValueFactory()).getMax());
            return null;
        });
    }

    @Test void addToCartStaysOnDetailAndUnavailableProductDisablesPurchasing() throws Exception {
        fx(() -> {
            RecordingListener listener = new RecordingListener();
            ShopDetailView view = new ShopDetailView(listener);
            new Scene(view, 900, 720);
            view.show(detail(4, 1));
            layout(view);
            ((Button) view.lookup("#shop-add-cart")).fire();
            assertEquals(1, listener.cartCalls);
            assertEquals(23, listener.addedProduct);
            assertSame(view, view.getScene().getRoot());

            view.show(new ProductDetail(product(false, 0), List.of()));
            layout(view);
            assertTrue(((Button) view.lookup("#shop-add-cart")).isDisabled());
            assertTrue(((Button) view.lookup("#shop-buy-now")).isDisabled());
            return null;
        });
    }

    private static ProductDetail detail(int stock, int images) {
        List<ImageRef> refs = new ArrayList<>();
        for (int index = 0; index < images; index++) {
            refs.add(new ImageRef(100 + index, "image/png", 100, String.valueOf(index + 1).repeat(64),
                    index, index == 0));
        }
        return new ProductDetail(product(true, stock), refs);
    }

    private static Product product(boolean enabled, int stock) {
        return new Product(23, "CAMPUS-23", "校园纪念帆布包",
                "这是一段很长的商品介绍。".repeat(30), ShopCategory.CAMPUS_MERCH,
                new BigDecimal("59.90"), stock, enabled, Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"), null);
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    private static void layout(javafx.scene.Parent root) { root.applyCss(); root.layout(); }

    private static final class RecordingListener implements ShopView.Listener {
        int cartCalls;
        long addedProduct;
        ShopDetailView.BuyNowSelection buyNow;
        @Override public void back() { }
        @Override public void search(String keyword, ShopCategory category, ShopProductSort sort, int page) { }
        @Override public void product(long productId) { }
        @Override public void addToCart(long productId, int quantity) {
            cartCalls++; addedProduct = productId;
        }
        @Override public void buyNow(ShopDetailView.BuyNowSelection selection) { buyNow = selection; }
    }
}
