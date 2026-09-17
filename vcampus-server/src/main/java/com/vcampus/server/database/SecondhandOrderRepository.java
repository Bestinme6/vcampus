package com.vcampus.server.database;

import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import com.vcampus.common.model.SecondhandItemStatus;
import com.vcampus.common.model.SecondhandModerationStatus;
import com.vcampus.common.model.ShippingAddressChoice;
import com.vcampus.common.model.ShippingAddressValue;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.server.database.NotificationWriter.NotificationDraft;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SecondhandOrderRepository implements SecondhandOrderStore {
    private static final String SELECT = "SELECT o.id,o.order_no,o.item_id,o.buyer_user_id,"
            + "o.seller_user_id,o.amount,o.status,e.status escrow_status,o.title_snapshot,"
            + "o.category_snapshot,o.condition_snapshot,o.seller_name_snapshot,"
            + "o.shipping_recipient,o.shipping_phone,o.shipping_address,o.created_at,"
            + "o.shipped_at,o.completed_at,o.cancelled_at FROM secondhand_orders o "
            + "JOIN secondhand_escrows e ON e.order_id=o.id";

    private final ConnectionFactory connections;
    private final SecondhandPaymentWriter payments;
    private final ShopAddressRepository addresses;
    private final NotificationWriter notifications;

    public SecondhandOrderRepository(ConnectionFactory connections,
                                     SecondhandPaymentWriter payments,
                                     ShopAddressRepository addresses,
                                     NotificationWriter notifications) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.addresses = Objects.requireNonNull(addresses, "addresses");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
    }

    @Override
    public BuyResult buy(long buyerId, long itemId, String operationId,
                         ShippingAddressChoice address) throws SQLException {
        positive(buyerId, "买家编号无效");
        positive(itemId, "物品编号无效");
        String operation = operation(operationId);
        Objects.requireNonNull(address, "请选择收货地址");
        return transaction(connection -> {
            ExistingPurchase existing = existingPurchase(connection, operation);
            if (existing != null) {
                if (existing.buyerId() != buyerId) {
                    throw new SecondhandRuleException("该业务编号已被使用");
                }
                return new BuyResult(existing.orderId(), existing.orderNo(), existing.status(), true);
            }
            LockedItem item = lockItem(connection, itemId);
            if (item.sellerId() == buyerId) throw new SecondhandRuleException("不能购买自己发布的物品");
            if (item.status() != SecondhandItemStatus.AVAILABLE
                    || item.moderation() != SecondhandModerationStatus.VISIBLE) {
                throw new SecondhandRuleException("该二手物品当前不可购买");
            }
            ShippingAddressValue resolved = addresses.resolve(connection, buyerId, address);
            String orderNo = "USED-" + UUID.randomUUID();
            long orderId = insertOrder(connection, orderNo, buyerId, operation, item, resolved);
            payments.hold(connection, buyerId, item.price(), orderNo);
            insertEscrow(connection, orderId, item.price());
            updateItemStatus(connection, itemId, SecondhandItemStatus.RESERVED);
            notifications.insertBatch(connection, List.of(
                    notice(buyerId, item.sellerId(), NotificationType.SECONDHAND_ORDER_PAID,
                            "二手订单支付成功", "订单 " + orderNo + " 已支付，资金由平台托管。", orderId),
                    notice(item.sellerId(), buyerId, NotificationType.SECONDHAND_ORDER_PAID,
                            "您的二手物品已售出", "订单 " + orderNo + " 等待您发货。", orderId)));
            return new BuyResult(orderId, orderNo, ShopOrderStatus.PAID, false);
        });
    }

    @Override
    public OrderDetail order(long requesterId, long orderId, boolean admin) throws SQLException {
        positive(requesterId, "用户编号无效");
        positive(orderId, "订单编号无效");
        try (Connection connection = connections.openConnection()) {
            OrderDetail row = findOrder(connection, orderId, false);
            requireAccess(row, requesterId, admin);
            return row;
        }
    }

    @Override
    public OrderPage search(long requesterId, boolean selling, int page, int pageSize)
            throws SQLException {
        positive(requesterId, "用户编号无效");
        paging(page, pageSize);
        String column = selling ? "o.seller_user_id" : "o.buyer_user_id";
        return queryPage(" WHERE " + column + "=?", List.of(requesterId), page, pageSize);
    }

    @Override
    public OrderPage searchAdmin(String keyword, ShopOrderStatus status, int page, int pageSize)
            throws SQLException {
        paging(page, pageSize);
        String term = Objects.requireNonNullElse(keyword, "").trim();
        if (term.length() > 100) throw new IllegalArgumentException("查询关键字过长");
        // Avoid SQL string-literal backslash semantics differing between MySQL and H2.
        String like = "%" + term.replace("!", "!!").replace("%", "!%")
                .replace("_", "!_") + "%";
        return queryPage(" WHERE (?='' OR o.order_no LIKE ? ESCAPE '!' OR "
                        + "o.title_snapshot LIKE ? ESCAPE '!') AND (? IS NULL OR o.status=?)",
                Arrays.asList(term, like, like, status == null ? null : status.name(),
                        status == null ? null : status.name()), page, pageSize);
    }

    @Override
    public OrderDetail cancel(long buyerId, long orderId) throws SQLException {
        return transition(orderId, row -> {
            if (row.buyerId() != buyerId || row.status() != ShopOrderStatus.PAID) {
                throw new SecondhandRuleException("只有买家可以取消待发货订单");
            }
        }, (connection, row) -> {
            requireHeld(row);
            payments.refund(connection, row.buyerId(), row.amount(), row.orderNo());
            markEscrow(connection, row.id(), EscrowStatus.REFUNDED);
            markOrder(connection, row.id(), ShopOrderStatus.CANCELLED);
            relistIfVisible(connection, row.itemId());
            notifications.insertBatch(connection, List.of(
                    notice(row.buyerId(), row.sellerId(), NotificationType.SECONDHAND_ORDER_REFUNDED,
                            "二手订单已退款", "订单 " + row.orderNo() + " 已取消并退款。", row.id()),
                    notice(row.sellerId(), row.buyerId(), NotificationType.SECONDHAND_ORDER_REFUNDED,
                            "二手订单已取消", "订单 " + row.orderNo() + " 已由买家取消。", row.id())));
        });
    }

    @Override
    public OrderDetail ship(long sellerId, long orderId) throws SQLException {
        return transition(orderId, row -> {
            if (row.sellerId() != sellerId || row.status() != ShopOrderStatus.PAID) {
                throw new SecondhandRuleException("只有卖家可以为待发货订单发货");
            }
        }, (connection, row) -> {
            requireHeld(row);
            markOrder(connection, row.id(), ShopOrderStatus.SHIPPED);
            notifications.insert(connection, notice(row.buyerId(), row.sellerId(),
                    NotificationType.SECONDHAND_ORDER_SHIPPED, "二手订单已发货",
                    "订单 " + row.orderNo() + " 已发货，请收货后确认。", row.id()));
        });
    }

    @Override
    public OrderDetail confirm(long buyerId, long orderId) throws SQLException {
        return transition(orderId, row -> {
            if (row.buyerId() != buyerId || row.status() != ShopOrderStatus.SHIPPED) {
                throw new SecondhandRuleException("只有买家可以确认已发货订单");
            }
        }, (connection, row) -> {
            requireHeld(row);
            payments.payout(connection, row.sellerId(), row.amount(), row.orderNo());
            markEscrow(connection, row.id(), EscrowStatus.SETTLED);
            markOrder(connection, row.id(), ShopOrderStatus.COMPLETED);
            updateItemStatus(connection, row.itemId(), SecondhandItemStatus.SOLD);
            notifications.insert(connection, notice(row.sellerId(), row.buyerId(),
                    NotificationType.SECONDHAND_ORDER_COMPLETED, "二手订单已完成",
                    "订单 " + row.orderNo() + " 已确认收货，款项已到账。", row.id()));
        });
    }

    @Override
    public OrderDetail resolve(long adminId, long orderId, Resolution resolution,
                               String operationId, String reason) throws SQLException {
        positive(adminId, "管理员编号无效");
        Objects.requireNonNull(resolution, "处理方式无效");
        String operation = operation(operationId);
        String normalizedReason = text(reason, 2, 255, "处理原因无效");
        return transaction(connection -> {
            ResolutionLog previous = resolutionLog(connection, operation);
            if (previous != null) {
                if (previous.orderId() != orderId || previous.resolution() != resolution) {
                    throw new SecondhandRuleException("该业务编号已被使用");
                }
                return findOrder(connection, orderId, false);
            }
            OrderDetail row = findOrder(connection, orderId, true);
            requireHeld(row);
            if (resolution == Resolution.SETTLE && row.status() != ShopOrderStatus.SHIPPED) {
                throw new SecondhandRuleException("交付前不能结算卖家款项");
            }
            if (resolution == Resolution.REFUND
                    && row.status() != ShopOrderStatus.PAID
                    && row.status() != ShopOrderStatus.SHIPPED) {
                throw new SecondhandRuleException("该订单当前不能退款");
            }
            if (resolution == Resolution.SETTLE) {
                payments.payout(connection, row.sellerId(), row.amount(), row.orderNo());
                markEscrow(connection, row.id(), EscrowStatus.SETTLED);
                markOrder(connection, row.id(), ShopOrderStatus.COMPLETED);
                updateItemStatus(connection, row.itemId(), SecondhandItemStatus.SOLD);
            } else {
                payments.refund(connection, row.buyerId(), row.amount(), row.orderNo());
                markEscrow(connection, row.id(), EscrowStatus.REFUNDED);
                markOrder(connection, row.id(), ShopOrderStatus.CANCELLED);
                relistIfVisible(connection, row.itemId());
            }
            insertResolution(connection, row.id(), adminId, operation, resolution, normalizedReason);
            return findOrder(connection, row.id(), false);
        });
    }

    private OrderDetail transition(long orderId, Check check, Change change) throws SQLException {
        positive(orderId, "订单编号无效");
        return transaction(connection -> {
            OrderDetail row = findOrder(connection, orderId, true);
            check.accept(row);
            change.apply(connection, row);
            return findOrder(connection, orderId, false);
        });
    }

    private OrderPage queryPage(String where, List<?> parameters, int page, int pageSize)
            throws SQLException {
        try (Connection connection = connections.openConnection()) {
            int total;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM secondhand_orders o" + where)) {
                bind(statement, parameters);
                try (ResultSet result = statement.executeQuery()) { result.next(); total = result.getInt(1); }
            }
            List<OrderDetail> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    SELECT + where + " ORDER BY o.created_at DESC,o.id DESC LIMIT ? OFFSET ?")) {
                int next = bind(statement, parameters);
                statement.setInt(next++, pageSize);
                statement.setInt(next, (page - 1) * pageSize);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) rows.add(map(result));
                }
            }
            return new OrderPage(rows, page, pageSize, total);
        }
    }

    private long insertOrder(Connection connection, String orderNo, long buyerId,
                             String operation, LockedItem item, ShippingAddressValue address)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO secondhand_orders(order_no,item_id,buyer_user_id,seller_user_id,"
                        + "purchase_operation_id,amount,title_snapshot,category_snapshot,"
                        + "condition_snapshot,price_snapshot,seller_name_snapshot,"
                        + "shipping_recipient,shipping_phone,shipping_address)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, orderNo); statement.setLong(2, item.id());
            statement.setLong(3, buyerId); statement.setLong(4, item.sellerId());
            statement.setString(5, operation); statement.setBigDecimal(6, item.price());
            statement.setString(7, item.title()); statement.setString(8, item.category());
            statement.setString(9, item.condition()); statement.setBigDecimal(10, item.price());
            statement.setString(11, item.sellerName()); statement.setString(12, address.recipient());
            statement.setString(13, address.phone()); statement.setString(14, address.address());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Missing secondhand order id");
                return keys.getLong(1);
            }
        }
    }

    private static void insertEscrow(Connection c, long orderId, BigDecimal amount) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "INSERT INTO secondhand_escrows(order_id,amount) VALUES(?,?)")) {
            s.setLong(1, orderId); s.setBigDecimal(2, amount); s.executeUpdate();
        }
    }

    private static LockedItem lockItem(Connection c, long itemId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT i.id,i.seller_user_id,u.display_name,i.title,i.category,i.condition_text,"
                        + "i.price,i.item_status,i.moderation_status FROM secondhand_items i "
                        + "JOIN users u ON u.id=i.seller_user_id WHERE i.id=? FOR UPDATE")) {
            s.setLong(1, itemId);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new SecondhandRuleException("二手物品不存在");
                return new LockedItem(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4),
                        r.getString(5), r.getString(6), r.getBigDecimal(7).setScale(2),
                        SecondhandItemStatus.valueOf(r.getString(8)),
                        SecondhandModerationStatus.valueOf(r.getString(9)));
            }
        }
    }

    private static ExistingPurchase existingPurchase(Connection c, String operation) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT id,buyer_user_id,order_no,status FROM secondhand_orders "
                        + "WHERE purchase_operation_id=?")) {
            s.setString(1, operation);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? new ExistingPurchase(r.getLong(1), r.getLong(2), r.getString(3),
                        ShopOrderStatus.valueOf(r.getString(4))) : null;
            }
        }
    }

    private static OrderDetail findOrder(Connection c, long orderId, boolean lock) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                SELECT + " WHERE o.id=?" + (lock ? " FOR UPDATE" : ""))) {
            s.setLong(1, orderId);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new SecondhandRuleException("二手订单不存在");
                return map(r);
            }
        }
    }

    private static OrderDetail map(ResultSet r) throws SQLException {
        return new OrderDetail(r.getLong("id"), r.getString("order_no"), r.getLong("item_id"),
                r.getLong("buyer_user_id"), r.getLong("seller_user_id"),
                r.getBigDecimal("amount").setScale(2), ShopOrderStatus.valueOf(r.getString("status")),
                EscrowStatus.valueOf(r.getString("escrow_status")), r.getString("title_snapshot"),
                r.getString("category_snapshot"), r.getString("condition_snapshot"),
                r.getString("seller_name_snapshot"), new ShippingAddressValue(
                        r.getString("shipping_recipient"), r.getString("shipping_phone"),
                        r.getString("shipping_address")), instant(r, "created_at"),
                instant(r, "shipped_at"), instant(r, "completed_at"), instant(r, "cancelled_at"));
    }

    private static void requireAccess(OrderDetail row, long requesterId, boolean admin) {
        if (!admin && row.buyerId() != requesterId && row.sellerId() != requesterId) {
            throw new SecondhandRuleException("无权查看该二手订单");
        }
    }

    private static void requireHeld(OrderDetail row) {
        if (row.escrowStatus() != EscrowStatus.HELD) {
            throw new SecondhandRuleException("该交易已经完成资金处理");
        }
    }

    private static void updateItemStatus(Connection c, long itemId, SecondhandItemStatus status)
            throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "UPDATE secondhand_items SET item_status=? WHERE id=?")) {
            s.setString(1, status.name()); s.setLong(2, itemId);
            if (s.executeUpdate() != 1) throw new SQLException("Secondhand item state update failed");
        }
    }

    private static void relistIfVisible(Connection c, long itemId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "UPDATE secondhand_items SET item_status='AVAILABLE' WHERE id=? "
                        + "AND item_status='RESERVED' AND moderation_status='VISIBLE'")) {
            s.setLong(1, itemId); s.executeUpdate();
        }
    }

    private static void markEscrow(Connection c, long orderId, EscrowStatus status) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "UPDATE secondhand_escrows SET status=? WHERE order_id=? AND status='HELD'")) {
            s.setString(1, status.name()); s.setLong(2, orderId);
            if (s.executeUpdate() != 1) throw new SecondhandRuleException("该交易已经完成资金处理");
        }
    }

    private static void markOrder(Connection c, long orderId, ShopOrderStatus status) throws SQLException {
        String timestamp = switch (status) {
            case SHIPPED -> ",shipped_at=CURRENT_TIMESTAMP";
            case COMPLETED -> ",completed_at=CURRENT_TIMESTAMP";
            case CANCELLED -> ",cancelled_at=CURRENT_TIMESTAMP";
            default -> "";
        };
        try (PreparedStatement s = c.prepareStatement(
                "UPDATE secondhand_orders SET status=?" + timestamp + " WHERE id=?")) {
            s.setString(1, status.name()); s.setLong(2, orderId);
            if (s.executeUpdate() != 1) throw new SQLException("Secondhand order state update failed");
        }
    }

    private static ResolutionLog resolutionLog(Connection c, String operation) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT order_id,action FROM secondhand_order_resolution_logs WHERE operation_id=?")) {
            s.setString(1, operation);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? new ResolutionLog(r.getLong(1), Resolution.valueOf(r.getString(2))) : null;
            }
        }
    }

    private static void insertResolution(Connection c, long orderId, long adminId, String operation,
                                         Resolution resolution, String reason) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "INSERT INTO secondhand_order_resolution_logs"
                        + "(order_id,operator_user_id,operation_id,action,reason) VALUES(?,?,?,?,?)")) {
            s.setLong(1, orderId); s.setLong(2, adminId); s.setString(3, operation);
            s.setString(4, resolution.name()); s.setString(5, reason); s.executeUpdate();
        }
    }

    private static NotificationDraft notice(long recipient, Long sender, NotificationType type,
                                             String title, String content, long orderId) {
        return new NotificationDraft(recipient, sender, type, NotificationSource.SHOP,
                title, content, NotificationTarget.SECONDHAND_ORDERS, orderId);
    }

    private <T> T transaction(Work<T> work) throws SQLException {
        try (Connection connection = connections.openConnection()) {
            boolean auto = connection.getAutoCommit(); connection.setAutoCommit(false);
            try {
                T result = work.run(connection); connection.commit(); return result;
            } catch (SQLException | RuntimeException error) {
                try { connection.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
                throw error;
            } finally { connection.setAutoCommit(auto); }
        }
    }

    private static int bind(PreparedStatement statement, List<?> values) throws SQLException {
        int index = 1;
        for (Object value : values) statement.setObject(index++, value);
        return index;
    }

    private static Instant instant(ResultSet r, String column) throws SQLException {
        Timestamp value = r.getTimestamp(column); return value == null ? null : value.toInstant();
    }

    private static String operation(String value) {
        try { return UUID.fromString(Objects.requireNonNullElse(value, "").trim()).toString(); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("业务编号无效"); }
    }

    private static String text(String value, int min, int max, String message) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.length() < min || normalized.length() > max
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private static void positive(long value, String message) {
        if (value < 1) throw new IllegalArgumentException(message);
    }

    private static void paging(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("分页参数无效");
    }

    private record LockedItem(long id, long sellerId, String sellerName, String title,
                              String category, String condition, BigDecimal price,
                              SecondhandItemStatus status, SecondhandModerationStatus moderation) { }
    private record ExistingPurchase(long orderId, long buyerId, String orderNo, ShopOrderStatus status) { }
    private record ResolutionLog(long orderId, Resolution resolution) { }
    @FunctionalInterface private interface Work<T> { T run(Connection connection) throws SQLException; }
    @FunctionalInterface private interface Check { void accept(OrderDetail row); }
    @FunctionalInterface private interface Change { void apply(Connection connection, OrderDetail row) throws SQLException; }
}
