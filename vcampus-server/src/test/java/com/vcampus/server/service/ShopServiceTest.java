package com.vcampus.server.service;

import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.ShopProductSort;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.BankRuleException;
import com.vcampus.server.database.ShopRuleException;
import com.vcampus.server.database.ShopStore;
import com.vcampus.server.database.ShopReviewStore;
import com.vcampus.server.model.ShopProductImageRecord;
import com.vcampus.server.model.ShopProductRecord;
import com.vcampus.server.model.ShopOrderRecord;
import com.vcampus.server.model.ShopOrderItemRecord;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.support.RecordingAuditStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopServiceTest {
    private final SessionManager sessions = new SessionManager();
    private final AtomicLong checkoutBuyer = new AtomicLong();
    private final AtomicReference<String> checkoutOperation = new AtomicReference<>();
    private final AtomicReference<Set<Long>> checkoutSelection = new AtomicReference<>();
    private final AtomicLong buyNowProduct = new AtomicLong();
    private final AtomicInteger buyNowQuantity = new AtomicInteger();
    private final AtomicInteger checkoutCalls = new AtomicInteger();
    private final AtomicReference<SQLException> checkoutSqlFailure = new AtomicReference<>();
    private final AtomicReference<String> failingMethod = new AtomicReference<>();
    private final AtomicReference<ShopStore.OrderQuery> orderQuery = new AtomicReference<>();
    private final AtomicReference<ShopStore.ProductDetail> productDetail = new AtomicReference<>();
    private final AtomicReference<ShopStore.ProductPage> productPage = new AtomicReference<>();
    private final AtomicReference<ShopStore.ProductQuery> productQuery = new AtomicReference<>();
    private final AtomicReference<ShopStore.ProductInput> productInput = new AtomicReference<>();
    private final AtomicReference<ShopStore.OrderDetail> orderDetail = new AtomicReference<>();
    private final AtomicReference<Map<Long, ShopReviewStore.ReviewSummary>> reviewSummaries =
            new AtomicReference<>(Map.of());
    private final AtomicReference<Map<Long, Long>> reviewedItems = new AtomicReference<>(Map.of());
    private ShopService service;
    private RecordingAuditStore audit;
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
                            checkoutCalls.incrementAndGet();
                            checkoutBuyer.set((Long) arguments[0]);
                            checkoutOperation.set((String) arguments[1]);
                            yield new ShopStore.CheckoutResult(31L, "SO20260829120000ABCDEF123456",
                                    new BigDecimal("20.00"), ShopOrderStatus.PAID, false);
                        }
                        case "checkoutCart" -> {
                            checkoutCalls.incrementAndGet();
                            checkoutBuyer.set((Long) arguments[0]);
                            checkoutOperation.set((String) arguments[1]);
                            Set<Long> captured = new java.util.HashSet<>();
                            for (Object value : (Set<?>) arguments[2]) captured.add((Long) value);
                            checkoutSelection.set(Set.copyOf(captured));
                            yield new ShopStore.CheckoutResult(32L, "SO20260901120000ABCDEF123456",
                                    new BigDecimal("30.00"), ShopOrderStatus.PAID, false);
                        }
                        case "buyNow" -> {
                            checkoutCalls.incrementAndGet();
                            checkoutBuyer.set((Long) arguments[0]);
                            checkoutOperation.set((String) arguments[1]);
                            buyNowProduct.set((Long) arguments[2]);
                            buyNowQuantity.set((Integer) arguments[3]);
                            yield new ShopStore.CheckoutResult(33L, "SO20260901120000FEDCBA654321",
                                    new BigDecimal("40.00"), ShopOrderStatus.PAID, false);
                        }
                        case "setCartQuantity", "removeCartItem", "cart" ->
                                new ShopStore.CartResult(java.util.List.of(), BigDecimal.ZERO);
                        case "saveProduct" -> {
                            productInput.set((ShopStore.ProductInput) arguments[1]);
                            yield new ShopStore.ProductSaveResult(5L);
                        }
                        case "cancelOrder" -> new ShopStore.OrderResult(31L, "SO1",
                                new BigDecimal("20.00"), ShopOrderStatus.CANCELLED);
                        case "confirmOrder" -> new ShopStore.OrderResult(31L, "SO1",
                                new BigDecimal("20.00"), ShopOrderStatus.COMPLETED);
                        case "shipOrder" -> new ShopStore.OrderResult(31L, "SO1",
                                new BigDecimal("20.00"), ShopOrderStatus.SHIPPED);
                        case "setProductEnabled" -> true;
                        case "adjustInventory" -> new ShopStore.InventoryResult(5L, 12);
                        case "searchOrders" -> {
                            orderQuery.set((ShopStore.OrderQuery) arguments[0]);
                            yield new ShopStore.OrderPage(java.util.List.of(), 1, 10, 0);
                        }
                        case "searchProducts" -> {
                            productQuery.set((ShopStore.ProductQuery) arguments[0]);
                            yield productPage.get();
                        }
                        case "productImages" -> productDetail.get() == null
                                ? List.of() : productDetail.get().images();
                        case "product" -> {
                            ShopStore.ProductDetail detail = productDetail.get();
                            if (detail == null || (!detail.product().enabled()
                                    && !(Boolean) arguments[1])) {
                                throw new ShopRuleException("商品不存在");
                            }
                            yield detail;
                        }
                        case "order" -> orderDetail.get();
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        ShopReviewStore reviewStore = (ShopReviewStore) Proxy.newProxyInstance(
                ShopReviewStore.class.getClassLoader(), new Class<?>[]{ShopReviewStore.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "summaries" -> reviewSummaries.get();
                    case "summary" -> reviewSummaries.get().getOrDefault((Long) arguments[0],
                            new ShopReviewStore.ReviewSummary(null, 0, List.of(0, 0, 0, 0, 0)));
                    case "reviewIdsByOrderItems" -> reviewedItems.get();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        audit = new RecordingAuditStore();
        service = new ShopService(store, reviewStore, sessions, audit);
    }

    @Test
    void criticalMutationsRetainExactActionsAndSuccessResult() {
        String operationId = UUID.randomUUID().toString();
        assertTrue(service.checkout(request(Actions.SHOP_CHECKOUT, studentToken,
                Map.of("operationId", operationId))).success());
        assertTrue(service.buyNow(request(Actions.SHOP_BUY_NOW, studentToken, Map.of(
                "operationId", UUID.randomUUID().toString(), "productId", "7", "quantity", "1"))).success());
        assertTrue(service.cancelOrder(request(Actions.SHOP_ORDER_CANCEL, studentToken,
                Map.of("orderId", "31"))).success());
        assertTrue(service.confirmOrder(request(Actions.SHOP_ORDER_CONFIRM, studentToken,
                Map.of("orderId", "31"))).success());
        assertTrue(service.saveProduct(request(Actions.SHOP_ADMIN_PRODUCT_SAVE, adminToken, Map.of(
                "name", "教材", "price", "20.00", "enabled", "true"))).success());
        assertTrue(service.setProductEnabled(request(Actions.SHOP_ADMIN_PRODUCT_SET_ENABLED, adminToken,
                Map.of("productId", "5", "enabled", "true"))).success());
        assertTrue(service.adjustInventory(request(Actions.SHOP_ADMIN_INVENTORY_ADJUST, adminToken,
                Map.of("productId", "5", "delta", "2", "reason", "盘点"))).success());
        assertTrue(service.shipOrder(request(Actions.SHOP_ADMIN_ORDER_SHIP, adminToken,
                Map.of("orderId", "31"))).success());

        assertEquals(List.of(
                Actions.SHOP_CHECKOUT, Actions.SHOP_BUY_NOW,
                Actions.SHOP_ORDER_CANCEL, Actions.SHOP_ORDER_CONFIRM,
                Actions.SHOP_ADMIN_PRODUCT_SAVE, Actions.SHOP_ADMIN_PRODUCT_SET_ENABLED,
                Actions.SHOP_ADMIN_INVENTORY_ADJUST, Actions.SHOP_ADMIN_ORDER_SHIP),
                audit.entries().stream().map(RecordingAuditStore.Entry::action).toList());
        assertTrue(audit.entries().stream().allMatch(entry -> "SUCCESS".equals(entry.result())));
    }

    @Test
    void criticalMutationFailuresUseStableAuditResults() {
        assertFalse(service.buyNow(request(Actions.SHOP_BUY_NOW, studentToken,
                Map.of("operationId", "bad", "productId", "7", "quantity", "1"))).success());
        assertEquals("INVALID", audit.last().result());

        failingMethod.set("checkout");
        assertFalse(service.checkout(request(Actions.SHOP_CHECKOUT, studentToken,
                Map.of("operationId", UUID.randomUUID().toString()))).success());
        assertEquals("CONFLICT", audit.last().result());

        assertFalse(service.saveProduct(request(Actions.SHOP_ADMIN_PRODUCT_SAVE, studentToken,
                Map.of())).success());
        assertEquals("DENIED", audit.last().result());

        failingMethod.set("shipOrder");
        assertFalse(service.shipOrder(request(Actions.SHOP_ADMIN_ORDER_SHIP, adminToken,
                Map.of("orderId", "31"))).success());
        assertEquals("DATABASE_ERROR", audit.last().result());
    }

    @Test
    void readOnlyShopRequestsDoNotCreateAuditEntries() {
        productPage.set(new ShopStore.ProductPage(List.of(), 1, 10, 0));

        assertTrue(service.searchProducts(request(Actions.SHOP_PRODUCT_SEARCH, studentToken,
                Map.of("page", "1"))).success());
        assertTrue(service.cart(request(Actions.SHOP_CART_GET, studentToken, Map.of())).success());
        assertTrue(service.searchAdminOrders(request(Actions.SHOP_ADMIN_ORDER_SEARCH, adminToken,
                Map.of("page", "1"))).success());
        assertTrue(audit.entries().isEmpty());
    }

    @Test
    void checkoutUsesSessionBuyerAndRequiresUuid() {
        String operationId = UUID.randomUUID().toString();
        ResponseMessage response = service.checkout(request(studentToken,
                Map.of("operationId", operationId)));

        assertTrue(response.success());
        assertEquals(11L, checkoutBuyer.get());
        assertEquals(operationId, checkoutOperation.get());
        assertFalse(service.checkout(request(studentToken, Map.of("operationId", "bad"))).success());
    }

    @Test
    void checkoutParsesExactSelectedIdsAndKeepsLegacyAllCartBehavior() {
        String legacyOperation = UUID.randomUUID().toString();
        ResponseMessage legacy = service.checkout(request(studentToken,
                Map.of("operationId", legacyOperation)));

        assertTrue(legacy.success(), legacy.message());
        assertEquals(1, checkoutCalls.get());
        assertEquals(null, checkoutSelection.get());

        String selectedOperation = UUID.randomUUID().toString();
        ResponseMessage selected = service.checkout(request(studentToken, Map.of(
                "operationId", selectedOperation, "selectedCount", "2",
                "selected.0", "9", "selected.1", "7")));

        assertTrue(selected.success(), selected.message());
        assertEquals(11L, checkoutBuyer.get());
        assertEquals(selectedOperation, checkoutOperation.get());
        assertEquals(Set.of(7L, 9L), checkoutSelection.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"buyerUserId", "price", "total", "accountId", "arbitrary"})
    void legacyCheckoutRejectsEveryUnexpectedFieldBeforeCallingStore(String unexpectedKey) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("operationId", UUID.randomUUID().toString());
        parameters.put(unexpectedKey, "999");

        ResponseMessage response = service.checkout(request(studentToken, parameters));

        assertFalse(response.success());
        assertEquals("结算参数无效", response.message());
        assertEquals(0, checkoutCalls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"buyerUserId", "price", "total", "accountId", "arbitrary"})
    void selectedCheckoutRejectsEveryUnexpectedFieldBeforeCallingStore(String unexpectedKey) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("operationId", UUID.randomUUID().toString());
        parameters.put("selectedCount", "1");
        parameters.put("selected.0", "7");
        parameters.put(unexpectedKey, "999");

        ResponseMessage response = service.checkout(request(studentToken, parameters));

        assertFalse(response.success());
        assertEquals("结算参数无效", response.message());
        assertEquals(0, checkoutCalls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"buyerUserId", "price", "total", "accountId", "arbitrary"})
    void buyNowRejectsEveryUnexpectedFieldBeforeCallingStore(String unexpectedKey) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("operationId", UUID.randomUUID().toString());
        parameters.put("productId", "7");
        parameters.put("quantity", "1");
        parameters.put(unexpectedKey, "999");

        ResponseMessage response = service.buyNow(request(studentToken, parameters));

        assertFalse(response.success());
        assertEquals("立即购买参数无效", response.message());
        assertEquals(0, checkoutCalls.get());
    }

    @Test
    void checkoutRejectsMalformedSelectedProtocolBeforeCallingStore() {
        List<Map<String, String>> invalid = List.of(
                Map.of("selectedCount", "0"),
                Map.of("selectedCount", "101"),
                Map.of("selectedCount", "999999999999999999999"),
                Map.of("selectedCount", "2", "selected.0", "1"),
                Map.of("selectedCount", "1", "selected.0", "1", "selected.1", "2"),
                Map.of("selectedCount", "2", "selected.0", "1", "selected.1", "1"),
                Map.of("selectedCount", "1", "selected.0", "0"),
                Map.of("selectedCount", "1", "selected.0", "999999999999999999999"),
                Map.of("selectedCount", "1", "selected.0", "1", "selected.bad", "2"));

        for (Map<String, String> values : invalid) {
            Map<String, String> parameters = new LinkedHashMap<>(values);
            parameters.put("operationId", UUID.randomUUID().toString());
            assertFalse(service.checkout(request(studentToken, parameters)).success(), values.toString());
        }
        assertEquals(0, checkoutCalls.get());
    }

    @Test
    void buyNowUsesOnlySessionBuyerAndRejectsBoundsOrUnexpectedParameters() {
        String operationId = UUID.randomUUID().toString();
        ResponseMessage response = service.buyNow(request(studentToken, Map.of(
                "operationId", operationId, "productId", "7", "quantity", "2")));

        assertTrue(response.success(), response.message());
        assertEquals(11L, checkoutBuyer.get());
        assertEquals(operationId, checkoutOperation.get());
        assertEquals(7L, buyNowProduct.get());
        assertEquals(2, buyNowQuantity.get());

        List<Map<String, String>> invalid = List.of(
                Map.of("operationId", UUID.randomUUID().toString(), "productId", "7",
                        "quantity", "0"),
                Map.of("operationId", UUID.randomUUID().toString(), "productId", "7",
                        "quantity", "1000"),
                Map.of("operationId", UUID.randomUUID().toString(), "productId", "0",
                        "quantity", "1"),
                Map.of("operationId", "bad", "productId", "7", "quantity", "1"),
                Map.of("operationId", UUID.randomUUID().toString(), "productId", "7",
                        "quantity", "1", "buyerUserId", "999"),
                Map.of("operationId", UUID.randomUUID().toString(), "productId", "7",
                        "quantity", "1", "price", "0.01"));
        for (Map<String, String> values : invalid) {
            assertFalse(service.buyNow(request(studentToken, values)).success(), values.toString());
        }
        assertEquals(1, checkoutCalls.get());
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

    @Test
    void searchForwardsOptionalCategoryAndSortAndKeepsLegacyDefaults() {
        productPage.set(new ShopStore.ProductPage(List.of(), 1, 10, 0));

        ResponseMessage filtered = service.searchProducts(request(studentToken, Map.of(
                "keyword", "杯", "category", "CAMPUS_MERCH", "sort", "PRICE_ASC", "page", "1")));

        assertTrue(filtered.success(), filtered.message());
        assertEquals(ShopCategory.CAMPUS_MERCH, productQuery.get().category());
        assertEquals(ShopProductSort.PRICE_ASC, productQuery.get().sort());
        assertEquals(Boolean.TRUE, productQuery.get().enabled());

        ResponseMessage legacy = service.searchProducts(request(studentToken, Map.of("page", "1")));

        assertTrue(legacy.success(), legacy.message());
        assertEquals(null, productQuery.get().category());
        assertEquals(ShopProductSort.NEWEST, productQuery.get().sort());
    }

    @Test
    void legacyBuyerEnabledTrueIsAcceptedButFalseIsRejected() {
        productPage.set(new ShopStore.ProductPage(List.of(), 1, 10, 0));

        ResponseMessage legacy = service.searchProducts(request(studentToken, Map.of(
                "keyword", "", "enabled", "true", "page", "1")));

        assertTrue(legacy.success(), legacy.message());
        assertEquals(Boolean.TRUE, productQuery.get().enabled());
        assertFalse(service.searchProducts(request(studentToken, Map.of(
                "keyword", "", "enabled", "false", "page", "1"))).success());
    }

    @Test
    void adminSaveForwardsOptionalCategoryAndKeepsLegacyOtherDefault() {
        ResponseMessage categorized = service.saveProduct(request(adminToken, Map.of(
                "name", "耳机", "description", "说明", "category", "DIGITAL_ACCESSORIES",
                "price", "20.00", "enabled", "true")));

        assertTrue(categorized.success(), categorized.message());
        assertEquals(ShopCategory.DIGITAL_ACCESSORIES, productInput.get().category());

        ResponseMessage legacy = service.saveProduct(request(adminToken, Map.of(
                "name", "教材", "price", "20.00", "enabled", "true")));

        assertTrue(legacy.success(), legacy.message());
        assertEquals(ShopCategory.OTHER, productInput.get().category());
    }

    @Test
    void searchAndSaveRejectInvalidNamesAndUnexpectedParameters() {
        productPage.set(new ShopStore.ProductPage(List.of(), 1, 10, 0));

        assertFalse(service.searchProducts(request(studentToken, Map.of(
                "category", "invalid", "page", "1"))).success());
        assertFalse(service.searchProducts(request(studentToken, Map.of(
                "sort", "invalid", "page", "1"))).success());
        assertFalse(service.searchProducts(request(studentToken, Map.of(
                "page", "1", "buyerId", "1"))).success());
        assertFalse(service.saveProduct(request(adminToken, Map.of(
                "name", "教材", "price", "20.00", "enabled", "true", "category", "invalid"))).success());
        assertFalse(service.saveProduct(request(adminToken, Map.of(
                "name", "教材", "price", "20.00", "enabled", "true", "buyerId", "1"))).success());
    }

    @Test
    void searchPreservesLegacyNineFieldBytesAndAddsCoverMetadataSeparately() {
        ShopProductRecord product = product(true);
        ShopProductImageRecord cover = image(41L, 7L, 0, true, "a".repeat(64));
        productPage.set(new ShopStore.ProductPage(List.of(product), 1, 10, 1,
                Map.of(7L, cover)));

        ResponseMessage response = service.searchProducts(request(studentToken, Map.of("page", "1")));

        assertTrue(response.success(), response.message());
        assertEquals("1:75:SKU-73:\u6821\u56ed\u676f2:\u9650\u91cf5:19.901:44:true20:2026-09-01T10:00:00Z20:2026-09-01T11:00:00Z",
                response.data().get("row.0"));
        assertEquals(9, RowCodec.decode(response.data().get("row.0")).size());
        assertEquals("CAMPUS_MERCH", response.data().get("row.0.category"));
        assertEquals("41", response.data().get("row.0.coverImageId"));
        assertEquals("a".repeat(64), response.data().get("row.0.coverHash"));
    }

    @Test
    void searchBatchesCoversOnceAndPreservesPageOrderingAndCoverAbsence() {
        ShopProductRecord covered = product(true);
        ShopProductRecord uncovered = new ShopProductRecord(8L, "SKU-8", "N", "D",
                ShopCategory.OTHER, BigDecimal.ONE, 2, true, Instant.EPOCH, Instant.EPOCH);
        productPage.set(new ShopStore.ProductPage(List.of(uncovered, covered), 1, 10, 2,
                Map.of(7L, image(41L, 7L, 0, true, "a".repeat(64)))));

        ResponseMessage response = service.searchProducts(request(studentToken, Map.of("page", "1")));

        assertTrue(response.success(), response.message());
        assertEquals("8", RowCodec.decode(response.data().get("row.0")).get(0));
        assertFalse(response.data().containsKey("row.0.coverImageId"));
        assertEquals("7", RowCodec.decode(response.data().get("row.1")).get(0));
        assertEquals("41", response.data().get("row.1.coverImageId"));
    }

    @Test
    void emptySearchPageSkipsCoverLookup() {
        productPage.set(new ShopStore.ProductPage(List.of(), 1, 10, 0));

        ResponseMessage response = service.searchProducts(request(studentToken, Map.of("page", "1")));

        assertTrue(response.success(), response.message());
        assertEquals("0", response.data().get("count"));
    }

    @Test
    void productDetailReturnsOrderedPublicMetadataWithoutStorageKeys() {
        ShopProductImageRecord second = image(43L, 7L, 1, false, "c".repeat(64));
        ShopProductImageRecord first = image(41L, 7L, 0, true, "a".repeat(64));
        productDetail.set(new ShopStore.ProductDetail(product(true), List.of(first, second)));

        ResponseMessage response = service.getProduct(request(studentToken, Map.of("productId", "7")));

        assertTrue(response.success(), response.message());
        assertEquals(9, RowCodec.decode(response.data().get("product")).size());
        assertEquals("CAMPUS_MERCH", response.data().get("category"));
        assertEquals("2", response.data().get("imageCount"));
        assertEquals(List.of("41", "image/png", "600000", "a".repeat(64), "0", "true"),
                RowCodec.decode(response.data().get("image.0")));
        assertEquals(List.of("43", "image/png", "600000", "c".repeat(64), "1", "false"),
                RowCodec.decode(response.data().get("image.1")));
        assertTrue(response.data().values().stream().noneMatch(value ->
                value.contains("private/full") || value.contains("private/thumb")));
    }

    @Test
    void disabledProductDetailIsHiddenFromBuyerButVisibleToManager() {
        productDetail.set(new ShopStore.ProductDetail(product(false), List.of()));

        ResponseMessage buyer = service.getProduct(request(studentToken, Map.of("productId", "7")));
        ResponseMessage manager = service.getProduct(request(adminToken, Map.of("productId", "7")));

        assertFalse(buyer.success());
        assertEquals("\u5546\u54c1\u4e0d\u5b58\u5728", buyer.message());
        assertTrue(manager.success(), manager.message());
        assertEquals("false", RowCodec.decode(manager.data().get("product")).get(6));
    }

    @Test
    void productSearchAndDetailExposeRatingsWithoutFabricatingZeroAverage() {
        ShopProductRecord rated = product(true);
        ShopProductRecord unrated = new ShopProductRecord(8L, "SKU-8", "普通商品", "说明",
                ShopCategory.OTHER, BigDecimal.ONE, 2, true, Instant.EPOCH, Instant.EPOCH);
        productPage.set(new ShopStore.ProductPage(List.of(rated, unrated), 1, 10, 2));
        reviewSummaries.set(Map.of(
                7L, new ShopReviewStore.ReviewSummary(new BigDecimal("4.8"), 36,
                        List.of(0, 0, 2, 4, 30)),
                8L, new ShopReviewStore.ReviewSummary(null, 0, List.of(0, 0, 0, 0, 0))));

        ResponseMessage page = service.searchProducts(request(studentToken, Map.of("page", "1")));
        assertEquals("4.8", page.data().get("row.0.ratingAverage"));
        assertEquals("36", page.data().get("row.0.ratingCount"));
        assertEquals("", page.data().get("row.1.ratingAverage"));
        assertEquals("0", page.data().get("row.1.ratingCount"));

        productDetail.set(new ShopStore.ProductDetail(rated, List.of()));
        ResponseMessage detail = service.getProduct(request(studentToken, Map.of("productId", "7")));
        assertEquals("4.8", detail.data().get("ratingAverage"));
        assertEquals("36", detail.data().get("ratingCount"));
        assertEquals(List.of("0", "0", "2", "4", "30"),
                java.util.stream.IntStream.rangeClosed(1, 5)
                        .mapToObj(star -> detail.data().get("ratingStars." + star)).toList());
    }

    @Test
    void completedBuyerOrderItemsExposeReviewEligibilityButAdminAndShippedDoNot() {
        ShopOrderItemRecord first = new ShopOrderItemRecord(31, 51, 7, "SKU-7", "校园杯",
                new BigDecimal("19.90"), 1, new BigDecimal("19.90"));
        ShopOrderItemRecord second = new ShopOrderItemRecord(32, 51, 8, "SKU-8", "笔记本",
                new BigDecimal("8.00"), 1, new BigDecimal("8.00"));
        orderDetail.set(new ShopStore.OrderDetail(order(ShopOrderStatus.COMPLETED), List.of(first, second)));
        reviewedItems.set(Map.of(32L, 91L));

        ResponseMessage buyer = service.getOrder(request(studentToken, Map.of("orderId", "51")));
        assertEquals("true", buyer.data().get("row.0.reviewable"));
        assertEquals("", buyer.data().get("row.0.reviewId"));
        assertEquals("false", buyer.data().get("row.1.reviewable"));
        assertEquals("91", buyer.data().get("row.1.reviewId"));

        ResponseMessage admin = service.getOrder(request(adminToken, Map.of("orderId", "51")));
        assertEquals("false", admin.data().get("row.0.reviewable"));
        orderDetail.set(new ShopStore.OrderDetail(order(ShopOrderStatus.SHIPPED), List.of(first)));
        ResponseMessage shipped = service.getOrder(request(studentToken, Map.of("orderId", "51")));
        assertEquals("false", shipped.data().get("row.0.reviewable"));
    }

    private ShopOrderRecord order(ShopOrderStatus status) {
        return new ShopOrderRecord(51, "SO51", 11, "student", "学生", "operation",
                new BigDecimal("27.90"), status, Instant.EPOCH, null,
                status == ShopOrderStatus.COMPLETED ? Instant.EPOCH : null, null);
    }

    private ShopProductRecord product(boolean enabled) {
        return new ShopProductRecord(7L, "SKU-7", "\u6821\u56ed\u676f", "\u9650\u91cf",
                ShopCategory.CAMPUS_MERCH, new BigDecimal("19.90"), 4, enabled,
                Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-01T11:00:00Z"));
    }

    private ShopProductImageRecord image(long id, long productId, int order, boolean cover,
                                         String hash) {
        return new ShopProductImageRecord(id, productId, "private/full-" + id + ".png",
                "private/thumb-" + id + ".png", "image/png", 600_000L, hash, order, cover,
                Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-01T11:00:00Z"));
    }

    private RequestMessage request(String token, Map<String, String> values) {
        return request("shop.test", token, values);
    }

    private RequestMessage request(String action, String token, Map<String, String> values) {
        Map<String, String> parameters = new LinkedHashMap<>(values);
        parameters.put("sessionToken", token);
        return RequestMessage.create(action, parameters);
    }

    private UserAccount account(long id, String username, Set<UserRole> roles) {
        return new UserAccount(id, username, "hash", "salt", username,
                true, false, roles);
    }
}
