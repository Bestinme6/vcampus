package com.vcampus.server.database;

import com.vcampus.common.model.ShopInventoryMovementType;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.ShopProductSort;
import com.vcampus.common.model.MoneyPolicy;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import com.vcampus.server.model.ShopCartItemRecord;
import com.vcampus.server.model.ShopOrderItemRecord;
import com.vcampus.server.model.ShopOrderRecord;
import com.vcampus.server.model.ShopProductRecord;
import com.vcampus.server.model.ShopProductImageRecord;
import com.vcampus.server.database.NotificationWriter.NotificationDraft;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ShopRepository implements ShopStore {
    @FunctionalInterface
    interface ConnectionProvider {
        Connection openConnection() throws SQLException;
    }

    private static final String PRODUCT_COLUMNS =
            "p.id,p.sku,p.name,p.description,p.category,p.price,p.stock,p.enabled,"
                    + "p.created_at,p.updated_at";
    private final ConnectionProvider connections;
    private final BankPaymentWriter payments;
    private final NotificationWriter notifications;
    private static final DateTimeFormatter ORDER_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    public ShopRepository(ConnectionFactory connections, BankPaymentWriter payments,
                          NotificationWriter notifications) {
        this(Objects.requireNonNull(connections, "connections")::openConnection,
                payments, notifications);
    }

    ShopRepository(ConnectionProvider connections, BankPaymentWriter payments,
                   NotificationWriter notifications) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
    }

    @Override
    public ProductPage searchProducts(ProductQuery query) throws SQLException {
        Objects.requireNonNull(query, "query");
        String where = " WHERE (?='' OR p.sku LIKE ? OR p.name LIKE ?)"
                + " AND (? IS NULL OR p.category=?) AND (? IS NULL OR p.enabled=?)";
        String like = "%" + query.keyword() + "%";
        try (Connection connection = connections.openConnection()) {
            int originalIsolation = connection.getTransactionIsolation();
            boolean originalAutoCommit = connection.getAutoCommit();
            Exception operationFailure = null;
            try {
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setAutoCommit(false);
                int total;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM shop_products p" + where)) {
                    bindProductQuery(statement, query, like);
                    try (ResultSet result = statement.executeQuery()) {
                        result.next(); total = result.getInt(1);
                    }
                }
                List<ShopProductRecord> rows = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT " + PRODUCT_COLUMNS + " FROM shop_products p" + where
                                + " ORDER BY " + productSort(query.sort())
                                + " LIMIT ? OFFSET ?")) {
                    bindProductQuery(statement, query, like);
                    statement.setInt(8, query.pageSize());
                    statement.setInt(9, (query.page() - 1) * query.pageSize());
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) rows.add(mapProduct(result));
                    }
                }
                Set<Long> productIds = new LinkedHashSet<>();
                for (ShopProductRecord row : rows) productIds.add(row.id());
                ProductPage page = new ProductPage(rows, query.page(), query.pageSize(), total,
                        coverImages(connection, productIds));
                connection.commit();
                return page;
            } catch (SQLException exception) {
                operationFailure = exception;
                rollback(connection, exception);
                throw exception;
            } catch (RuntimeException exception) {
                operationFailure = exception;
                rollback(connection, exception);
                throw exception;
            } finally {
                restoreCatalogConnectionState(connection, originalIsolation, originalAutoCommit,
                        operationFailure);
            }
        }
    }

    @Override
    public ProductDetail product(long productId, boolean includeDisabled) throws SQLException {
        positiveId(productId, "商品ID无效");
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + PRODUCT_COLUMNS + " FROM shop_products p"
                             + " WHERE p.id=? AND (? OR p.enabled=TRUE)")) {
            statement.setLong(1, productId);
            statement.setBoolean(2, includeDisabled);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new ShopRuleException("商品不存在");
                return new ProductDetail(mapProduct(result), productImages(connection, productId));
            }
        }
    }

    @Override
    public List<ShopProductImageRecord> productImages(long productId) throws SQLException {
        positiveId(productId, "商品ID无效");
        try (Connection connection = connections.openConnection()) {
            return productImages(connection, productId);
        }
    }

    @Override
    public Map<Long, ShopProductImageRecord> coverImages(Set<Long> productIds) throws SQLException {
        Objects.requireNonNull(productIds, "productIds");
        if (productIds.isEmpty()) return Map.of();
        try (Connection connection = connections.openConnection()) {
            return coverImages(connection, productIds);
        }
    }

    @Override
    public ShopProductImageRecord productImage(long imageId, boolean includeDisabled)
            throws SQLException {
        positiveId(imageId, "\u56fe\u7247ID\u65e0\u6548");
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT i.id,i.product_id,i.storage_key,i.thumbnail_storage_key,i.mime_type,"
                             + "i.byte_size,i.sha256,i.sort_order,i.is_cover,i.created_at,i.updated_at"
                             + " FROM shop_product_images i JOIN shop_products p ON p.id=i.product_id"
                             + " WHERE i.id=? AND (? OR p.enabled=TRUE)")) {
            statement.setLong(1, imageId);
            statement.setBoolean(2, includeDisabled);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new ShopRuleException("\u56fe\u7247\u4e0d\u5b58\u5728");
                return mapProductImage(result);
            }
        }
    }

    @Override
    public Set<String> productImageStorageKeys() throws SQLException {
        Set<String> keys = new LinkedHashSet<>();
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT storage_key,thumbnail_storage_key FROM shop_product_images");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                keys.add(result.getString("storage_key"));
                keys.add(result.getString("thumbnail_storage_key"));
            }
        }
        return Set.copyOf(keys);
    }

    @Override
    public ImageCommitResult replaceProductImages(long operatorId, long productId,
                                                   Map<String, FinalizedUpload> finalizedUploads,
                                                   ImagePlan plan) throws SQLException {
        positiveId(operatorId, "操作人无效");
        positiveId(productId, "商品ID无效");
        Objects.requireNonNull(finalizedUploads, "finalizedUploads");
        Objects.requireNonNull(plan, "plan");
        Connection connection = connections.openConnection();
        boolean autoCommit = true;
        boolean autoCommitKnown = false;
        boolean committed = false;
        Exception operationFailure = null;
        try {
            autoCommit = connection.getAutoCommit();
            autoCommitKnown = true;
            connection.setAutoCommit(false);
            requireImageManager(connection, operatorId);
            lockProduct(connection, productId);
            List<ShopProductImageRecord> current = lockProductImages(connection, productId);
            validateImagePlan(productId, current, finalizedUploads, plan);

            Set<Long> retainedIds = new HashSet<>();
            for (ImagePlanItem item : plan.items()) {
                if (item.existingImageId() != null) retainedIds.add(item.existingImageId());
            }
            Set<String> deletedKeys = new LinkedHashSet<>();
            for (ShopProductImageRecord image : current) {
                if (!retainedIds.contains(image.id())) {
                    deletedKeys.add(image.storageKey());
                    deletedKeys.add(image.thumbnailStorageKey());
                }
            }

            clearImageOrdering(connection, productId);
            deleteRemovedImages(connection, productId, retainedIds);
            for (int index = 0; index < plan.items().size(); index++) {
                ImagePlanItem item = plan.items().get(index);
                if (item.existingImageId() != null) {
                    updateExistingImage(connection, item.existingImageId(), index, item.cover());
                } else {
                    insertFinalizedImage(connection, productId,
                            finalizedUploads.get(item.uploadId()), index, item.cover());
                }
            }
            List<ShopProductImageRecord> images = productImages(connection, productId);
            ImageCommitResult result = new ImageCommitResult(
                    imageKeys(images), deletedKeys, images);
            connection.commit();
            committed = true;
            return result;
        } catch (SQLException | RuntimeException exception) {
            operationFailure = exception;
            if (!committed) rollback(connection, exception);
            throw exception;
        } finally {
            SQLException cleanupFailure = null;
            if (autoCommitKnown) {
                try {
                    connection.setAutoCommit(autoCommit);
                } catch (SQLException exception) {
                    cleanupFailure = exception;
                }
            }
            try {
                connection.close();
            } catch (SQLException exception) {
                if (cleanupFailure == null) cleanupFailure = exception;
                else cleanupFailure.addSuppressed(exception);
            }
            if (cleanupFailure != null) {
                if (committed) {
                    System.err.println("Shop image connection cleanup failed after commit: "
                            + cleanupFailure.getMessage());
                } else if (operationFailure != null) {
                    operationFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    @Override
    public ProductSaveResult saveProduct(long operatorId, ProductInput input) throws SQLException {
        positiveId(operatorId, "操作人无效");
        ValidProduct product = validate(input);
        try (Connection connection = connections.openConnection()) {
            if (input.productId() == null) {
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    long productId;
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO shop_products(sku,name,description,category,price,enabled)"
                                    + " VALUES(?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                        statement.setString(1, pendingSku());
                        bindProductValues(statement, product, 2);
                        statement.executeUpdate();
                        try (ResultSet keys = statement.getGeneratedKeys()) {
                            if (!keys.next()) throw new SQLException("Missing generated product id");
                            productId = keys.getLong(1);
                        }
                    }
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE shop_products SET sku=? WHERE id=?")) {
                        statement.setString(1, generatedSku(productId));
                        statement.setLong(2, productId);
                        if (statement.executeUpdate() != 1) {
                            throw new SQLException("Generated product sku update failed");
                        }
                    }
                    connection.commit();
                    return new ProductSaveResult(productId);
                } catch (Exception exception) {
                    rollback(connection, exception);
                    throw exception;
                } finally {
                    connection.setAutoCommit(autoCommit);
                }
            }
            positiveId(input.productId(), "商品ID无效");
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE shop_products SET name=?,description=?,category=?,price=?,enabled=?"
                            + " WHERE id=?")) {
                bindProductValues(statement, product, 1);
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
        return checkoutCart(buyerUserId, operationId, null, true);
    }

    @Override
    public CheckoutResult checkoutCart(long buyerUserId, String operationId,
                                       Set<Long> selectedProductIds) throws SQLException {
        return checkoutCart(buyerUserId, operationId, selectedProductIds, false);
    }

    private CheckoutResult checkoutCart(long buyerUserId, String operationId,
                                        Set<Long> selectedProductIds, boolean allCartItems)
            throws SQLException {
        return checkout(buyerUserId, operationId, CheckoutMode.CART,
                selectedProductIds, 0, allCartItems);
    }

    @Override
    public CheckoutResult buyNow(long buyerUserId, String operationId, long productId, int quantity)
            throws SQLException {
        return checkout(buyerUserId, operationId, CheckoutMode.DIRECT,
                Set.of(productId), quantity, false);
    }

    private CheckoutResult checkout(long buyerUserId, String operationId, CheckoutMode mode,
                                    Set<Long> selectedProductIds, int directQuantity,
                                    boolean allCartItems) throws SQLException {
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
                List<CartLock> items;
                if (mode == CheckoutMode.DIRECT) {
                    long productId = singleProductId(selectedProductIds);
                    if (directQuantity < 1 || directQuantity > 999) {
                        throw new IllegalArgumentException("商品数量无效");
                    }
                    items = List.of(new CartLock(productId, directQuantity));
                } else {
                    List<Long> selection = allCartItems ? null : selectedProductIds(selectedProductIds);
                    items = lockCart(connection, buyerUserId, selection);
                    if (items.isEmpty()) throw new ShopRuleException("购物车为空");
                    if (selection != null && items.size() != selection.size()) {
                        throw new ShopRuleException("所选商品不在购物车中");
                    }
                }
                List<CheckoutProduct> products = new ArrayList<>(items.size());
                BigDecimal total = BigDecimal.ZERO.setScale(2);
                for (CartLock item : items) {
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
                    if (mode == CheckoutMode.CART) {
                        deleteLockedCartItem(connection, buyerUserId, product.productId());
                    }
                }
                notifications.insert(connection, new NotificationDraft(
                        buyerUserId, null, NotificationType.SHOP_ORDER_PAID,
                        NotificationSource.SHOP, "您的校园商店订单支付成功",
                        "订单 " + orderNo + " 已支付成功，实付 "
                                + MoneyPolicy.format(total) + " 元。",
                        NotificationTarget.SHOP_ORDERS, orderId));
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

    @Override
    public OrderPage searchOrders(OrderQuery query) throws SQLException {
        Objects.requireNonNull(query, "query");
        String where = " WHERE (? IS NULL OR o.buyer_user_id=?)"
                + " AND (?='' OR o.order_no LIKE ? OR u.username LIKE ?)"
                + " AND (? IS NULL OR o.status=?)";
        try (Connection connection = connections.openConnection()) {
            int total;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM shop_orders o "
                            + "JOIN users u ON u.id=o.buyer_user_id" + where)) {
                bindOrderQuery(statement, query);
                try (ResultSet result = statement.executeQuery()) {
                    result.next(); total = result.getInt(1);
                }
            }
            List<ShopOrderRecord> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    orderSelect() + where + " ORDER BY o.created_at DESC,o.id DESC LIMIT ? OFFSET ?")) {
                bindOrderQuery(statement, query);
                statement.setInt(8, query.pageSize());
                statement.setInt(9, (query.page() - 1) * query.pageSize());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) rows.add(mapOrder(result));
                }
            }
            return new OrderPage(rows, query.page(), query.pageSize(), total);
        }
    }

    @Override
    public OrderDetail order(long requesterId, long orderId, boolean admin) throws SQLException {
        positiveId(requesterId, "用户无效"); positiveId(orderId, "订单ID无效");
        try (Connection connection = connections.openConnection()) {
            ShopOrderRecord order = findOrder(connection, orderId, false);
            requireOrderAccess(order, requesterId, admin);
            return new OrderDetail(order, orderItems(connection, orderId, false));
        }
    }

    @Override
    public OrderResult cancelOrder(long buyerId, long orderId) throws SQLException {
        positiveId(buyerId, "用户无效"); positiveId(orderId, "订单ID无效");
        try (Connection connection = connections.openConnection()) {
            boolean autoCommit = connection.getAutoCommit(); connection.setAutoCommit(false);
            try {
                ShopOrderRecord order = findOrder(connection, orderId, true);
                requireOrderAccess(order, buyerId, false);
                if (order.status() == ShopOrderStatus.CANCELLED) {
                    connection.commit(); return result(order);
                }
                if (order.status() != ShopOrderStatus.PAID) {
                    throw new ShopRuleException("订单状态已变化，请刷新后重试");
                }
                List<ShopOrderItemRecord> items = orderItems(connection, orderId, false);
                for (ShopOrderItemRecord item : items) {
                    int stock = lockStock(connection, item.productId());
                    int after;
                    try { after = Math.addExact(stock, item.quantity()); }
                    catch (ArithmeticException exception) { throw new ShopRuleException("库存数量超出范围"); }
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE shop_products SET stock=? WHERE id=?")) {
                        statement.setInt(1, after); statement.setLong(2, item.productId());
                        statement.executeUpdate();
                    }
                    insertMovement(connection, item.productId(),
                            ShopInventoryMovementType.ORDER_CANCEL, item.quantity(), after,
                            orderId, buyerId, "订单取消 " + order.orderNo());
                }
                payments.refundForShop(connection, buyerId, order.totalAmount(),
                        "REFUND-" + order.orderNo(), "校园商店订单退款 " + order.orderNo());
                updateOrderStatus(connection, orderId, ShopOrderStatus.CANCELLED, "cancelled_at");
                notifications.insert(connection, new NotificationDraft(
                        buyerId, null, NotificationType.SHOP_ORDER_REFUNDED,
                        NotificationSource.SHOP, "您的校园商店订单已取消并退款",
                        "订单 " + order.orderNo() + " 已取消，退款 "
                                + MoneyPolicy.format(order.totalAmount()) + " 元已退回虚拟银行账户。",
                        NotificationTarget.SHOP_ORDERS, order.id()));
                connection.commit();
                return new OrderResult(order.id(), order.orderNo(), order.totalAmount(),
                        ShopOrderStatus.CANCELLED);
            } catch (Exception exception) {
                rollback(connection, exception); throw exception;
            } finally { connection.setAutoCommit(autoCommit); }
        }
    }

    @Override
    public OrderResult shipOrder(long operatorId, long orderId) throws SQLException {
        positiveId(operatorId, "操作人无效"); positiveId(orderId, "订单ID无效");
        try (Connection connection = connections.openConnection()) {
            boolean autoCommit = connection.getAutoCommit(); connection.setAutoCommit(false);
            try {
                ShopOrderRecord order = findOrder(connection, orderId, true);
                if (order.status() == ShopOrderStatus.SHIPPED) {
                    connection.commit(); return result(order);
                }
                if (order.status() != ShopOrderStatus.PAID) {
                    throw new ShopRuleException("订单状态已变化，请刷新后重试");
                }
                updateOrderStatus(connection, orderId, ShopOrderStatus.SHIPPED, "shipped_at");
                notifications.insert(connection, new NotificationDraft(
                        order.buyerUserId(), operatorId, NotificationType.SHOP_ORDER_SHIPPED,
                        NotificationSource.SHOP, "您的校园商店订单已发货",
                        "订单 " + order.orderNo() + " 已发货，请注意查收。",
                        NotificationTarget.SHOP_ORDERS, order.id()));
                connection.commit();
                return new OrderResult(order.id(), order.orderNo(), order.totalAmount(),
                        ShopOrderStatus.SHIPPED);
            } catch (Exception exception) {
                rollback(connection, exception); throw exception;
            } finally { connection.setAutoCommit(autoCommit); }
        }
    }

    @Override
    public OrderResult confirmOrder(long buyerId, long orderId) throws SQLException {
        positiveId(buyerId, "用户无效"); positiveId(orderId, "订单ID无效");
        try (Connection connection = connections.openConnection()) {
            boolean autoCommit = connection.getAutoCommit(); connection.setAutoCommit(false);
            try {
                ShopOrderRecord order = findOrder(connection, orderId, true);
                requireOrderAccess(order, buyerId, false);
                if (order.status() == ShopOrderStatus.COMPLETED) {
                    connection.commit(); return result(order);
                }
                if (order.status() != ShopOrderStatus.SHIPPED) {
                    throw new ShopRuleException("订单状态已变化，请刷新后重试");
                }
                updateOrderStatus(connection, orderId, ShopOrderStatus.COMPLETED, "completed_at");
                connection.commit();
                return new OrderResult(order.id(), order.orderNo(), order.totalAmount(),
                        ShopOrderStatus.COMPLETED);
            } catch (Exception exception) {
                rollback(connection, exception); throw exception;
            } finally { connection.setAutoCommit(autoCommit); }
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

    private void bindOrderQuery(PreparedStatement statement, OrderQuery query) throws SQLException {
        if (query.buyerUserId() == null) {
            statement.setNull(1, Types.BIGINT); statement.setNull(2, Types.BIGINT);
        } else {
            statement.setLong(1, query.buyerUserId()); statement.setLong(2, query.buyerUserId());
        }
        String keyword = "%" + query.keyword() + "%";
        statement.setString(3, query.keyword());
        statement.setString(4, keyword);
        statement.setString(5, keyword);
        String status = query.status() == null ? null : query.status().name();
        statement.setString(6, status); statement.setString(7, status);
    }

    private String orderSelect() {
        return "SELECT o.id,o.order_no,o.buyer_user_id,u.username,u.display_name,"
                + "o.checkout_operation_id,o.total_amount,o.status,o.created_at,o.shipped_at,"
                + "o.completed_at,o.cancelled_at FROM shop_orders o "
                + "JOIN users u ON u.id=o.buyer_user_id";
    }

    private ShopOrderRecord findOrder(Connection connection, long orderId, boolean lock)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                orderSelect() + " WHERE o.id=?" + (lock ? " FOR UPDATE" : ""))) {
            statement.setLong(1, orderId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new ShopRuleException("订单不存在");
                return mapOrder(result);
            }
        }
    }

    private ShopOrderRecord mapOrder(ResultSet result) throws SQLException {
        return new ShopOrderRecord(result.getLong("id"), result.getString("order_no"),
                result.getLong("buyer_user_id"), result.getString("username"),
                result.getString("display_name"), result.getString("checkout_operation_id"),
                result.getBigDecimal("total_amount").setScale(2),
                ShopOrderStatus.valueOf(result.getString("status")),
                instant(result.getTimestamp("created_at")), nullableInstant(result.getTimestamp("shipped_at")),
                nullableInstant(result.getTimestamp("completed_at")),
                nullableInstant(result.getTimestamp("cancelled_at")));
    }

    private List<ShopOrderItemRecord> orderItems(Connection connection, long orderId, boolean lock)
            throws SQLException {
        List<ShopOrderItemRecord> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,order_id,product_id,sku_snapshot,name_snapshot,unit_price,quantity,subtotal"
                        + " FROM shop_order_items WHERE order_id=? ORDER BY product_id"
                        + (lock ? " FOR UPDATE" : ""))) {
            statement.setLong(1, orderId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(new ShopOrderItemRecord(
                        result.getLong("id"), result.getLong("order_id"),
                        result.getLong("product_id"), result.getString("sku_snapshot"),
                        result.getString("name_snapshot"),
                        result.getBigDecimal("unit_price").setScale(2), result.getInt("quantity"),
                        result.getBigDecimal("subtotal").setScale(2)));
            }
        }
        return rows;
    }

    private void requireOrderAccess(ShopOrderRecord order, long requesterId, boolean admin) {
        if (!admin && order.buyerUserId() != requesterId) throw new ShopRuleException("订单不存在");
    }

    private void updateOrderStatus(Connection connection, long orderId, ShopOrderStatus status,
                                   String timestampColumn) throws SQLException {
        String allowed = switch (timestampColumn) {
            case "shipped_at" -> "shipped_at";
            case "completed_at" -> "completed_at";
            case "cancelled_at" -> "cancelled_at";
            default -> throw new IllegalArgumentException("Invalid timestamp column");
        };
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE shop_orders SET status=?," + allowed + "=CURRENT_TIMESTAMP WHERE id=?")) {
            statement.setString(1, status.name()); statement.setLong(2, orderId);
            statement.executeUpdate();
        }
    }

    private OrderResult result(ShopOrderRecord order) {
        return new OrderResult(order.id(), order.orderNo(), order.totalAmount(), order.status());
    }

    private List<CartLock> lockCart(Connection connection, long buyerUserId,
                                    List<Long> selectedProductIds) throws SQLException {
        List<CartLock> rows = new ArrayList<>();
        String selectedClause = selectedProductIds == null ? "" : " AND product_id IN ("
                + String.join(",", java.util.Collections.nCopies(selectedProductIds.size(), "?"))
                + ")";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT product_id,quantity FROM shop_cart_items WHERE user_id=?"
                        + selectedClause + " ORDER BY product_id FOR UPDATE")) {
            statement.setLong(1, buyerUserId);
            if (selectedProductIds != null) {
                for (int index = 0; index < selectedProductIds.size(); index++) {
                    statement.setLong(index + 2, selectedProductIds.get(index));
                }
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(new CartLock(
                        result.getLong("product_id"), result.getInt("quantity")));
            }
        }
        return rows;
    }

    private List<Long> selectedProductIds(Set<Long> selectedProductIds) {
        if (selectedProductIds == null) throw new IllegalArgumentException("所选商品无效");
        List<Long> ordered = new ArrayList<>();
        Set<Long> unique = new HashSet<>();
        for (Long productId : selectedProductIds) {
            if (ordered.size() == 100) throw new IllegalArgumentException("所选商品数量无效");
            if (productId == null || productId < 1) {
                throw new IllegalArgumentException("商品ID无效");
            }
            if (!unique.add(productId)) throw new IllegalArgumentException("所选商品重复");
            ordered.add(productId);
        }
        if (ordered.isEmpty()) throw new ShopRuleException("购物车为空");
        ordered.sort(Long::compareTo);
        return List.copyOf(ordered);
    }

    private long singleProductId(Set<Long> productIds) {
        if (productIds == null || productIds.size() != 1) {
            throw new IllegalArgumentException("商品ID无效");
        }
        Long productId = productIds.iterator().next();
        if (productId == null) throw new IllegalArgumentException("商品ID无效");
        positiveId(productId, "商品ID无效");
        return productId;
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
        if (query.category() == null) {
            statement.setNull(4, Types.VARCHAR); statement.setNull(5, Types.VARCHAR);
        } else {
            statement.setString(4, query.category().name());
            statement.setString(5, query.category().name());
        }
        if (query.enabled() == null) {
            statement.setNull(6, Types.BOOLEAN); statement.setNull(7, Types.BOOLEAN);
        } else {
            statement.setBoolean(6, query.enabled()); statement.setBoolean(7, query.enabled());
        }
    }

    private void bindProductValues(
            PreparedStatement statement, ValidProduct product, int start) throws SQLException {
        statement.setString(start, product.name());
        statement.setString(start + 1, product.description());
        statement.setString(start + 2, product.category().name());
        statement.setBigDecimal(start + 3, product.price());
        statement.setBoolean(start + 4, product.enabled());
    }

    private ValidProduct validate(ProductInput input) {
        Objects.requireNonNull(input, "input");
        String name = text(input.name(), "请填写商品名称", 120);
        String description = input.description() == null ? "" : input.description().trim();
        if (description.length() > 1000) throw new IllegalArgumentException("商品说明不能超过1000个字符");
        BigDecimal price = Objects.requireNonNull(input.price(), "price");
        if (price.signum() <= 0 || price.scale() > 2
                || price.compareTo(new BigDecimal("9999999999999.99")) > 0) {
            throw new IllegalArgumentException("商品价格无效");
        }
        return new ValidProduct(name, description, Objects.requireNonNull(input.category(), "category"),
                price.setScale(2, RoundingMode.UNNECESSARY), input.enabled());
    }

    private String pendingSku() {
        return "PENDING-" + UUID.randomUUID().toString().replace("-", "");
    }

    private String generatedSku(long productId) {
        if (productId < 1 || productId > 999_999) {
            throw new ShopRuleException("自动货号数量已达到上限");
        }
        return String.format(java.util.Locale.ROOT, "SKU-%06d", productId);
    }

    private ShopProductRecord mapProduct(ResultSet result) throws SQLException {
        return new ShopProductRecord(result.getLong("id"), result.getString("sku"),
                result.getString("name"), result.getString("description"),
                category(result.getString("category")), result.getBigDecimal("price").setScale(2), result.getInt("stock"),
                result.getBoolean("enabled"), instant(result.getTimestamp("created_at")),
                instant(result.getTimestamp("updated_at")));
    }

    private void requireImageManager(Connection connection, long operatorId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id"
                        + " WHERE ur.user_id=? AND r.role_code IN ('SHOP_ADMIN','SUPER_ADMIN')")) {
            statement.setLong(1, operatorId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new ShopRuleException("无权执行商店图片管理操作");
            }
        }
    }

    private void lockProduct(Connection connection, long productId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM shop_products WHERE id=? FOR UPDATE")) {
            statement.setLong(1, productId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new ShopRuleException("商品不存在");
            }
        }
    }

    private List<ShopProductImageRecord> lockProductImages(Connection connection, long productId)
            throws SQLException {
        List<ShopProductImageRecord> images = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,product_id,storage_key,thumbnail_storage_key,mime_type,byte_size,sha256,"
                        + "sort_order,is_cover,created_at,updated_at FROM shop_product_images"
                        + " WHERE product_id=? ORDER BY sort_order,id FOR UPDATE")) {
            statement.setLong(1, productId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) images.add(mapProductImage(result));
            }
        }
        return images;
    }

    private void validateImagePlan(long productId, List<ShopProductImageRecord> current,
                                   Map<String, FinalizedUpload> finalizedUploads,
                                   ImagePlan plan) {
        if (plan.items().size() > 5) throw new ShopRuleException("每件商品最多五张图片");
        long covers = plan.items().stream().filter(ImagePlanItem::cover).count();
        if (!plan.items().isEmpty() && covers != 1) {
            throw new ShopRuleException("商品图片必须且只能设置一张封面");
        }
        Set<Long> currentIds = new HashSet<>();
        for (ShopProductImageRecord image : current) currentIds.add(image.id());
        Set<Long> existingIds = new HashSet<>();
        Set<String> uploadIds = new HashSet<>();
        Set<String> storageKeys = new HashSet<>();
        for (ImagePlanItem item : plan.items()) {
            boolean existing = item.existingImageId() != null;
            boolean uploaded = item.uploadId() != null && !item.uploadId().isBlank();
            if (existing == uploaded) throw new ShopRuleException("图片计划来源无效");
            if (existing) {
                if (item.existingImageId() < 1 || !existingIds.add(item.existingImageId())) {
                    throw new ShopRuleException("图片计划包含重复图片");
                }
                if (!currentIds.contains(item.existingImageId())) {
                    throw new ShopRuleException("图片不属于当前商品");
                }
            } else {
                if (!uploadIds.add(item.uploadId())) {
                    throw new ShopRuleException("图片计划包含重复上传");
                }
                FinalizedUpload upload = finalizedUploads.get(item.uploadId());
                if (upload == null) throw new ShopRuleException("上传尚未完成或已过期");
                if (!item.uploadId().equals(upload.uploadId())) {
                    throw new ShopRuleException("上传标识不匹配");
                }
                if (upload.productId() != productId) throw new ShopRuleException("上传不属于当前商品");
                validateFinalizedUpload(upload);
                if (!storageKeys.add(upload.storageKey())
                        || !storageKeys.add(upload.thumbnailStorageKey())) {
                    throw new ShopRuleException("图片存储键重复");
                }
            }
        }
    }

    private void validateFinalizedUpload(FinalizedUpload upload) {
        if (upload.productId() < 1 || upload.byteSize() < 1
                || upload.storageKey().isBlank() || upload.thumbnailStorageKey().isBlank()
                || upload.mimeType().isBlank() || upload.sha256().length() != 64) {
            throw new ShopRuleException("已完成图片信息无效");
        }
    }

    private void clearImageOrdering(Connection connection, long productId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE shop_product_images SET sort_order=sort_order+1000,is_cover=FALSE"
                        + " WHERE product_id=?")) {
            statement.setLong(1, productId);
            statement.executeUpdate();
        }
    }

    private void deleteRemovedImages(Connection connection, long productId, Set<Long> retainedIds)
            throws SQLException {
        if (retainedIds.isEmpty()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM shop_product_images WHERE product_id=?")) {
                statement.setLong(1, productId);
                statement.executeUpdate();
            }
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(retainedIds.size(), "?"));
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM shop_product_images WHERE product_id=? AND id NOT IN ("
                        + placeholders + ")")) {
            statement.setLong(1, productId);
            int index = 2;
            for (Long retainedId : retainedIds) statement.setLong(index++, retainedId);
            statement.executeUpdate();
        }
    }

    private void updateExistingImage(Connection connection, long imageId, int sortOrder,
                                     boolean cover) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE shop_product_images SET sort_order=?,is_cover=? WHERE id=?")) {
            statement.setInt(1, sortOrder);
            statement.setBoolean(2, cover);
            statement.setLong(3, imageId);
            if (statement.executeUpdate() != 1) throw new SQLException("Image update failed");
        }
    }

    private void insertFinalizedImage(Connection connection, long productId,
                                      FinalizedUpload upload, int sortOrder, boolean cover)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO shop_product_images(product_id,storage_key,thumbnail_storage_key,"
                        + "mime_type,byte_size,sha256,sort_order,is_cover) VALUES(?,?,?,?,?,?,?,?)")) {
            statement.setLong(1, productId);
            statement.setString(2, upload.storageKey());
            statement.setString(3, upload.thumbnailStorageKey());
            statement.setString(4, upload.mimeType());
            statement.setLong(5, upload.byteSize());
            statement.setString(6, upload.sha256());
            statement.setInt(7, sortOrder);
            statement.setBoolean(8, cover);
            statement.executeUpdate();
        }
    }

    private Set<String> imageKeys(List<ShopProductImageRecord> images) {
        Set<String> keys = new LinkedHashSet<>();
        for (ShopProductImageRecord image : images) {
            keys.add(image.storageKey());
            keys.add(image.thumbnailStorageKey());
        }
        return keys;
    }

    private List<ShopProductImageRecord> productImages(Connection connection, long productId)
            throws SQLException {
        List<ShopProductImageRecord> images = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,product_id,storage_key,thumbnail_storage_key,mime_type,byte_size,sha256,"
                        + "sort_order,is_cover,created_at,updated_at FROM shop_product_images"
                        + " WHERE product_id=? ORDER BY sort_order,id")) {
            statement.setLong(1, productId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) images.add(new ShopProductImageRecord(
                        result.getLong("id"), result.getLong("product_id"),
                        result.getString("storage_key"), result.getString("thumbnail_storage_key"),
                        result.getString("mime_type"), result.getLong("byte_size"),
                        result.getString("sha256"), result.getInt("sort_order"),
                        result.getBoolean("is_cover"), instant(result.getTimestamp("created_at")),
                        instant(result.getTimestamp("updated_at"))));
            }
        }
        return images;
    }

    private Map<Long, ShopProductImageRecord> coverImages(Connection connection,
                                                            Set<Long> productIds)
            throws SQLException {
        Objects.requireNonNull(productIds, "productIds");
        if (productIds.isEmpty()) return Map.of();
        List<Long> ids = new ArrayList<>(productIds);
        for (Long id : ids) positiveId(Objects.requireNonNull(id, "productId"), "商品ID无效");
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Map<Long, ShopProductImageRecord> covers = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,product_id,storage_key,thumbnail_storage_key,mime_type,byte_size,"
                        + "sha256,sort_order,is_cover,created_at,updated_at"
                        + " FROM shop_product_images WHERE is_cover=TRUE AND product_id IN ("
                        + placeholders + ") ORDER BY product_id,id")) {
            int parameter = 1;
            for (Long id : ids) statement.setLong(parameter++, id);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ShopProductImageRecord cover = mapProductImage(result);
                    if (covers.putIfAbsent(cover.productId(), cover) != null) {
                        throw new ShopRuleException("商品封面数据无效");
                    }
                }
            }
        }
        return Map.copyOf(covers);
    }

    private ShopProductImageRecord mapProductImage(ResultSet result) throws SQLException {
        return new ShopProductImageRecord(result.getLong("id"), result.getLong("product_id"),
                result.getString("storage_key"), result.getString("thumbnail_storage_key"),
                result.getString("mime_type"), result.getLong("byte_size"),
                result.getString("sha256"), result.getInt("sort_order"),
                result.getBoolean("is_cover"), instant(result.getTimestamp("created_at")),
                instant(result.getTimestamp("updated_at")));
    }

    private ShopCategory category(String value) {
        if (value == null) return ShopCategory.OTHER;
        return ShopCategory.parse(value);
    }

    private String productSort(ShopProductSort sort) {
        return switch (sort) {
            case NEWEST -> "p.created_at DESC,p.id DESC";
            case PRICE_ASC -> "p.price ASC,p.id ASC";
            case PRICE_DESC -> "p.price DESC,p.id DESC";
            case NAME_ASC -> "p.name ASC,p.id ASC";
        };
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

    private void restoreCatalogConnectionState(Connection connection, int originalIsolation,
                                               boolean originalAutoCommit,
                                               Exception operationFailure) throws SQLException {
        SQLException restorationFailure = null;
        try {
            connection.setTransactionIsolation(originalIsolation);
        } catch (SQLException exception) {
            restorationFailure = exception;
        }
        try {
            connection.setAutoCommit(originalAutoCommit);
        } catch (SQLException exception) {
            if (restorationFailure == null) restorationFailure = exception;
            else restorationFailure.addSuppressed(exception);
        }
        if (restorationFailure == null) return;
        if (operationFailure != null) {
            operationFailure.addSuppressed(restorationFailure);
            return;
        }
        throw restorationFailure;
    }

    private Instant instant(Timestamp timestamp) { return timestamp.toInstant(); }
    private Instant nullableInstant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }

    private record ValidProduct(String name, String description, ShopCategory category,
                                BigDecimal price, boolean enabled) {
    }

    private record CartLock(long productId, int quantity) {
    }

    private enum CheckoutMode { DIRECT, CART }

    private record CheckoutProduct(long productId, String sku, String name, BigDecimal price,
                                   int quantity, int stock, BigDecimal subtotal) {
    }
}
