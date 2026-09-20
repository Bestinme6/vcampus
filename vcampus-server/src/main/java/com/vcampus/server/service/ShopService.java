package com.vcampus.server.service;

import com.vcampus.common.model.AccessPolicy;
import com.vcampus.common.model.MoneyPolicy;
import com.vcampus.common.model.ModuleCode;
import com.vcampus.common.model.ShopAccessPolicy;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.ShopProductSort;
import com.vcampus.common.model.ShippingAddressChoice;
import com.vcampus.common.model.ShippingAddressValue;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.common.protocol.Actions;
import com.vcampus.server.database.AuditStore;
import com.vcampus.server.database.BankRuleException;
import com.vcampus.server.database.ShopRuleException;
import com.vcampus.server.database.ShopStore;
import com.vcampus.server.database.ShopReviewStore;
import com.vcampus.server.database.ShopStore.CartResult;
import com.vcampus.server.database.ShopStore.OrderDetail;
import com.vcampus.server.database.ShopStore.OrderPage;
import com.vcampus.server.database.ShopStore.OrderQuery;
import com.vcampus.server.database.ShopStore.ProductInput;
import com.vcampus.server.database.ShopStore.ProductPage;
import com.vcampus.server.database.ShopStore.ProductDetail;
import com.vcampus.server.database.ShopStore.ProductQuery;
import com.vcampus.server.model.ShopCartItemRecord;
import com.vcampus.server.model.ShopOrderItemRecord;
import com.vcampus.server.model.ShopOrderRecord;
import com.vcampus.server.model.ShopProductRecord;
import com.vcampus.server.model.ShopProductImageRecord;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.math.RoundingMode;

/**
 * 校园商店商品、购物车、订单、库存和退款业务服务。
 *
 * <p>下单时锁定库存并保存商品及地址快照，校园银行扣款与订单写入在同一事务边界内协调。</p>
 *
 * @since 1.0
 */
public final class ShopService {
    private static final int PAGE_SIZE = 10;
    private static final AuditStore NO_AUDIT = (userId, action, result, address) -> { };
    private static final Set<String> AUDITED_ACTIONS = Set.of(
            Actions.SHOP_CHECKOUT, Actions.SHOP_BUY_NOW,
            Actions.SHOP_ORDER_CANCEL, Actions.SHOP_ORDER_CONFIRM,
            Actions.SHOP_ADMIN_PRODUCT_SAVE, Actions.SHOP_ADMIN_PRODUCT_SET_ENABLED,
            Actions.SHOP_ADMIN_INVENTORY_ADJUST, Actions.SHOP_ADMIN_ORDER_SHIP);
    private final ShopStore shop;
    private final ShopReviewStore reviews;
    private final SessionManager sessions;
    private final AuditStore audit;

    public ShopService(ShopStore shop, SessionManager sessions) {
        this(shop, null, sessions, NO_AUDIT);
    }

    public ShopService(ShopStore shop, SessionManager sessions, AuditStore audit) {
        this(shop, null, sessions, audit);
    }

    public ShopService(ShopStore shop, ShopReviewStore reviews,
                       SessionManager sessions, AuditStore audit) {
        this.shop = Objects.requireNonNull(shop);
        this.reviews = reviews;
        this.sessions = Objects.requireNonNull(sessions);
        this.audit = Objects.requireNonNull(audit);
    }

    public ResponseMessage searchProducts(RequestMessage request) {
        return handle(request, false, session -> {
            boolean managing = ShopAccessPolicy.canManage(session.roles());
            Set<String> allowed = new HashSet<>(Set.of(
                    "sessionToken", "keyword", "category", "sort", "page", "enabled"));
            requireOnly(request, allowed, "商品查询参数无效");
            Boolean requestedEnabled = optionalBoolean(request.parameters().get("enabled"));
            if (!managing && Boolean.FALSE.equals(requestedEnabled)) {
                throw new IllegalArgumentException("商品查询参数无效");
            }
            Boolean enabled = managing ? requestedEnabled : Boolean.TRUE;
            ProductPage result = shop.searchProducts(new ProductQuery(
                    request.parameters().get("keyword"), optionalCategory(
                            request.parameters().get("category"), null), enabled,
                    optionalSort(request.parameters().get("sort")),
                    page(request), PAGE_SIZE));
            return success(request, "查询成功", productPage(result));
        });
    }

    public ResponseMessage getProduct(RequestMessage request) {
        return handle(request, false, session -> {
            ProductDetail detail = shop.product(
                    positiveLong(request.parameters().get("productId"), "\u5546\u54c1ID"),
                    ShopAccessPolicy.canManage(session.roles()));
            return success(request, "\u67e5\u8be2\u6210\u529f", productDetail(detail));
        });
    }

    public ResponseMessage cart(RequestMessage request) {
        return handle(request, false, session ->
                success(request, "查询成功", cartData(shop.cart(session.userId()))));
    }

    public ResponseMessage setCartQuantity(RequestMessage request) {
        return handle(request, false, session -> {
            int quantity = integer(request.parameters().get("quantity"), "商品数量");
            if (quantity < 0 || quantity > 999) throw new IllegalArgumentException("商品数量无效");
            CartResult result = shop.setCartQuantity(session.userId(),
                    positiveLong(request.parameters().get("productId"), "商品ID"), quantity);
            return success(request, quantity == 0 ? "商品已移出购物车" : "购物车已更新",
                    cartData(result));
        });
    }

    public ResponseMessage removeCartItem(RequestMessage request) {
        return handle(request, false, session -> success(request, "商品已移出购物车",
                cartData(shop.removeCartItem(session.userId(),
                        positiveLong(request.parameters().get("productId"), "商品ID")))));
    }

    public ResponseMessage checkout(RequestMessage request) {
        return handle(request, false, session -> {
            String operation = operationId(request.parameters().get("operationId"));
            AddressParameters address = shippingAddress(request, "结算参数无效");
            boolean selectedCheckout = request.parameters().containsKey("selectedCount");
            Set<String> baseKeys = new HashSet<>(Set.of("sessionToken", "operationId"));
            baseKeys.addAll(address.keys());
            if (!selectedCheckout && !request.parameters().keySet().equals(baseKeys)) {
                throw new IllegalArgumentException("结算参数无效");
            }
            var result = selectedCheckout
                    ? shop.checkoutCart(session.userId(), operation,
                            selectedProductIds(request, address.keys()), address.choice())
                    : shop.checkout(session.userId(), operation, address.choice());
            return checkoutResult(request, result);
        });
    }

    public ResponseMessage buyNow(RequestMessage request) {
        return handle(request, false, session -> {
            AddressParameters address = shippingAddress(request, "立即购买参数无效");
            Set<String> expected = new HashSet<>(Set.of(
                    "sessionToken", "operationId", "productId", "quantity"));
            expected.addAll(address.keys());
            if (!request.parameters().keySet().equals(expected)) {
                throw new IllegalArgumentException("立即购买参数无效");
            }
            int quantity = integer(request.parameters().get("quantity"), "商品数量");
            if (quantity < 1 || quantity > 999) throw new IllegalArgumentException("商品数量无效");
            var result = shop.buyNow(session.userId(),
                    operationId(request.parameters().get("operationId")),
                    positiveLong(request.parameters().get("productId"), "商品ID"), quantity,
                    address.choice());
            return checkoutResult(request, result);
        });
    }

    public ResponseMessage searchOrders(RequestMessage request) {
        return handle(request, false, session -> {
            OrderPage result = shop.searchOrders(new OrderQuery(session.userId(), "",
                    optionalStatus(request.parameters().get("status")), page(request), PAGE_SIZE));
            return success(request, "查询成功", orderPage(result));
        });
    }

    public ResponseMessage getOrder(RequestMessage request) {
        return handle(request, false, session -> success(request, "查询成功", orderDetail(
                shop.order(session.userId(), positiveLong(request.parameters().get("orderId"), "订单ID"),
                        ShopAccessPolicy.canManage(session.roles())), session)));
    }

    public ResponseMessage cancelOrder(RequestMessage request) {
        return handle(request, false, session -> orderMutation(request, "订单已取消",
                shop.cancelOrder(session.userId(),
                        positiveLong(request.parameters().get("orderId"), "订单ID"))));
    }

    public ResponseMessage confirmOrder(RequestMessage request) {
        return handle(request, false, session -> orderMutation(request, "已确认收货",
                shop.confirmOrder(session.userId(),
                        positiveLong(request.parameters().get("orderId"), "订单ID"))));
    }

    public ResponseMessage saveProduct(RequestMessage request) {
        return handle(request, true, session -> {
            requireOnly(request, Set.of("sessionToken", "productId", "sku", "name", "description",
                    "category", "price", "enabled"), "商品保存参数无效");
            Long productId = optionalPositiveLong(request.parameters().get("productId"), "商品ID");
            var result = shop.saveProduct(session.userId(), new ProductInput(productId,
                    null, required(request, "name", "商品名称"),
                    request.parameters().getOrDefault("description", ""),
                    optionalCategory(request.parameters().get("category"), ShopCategory.OTHER),
                    MoneyPolicy.parsePositive(request.parameters().get("price")),
                    strictBoolean(request.parameters().get("enabled"), "启用状态")));
            return success(request, productId == null ? "商品已创建" : "商品已更新",
                    Map.of("productId", Long.toString(result.productId())));
        });
    }

    public ResponseMessage setProductEnabled(RequestMessage request) {
        return handle(request, true, session -> {
            boolean enabled = strictBoolean(request.parameters().get("enabled"), "启用状态");
            boolean changed = shop.setProductEnabled(session.userId(),
                    positiveLong(request.parameters().get("productId"), "商品ID"), enabled);
            return success(request, changed ? (enabled ? "商品已上架" : "商品已下架") : "商品状态未变化",
                    Map.of("changed", Boolean.toString(changed)));
        });
    }

    public ResponseMessage adjustInventory(RequestMessage request) {
        return handle(request, true, session -> {
            int delta = integer(request.parameters().get("delta"), "库存变动数量");
            if (delta == 0) throw new IllegalArgumentException("库存变动不能为零");
            var result = shop.adjustInventory(session.userId(),
                    positiveLong(request.parameters().get("productId"), "商品ID"), delta,
                    required(request, "reason", "库存变动原因"));
            return success(request, "库存已更新", Map.of(
                    "productId", Long.toString(result.productId()),
                    "stockAfter", Integer.toString(result.stockAfter())));
        });
    }

    public ResponseMessage searchAdminOrders(RequestMessage request) {
        return handle(request, true, session -> {
            OrderPage result = shop.searchOrders(new OrderQuery(
                    null, request.parameters().get("keyword"),
                    optionalStatus(request.parameters().get("status")), page(request), PAGE_SIZE));
            return success(request, "查询成功", orderPage(result));
        });
    }

    public ResponseMessage shipOrder(RequestMessage request) {
        return handle(request, true, session -> orderMutation(request, "订单已发货",
                shop.shipOrder(session.userId(),
                        positiveLong(request.parameters().get("orderId"), "订单ID"))));
    }

    private ResponseMessage handle(RequestMessage request, boolean administrative, Work work) {
        Optional<UserSession> session = sessions.find(request.parameters().get("sessionToken"));
        if (session.isEmpty()) return ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录");
        UserSession actor = session.get();
        boolean allowed = administrative ? ShopAccessPolicy.canManage(session.get().roles())
                : AccessPolicy.canAccess(ModuleCode.SHOP, session.get().roles());
        if (!allowed) return audited(actor, request, "DENIED",
                ResponseMessage.failure(request.requestId(), administrative
                        ? "无权执行商店管理操作" : "无权使用校园商店"));
        try {
            return audited(actor, request, "SUCCESS", work.run(actor));
        } catch (ShopRuleException | BankRuleException exception) {
            return audited(actor, request, "CONFLICT",
                    ResponseMessage.failure(request.requestId(), exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            return audited(actor, request, "INVALID",
                    ResponseMessage.failure(request.requestId(), exception.getMessage()));
        } catch (SQLException exception) {
            System.err.println("Shop database operation failed: " + exception.getMessage());
            return audited(actor, request, "DATABASE_ERROR",
                    ResponseMessage.failure(request.requestId(), "数据库操作失败，请稍后重试"));
        }
    }

    private ResponseMessage audited(UserSession actor, RequestMessage request,
                                    String result, ResponseMessage response) {
        if (AUDITED_ACTIONS.contains(request.action())) {
            audit.record(actor.userId(), request.action(), result, null);
        }
        return response;
    }

    private Map<String, String> productPage(ProductPage page) throws SQLException {
        Map<String, String> data = pageData(page.page(), page.pageSize(), page.total(), page.rows().size());
        Set<Long> productIds = new java.util.LinkedHashSet<>();
        for (ShopProductRecord row : page.rows()) productIds.add(row.id());
        Map<Long, ShopReviewStore.ReviewSummary> summaries = reviews == null
                ? Map.of() : reviews.summaries(productIds);
        for (int index = 0; index < page.rows().size(); index++) {
            ShopProductRecord row = page.rows().get(index);
            String prefix = "row." + index;
            data.put(prefix, encodeProduct(row));
            data.put(prefix + ".category", row.category().name());
            putSummary(data, prefix + ".", summaries.getOrDefault(row.id(), emptySummary()));
            ShopProductImageRecord cover = page.covers().get(row.id());
            if (cover != null) {
                data.put(prefix + ".coverImageId", Long.toString(cover.id()));
                data.put(prefix + ".coverHash", cover.sha256());
            }
        }
        return data;
    }

    private Map<String, String> productDetail(ProductDetail detail) throws SQLException {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("product", encodeProduct(detail.product()));
        data.put("category", detail.product().category().name());
        putSummary(data, "", reviews == null ? emptySummary() : reviews.summary(detail.product().id()));
        data.put("imageCount", Integer.toString(detail.images().size()));
        for (int index = 0; index < detail.images().size(); index++) {
            ShopProductImageRecord image = detail.images().get(index);
            data.put("image." + index, RowCodec.encode(Long.toString(image.id()),
                    image.mimeType(), Long.toString(image.byteSize()), image.sha256(),
                    Integer.toString(image.sortOrder()), Boolean.toString(image.cover())));
        }
        return data;
    }

    private String encodeProduct(ShopProductRecord row) {
        return RowCodec.encode(Long.toString(row.id()), row.sku(), row.name(), row.description(),
                MoneyPolicy.format(row.price()), Integer.toString(row.stock()),
                Boolean.toString(row.enabled()), row.createdAt().toString(), row.updatedAt().toString());
    }

    private Map<String, String> cartData(CartResult cart) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("count", Integer.toString(cart.rows().size()));
        data.put("estimatedTotal", MoneyPolicy.format(cart.estimatedTotal()));
        for (int index = 0; index < cart.rows().size(); index++) {
            ShopCartItemRecord row = cart.rows().get(index);
            data.put("row." + index, RowCodec.encode(Long.toString(row.productId()), row.sku(),
                    row.name(), row.description(), MoneyPolicy.format(row.unitPrice()),
                    Integer.toString(row.quantity()), Integer.toString(row.stock()),
                    Boolean.toString(row.enabled()), MoneyPolicy.format(row.subtotal()),
                    row.updatedAt().toString()));
        }
        return data;
    }

    private Map<String, String> orderPage(OrderPage page) {
        Map<String, String> data = pageData(page.page(), page.pageSize(), page.total(), page.rows().size());
        for (int index = 0; index < page.rows().size(); index++) {
            data.put("row." + index, encodeOrder(page.rows().get(index)));
        }
        return data;
    }

    private Map<String, String> orderDetail(OrderDetail detail, UserSession session) throws SQLException {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("order", encodeOrder(detail.order()));
        ShippingAddressValue shippingAddress = detail.order().shippingAddress();
        if (shippingAddress != null) {
            data.put("shippingRecipient", shippingAddress.recipient());
            data.put("shippingPhone", shippingAddress.phone());
            data.put("shippingAddress", shippingAddress.address());
        }
        data.put("count", Integer.toString(detail.items().size()));
        Set<Long> itemIds = new java.util.LinkedHashSet<>();
        Set<Long> productIds = new java.util.LinkedHashSet<>();
        for (ShopOrderItemRecord item : detail.items()) {
            itemIds.add(item.id());
            productIds.add(item.productId());
        }
        Map<Long, ShopProductImageRecord> covers = productIds.isEmpty()
                ? Map.of() : shop.coverImages(productIds);
        Map<Long, Long> reviewIds = reviews == null || itemIds.isEmpty()
                ? Map.of() : reviews.reviewIdsByOrderItems(itemIds);
        boolean buyerCanReview = session.userId() == detail.order().buyerUserId()
                && detail.order().status() == ShopOrderStatus.COMPLETED;
        for (int index = 0; index < detail.items().size(); index++) {
            ShopOrderItemRecord row = detail.items().get(index);
            data.put("row." + index, RowCodec.encode(Long.toString(row.id()),
                    Long.toString(row.productId()), row.skuSnapshot(), row.nameSnapshot(),
                    MoneyPolicy.format(row.unitPrice()), Integer.toString(row.quantity()),
                    MoneyPolicy.format(row.subtotal())));
            Long reviewId = reviewIds.get(row.id());
            data.put("row." + index + ".reviewable",
                    Boolean.toString(buyerCanReview && reviewId == null));
            data.put("row." + index + ".reviewId", reviewId == null ? "" : Long.toString(reviewId));
            ShopProductImageRecord cover = covers.get(row.productId());
            if (cover != null) {
                data.put("row." + index + ".coverImageId", Long.toString(cover.id()));
                data.put("row." + index + ".coverHash", cover.sha256());
            }
        }
        return data;
    }

    private void putSummary(Map<String, String> data, String prefix,
                            ShopReviewStore.ReviewSummary summary) {
        data.put(prefix + "ratingAverage", summary.average() == null ? ""
                : summary.average().setScale(1, RoundingMode.HALF_UP).toPlainString());
        data.put(prefix + "ratingCount", Integer.toString(summary.total()));
        if (prefix.isEmpty()) {
            for (int index = 0; index < 5; index++) {
                data.put("ratingStars." + (index + 1),
                        Integer.toString(summary.starCounts().get(index)));
            }
        }
    }

    private ShopReviewStore.ReviewSummary emptySummary() {
        return new ShopReviewStore.ReviewSummary(null, 0, java.util.List.of(0, 0, 0, 0, 0));
    }

    private String encodeOrder(ShopOrderRecord row) {
        return RowCodec.encode(Long.toString(row.id()), row.orderNo(),
                row.buyerUsername(), row.buyerDisplayName(),
                MoneyPolicy.format(row.totalAmount()), row.status().name(), row.createdAt().toString(),
                nullable(row.shippedAt()), nullable(row.completedAt()), nullable(row.cancelledAt()));
    }

    private ResponseMessage orderMutation(RequestMessage request, String message,
                                          ShopStore.OrderResult result) {
        return success(request, message, Map.of("orderId", Long.toString(result.orderId()),
                "orderNo", result.orderNo(), "totalAmount", MoneyPolicy.format(result.totalAmount()),
                "status", result.status().name()));
    }

    private ResponseMessage checkoutResult(RequestMessage request, ShopStore.CheckoutResult result) {
        return success(request, result.duplicate() ? "该订单已经处理" : "结算成功", Map.of(
                "orderId", Long.toString(result.orderId()), "orderNo", result.orderNo(),
                "totalAmount", MoneyPolicy.format(result.totalAmount()),
                "status", result.status().name(),
                "duplicate", Boolean.toString(result.duplicate())));
    }

    private Set<Long> selectedProductIds(RequestMessage request, Set<String> addressKeys) {
        int count = integer(request.parameters().get("selectedCount"), "所选商品数量");
        if (count < 1 || count > 100) throw new IllegalArgumentException("所选商品数量无效");
        Set<String> expectedKeys = new HashSet<>(Set.of(
                "sessionToken", "operationId", "selectedCount"));
        expectedKeys.addAll(addressKeys);
        Set<Long> selected = new HashSet<>();
        for (int index = 0; index < count; index++) {
            String key = "selected." + index;
            expectedKeys.add(key);
            long productId = positiveLong(request.parameters().get(key), "商品ID");
            if (!selected.add(productId)) throw new IllegalArgumentException("所选商品重复");
        }
        if (!request.parameters().keySet().equals(expectedKeys)) {
            throw new IllegalArgumentException("结算参数无效");
        }
        return Set.copyOf(selected);
    }

    private AddressParameters shippingAddress(RequestMessage request, String invalidMessage) {
        String mode = request.parameters().get("addressMode");
        if ("saved".equals(mode)) {
            Set<String> keys = Set.of("addressMode", "addressSlot", "addressVersion");
            String version = request.parameters().get("addressVersion");
            if (version == null || version.isBlank()) throw new IllegalArgumentException(invalidMessage);
            return new AddressParameters(new ShippingAddressChoice(
                    addressSlot(request.parameters().get("addressSlot")), version, null), keys);
        }
        if ("inline".equals(mode)) {
            Set<String> keys = Set.of("addressMode", "recipient", "phone", "address");
            try {
                return new AddressParameters(new ShippingAddressChoice(null, null,
                        new ShippingAddressValue(request.parameters().get("recipient"),
                                request.parameters().get("phone"),
                                request.parameters().get("address"))), keys);
            } catch (NullPointerException failure) {
                throw new IllegalArgumentException(invalidMessage);
            }
        }
        throw new IllegalArgumentException(invalidMessage);
    }

    private int addressSlot(String value) {
        int slot = integer(value, "地址槽位");
        if (slot < 1 || slot > 3) throw new IllegalArgumentException("地址槽位无效");
        return slot;
    }

    private Map<String, String> pageData(int page, int size, int total, int count) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("page", Integer.toString(page)); data.put("pageSize", Integer.toString(size));
        data.put("total", Integer.toString(total)); data.put("count", Integer.toString(count));
        return data;
    }

    private ResponseMessage success(RequestMessage request, String message, Map<String, String> data) {
        return ResponseMessage.success(request.requestId(), message, data);
    }

    private int page(RequestMessage request) {
        String value = request.parameters().get("page");
        if (value == null || value.isBlank()) return 1;
        int page = integer(value, "页码");
        if (page < 1) throw new IllegalArgumentException("页码无效");
        return page;
    }

    private int integer(String value, String label) {
        try { return Integer.parseInt(value == null ? "" : value.trim()); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(label + "无效"); }
    }

    private long positiveLong(String value, String label) {
        try {
            long parsed = Long.parseLong(value == null ? "" : value.trim());
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) { throw new IllegalArgumentException(label + "无效"); }
    }

    private Long optionalPositiveLong(String value, String label) {
        return value == null || value.isBlank() ? null : positiveLong(value, label);
    }

    private String operationId(String value) {
        try { return UUID.fromString(value == null ? "" : value.trim()).toString(); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("业务编号无效"); }
    }

    private String required(RequestMessage request, String key, String label) {
        String value = request.parameters().get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("请填写" + label);
        return value.trim();
    }

    private boolean strictBoolean(String value, String label) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(label + "无效");
        }
        return Boolean.parseBoolean(value);
    }

    private Boolean optionalBoolean(String value) {
        return value == null || value.isBlank() ? null : strictBoolean(value, "启用状态");
    }

    private ShopCategory optionalCategory(String value, ShopCategory fallback) {
        if (value == null) return fallback;
        try {
            return ShopCategory.parse(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("商品分类无效");
        }
    }

    private ShopProductSort optionalSort(String value) {
        if (value == null) return ShopProductSort.NEWEST;
        try {
            return ShopProductSort.valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("商品排序无效");
        }
    }

    private void requireOnly(RequestMessage request, Set<String> allowed, String message) {
        if (!allowed.containsAll(request.parameters().keySet())) {
            throw new IllegalArgumentException(message);
        }
    }

    private ShopOrderStatus optionalStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try { return ShopOrderStatus.valueOf(value.trim()); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("订单状态无效"); }
    }

    private String nullable(java.time.Instant value) { return value == null ? "" : value.toString(); }

    private record AddressParameters(ShippingAddressChoice choice, Set<String> keys) {
        private AddressParameters {
            keys = Set.copyOf(keys);
        }
    }

    @FunctionalInterface
    private interface Work { ResponseMessage run(UserSession session) throws SQLException; }
}
