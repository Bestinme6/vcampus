package com.vcampus.client.ui;

import com.vcampus.common.model.ShopAccessPolicy;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ShopViewData {
    private static final String MALFORMED = "服务器返回的商店数据格式不正确";

    private ShopViewData() {
    }

    static boolean showAdminTabs(Set<UserRole> roles) {
        return ShopAccessPolicy.canManage(roles);
    }

    static boolean canCancel(ShopOrderStatus status) {
        return status == ShopOrderStatus.PAID;
    }

    static boolean canConfirm(ShopOrderStatus status) {
        return status == ShopOrderStatus.SHIPPED;
    }

    static boolean canShip(ShopOrderStatus status) {
        return status == ShopOrderStatus.PAID;
    }

    static ProductPage productPage(ResponseMessage response) {
        try {
            Map<String, String> data = data(response);
            PageHeader header = header(data);
            List<ProductRow> rows = new ArrayList<>(header.count());
            for (int index = 0; index < header.count(); index++) {
                List<String> fields = row(data, index, 9);
                rows.add(new ProductRow(positiveLong(fields.get(0)), fields.get(1), fields.get(2),
                        fields.get(3), money(fields.get(4)), nonNegativeInt(fields.get(5)),
                        strictBoolean(fields.get(6)), Instant.parse(fields.get(7)),
                        Instant.parse(fields.get(8))));
            }
            return new ProductPage(rows, header.page(), header.pageSize(), header.total());
        } catch (ResponseFailure failure) {
            throw new IllegalArgumentException(failure.getMessage(), failure);
        } catch (RuntimeException exception) {
            throw malformed(exception);
        }
    }

    static CartView cart(ResponseMessage response) {
        try {
            Map<String, String> data = data(response);
            int count = nonNegativeInt(required(data, "count"));
            List<CartRow> rows = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                List<String> fields = row(data, index, 10);
                rows.add(new CartRow(positiveLong(fields.get(0)), fields.get(1), fields.get(2),
                        fields.get(3), money(fields.get(4)), positiveInt(fields.get(5)),
                        nonNegativeInt(fields.get(6)), strictBoolean(fields.get(7)),
                        money(fields.get(8)), Instant.parse(fields.get(9))));
            }
            return new CartView(rows, money(required(data, "estimatedTotal")));
        } catch (ResponseFailure failure) {
            throw new IllegalArgumentException(failure.getMessage(), failure);
        } catch (RuntimeException exception) {
            throw malformed(exception);
        }
    }

    static OrderPage orderPage(ResponseMessage response) {
        try {
            Map<String, String> data = data(response);
            PageHeader header = header(data);
            List<OrderRow> rows = new ArrayList<>(header.count());
            for (int index = 0; index < header.count(); index++) {
                rows.add(parseOrder(row(data, index, 11)));
            }
            return new OrderPage(rows, header.page(), header.pageSize(), header.total());
        } catch (ResponseFailure failure) {
            throw new IllegalArgumentException(failure.getMessage(), failure);
        } catch (RuntimeException exception) {
            throw malformed(exception);
        }
    }

    static OrderDetail orderDetail(ResponseMessage response) {
        try {
            Map<String, String> data = data(response);
            List<String> orderFields = RowCodec.decode(required(data, "order"));
            if (orderFields.size() != 11) throw new IllegalArgumentException("invalid order");
            int count = nonNegativeInt(required(data, "count"));
            List<OrderItem> items = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                List<String> fields = row(data, index, 7);
                items.add(new OrderItem(positiveLong(fields.get(0)), positiveLong(fields.get(1)),
                        fields.get(2), fields.get(3), money(fields.get(4)),
                        positiveInt(fields.get(5)), money(fields.get(6))));
            }
            return new OrderDetail(parseOrder(orderFields), items);
        } catch (ResponseFailure failure) {
            throw new IllegalArgumentException(failure.getMessage(), failure);
        } catch (RuntimeException exception) {
            throw malformed(exception);
        }
    }

    static ResponseMessage requireSuccess(ResponseMessage response) {
        if (response == null) throw new IllegalArgumentException(MALFORMED);
        if (!response.success()) throw new IllegalArgumentException(response.message());
        return response;
    }

    private static OrderRow parseOrder(List<String> fields) {
        return new OrderRow(positiveLong(fields.get(0)), fields.get(1),
                positiveLong(fields.get(2)), fields.get(3), fields.get(4), money(fields.get(5)),
                ShopOrderStatus.valueOf(fields.get(6)), Instant.parse(fields.get(7)),
                nullableInstant(fields.get(8)), nullableInstant(fields.get(9)),
                nullableInstant(fields.get(10)));
    }

    private static Map<String, String> data(ResponseMessage response) {
        if (response == null) throw new IllegalArgumentException("null response");
        if (!response.success()) throw new ResponseFailure(response.message());
        return response.data();
    }

    private static PageHeader header(Map<String, String> data) {
        int page = positiveInt(required(data, "page"));
        int pageSize = positiveInt(required(data, "pageSize"));
        int total = nonNegativeInt(required(data, "total"));
        int count = nonNegativeInt(required(data, "count"));
        if (count > pageSize || count > total) throw new IllegalArgumentException("invalid count");
        return new PageHeader(page, pageSize, total, count);
    }

    private static List<String> row(Map<String, String> data, int index, int size) {
        List<String> fields = RowCodec.decode(required(data, "row." + index));
        if (fields.size() != size) throw new IllegalArgumentException("invalid row size");
        return fields;
    }

    private static String required(Map<String, String> data, String key) {
        String value = data.get(key);
        if (value == null) throw new IllegalArgumentException("missing " + key);
        return value;
    }

    private static BigDecimal money(String value) {
        BigDecimal parsed = new BigDecimal(value);
        if (parsed.signum() < 0 || parsed.scale() > 2) throw new IllegalArgumentException("invalid money");
        return parsed.setScale(2);
    }

    private static int positiveInt(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 1) throw new IllegalArgumentException("invalid positive int");
        return parsed;
    }

    private static int nonNegativeInt(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 0) throw new IllegalArgumentException("invalid non-negative int");
        return parsed;
    }

    private static long positiveLong(String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 1) throw new IllegalArgumentException("invalid positive long");
        return parsed;
    }

    private static boolean strictBoolean(String value) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("invalid boolean");
        }
        return Boolean.parseBoolean(value);
    }

    private static Instant nullableInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static IllegalArgumentException malformed(RuntimeException exception) {
        return new IllegalArgumentException(MALFORMED, exception);
    }

    record ProductRow(long id, String sku, String name, String description, BigDecimal price,
                      int stock, boolean enabled, Instant createdAt, Instant updatedAt) {
    }

    record CartRow(long productId, String sku, String name, String description,
                   BigDecimal unitPrice, int quantity, int stock, boolean enabled,
                   BigDecimal subtotal, Instant updatedAt) {
    }

    record OrderRow(long id, String orderNo, long buyerUserId, String buyerUsername,
                    String buyerDisplayName, BigDecimal totalAmount, ShopOrderStatus status,
                    Instant createdAt, Instant shippedAt, Instant completedAt,
                    Instant cancelledAt) {
    }

    record OrderItem(long id, long productId, String sku, String name, BigDecimal unitPrice,
                     int quantity, BigDecimal subtotal) {
    }

    record ProductPage(List<ProductRow> rows, int page, int pageSize, int total) {
        ProductPage { rows = List.copyOf(rows); }
    }

    record CartView(List<CartRow> rows, BigDecimal estimatedTotal) {
        CartView { rows = List.copyOf(rows); }
    }

    record OrderPage(List<OrderRow> rows, int page, int pageSize, int total) {
        OrderPage { rows = List.copyOf(rows); }
    }

    record OrderDetail(OrderRow order, List<OrderItem> items) {
        OrderDetail { items = List.copyOf(items); }
    }

    private record PageHeader(int page, int pageSize, int total, int count) {
    }

    private static final class ResponseFailure extends RuntimeException {
        private ResponseFailure(String message) { super(message); }
    }
}
