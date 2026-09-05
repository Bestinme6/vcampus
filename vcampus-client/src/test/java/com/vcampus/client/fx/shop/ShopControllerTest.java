package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.Product;
import com.vcampus.client.fx.shop.ShopData.ProductPage;
import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.ShopProductSort;
import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
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
        fx(() -> { controller.search("late", null, ShopProductSort.NEWEST, 1); controller.deactivate(); return null; });
        executor.runFirst();
        flushFx();
        assertNull(fx(() -> controller.catalogView().lastPage()));
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
        @Override public ProductPage search(String keyword, ShopCategory category, ShopProductSort sort, int page) {
            return page(keyword);
        }
        @Override public ShopData.ProductDetail product(long productId) { throw new UnsupportedOperationException(); }
        @Override public ShopData.ImageChunk imageChunk(long imageId, ShopData.ImageVariant variant, int chunkIndex) { throw new UnsupportedOperationException(); }
        @Override public ShopData.Cart cart() { throw new UnsupportedOperationException(); }
        @Override public ShopData.Cart setCartQuantity(long productId, int quantity) { throw new UnsupportedOperationException(); }
        @Override public ShopData.Cart removeCartItem(long productId) { throw new UnsupportedOperationException(); }
        @Override public ShopData.CheckoutReceipt checkout(Set<Long> ids, String operationId) { throw new UnsupportedOperationException(); }
        @Override public ShopData.CheckoutReceipt buyNow(long productId, int quantity, String operationId) { throw new UnsupportedOperationException(); }
        @Override public ShopData.OrderPage orders(com.vcampus.common.model.ShopOrderStatus status, int page) { throw new UnsupportedOperationException(); }
        @Override public ShopData.OrderDetail order(long orderId) { throw new UnsupportedOperationException(); }
        @Override public ShopData.CheckoutReceipt cancelOrder(long orderId) { throw new UnsupportedOperationException(); }
        @Override public ShopData.CheckoutReceipt confirmOrder(long orderId) { throw new UnsupportedOperationException(); }
        @Override public ProductPage adminSearchProducts(String keyword, ShopCategory category, boolean enabled, ShopProductSort sort, int page) { throw new UnsupportedOperationException(); }
        @Override public long saveProduct(ShopData.ProductInput product) { throw new UnsupportedOperationException(); }
        @Override public boolean setProductEnabled(long productId, boolean enabled) { throw new UnsupportedOperationException(); }
        @Override public int adjustInventory(long productId, int delta, String reason) { throw new UnsupportedOperationException(); }
        @Override public ShopData.OrderPage adminOrders(String keyword, com.vcampus.common.model.ShopOrderStatus status, int page) { throw new UnsupportedOperationException(); }
        @Override public ShopData.CheckoutReceipt shipOrder(long orderId) { throw new UnsupportedOperationException(); }
        @Override public BigDecimal bankBalance() { throw new UnsupportedOperationException(); }
        @Override public ShopData.UploadTicket uploadStart(long productId, String mimeType, long expectedBytes) { throw new UnsupportedOperationException(); }
        @Override public void uploadChunk(String uploadId, int chunkIndex, byte[] content) { throw new UnsupportedOperationException(); }
        @Override public void uploadComplete(String uploadId) { throw new UnsupportedOperationException(); }
        @Override public List<ShopData.ImageRef> commitImages(long productId, List<ShopData.ImagePlanItem> items) { throw new UnsupportedOperationException(); }
    }
}
