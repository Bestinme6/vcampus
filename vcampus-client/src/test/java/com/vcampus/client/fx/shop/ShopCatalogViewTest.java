package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.ImageRef;
import com.vcampus.client.fx.shop.ShopData.Product;
import com.vcampus.client.fx.shop.ShopData.ProductPage;
import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.ShopProductSort;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
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

class ShopCatalogViewTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(15, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }

    @Test void catalogColumnsRespondWithoutHorizontalScroll() {
        assertEquals(4, ShopCatalogView.columnsFor(1320));
        assertEquals(3, ShopCatalogView.columnsFor(1040));
        assertEquals(2, ShopCatalogView.columnsFor(760));
    }

    @Test void wholeCardOpensDetailAndFiltersResetToFirstPage() throws Exception {
        fx(() -> {
            RecordingListener listener = new RecordingListener();
            ShopCatalogView view = new ShopCatalogView(listener);
            new Scene(view, 1040, 760);
            view.show(new ProductPage(List.of(product(7, true, 4, cover())), 2, 12, 25));
            layout(view);
            ShopProductCard card = (ShopProductCard) view.lookup("#shop-product-7");
            card.getOnMouseClicked().handle(new MouseEvent(MouseEvent.MOUSE_CLICKED, 1, 1, 1, 1,
                    MouseButton.PRIMARY, 1, false, false, false, false,
                    true, false, false, true, false, false, null));
            assertEquals(7, listener.productId);
            view.categoryControl().setValue(ShopCategory.CAMPUS_MERCH);
            assertEquals(1, listener.page);
            assertEquals(ShopCategory.CAMPUS_MERCH, listener.category);
            view.sortControl().setValue(ShopProductSort.PRICE_ASC);
            assertEquals(1, listener.page);
            assertEquals(ShopProductSort.PRICE_ASC, listener.sort);
            return null;
        });
    }

    @Test void rendersLoadingEmptyFailureAndMissingImagePlaceholder() throws Exception {
        fx(() -> {
            RecordingListener listener = new RecordingListener();
            ShopCatalogView view = new ShopCatalogView(listener);
            new Scene(view, 900, 700);
            view.showLoading();
            layout(view);
            assertNotNull(view.lookup("#shop-catalog-loading"));
            view.show(new ProductPage(List.of(), 1, 12, 0));
            layout(view);
            assertNotNull(view.lookup("#shop-catalog-empty"));
            view.show(new ProductPage(List.of(product(9, true, 2, null)), 1, 12, 1));
            layout(view);
            assertNotNull(view.lookup("#shop-image-placeholder-9"));
            view.showFailure("网络暂不可用", () -> listener.retries++);
            layout(view);
            ((Button) view.lookup("#shop-catalog-retry")).fire();
            assertEquals(1, listener.retries);
            return null;
        });
    }

    private static Product product(long id, boolean enabled, int stock, ImageRef cover) {
        return new Product(id, "SKU-" + id, "校园商品 " + id, "适合校园生活的商品", ShopCategory.OTHER,
                new BigDecimal("29.90"), stock, enabled, Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"), cover);
    }

    private static ImageRef cover() { return new ImageRef(17, "a".repeat(64)); }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    private static void layout(javafx.scene.Parent root) { root.applyCss(); root.layout(); }

    private static final class RecordingListener implements ShopView.Listener {
        long productId;
        int page = -1;
        int retries;
        ShopCategory category;
        ShopProductSort sort = ShopProductSort.NEWEST;

        @Override public void back() { }
        @Override public void search(String keyword, ShopCategory category, ShopProductSort sort, int page) {
            this.category = category; this.sort = sort; this.page = page;
        }
        @Override public void product(long productId) { this.productId = productId; }
        @Override public void addToCart(long productId, int quantity) { }
        @Override public void buyNow(ShopDetailView.BuyNowSelection selection) { }
    }
}
