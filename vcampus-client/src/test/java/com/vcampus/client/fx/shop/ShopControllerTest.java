package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.Product;
import com.vcampus.client.fx.shop.ShopData.ProductPage;
import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.ShopProductSort;
import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ShopControllerTest {
    @TempDir Path temporary;

    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(15, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }

    @Test void staleSearchCannotReplaceNewerResults() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        FakeGateway gateway = new FakeGateway();
        ShopImageCache cache = new ShopImageCache(gateway, executor,
                new ShopImageCacheConfig(temporary, 4, 1024, 4096, Duration.ofDays(30)));
        ShopController controller = fx(() -> new ShopController(gateway, Set.of(UserRole.STUDENT),
                executor, cache, () -> { }, () -> { }));
        fx(() -> { controller.search("A", null, ShopProductSort.NEWEST, 1);
            controller.search("B", null, ShopProductSort.NEWEST, 1); return null; });
        executor.runLast();
        flushFx();
        executor.runFirst();
        flushFx();
        assertEquals("B", fx(() -> controller.catalogView().lastPage().rows().getFirst().name()));
        fx(() -> { controller.close(); return null; });
    }

    @Test void publicLifecycleRequiresFxThreadAndDeactivateSuppressesCallbacks() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        FakeGateway gateway = new FakeGateway();
        ShopImageCache cache = new ShopImageCache(gateway, executor,
                new ShopImageCacheConfig(temporary.resolve("lifecycle"), 4, 1024, 4096, Duration.ofDays(30)));
        ShopController controller = fx(() -> new ShopController(gateway, Set.of(UserRole.STUDENT),
                executor, cache, () -> { }, () -> { }));
        assertThrows(IllegalStateException.class, () -> controller.open("catalog"));
        assertThrows(IllegalStateException.class, controller::canLeave);
        assertTrue(fx(controller::canLeave));
        fx(() -> { controller.search("late", null, ShopProductSort.NEWEST, 1); controller.deactivate(); return null; });
        executor.runFirst();
        flushFx();
        assertNull(fx(() -> controller.catalogView().lastPage()));
        fx(() -> { controller.close(); return null; });
    }

    @Test void serverBusinessErrorsKeepTheirMessagesWhileNetworkErrorsUseConnectionWarning() throws Exception {
        Throwable stockFailure = assertThrows(Throwable.class, () -> ShopData.require(
                com.vcampus.common.protocol.ResponseMessage.failure("stock", "库存不足，当前仅剩 3 件")));
        Throwable balanceFailure = assertThrows(Throwable.class, () -> ShopData.require(
                com.vcampus.common.protocol.ResponseMessage.failure("balance", "余额不足")));

        assertEquals("库存不足，当前仅剩 3 件", ShopController.message(stockFailure));
        assertEquals("余额不足", ShopController.message(balanceFailure));
        assertEquals("无法连接校园商店，请稍后重试",
                ShopController.message(new ShopTransportException(new IOException("connection reset"))));
        assertEquals("图片大小必须在 2 MiB 以内",
                ShopController.message(new IOException("图片大小必须在 2 MiB 以内")));
    }

    @Test void checkoutKeepsExactStockAndBalanceRejectionMessages() throws Exception {
        assertCheckoutFailure("库存不足，当前仅剩 3 件", "checkout-stock");
        assertCheckoutFailure("余额不足，当前余额为 1.00 元", "checkout-balance");
    }

    @Test void productLoadsReviewsSeparatelyAndLikeUsesExplicitTargetState() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        FakeGateway gateway = new FakeGateway();
        ShopImageCache cache = new ShopImageCache(gateway, executor,
                new ShopImageCacheConfig(temporary.resolve("reviews"), 4, 1024, 4096, Duration.ofDays(30)));
        ShopController controller = fx(() -> {
            ShopController value = new ShopController(gateway, Set.of(UserRole.STUDENT), executor,
                    cache, () -> { }, () -> { });
            new Scene(value.view(), 1100, 760);
            value.openProduct(1);
            return value;
        });
        executor.runFirst();
        flushFx();
        executor.runFirst();
        flushFx();
        fx(() -> {
            controller.view().applyCss(); controller.view().layout();
            assertNotNull(controller.view().lookup("#shop-review-card-41"));
            ((Button) controller.view().lookup("#shop-review-like-41")).fire();
            return null;
        });
        executor.runFirst();
        flushFx();
        assertEquals("41:true", gateway.likeCall);
        assertEquals("有帮助 · 1", fx(() ->
                ((Button) controller.view().lookup("#shop-review-like-41")).getText()));
        fx(() -> { controller.close(); return null; });
    }

    @Test void failedProductOpenClearsLoadingStatusAndRestoresCatalog() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        FakeGateway gateway = new FakeGateway();
        gateway.productFailure = new IllegalArgumentException("商品图片数据无效");
        ShopImageCache cache = new ShopImageCache(gateway, executor,
                new ShopImageCacheConfig(temporary.resolve("product-failure"), 4, 1024, 4096,
                        Duration.ofDays(30)));
        ShopController controller = fx(() -> {
            ShopController value = new ShopController(gateway, Set.of(UserRole.STUDENT), executor,
                    cache, () -> { }, () -> { });
            new Scene(value.view(), 1100, 760);
            value.openProduct(1);
            return value;
        });

        executor.runFirst();
        flushFx();

        fx(() -> {
            controller.view().applyCss();
            assertEquals("", ((Label) controller.view().lookup(".shop-status")).getText());
            assertNotNull(controller.view().lookup("#shop-catalog-failure"));
            assertFalse(((ShopView) controller.view()).getCenter().isDisabled());
            controller.close();
            return null;
        });
    }

    private void assertCheckoutFailure(String message, String cacheName) throws Exception {
        ManualExecutor executor = new ManualExecutor();
        FakeGateway gateway = new FakeGateway();
        gateway.buyNowFailure = new ShopData.Rejected(message);
        ShopImageCache cache = new ShopImageCache(gateway, executor,
                new ShopImageCacheConfig(temporary.resolve(cacheName), 4, 1024, 4096, Duration.ofDays(30)));
        ShopController controller = fx(() -> {
            ShopController value = new ShopController(gateway, Set.of(UserRole.STUDENT),
                    executor, cache, () -> { }, () -> { });
            new Scene(value.view(), 1100, 760);
            return value;
        });

        fx(() -> { controller.open("checkout/direct/1/1"); return null; });
        executor.runFirst();
        flushFx();
        fx(() -> {
            controller.view().applyCss(); controller.view().layout();
            ((Button) controller.view().lookup("#shop-checkout-confirm")).fire();
            return null;
        });
        executor.runFirst();
        flushFx();

        assertEquals(message, fx(() ->
                ((Label) controller.view().lookup("#shop-checkout-status")).getText()));
        fx(() -> { controller.close(); return null; });
    }

    private static ProductPage page(String name) {
        Product product = new Product(1, "SKU-1", name, "", ShopCategory.OTHER,
                new BigDecimal("1.00"), 1, true, Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"), null);
        return new ProductPage(List.of(product), 1, 12, 1);
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work); Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    private static void flushFx() throws Exception { fx(() -> null); }

    private static final class ManualExecutor implements Executor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.addLast(command); }
        void runFirst() { tasks.removeFirst().run(); }
        void runLast() { tasks.removeLast().run(); }
    }

    private static final class FakeGateway implements ShopGateway {
        private RuntimeException buyNowFailure;
        private RuntimeException productFailure;
        private String likeCall;
        @Override public ProductPage search(String keyword, ShopCategory category, ShopProductSort sort, int page) {
            return page(keyword);
        }
        @Override public ShopData.ProductDetail product(long productId) {
            if (productFailure != null) throw productFailure;
            return new ShopData.ProductDetail(page("结算商品").rows().getFirst(), List.of());
        }
        @Override public ShopData.ImageChunk imageChunk(long imageId, ShopData.ImageVariant variant, int chunkIndex) { throw new UnsupportedOperationException(); }
        @Override public ShopData.Cart cart() { throw new UnsupportedOperationException(); }
        @Override public ShopData.Cart setCartQuantity(long productId, int quantity) { throw new UnsupportedOperationException(); }
        @Override public ShopData.Cart removeCartItem(long productId) { throw new UnsupportedOperationException(); }
        @Override public List<com.vcampus.common.model.ShopAddress> addresses() {
            return List.of(new com.vcampus.common.model.ShopAddress(1, "v1",
                    new com.vcampus.common.model.ShippingAddressValue(
                            "测试收件人", "13800000000", "测试校区一号楼 101")));
        }
        @Override public com.vcampus.common.model.ShopAddress saveAddress(
                Integer replaceSlot, com.vcampus.common.model.ShippingAddressValue value) {
            return new com.vcampus.common.model.ShopAddress(
                    replaceSlot == null ? 1 : replaceSlot, "saved-version", value);
        }
        @Override public boolean deleteAddress(int slot) { return true; }
        @Override public ShopData.CheckoutReceipt checkout(Set<Long> ids, String operationId,
                com.vcampus.common.model.ShippingAddressChoice address) {
            throw new UnsupportedOperationException();
        }
        @Override public ShopData.CheckoutReceipt buyNow(long productId, int quantity,
                String operationId, com.vcampus.common.model.ShippingAddressChoice address) {
            if (buyNowFailure != null) throw buyNowFailure;
            throw new UnsupportedOperationException();
        }
        @Override public ShopData.OrderPage orders(com.vcampus.common.model.ShopOrderStatus status, int page) { throw new UnsupportedOperationException(); }
        @Override public ShopData.OrderDetail order(long orderId) { throw new UnsupportedOperationException(); }
        @Override public ShopData.CheckoutReceipt cancelOrder(long orderId) { throw new UnsupportedOperationException(); }
        @Override public ShopData.CheckoutReceipt confirmOrder(long orderId) { throw new UnsupportedOperationException(); }
        @Override public ProductPage adminSearchProducts(String keyword, ShopCategory category, Boolean enabled, ShopProductSort sort, int page) { throw new UnsupportedOperationException(); }
        @Override public long saveProduct(ShopData.ProductInput product) { throw new UnsupportedOperationException(); }
        @Override public boolean setProductEnabled(long productId, boolean enabled) { throw new UnsupportedOperationException(); }
        @Override public int adjustInventory(long productId, int delta, String reason) { throw new UnsupportedOperationException(); }
        @Override public ShopData.OrderPage adminOrders(String keyword, com.vcampus.common.model.ShopOrderStatus status, int page) { throw new UnsupportedOperationException(); }
        @Override public ShopData.CheckoutReceipt shipOrder(long orderId) { throw new UnsupportedOperationException(); }
        @Override public BigDecimal bankBalance() { return new BigDecimal("100.00"); }
        @Override public ShopData.UploadTicket uploadStart(long productId, String mimeType, long expectedBytes) { throw new UnsupportedOperationException(); }
        @Override public void uploadChunk(String uploadId, int chunkIndex, byte[] content) { throw new UnsupportedOperationException(); }
        @Override public void uploadComplete(String uploadId) { throw new UnsupportedOperationException(); }
        @Override public List<ShopData.ImageRef> commitImages(long productId, List<ShopData.ImagePlanItem> items) { throw new UnsupportedOperationException(); }
        @Override public ShopData.ReviewPage reviews(long productId, int page) {
            ShopData.Review review = new ShopData.Review(41, productId, 31, "小林", 5,
                    "质量很好，值得推荐", false, 0, false,
                    Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"), List.of());
            return new ShopData.ReviewPage(List.of(review), page, 10, 1,
                    new ShopData.RatingSummary(new BigDecimal("5.0"), 1, List.of(0, 0, 0, 0, 1)));
        }
        @Override public ShopData.LikeResult setReviewLike(long reviewId, boolean enabled) {
            likeCall = reviewId + ":" + enabled;
            return new ShopData.LikeResult(reviewId, enabled, true, 1);
        }
    }
}
