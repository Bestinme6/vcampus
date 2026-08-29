package com.vcampus.server.database;

import com.vcampus.server.model.ShopCartItemRecord;
import com.vcampus.server.model.ShopProductRecord;

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
}
