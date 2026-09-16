package com.vcampus.server.service;

import com.vcampus.common.model.MoneyPolicy;
import com.vcampus.common.model.ShippingAddressChoice;
import com.vcampus.common.model.ShippingAddressValue;
import com.vcampus.common.model.ShopAccessPolicy;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.SecondhandOrderStore;
import com.vcampus.server.database.SecondhandOrderStore.OrderDetail;
import com.vcampus.server.database.SecondhandOrderStore.OrderPage;
import com.vcampus.server.database.SecondhandOrderStore.Resolution;
import com.vcampus.server.database.SecondhandRuleException;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class SecondhandOrderService {
    private static final int PAGE_SIZE = 12;
    private final SecondhandOrderStore orders;
    private final SessionManager sessions;

    public SecondhandOrderService(SecondhandOrderStore orders, SessionManager sessions) {
        this.orders = Objects.requireNonNull(orders, "orders");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public ResponseMessage buy(RequestMessage request) {
        return handle(request, session -> {
            var result = orders.buy(session.userId(), positiveLong(request, "itemId", "物品ID"),
                    request.parameters().get("operationId"), address(request));
            return ResponseMessage.success(request.requestId(),
                    result.duplicate() ? "已返回原二手订单" : "二手订单支付成功",
                    Map.of("orderId", Long.toString(result.orderId()), "orderNo", result.orderNo(),
                            "status", result.status().name(),
                            "duplicate", Boolean.toString(result.duplicate())));
        });
    }

    public ResponseMessage search(RequestMessage request) {
        return handle(request, session -> page(request, orders.search(session.userId(),
                strictBoolean(request.parameters().get("selling"), "订单方向"), pageNo(request), PAGE_SIZE)));
    }

    public ResponseMessage get(RequestMessage request) {
        return handle(request, session -> detail(request,
                orders.order(session.userId(), positiveLong(request, "orderId", "订单ID"), false)));
    }

    public ResponseMessage cancel(RequestMessage request) {
        return handle(request, session -> detail(request,
                orders.cancel(session.userId(), positiveLong(request, "orderId", "订单ID"))));
    }

    public ResponseMessage ship(RequestMessage request) {
        return handle(request, session -> detail(request,
                orders.ship(session.userId(), positiveLong(request, "orderId", "订单ID"))));
    }

    public ResponseMessage confirm(RequestMessage request) {
        return handle(request, session -> detail(request,
                orders.confirm(session.userId(), positiveLong(request, "orderId", "订单ID"))));
    }

    public ResponseMessage searchAdmin(RequestMessage request) {
        return handle(request, session -> {
            requireAdmin(session);
            return page(request, orders.searchAdmin(request.parameters().get("keyword"),
                    optionalStatus(request.parameters().get("status")), pageNo(request), PAGE_SIZE));
        });
    }

    public ResponseMessage getAdmin(RequestMessage request) {
        return handle(request, session -> {
            requireAdmin(session);
            return detail(request, orders.order(session.userId(),
                    positiveLong(request, "orderId", "订单ID"), true));
        });
    }

    public ResponseMessage resolve(RequestMessage request) {
        return handle(request, session -> {
            requireAdmin(session);
            Resolution resolution;
            try { resolution = Resolution.valueOf(value(request, "resolution")); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException("处理方式无效"); }
            return detail(request, orders.resolve(session.userId(),
                    positiveLong(request, "orderId", "订单ID"), resolution,
                    request.parameters().get("operationId"), request.parameters().get("reason")));
        });
    }

    private static ResponseMessage page(RequestMessage request, OrderPage page) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("page", Integer.toString(page.page()));
        data.put("pageSize", Integer.toString(page.pageSize()));
        data.put("total", Integer.toString(page.total()));
        data.put("count", Integer.toString(page.rows().size()));
        for (int i = 0; i < page.rows().size(); i++) data.put("row." + i, encode(page.rows().get(i)));
        return ResponseMessage.success(request.requestId(), "二手订单查询成功", data);
    }

    private static ResponseMessage detail(RequestMessage request, OrderDetail row) {
        return ResponseMessage.success(request.requestId(), "二手订单操作成功",
                Map.of("row", encode(row)));
    }

    private static String encode(OrderDetail row) {
        return RowCodec.encode(Long.toString(row.id()), row.orderNo(), Long.toString(row.itemId()),
                Long.toString(row.buyerId()), Long.toString(row.sellerId()),
                MoneyPolicy.format(row.amount()), row.status().name(), row.escrowStatus().name(),
                row.title(), row.category(), row.condition(), row.sellerName(),
                row.shippingAddress().recipient(), row.shippingAddress().phone(),
                row.shippingAddress().address(), row.createdAt().toString(), nullable(row.shippedAt()),
                nullable(row.completedAt()), nullable(row.cancelledAt()));
    }

    private static ShippingAddressChoice address(RequestMessage request) {
        String mode = request.parameters().get("addressMode");
        if ("saved".equals(mode)) {
            int slot = integer(request.parameters().get("addressSlot"), "地址槽位");
            if (slot < 1 || slot > 3) throw new IllegalArgumentException("地址槽位无效");
            return new ShippingAddressChoice(slot, request.parameters().get("addressVersion"), null);
        }
        if ("inline".equals(mode)) {
            try {
                return new ShippingAddressChoice(null, null, new ShippingAddressValue(
                        request.parameters().get("recipient"), request.parameters().get("phone"),
                        request.parameters().get("address")));
            } catch (NullPointerException error) {
                throw new IllegalArgumentException("请填写完整收货地址");
            }
        }
        throw new IllegalArgumentException("请选择收货地址");
    }

    private ResponseMessage handle(RequestMessage request, Work work) {
        Optional<UserSession> session = sessions.find(request.parameters().get("sessionToken"));
        if (session.isEmpty()) return ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录");
        try { return work.run(session.get()); }
        catch (SecondhandRuleException | IllegalArgumentException error) {
            return ResponseMessage.failure(request.requestId(), error.getMessage());
        } catch (SQLException error) {
            System.err.println("Secondhand order database operation failed: " + error.getMessage());
            return ResponseMessage.failure(request.requestId(), "二手订单暂不可用，请稍后重试");
        }
    }

    private static void requireAdmin(UserSession session) {
        if (!ShopAccessPolicy.canManage(session.roles())) throw new SecondhandRuleException("无权管理二手订单");
    }

    private static int pageNo(RequestMessage request) {
        String value = request.parameters().get("page");
        if (value == null || value.isBlank()) return 1;
        int page = integer(value, "页码");
        if (page < 1) throw new IllegalArgumentException("页码无效");
        return page;
    }

    private static long positiveLong(RequestMessage request, String key, String label) {
        try {
            long result = Long.parseLong(value(request, key));
            if (result < 1) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException error) { throw new IllegalArgumentException(label + "无效"); }
    }

    private static int integer(String value, String label) {
        try { return Integer.parseInt(Objects.requireNonNullElse(value, "").trim()); }
        catch (NumberFormatException error) { throw new IllegalArgumentException(label + "无效"); }
    }

    private static boolean strictBoolean(String value, String label) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(label + "无效");
        }
        return Boolean.parseBoolean(value);
    }

    private static ShopOrderStatus optionalStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try { return ShopOrderStatus.valueOf(value.trim()); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("订单状态无效"); }
    }

    private static String value(RequestMessage request, String key) {
        return Objects.requireNonNullElse(request.parameters().get(key), "").trim();
    }

    private static String nullable(java.time.Instant value) { return value == null ? "" : value.toString(); }

    @FunctionalInterface private interface Work { ResponseMessage run(UserSession session) throws SQLException; }
}
