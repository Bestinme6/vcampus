package com.vcampus.server.database;

import com.vcampus.common.model.ShopInventoryMovementType;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.MoneyPolicy;
import com.vcampus.server.model.ShopCartItemRecord;
import com.vcampus.server.model.ShopProductRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ShopRepository implements ShopStore {
    private static final String PRODUCT_COLUMNS =
            "id,sku,name,description,price,stock,enabled,created_at,updated_at";
    private final ConnectionFactory connections;
    private final BankPaymentWriter payments;
    private final NotificationWriter notifications;
    private static final DateTimeFormatter ORDER_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    public ShopRepository(ConnectionFactory connections, BankPaymentWriter payments,
                          NotificationWriter notifications) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
    }

    @Override
    public ProductPage searchProducts(ProductQuery query) throws SQLException {
        Objects.requireNonNull(query, "query");
        String where = " WHERE (?='' OR sku LIKE ? OR name LIKE ?)"
                + " AND (? IS NULL OR enabled=?)";
        String like = "%" + query.keyword() + "%";
        try (Connection connection = connections.openConnection()) {
            int total;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM shop_products" + where)) {
                bindProductQuery(statement, query, like);
                try (ResultSet result = statement.executeQuery()) {
                    result.next(); total = result.getInt(1);
                }
            }
            List<ShopProductRecord> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + PRODUCT_COLUMNS + " FROM shop_products" + where
                            + " ORDER BY id LIMIT ? OFFSET ?")) {
                bindProductQuery(statement, query, like);
                statement.setInt(6, query.pageSize());
                statement.setInt(7, (query.page() - 1) * query.pageSize());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) rows.add(mapProduct(result));
                }
            }
            return new ProductPage(rows, query.page(), query.pageSize(), total);
        }
    }

    @Override
    public ProductSaveResult saveProduct(long operatorId, ProductInput input) throws SQLException {
        positiveId(operatorId, "操作人无效");
        ValidProduct product = validate(input);
        try (Connection connection = connections.openConnection()) {
            if (input.productId() == null) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO shop_products(sku,name,description,price,enabled)"
                                + " VALUES(?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    bindProduct(statement, product);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Missing generated product id");
                        return new ProductSaveResult(keys.getLong(1));
                    }
                }
            }
            positiveId(input.productId(), "商品ID无效");
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE shop_products SET sku=?,name=?,description=?,price=?,enabled=? WHERE id=?")) {
                bindProduct(statement, product);
                statement.setLong(6, input.productId());
                if (statement.executeUpdate() != 1) throw new ShopRuleException("商品不存在");
                return new ProductSaveResult(input.productId());
            }
        }
    }

    @Override
    public boolean setProductEnabled(long operatorId, long productId, boolean enabled)
            throws SQLException {
        positiveId(operatorId, "操作人无效"); positiveId(productId, "商品ID无效");
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE shop_products SET enabled=? WHERE id=? AND enabled<>?")) {
            statement.setBoolean(1, enabled); statement.setLong(2, productId);
            statement.setBoolean(3, enabled);
            return statement.executeUpdate() == 1;
        }
    }

    @Override
    public InventoryResult adjustInventory(long operatorId, long productId, int delta,
                                           String reason) throws SQLException {
        positiveId(operatorId, "操作人无效"); positiveId(productId, "商品ID无效");
        if (delta == 0) throw new IllegalArgumentException("库存变动不能为零");
        String normalizedReason = text(reason, "请填写库存变动原因", 255);
        try (Connection connection = connections.openConnection()) {
            boolean autoCommit = connection.getAutoCommit(); connection.setAutoCommit(false);
            try {
                int stock = lockStock(connection, productId);
                int after;
                try { after = Math.addExact(stock, delta); }
                catch (ArithmeticException exception) { throw new ShopRuleException("库存数量超出范围"); }
                if (after < 0) throw new ShopRuleException("库存不足，当前仅剩 " + stock + " 件");
                boolean first = movementCount(connection, productId) == 0;
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE shop_products SET stock=? WHERE id=?")) {
                    statement.setInt(1, after); statement.setLong(2, productId);
                    statement.executeUpdate();
                }
                ShopInventoryMovementType type = first && delta > 0
                        ? ShopInventoryMovementType.INITIAL
                        : ShopInventoryMovementType.ADMIN_ADJUST;
                insertMovement(connection, productId, type, delta, after, null,
                        operatorId, normalizedReason);
                connection.commit();
                return new InventoryResult(productId, after);
            } catch (Exception exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    @Override
    public CartResult cart(long userId) throws SQLException {
        positiveId(userId, "用户无效");
        try (Connection connection = connections.openConnection()) {
            return cart(connection, userId);
        }
    }

    @Override
    public CartResult setCartQuantity(long userId, long productId, int quantity)
            throws SQLException {
        positiveId(userId, "用户无效"); positiveId(productId, "商品ID无效");
        if (quantity < 0) throw new IllegalArgumentException("商品数量无效");
        if (quantity == 0) return removeCartItem(userId, productId);
        try (Connection connection = connections.openConnection()) {
            requireProduct(connection, productId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO shop_cart_items(user_id,product_id,quantity) VALUES(?,?,?)"
                            + " ON DUPLICATE KEY UPDATE quantity=VALUES(quantity),"
                            + "updated_at=CURRENT_TIMESTAMP")) {
                statement.setLong(1, userId); statement.setLong(2, productId);
                statement.setInt(3, quantity); statement.executeUpdate();
            }
            return cart(connection, userId);
        }
    }

    @Override
    public CartResult removeCartItem(long userId, long productId) throws SQLException {
        positiveId(userId, "用户无效"); positiveId(productId, "商品ID无效");
        try (Connection connection = connections.openConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM shop_cart_items WHERE user_id=? AND product_id=?")) {
                statement.setLong(1, userId); statement.setLong(2, productId);
                statement.executeUpdate();
            }
            return cart(connection, userId);
        }
    }

    @Override
    public CheckoutResult checkout(long buyerUserId, String operationId) throws SQLException {
        positiveId(buyerUserId, "用户无效");
        String operation = operationId(operationId);
        try (Connection connection = connections.openConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                CheckoutResult existing = existingCheckout(connection, buyerUserId, operation);
                if (existing != null) {
                    connection.commit();
                    return existing;
                }
                List<CartLock> cart = lockCart(connection, buyerUserId);
                if (cart.isEmpty()) throw new ShopRuleException("购物车为空");
                List<CheckoutProduct> products = new ArrayList<>(cart.size());
                BigDecimal total = BigDecimal.ZERO.setScale(2);
                for (CartLock item : cart) {
                    CheckoutProduct product = lockCheckoutProduct(
                            connection, item.productId(), item.quantity());
                    products.add(product);
                    total = total.add(product.subtotal());
                }
                total = MoneyPolicy.parsePositive(total.toPlainString());
                String orderNo = orderNo();
                payments.debitForShop(connection, buyerUserId, total, orderNo,
                        "校园商店订单 " + orderNo);
                long orderId = insertOrder(connection, orderNo, buyerUserId, operation, total);
                for (CheckoutProduct product : products) {
                    int stockAfter = product.stock() - product.quantity();
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE shop_products SET stock=? WHERE id=?")) {
                        statement.setInt(1, stockAfter);
                        statement.setLong(2, product.productId());
                        statement.executeUpdate();
                    }
                    insertMovement(connection, product.productId(),
                            ShopInventoryMovementType.SALE, -product.quantity(), stockAfter,
                            orderId, buyerUserId, "订单销售 " + orderNo);
                    insertOrderItem(connection, orderId, product);
                    deleteLockedCartItem(connection, buyerUserId, product.productId());
                }
                connection.commit();
                return new CheckoutResult(orderId, orderNo, total, ShopOrderStatus.PAID, false);
            } catch (Exception exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    private CartResult cart(Connection connection, long userId) throws SQLException {
        List<ShopCartItemRecord> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT p.id,p.sku,p.name,p.description,p.price,p.stock,p.enabled,"
                        + "c.quantity,c.updated_at FROM shop_cart_items c "
                        + "JOIN shop_products p ON p.id=c.product_id WHERE c.user_id=?"
                        + " ORDER BY c.updated_at DESC,p.id")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    BigDecimal price = result.getBigDecimal("price").setScale(2);
                    int quantity = result.getInt("quantity");
                    BigDecimal subtotal = price.multiply(BigDecimal.valueOf(quantity)).setScale(2);
                    total = total.add(subtotal);
                    rows.add(new ShopCartItemRecord(result.getLong("id"),
                            result.getString("sku"), result.getString("name"),
                            result.getString("description"), price, quantity,
                            result.getInt("stock"), result.getBoolean("enabled"), subtotal,
                            instant(result.getTimestamp("updated_at"))));
                }
            }
        }
        return new CartResult(rows, total);
    }

    private CheckoutResult existingCheckout(Connection connection, long buyerUserId,
                                             String operation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,order_no,buyer_user_id,total_amount,status FROM shop_orders"
                        + " WHERE checkout_operation_id=? FOR UPDATE")) {
            statement.setString(1, operation);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                if (result.getLong("buyer_user_id") != buyerUserId) {
                    throw new ShopRuleException("该业务编号已被使用");
                }
                return new CheckoutResult(result.getLong("id"), result.getString("order_no"),
                        result.getBigDecimal("total_amount").setScale(2),
                        ShopOrderStatus.valueOf(result.getString("status")), true);
            }
        }
    }

    private List<CartLock> lockCart(Connection connection, long buyerUserId) throws SQLException {
        List<CartLock> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT product_id,quantity FROM shop_cart_items WHERE user_id=?"
                        + " ORDER BY product_id FOR UPDATE")) {
            statement.setLong(1, buyerUserId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(new CartLock(
                        result.getLong("product_id"), result.getInt("quantity")));
            }
        }
        return rows;
    }

    private CheckoutProduct lockCheckoutProduct(Connection connection, long productId,
                                                int quantity) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,sku,name,price,stock,enabled FROM shop_products WHERE id=? FOR UPDATE")) {
            statement.setLong(1, productId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new ShopRuleException("商品不存在");
                if (!result.getBoolean("enabled")) throw new ShopRuleException("商品已下架");
                int stock = result.getInt("stock");
                if (stock < quantity) {
                    throw new ShopRuleException("库存不足，当前仅剩 " + stock + " 件");
                }
                BigDecimal price = result.getBigDecimal("price").setScale(2);
                BigDecimal subtotal = price.multiply(BigDecimal.valueOf(quantity)).setScale(2);
                return new CheckoutProduct(result.getLong("id"), result.getString("sku"),
                        result.getString("name"), price, quantity, stock, subtotal);
            }
        }
    }

    private long insertOrder(Connection connection, String orderNo, long buyerUserId,
                             String operation, BigDecimal total) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO shop_orders(order_no,buyer_user_id,checkout_operation_id,"
                        + "total_amount,status) VALUES(?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, orderNo); statement.setLong(2, buyerUserId);
            statement.setString(3, operation); statement.setBigDecimal(4, total);
            statement.setString(5, ShopOrderStatus.PAID.name());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Missing generated order id");
                return keys.getLong(1);
            }
        }
    }

    private void insertOrderItem(Connection connection, long orderId,
                                 CheckoutProduct product) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO shop_order_items(order_id,product_id,sku_snapshot,name_snapshot,"
                        + "unit_price,quantity,subtotal) VALUES(?,?,?,?,?,?,?)")) {
            statement.setLong(1, orderId); statement.setLong(2, product.productId());
            statement.setString(3, product.sku()); statement.setString(4, product.name());
            statement.setBigDecimal(5, product.price()); statement.setInt(6, product.quantity());
            statement.setBigDecimal(7, product.subtotal()); statement.executeUpdate();
        }
    }

    private void deleteLockedCartItem(Connection connection, long buyerUserId, long productId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM shop_cart_items WHERE user_id=? AND product_id=?")) {
            statement.setLong(1, buyerUserId); statement.setLong(2, productId);
            statement.executeUpdate();
        }
    }

    private String operationId(String value) {
        try {
            return UUID.fromString(value == null ? "" : value.trim()).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("业务编号无效");
        }
    }

    private String orderNo() {
        return "SO" + ORDER_TIME.format(Instant.now())
                + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(java.util.Locale.ROOT);
    }

    private void bindProductQuery(PreparedStatement statement, ProductQuery query, String like)
            throws SQLException {
        statement.setString(1, query.keyword()); statement.setString(2, like);
        statement.setString(3, like);
        if (query.enabled() == null) {
            statement.setNull(4, Types.BOOLEAN); statement.setNull(5, Types.BOOLEAN);
        } else {
            statement.setBoolean(4, query.enabled()); statement.setBoolean(5, query.enabled());
        }
    }

    private void bindProduct(PreparedStatement statement, ValidProduct product) throws SQLException {
        statement.setString(1, product.sku()); statement.setString(2, product.name());
        statement.setString(3, product.description()); statement.setBigDecimal(4, product.price());
        statement.setBoolean(5, product.enabled());
    }

    private ValidProduct validate(ProductInput input) {
        Objects.requireNonNull(input, "input");
        String sku = text(input.sku(), "请填写货号", 64);
        String name = text(input.name(), "请填写商品名称", 120);
        String description = input.description() == null ? "" : input.description().trim();
        if (description.length() > 1000) throw new IllegalArgumentException("商品说明不能超过1000个字符");
        BigDecimal price = Objects.requireNonNull(input.price(), "price");
        if (price.signum() <= 0 || price.scale() > 2
                || price.compareTo(new BigDecimal("9999999999999.99")) > 0) {
            throw new IllegalArgumentException("商品价格无效");
        }
        return new ValidProduct(sku, name, description,
                price.setScale(2, RoundingMode.UNNECESSARY), input.enabled());
    }

    private ShopProductRecord mapProduct(ResultSet result) throws SQLException {
        return new ShopProductRecord(result.getLong("id"), result.getString("sku"),
                result.getString("name"), result.getString("description"),
                result.getBigDecimal("price").setScale(2), result.getInt("stock"),
                result.getBoolean("enabled"), instant(result.getTimestamp("created_at")),
                instant(result.getTimestamp("updated_at")));
    }

    private void requireProduct(Connection connection, long productId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM shop_products WHERE id=?")) {
            statement.setLong(1, productId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new ShopRuleException("商品不存在");
            }
        }
    }

    private int lockStock(Connection connection, long productId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT stock FROM shop_products WHERE id=? FOR UPDATE")) {
            statement.setLong(1, productId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new ShopRuleException("商品不存在");
                return result.getInt(1);
            }
        }
    }

    private int movementCount(Connection connection, long productId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM shop_inventory_movements WHERE product_id=?")) {
            statement.setLong(1, productId);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getInt(1); }
        }
    }

    private void insertMovement(Connection connection, long productId,
                                ShopInventoryMovementType type, int delta, int stockAfter,
                                Long orderId, Long operatorId, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO shop_inventory_movements(product_id,movement_type,quantity_delta,"
                        + "stock_after,order_id,operator_user_id,reason) VALUES(?,?,?,?,?,?,?)")) {
            statement.setLong(1, productId); statement.setString(2, type.name());
            statement.setInt(3, delta); statement.setInt(4, stockAfter);
            if (orderId == null) statement.setNull(5, Types.BIGINT); else statement.setLong(5, orderId);
            if (operatorId == null) statement.setNull(6, Types.BIGINT); else statement.setLong(6, operatorId);
            statement.setString(7, reason); statement.executeUpdate();
        }
    }

    private void positiveId(long id, String message) {
        if (id < 1) throw new IllegalArgumentException(message);
    }

    private String text(String value, String missing, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(missing);
        if (normalized.length() > maximum) throw new IllegalArgumentException("内容长度超过限制");
        return normalized;
    }

    private void rollback(Connection connection, Exception original) {
        try { connection.rollback(); } catch (SQLException failure) { original.addSuppressed(failure); }
    }

    private Instant instant(Timestamp timestamp) { return timestamp.toInstant(); }

    private record ValidProduct(String sku, String name, String description,
                                BigDecimal price, boolean enabled) {
    }

    private record CartLock(long productId, int quantity) {
    }

    private record CheckoutProduct(long productId, String sku, String name, BigDecimal price,
                                   int quantity, int stock, BigDecimal subtotal) {
    }
}
