package com.vcampus.server.service;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.server.database.ShopStore;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
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
}
