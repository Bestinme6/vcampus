package com.vcampus.client.fx.shop;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.ShopProductSort;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Synchronous Socket adapter; controllers are responsible for asynchronous execution. */
public final class SocketShopGateway implements ShopGateway {
    private final VCampusClient client;
    private final String token;

    public SocketShopGateway(VCampusClient client, String token) {
        this.client = Objects.requireNonNull(client, "client");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("token");
        this.token = token;
    }

    @Override public ShopData.ProductPage search(String keyword, ShopCategory category, ShopProductSort sort, int page) throws IOException {
        return ShopData.productPage(client.searchShopProducts(token, keyword, category, null, sort, page));
    }
    @Override public ShopData.ProductDetail product(long productId) throws IOException { return ShopData.productDetail(client.getShopProduct(token, productId)); }
    @Override public ShopData.ImageChunk imageChunk(long imageId, ShopData.ImageVariant variant, int chunkIndex) throws IOException { return ShopData.imageChunk(client.getShopImageChunk(token, imageId, variant.name(), chunkIndex)); }
    @Override public ShopData.Cart cart() throws IOException { return ShopData.cart(client.getShopCart(token)); }
    @Override public ShopData.Cart setCartQuantity(long productId, int quantity) throws IOException { return ShopData.cart(client.setShopCartQuantity(token, productId, quantity)); }
    @Override public ShopData.Cart removeCartItem(long productId) throws IOException { return ShopData.cart(client.removeShopCartItem(token, productId)); }
    @Override public ShopData.CheckoutReceipt checkout(Set<Long> selectedProductIds, String operationId) throws IOException { return ShopData.checkoutReceipt(client.checkoutShop(token, operationId, selectedProductIds)); }
    @Override public ShopData.CheckoutReceipt buyNow(long productId, int quantity, String operationId) throws IOException { return ShopData.checkoutReceipt(client.buyNowShop(token, operationId, productId, quantity)); }
    @Override public ShopData.OrderPage orders(ShopOrderStatus status, int page) throws IOException { return ShopData.orderPage(client.searchShopOrders(token, status == null ? null : status.name(), page)); }
    @Override public ShopData.OrderDetail order(long orderId) throws IOException { return ShopData.orderDetail(client.getShopOrder(token, orderId)); }
    @Override public ShopData.CheckoutReceipt cancelOrder(long orderId) throws IOException { return ShopData.checkoutReceipt(client.cancelShopOrder(token, orderId)); }
    @Override public ShopData.CheckoutReceipt confirmOrder(long orderId) throws IOException { return ShopData.checkoutReceipt(client.confirmShopOrder(token, orderId)); }
    @Override public ShopData.ProductPage adminSearchProducts(String keyword, ShopCategory category, boolean enabled, ShopProductSort sort, int page) throws IOException { return ShopData.productPage(client.searchShopProducts(token, keyword, category, enabled, sort, page)); }
    @Override public long saveProduct(ShopData.ProductInput product) throws IOException { ResponseMessage response = client.saveShopProduct(token, product.productId(), product.name(), product.description(), product.category().name(), product.price().toPlainString(), product.enabled()); ShopData.require(response); return ShopData.id(response.data().get("productId"), "商品响应数据无效"); }
    @Override public boolean setProductEnabled(long productId, boolean enabled) throws IOException { ResponseMessage response = client.setShopProductEnabled(token, productId, enabled); ShopData.require(response); return strictBoolean(response.data().get("changed")); }
    @Override public int adjustInventory(long productId, int delta, String reason) throws IOException { ResponseMessage response = client.adjustShopInventory(token, productId, delta, reason); ShopData.require(response); return ShopData.nonNegative(response.data().get("stockAfter"), "库存响应数据无效"); }
    @Override public ShopData.OrderPage adminOrders(String keyword, ShopOrderStatus status, int page) throws IOException { return ShopData.orderPage(client.searchShopAdminOrders(token, keyword, status == null ? null : status.name(), page)); }
    @Override public ShopData.CheckoutReceipt shipOrder(long orderId) throws IOException { return ShopData.checkoutReceipt(client.shipShopOrder(token, orderId)); }
    @Override public BigDecimal bankBalance() throws IOException { ResponseMessage response = client.getBankAccount(token); ShopData.require(response); return ShopData.money(response.data().get("balance")); }
    @Override public ShopData.UploadTicket uploadStart(long productId, String mimeType, long expectedBytes) throws IOException { return ShopData.uploadTicket(client.startShopImageUpload(token, productId, mimeType, expectedBytes)); }
    @Override public void uploadChunk(String uploadId, int chunkIndex, byte[] content) throws IOException { ShopData.require(client.uploadShopImageChunk(token, uploadId, chunkIndex, Base64.getEncoder().encodeToString(content))); }
    @Override public void uploadComplete(String uploadId) throws IOException { ShopData.require(client.completeShopImageUpload(token, uploadId)); }
    @Override public List<ShopData.ImageRef> commitImages(long productId, List<ShopData.ImagePlanItem> items) throws IOException {
        if (items == null || items.size() > 5) throw new IllegalArgumentException("图片计划无效");
        Set<Long> existing = new LinkedHashSet<>(); Set<String> uploads = new LinkedHashSet<>(); int covers = 0;
        List<String> encoded = items.stream().map(item -> {
            if (item.existingImageId() != null && !existing.add(item.existingImageId()) || item.uploadId() != null && !uploads.add(item.uploadId())) throw new IllegalArgumentException("图片计划无效");
            return RowCodec.encode(item.existingImageId() == null ? "" : item.existingImageId().toString(), item.uploadId() == null ? "" : item.uploadId(), Boolean.toString(item.cover()));
        }).toList();
        for (ShopData.ImagePlanItem item : items) if (item.cover()) covers++;
        if (!items.isEmpty() && covers != 1) throw new IllegalArgumentException("图片计划无效");
        return ShopData.imageRefs(client.commitShopImages(token, productId, encoded));
    }

    private static boolean strictBoolean(String value) { if (!"true".equals(value) && !"false".equals(value)) throw new IllegalArgumentException("响应数据无效"); return Boolean.parseBoolean(value); }
}
