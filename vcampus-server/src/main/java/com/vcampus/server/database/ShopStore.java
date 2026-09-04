package com.vcampus.server.database;

import com.vcampus.server.model.ShopCartItemRecord;
import com.vcampus.server.model.ShopProductImageRecord;
import com.vcampus.server.model.ShopProductRecord;
import com.vcampus.server.model.ShopOrderRecord;
import com.vcampus.server.model.ShopOrderItemRecord;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.ShopProductSort;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public interface ShopStore {
    ProductPage searchProducts(ProductQuery query) throws SQLException;

    ProductDetail product(long productId, boolean includeDisabled) throws SQLException;

    List<ShopProductImageRecord> productImages(long productId) throws SQLException;

    Map<Long, ShopProductImageRecord> coverImages(Set<Long> productIds) throws SQLException;

    ShopProductImageRecord productImage(long imageId, boolean includeDisabled) throws SQLException;

    Set<String> productImageStorageKeys() throws SQLException;

    ImageCommitResult replaceProductImages(long operatorId, long productId,
                                           Map<String, FinalizedUpload> finalizedUploads,
                                           ImagePlan plan) throws SQLException;

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
                        ShopCategory category, BigDecimal price, boolean enabled) {
    }

    record ProductQuery(String keyword, ShopCategory category, Boolean enabled,
                        ShopProductSort sort, int page, int pageSize) {
        public ProductQuery {
            keyword = keyword == null ? "" : keyword.trim();
            sort = sort == null ? ShopProductSort.NEWEST : sort;
            if (page < 1 || pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("分页参数无效");
            }
        }
    }

    record ProductPage(List<ShopProductRecord> rows, int page, int pageSize, int total,
                       Map<Long, ShopProductImageRecord> covers) {
        public ProductPage {
            rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
            covers = Map.copyOf(Objects.requireNonNull(covers, "covers"));
        }

        public ProductPage(List<ShopProductRecord> rows, int page, int pageSize, int total) {
            this(rows, page, pageSize, total, Map.of());
        }
    }

    record ProductDetail(ShopProductRecord product, List<ShopProductImageRecord> images) {
        public ProductDetail { images = List.copyOf(images); }
    }

    record ImagePlan(List<ImagePlanItem> items) {
        public ImagePlan {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }

    record ImagePlanItem(Long existingImageId, String uploadId, boolean cover) {
    }

    record FinalizedUpload(String uploadId, long productId, String storageKey,
                           String thumbnailStorageKey, String mimeType, long byteSize,
                           String sha256) {
        public FinalizedUpload {
            Objects.requireNonNull(uploadId, "uploadId");
            Objects.requireNonNull(storageKey, "storageKey");
            Objects.requireNonNull(thumbnailStorageKey, "thumbnailStorageKey");
            Objects.requireNonNull(mimeType, "mimeType");
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    record ImageCommitResult(Set<String> keptKeys, Set<String> deletedKeys,
                             List<ShopProductImageRecord> images) {
        public ImageCommitResult {
            keptKeys = Set.copyOf(Objects.requireNonNull(keptKeys, "keptKeys"));
            deletedKeys = Set.copyOf(Objects.requireNonNull(deletedKeys, "deletedKeys"));
            images = List.copyOf(Objects.requireNonNull(images, "images"));
        }
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
