package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.Product;
import com.vcampus.client.fx.shop.ShopData.ProductPage;
import com.vcampus.common.model.ShopAccessPolicy;
import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.UserRole;
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

class ShopAdminViewTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(15, TimeUnit.SECONDS)); Platform.setImplicitExit(false);
    }

    @Test void onlyShopManagersCanOpenAdministration() {
        assertFalse(ShopAccessPolicy.canManage(Set.of(UserRole.STUDENT)));
        assertFalse(ShopAccessPolicy.canManage(Set.of(UserRole.TEACHER)));
        assertTrue(ShopAccessPolicy.canManage(Set.of(UserRole.SHOP_ADMIN)));
        assertTrue(ShopAccessPolicy.canManage(Set.of(UserRole.SUPER_ADMIN)));
    }

    @Test void productTableExposesEditEnableAndInventoryActions() throws Exception {
        fx(() -> {
            RecordingListener listener = new RecordingListener();
            ShopAdminView view = new ShopAdminView(listener);
            new Scene(view, 1100, 760);
            view.showProducts(new ProductPage(List.of(product()), 1, 12, 1));
            view.applyCss(); view.layout();
            ((Button) view.lookup("#shop-admin-edit-41")).fire();
            ((Button) view.lookup("#shop-admin-toggle-41")).fire();
            view.adjustInventory(41, 5, "到货入库");
            assertEquals(41, listener.edited);
            assertFalse(listener.enabled);
            assertEquals("到货入库", listener.reason);
            return null;
        });
    }

    private static Product product() {
        return new Product(41, "SKU-41", "管理员商品", "", ShopCategory.OTHER,
                new BigDecimal("9.90"), 3, true, Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"), null);
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work); Platform.runLater(task); return task.get(20, TimeUnit.SECONDS);
    }

    private static final class RecordingListener implements ShopAdminView.Listener {
        long edited; boolean enabled = true; String reason;
        @Override public void searchProducts(ShopAdminView.ProductQuery query) { }
        @Override public void editProduct(long productId) { edited = productId; }
        @Override public void newProduct() { }
        @Override public void setEnabled(long productId, boolean enabled) { this.enabled = enabled; }
        @Override public void adjustInventory(long productId, int delta, String reason) { this.reason = reason; }
        @Override public void searchOrders(ShopAdminView.OrderQuery query) { }
        @Override public void ship(long orderId) { }
    }
}
