package com.vcampus.server.service;

import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.server.database.BankRuleException;
import com.vcampus.server.database.ShopRuleException;
import com.vcampus.server.database.ShopStore;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopServiceTest {
    private final SessionManager sessions = new SessionManager();
    private final AtomicLong checkoutBuyer = new AtomicLong();
    private final AtomicReference<String> checkoutOperation = new AtomicReference<>();
    private final AtomicReference<SQLException> checkoutSqlFailure = new AtomicReference<>();
    private final AtomicReference<String> failingMethod = new AtomicReference<>();
    private final AtomicReference<ShopStore.OrderQuery> orderQuery = new AtomicReference<>();
    private ShopService service;
    private String studentToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        studentToken = sessions.create(account(11L, "student", Set.of(UserRole.STUDENT))).token();
        adminToken = sessions.create(account(19L, "shopadmin",
                Set.of(UserRole.TEACHER, UserRole.SHOP_ADMIN))).token();
        ShopStore store = (ShopStore) Proxy.newProxyInstance(ShopStore.class.getClassLoader(),
                new Class<?>[]{ShopStore.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("checkout") && checkoutSqlFailure.get() != null) {
                        throw checkoutSqlFailure.get();
                    }
                    if (method.getName().equals(failingMethod.get())) {
                        if (method.getName().equals("checkout")) throw new ShopRuleException("余额不足");
                        throw new SQLException("secret sql table");
                    }
                    return switch (method.getName()) {
                        case "checkout" -> {
                            checkoutBuyer.set((Long) arguments[0]);
                            checkoutOperation.set((String) arguments[1]);
                            yield new ShopStore.CheckoutResult(31L, "SO20260829120000ABCDEF123456",
                                    new BigDecimal("20.00"), ShopOrderStatus.PAID, false);
                        }
                        case "setCartQuantity", "removeCartItem", "cart" ->
                                new ShopStore.CartResult(java.util.List.of(), BigDecimal.ZERO);
                        case "saveProduct" -> new ShopStore.ProductSaveResult(5L);
                        case "shipOrder" -> new ShopStore.OrderResult(31L, "SO1",
                                new BigDecimal("20.00"), ShopOrderStatus.SHIPPED);
                        case "searchOrders" -> {
                            orderQuery.set((ShopStore.OrderQuery) arguments[0]);
                            yield new ShopStore.OrderPage(java.util.List.of(), 1, 10, 0);
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        service = new ShopService(store, sessions);
    }

    @Test
    void checkoutUsesSessionBuyerAndRequiresUuid() {
        String operationId = UUID.randomUUID().toString();
        ResponseMessage response = service.checkout(request(studentToken,
                Map.of("buyerUserId", "999", "operationId", operationId)));

        assertTrue(response.success());
        assertEquals(11L, checkoutBuyer.get());
        assertEquals(operationId, checkoutOperation.get());
        assertFalse(service.checkout(request(studentToken, Map.of("operationId", "bad"))).success());
    }

    @Test
    void ordinaryUserCannotSaveProductOrShipOrder() {
        assertEquals("无权执行商店管理操作",
                service.saveProduct(request(studentToken, Map.of())).message());
        assertEquals("无权执行商店管理操作",
                service.shipOrder(request(studentToken, Map.of())).message());
        assertTrue(service.saveProduct(request(adminToken, Map.of(
                "name", "教材", "description", "说明",
                "price", "20.00", "enabled", "true"))).success());
    }

    @Test
    void quantityValidationAndExpiredSessionStopBeforeStore() {
        assertFalse(service.setCartQuantity(request(studentToken,
                Map.of("productId", "1", "quantity", "-1"))).success());
        assertEquals("登录已过期，请重新登录",
                service.cart(RequestMessage.create("shop.test", Map.of())).message());
    }

    @Test
    void safeRuleMessageIsReturnedButDatabaseDetailsAreHidden() {
        failingMethod.set("checkout");
        ResponseMessage rule = service.checkout(request(studentToken,
                Map.of("operationId", UUID.randomUUID().toString())));
        assertEquals("余额不足", rule.message());

        failingMethod.set("cart");
        ResponseMessage database = service.cart(request(studentToken, Map.of()));
        assertEquals("数据库操作失败，请稍后重试", database.message());
    }

    @Test
    void frozenAccountMessageIsReturnedDuringCheckout() {
        checkoutSqlFailure.set(new BankRuleException("账户已冻结，不能支付"));

        ResponseMessage response = service.checkout(request(studentToken,
                Map.of("operationId", UUID.randomUUID().toString())));

        assertEquals("账户已冻结，不能支付", response.message());
    }

    @Test
    void administratorSearchesOrdersByOrderNumberOrUsernameWithoutBuyerId() {
        ResponseMessage response = service.searchAdminOrders(request(adminToken, Map.of(
                "keyword", "student", "status", "PAID", "page", "1")));

        assertTrue(response.success());
        assertEquals(null, orderQuery.get().buyerUserId());
        assertEquals("student", orderQuery.get().keyword());
        assertEquals(ShopOrderStatus.PAID, orderQuery.get().status());
    }

    private RequestMessage request(String token, Map<String, String> values) {
        Map<String, String> parameters = new LinkedHashMap<>(values);
        parameters.put("sessionToken", token);
        return RequestMessage.create("shop.test", parameters);
    }

    private UserAccount account(long id, String username, Set<UserRole> roles) {
        return new UserAccount(id, username, "hash", "salt", username,
                true, false, roles);
    }
}
