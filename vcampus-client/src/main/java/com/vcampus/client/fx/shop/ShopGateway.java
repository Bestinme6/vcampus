package com.vcampus.client.fx.shop;

import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.ShopProductSort;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** Synchronous, typed boundary for shop requests. Callers run it off the JavaFX thread. */
public interface ShopGateway {
    ShopData.ProductPage search(String keyword, ShopCategory category, ShopProductSort sort, int page)
            throws IOException;
    ShopData.ProductDetail product(long productId) throws IOException;
    ShopData.ImageChunk imageChunk(long imageId, ShopData.ImageVariant variant, int chunkIndex)
            throws IOException;
    ShopData.Cart cart() throws IOException;
    ShopData.Cart setCartQuantity(long productId, int quantity) throws IOException;
    ShopData.Cart removeCartItem(long productId) throws IOException;
    ShopData.CheckoutReceipt checkout(Set<Long> selectedProductIds, String operationId) throws IOException;
    ShopData.CheckoutReceipt buyNow(long productId, int quantity, String operationId) throws IOException;
    ShopData.OrderPage orders(ShopOrderStatus status, int page) throws IOException;
    ShopData.OrderDetail order(long orderId) throws IOException;
    ShopData.CheckoutReceipt cancelOrder(long orderId) throws IOException;
    ShopData.CheckoutReceipt confirmOrder(long orderId) throws IOException;
    ShopData.ProductPage adminSearchProducts(String keyword, ShopCategory category, boolean enabled,
                                             ShopProductSort sort, int page) throws IOException;
    long saveProduct(ShopData.ProductInput product) throws IOException;
    boolean setProductEnabled(long productId, boolean enabled) throws IOException;
    int adjustInventory(long productId, int delta, String reason) throws IOException;
    ShopData.OrderPage adminOrders(String keyword, ShopOrderStatus status, int page) throws IOException;
    ShopData.CheckoutReceipt shipOrder(long orderId) throws IOException;
    BigDecimal bankBalance() throws IOException;
    ShopData.UploadTicket uploadStart(long productId, String mimeType, long expectedBytes) throws IOException;
    void uploadChunk(String uploadId, int chunkIndex, byte[] content) throws IOException;
    void uploadComplete(String uploadId) throws IOException;
    List<ShopData.ImageRef> commitImages(long productId, List<ShopData.ImagePlanItem> items)
            throws IOException;
}
