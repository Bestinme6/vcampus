package com.vcampus.server.service;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.ShopStore;
import com.vcampus.server.image.ShopImageStore;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestRouterShopTest {
    @Test
    void routesAllFourteenShopActionsToShopService() {
        SessionManager sessions = new SessionManager();
        String token = sessions.create(new UserAccount(9L, "admin", "h", "s", "管理员",
                true, false, Set.of(UserRole.TEACHER, UserRole.SHOP_ADMIN))).token();
        AtomicInteger calls = new AtomicInteger();
        ShopStore store = (ShopStore) Proxy.newProxyInstance(ShopStore.class.getClassLoader(),
                new Class<?>[]{ShopStore.class}, (proxy, method, arguments) -> {
                    calls.incrementAndGet();
                    return switch (method.getName()) {
                        case "searchProducts" -> new ShopStore.ProductPage(java.util.List.of(), 1, 10, 0);
                        case "cart", "setCartQuantity", "removeCartItem" ->
                                new ShopStore.CartResult(java.util.List.of(), BigDecimal.ZERO);
                        case "checkout" -> new ShopStore.CheckoutResult(1L, "SO1", BigDecimal.ONE,
                                com.vcampus.common.model.ShopOrderStatus.PAID, false);
                        case "searchOrders" -> new ShopStore.OrderPage(java.util.List.of(), 1, 10, 0);
                        case "order" -> throw new com.vcampus.server.database.ShopRuleException("订单不存在");
                        case "cancelOrder", "confirmOrder", "shipOrder" ->
                                new ShopStore.OrderResult(1L, "SO1", BigDecimal.ONE,
                                        com.vcampus.common.model.ShopOrderStatus.PAID);
                        case "saveProduct" -> new ShopStore.ProductSaveResult(1L);
                        case "setProductEnabled" -> true;
                        case "adjustInventory" -> new ShopStore.InventoryResult(1L, 2);
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        RequestRouter router = new RequestRouter(null, null, null, null, null, null,
                null, null, null, new ShopService(store, sessions), sessions);

        Map<String, Map<String, String>> requests = Map.ofEntries(
                Map.entry(Actions.SHOP_PRODUCT_SEARCH, Map.of("page", "1")),
                Map.entry(Actions.SHOP_CART_GET, Map.of()),
                Map.entry(Actions.SHOP_CART_SET_QUANTITY, Map.of("productId", "1", "quantity", "1")),
                Map.entry(Actions.SHOP_CART_REMOVE, Map.of("productId", "1")),
                Map.entry(Actions.SHOP_CHECKOUT, Map.of("operationId", java.util.UUID.randomUUID().toString())),
                Map.entry(Actions.SHOP_ORDER_SEARCH, Map.of("page", "1")),
                Map.entry(Actions.SHOP_ORDER_GET, Map.of("orderId", "999")),
                Map.entry(Actions.SHOP_ORDER_CANCEL, Map.of("orderId", "1")),
                Map.entry(Actions.SHOP_ORDER_CONFIRM, Map.of("orderId", "1")),
                Map.entry(Actions.SHOP_ADMIN_PRODUCT_SAVE, Map.of("sku", "S", "name", "N", "price", "1", "enabled", "true")),
                Map.entry(Actions.SHOP_ADMIN_PRODUCT_SET_ENABLED, Map.of("productId", "1", "enabled", "true")),
                Map.entry(Actions.SHOP_ADMIN_INVENTORY_ADJUST, Map.of("productId", "1", "delta", "1", "reason", "入库")),
                Map.entry(Actions.SHOP_ADMIN_ORDER_SEARCH, Map.of("page", "1")),
                Map.entry(Actions.SHOP_ADMIN_ORDER_SHIP, Map.of("orderId", "1")));
        for (var entry : requests.entrySet()) {
            var parameters = new java.util.LinkedHashMap<>(entry.getValue());
            parameters.put("sessionToken", token);
            var response = router.route(RequestMessage.create(entry.getKey(), parameters), "local");
            assertTrue(response.success() || "订单不存在".equals(response.message()), entry.getKey());
        }
        assertEquals(14, calls.get());
    }

    @Test
    void routesExactlyFourAdministrativeImageActionsWithoutChangingLegacyShopRoutes() {
        SessionManager sessions = new SessionManager();
        String token = sessions.create(new UserAccount(9L, "admin", "h", "s", "管理员",
                true, false, Set.of(UserRole.TEACHER, UserRole.SHOP_ADMIN))).token();
        AtomicInteger metadataCommits = new AtomicInteger();
        ShopStore store = (ShopStore) Proxy.newProxyInstance(ShopStore.class.getClassLoader(),
                new Class<?>[]{ShopStore.class}, (proxy, method, arguments) -> {
                    if ("replaceProductImages".equals(method.getName())) {
                        metadataCommits.incrementAndGet();
                        return new ShopStore.ImageCommitResult(Set.of("full.png", "thumb.png"),
                                Set.of(), java.util.List.of());
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        RoutingImageStore imageStore = new RoutingImageStore();
        ShopImageService imageService = new ShopImageService(store, imageStore, sessions);
        RequestRouter router = new RequestRouter(null, null, null, null, null, null,
                null, null, null, null, imageService, sessions);

        var start = router.route(request(Actions.SHOP_ADMIN_IMAGE_UPLOAD_START, token, Map.of(
                "productId", "4", "mimeType", "image/png", "expectedBytes", "1")), "local");
        var chunk = router.route(request(Actions.SHOP_ADMIN_IMAGE_UPLOAD_CHUNK, token, Map.of(
                "uploadId", "router-upload", "chunkIndex", "0", "contentBase64", "AQ==")),
                "local");
        var complete = router.route(request(Actions.SHOP_ADMIN_IMAGE_UPLOAD_COMPLETE, token,
                Map.of("uploadId", "router-upload")), "local");
        var commit = router.route(request(Actions.SHOP_ADMIN_IMAGE_COMMIT, token, Map.of(
                "productId", "4", "itemCount", "1", "item.0",
                RowCodec.encode("", "router-upload", "true"))), "local");

        assertTrue(start.success(), start.message());
        assertTrue(chunk.success(), chunk.message());
        assertTrue(complete.success(), complete.message());
        assertTrue(commit.success(), commit.message());
        assertEquals(1, imageStore.startCalls);
        assertEquals(1, imageStore.appendCalls);
        assertEquals(1, imageStore.completeCalls);
        assertEquals(1, imageStore.finalizeCalls);
        assertEquals(1, metadataCommits.get());
    }

    private RequestMessage request(String action, String token, Map<String, String> values) {
        var parameters = new java.util.LinkedHashMap<>(values);
        parameters.put("sessionToken", token);
        return RequestMessage.create(action, parameters);
    }

    private static final class RoutingImageStore implements ShopImageStore {
        private int startCalls;
        private int appendCalls;
        private int completeCalls;
        private int finalizeCalls;

        @Override
        public UploadTicket startUpload(long ownerId, long productId, String mimeType,
                                        long expectedBytes) {
            startCalls++;
            return new UploadTicket("router-upload", productId, expectedBytes, 192 * 1024,
                    Instant.parse("2026-09-01T12:30:00Z"));
        }

        @Override public void appendChunk(long ownerId, String uploadId, int index, byte[] bytes) {
            appendCalls++;
        }

        @Override public UploadedImage completeUpload(long ownerId, String uploadId) {
            completeCalls++;
            return new UploadedImage(uploadId, 4L, "image/png", new byte[]{1},
                    "a".repeat(64), 1, 1);
        }

        @Override public FinalizedImage finalizeUpload(long ownerId, String uploadId) {
            finalizeCalls++;
            return new FinalizedImage("full.png", "thumb.png");
        }

        @Override public InputStream open(String storageKey) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override public boolean deleteIfExists(String storageKey) { return true; }

        @Override public void cleanup(Set<String> referencedKeys, Instant now) { }
    }
}
