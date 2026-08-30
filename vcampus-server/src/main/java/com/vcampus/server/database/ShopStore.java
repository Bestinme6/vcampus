package com.vcampus.server.database;

import com.vcampus.server.model.ShopCartItemRecord;
import com.vcampus.server.model.ShopProductRecord;
import com.vcampus.server.model.ShopOrderRecord;
import com.vcampus.server.model.ShopOrderItemRecord;
import com.vcampus.common.model.ShopOrderStatus;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

public interface ShopStore {
    ProductPage searchProducts(ProductQuery query) throws SQLException;

    ProductSaveResult saveProduct(long operatorId, ProductInput input) throws SQLException;

    boolean setProductEnabled(long operatorId, long productId, boolean enabled) throws SQLException;

    InventoryResult adjustInventory(long operatorId, long productId, int delta, String reason)
            throws SQLException;

    CartResult cart(long userId) throws SQLException;

    CartResult setCartQuantity(long userId, long productId, int quantity) throws SQLException;

    CartResult removeCartItem(long userId, long productId) throws SQLException;

    CheckoutResult checkout(long buyerUserId, String operationId) throws SQLException;

    OrderPage searchOrders(OrderQuery query) throws SQLException;

    OrderDetail order(long requesterId, long orderId, boolean admin) throws SQLException;

    OrderResult cancelOrder(long buyerId, long orderId) throws SQLException;

    OrderResult shipOrder(long operatorId, long orderId) throws SQLException;

    OrderResult confirmOrder(long buyerId, long orderId) throws SQLException;

    record ProductInput(Long productId, String sku, String name, String description,
                        BigDecimal price, boolean enabled) {
    }

    record ProductQuery(String keyword, Boolean enabled, int page, int pageSize) {
        public ProductQuery {
            keyword = keyword == null ? "" : keyword.trim();
            if (page < 1 || pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("分页参数无效");
            }
        }
    }

    record ProductPage(List<ShopProductRecord> rows, int page, int pageSize, int total) {
        public ProductPage { rows = List.copyOf(rows); }
    }

    record ProductSaveResult(long productId) {
    }

    record InventoryResult(long productId, int stockAfter) {
    }

    record CartResult(List<ShopCartItemRecord> rows, BigDecimal estimatedTotal) {
        public CartResult {
            rows = List.copyOf(rows);
            estimatedTotal = estimatedTotal.setScale(2);
        }
    }

    record CheckoutResult(long orderId, String orderNo, BigDecimal totalAmount,
                          ShopOrderStatus status, boolean duplicate) {
    }

    record OrderQuery(
            Long buyerUserId, String keyword, ShopOrderStatus status, int page, int pageSize) {
        public OrderQuery {
            keyword = keyword == null ? "" : keyword.trim();
            if ((buyerUserId != null && buyerUserId < 1)
                    || page < 1 || pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("订单分页参数无效");
            }
        }
    }

    record OrderPage(List<ShopOrderRecord> rows, int page, int pageSize, int total) {
        public OrderPage { rows = List.copyOf(rows); }
    }

    record OrderDetail(ShopOrderRecord order, List<ShopOrderItemRecord> items) {
        public OrderDetail { items = List.copyOf(items); }
    }

    record OrderResult(long orderId, String orderNo, BigDecimal totalAmount,
                       ShopOrderStatus status) {
    }
}
