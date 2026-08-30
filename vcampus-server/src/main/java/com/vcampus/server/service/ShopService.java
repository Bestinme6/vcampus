package com.vcampus.server.service;

import com.vcampus.common.model.AccessPolicy;
import com.vcampus.common.model.MoneyPolicy;
import com.vcampus.common.model.ModuleCode;
import com.vcampus.common.model.ShopAccessPolicy;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.ShopRuleException;
import com.vcampus.server.database.ShopStore;
import com.vcampus.server.database.ShopStore.CartResult;
import com.vcampus.server.database.ShopStore.OrderDetail;
import com.vcampus.server.database.ShopStore.OrderPage;
import com.vcampus.server.database.ShopStore.OrderQuery;
import com.vcampus.server.database.ShopStore.ProductInput;
import com.vcampus.server.database.ShopStore.ProductPage;
import com.vcampus.server.database.ShopStore.ProductQuery;
import com.vcampus.server.model.ShopCartItemRecord;
import com.vcampus.server.model.ShopOrderItemRecord;
import com.vcampus.server.model.ShopOrderRecord;
import com.vcampus.server.model.ShopProductRecord;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ShopService {
    private static final int PAGE_SIZE = 10;
    private final ShopStore shop;
    private final SessionManager sessions;

    public ShopService(ShopStore shop, SessionManager sessions) {
        this.shop = shop;
        this.sessions = sessions;
    }

    public ResponseMessage searchProducts(RequestMessage request) {
        return handle(request, false, session -> {
            Boolean enabled = ShopAccessPolicy.canManage(session.roles())
                    ? optionalBoolean(request.parameters().get("enabled")) : Boolean.TRUE;
            ProductPage result = shop.searchProducts(new ProductQuery(
                    request.parameters().get("keyword"), enabled, page(request), PAGE_SIZE));
            return success(request, "查询成功", productPage(result));
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
            var result = shop.checkout(session.userId(), operationId(
                    request.parameters().get("operationId")));
            return success(request, result.duplicate() ? "该订单已经处理" : "结算成功", Map.of(
                    "orderId", Long.toString(result.orderId()), "orderNo", result.orderNo(),
                    "totalAmount", MoneyPolicy.format(result.totalAmount()),
                    "status", result.status().name(),
                    "duplicate", Boolean.toString(result.duplicate())));
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
                        ShopAccessPolicy.canManage(session.roles())))));
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
            Long productId = optionalPositiveLong(request.parameters().get("productId"), "商品ID");
            var result = shop.saveProduct(session.userId(), new ProductInput(productId,
                    required(request, "sku", "货号"), required(request, "name", "商品名称"),
                    request.parameters().getOrDefault("description", ""),
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
        boolean allowed = administrative ? ShopAccessPolicy.canManage(session.get().roles())
                : AccessPolicy.canAccess(ModuleCode.SHOP, session.get().roles());
        if (!allowed) return ResponseMessage.failure(request.requestId(), administrative
                ? "无权执行商店管理操作" : "无权使用校园商店");
        try {
            return work.run(session.get());
        } catch (ShopRuleException | IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            System.err.println("Shop database operation failed: " + exception.getMessage());
            return ResponseMessage.failure(request.requestId(), "数据库操作失败，请稍后重试");
        }
    }

    private Map<String, String> productPage(ProductPage page) {
        Map<String, String> data = pageData(page.page(), page.pageSize(), page.total(), page.rows().size());
        for (int index = 0; index < page.rows().size(); index++) {
            ShopProductRecord row = page.rows().get(index);
            data.put("row." + index, RowCodec.encode(Long.toString(row.id()), row.sku(), row.name(),
                    row.description(), MoneyPolicy.format(row.price()), Integer.toString(row.stock()),
                    Boolean.toString(row.enabled()), row.createdAt().toString(), row.updatedAt().toString()));
        }
        return data;
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

    private Map<String, String> orderDetail(OrderDetail detail) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("order", encodeOrder(detail.order()));
        data.put("count", Integer.toString(detail.items().size()));
        for (int index = 0; index < detail.items().size(); index++) {
            ShopOrderItemRecord row = detail.items().get(index);
            data.put("row." + index, RowCodec.encode(Long.toString(row.id()),
                    Long.toString(row.productId()), row.skuSnapshot(), row.nameSnapshot(),
                    MoneyPolicy.format(row.unitPrice()), Integer.toString(row.quantity()),
                    MoneyPolicy.format(row.subtotal())));
        }
        return data;
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

    private ShopOrderStatus optionalStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try { return ShopOrderStatus.valueOf(value.trim()); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("订单状态无效"); }
    }

    private String nullable(java.time.Instant value) { return value == null ? "" : value.toString(); }

    @FunctionalInterface
    private interface Work { ResponseMessage run(UserSession session) throws SQLException; }
}
