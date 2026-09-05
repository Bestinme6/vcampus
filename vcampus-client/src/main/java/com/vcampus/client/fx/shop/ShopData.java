package com.vcampus.client.fx.shop;

import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable shop response models and strict Socket response decoders. */
public final class ShopData {
    private static final Pattern MONEY = Pattern.compile("[0-9]+\\.[0-9]{2}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_CHUNK_BYTES = 192 * 1024;
    private static final long MAX_IMAGE_BYTES = 2L * 1024 * 1024;
    private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999999.99");

    private ShopData() {
    }

    public enum ImageVariant { THUMBNAIL, DETAIL }

    public record ImageRef(long id, String mimeType, long byteSize, String sha256,
                           int sortOrder, boolean cover) {
        public ImageRef {
            if (id < 1 || byteSize < 0 || sortOrder < 0 || !HASH.matcher(sha256).matches()) {
                throw new IllegalArgumentException("图片数据无效");
            }
            if (mimeType != null && mimeType.isBlank()) throw new IllegalArgumentException("图片数据无效");
        }

        public ImageRef(long id, String sha256) {
            this(id, null, 0, sha256, 0, true);
        }
    }

    public record Product(long id, String sku, String name, String description, ShopCategory category,
                          BigDecimal price, int stock, boolean enabled, Instant createdAt,
                          Instant updatedAt, ImageRef cover) {
        public Product {
            if (id < 1 || blank(sku) || blank(name) || category == null || stock < 0) {
                throw new IllegalArgumentException("商品数据无效");
            }
            price = nonNegativeMoney(price);
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            description = Objects.requireNonNullElse(description, "");
        }
    }

    public record ProductPage(List<Product> rows, int page, int pageSize, int total) {
        public ProductPage {
            rows = List.copyOf(rows);
            pageData(rows.size(), page, pageSize, total);
        }
    }

    public record ProductDetail(Product product, List<ImageRef> images) {
        public ProductDetail {
            product = Objects.requireNonNull(product, "product");
            images = orderedImages(images);
            validateImages(images);
        }
    }

    public record CartItem(long productId, String sku, String name, String description,
                           BigDecimal unitPrice, int quantity, int stock, boolean enabled,
                           BigDecimal subtotal, Instant updatedAt) {
        public CartItem {
            if (productId < 1 || blank(sku) || blank(name) || quantity < 1 || stock < 0) {
                throw new IllegalArgumentException("购物车数据无效");
            }
            unitPrice = nonNegativeMoney(unitPrice);
            subtotal = nonNegativeMoney(subtotal);
            if (unitPrice.multiply(BigDecimal.valueOf(quantity)).compareTo(subtotal) != 0) {
                throw new IllegalArgumentException("购物车金额无效");
            }
            description = Objects.requireNonNullElse(description, "");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    public record Cart(List<CartItem> items, BigDecimal estimatedTotal) {
        public Cart {
            items = List.copyOf(items);
            estimatedTotal = nonNegativeMoney(estimatedTotal);
            BigDecimal calculated = items.stream().map(CartItem::subtotal)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            if (calculated.compareTo(estimatedTotal) != 0) throw new IllegalArgumentException("购物车金额无效");
        }
    }

    public record CheckoutReceipt(long orderId, String orderNo, BigDecimal totalAmount,
                                  ShopOrderStatus status, boolean duplicate) {
        public CheckoutReceipt {
            if (orderId < 1 || blank(orderNo) || status == null) throw new IllegalArgumentException("订单数据无效");
            totalAmount = nonNegativeMoney(totalAmount);
        }
    }

    public record Order(long id, String orderNo, String buyerUsername, String buyerDisplayName,
                        BigDecimal totalAmount, ShopOrderStatus status, Instant createdAt,
                        Instant shippedAt, Instant completedAt, Instant cancelledAt) {
        public Order {
            if (id < 1 || blank(orderNo) || blank(buyerUsername) || blank(buyerDisplayName) || status == null) {
                throw new IllegalArgumentException("订单数据无效");
            }
            totalAmount = nonNegativeMoney(totalAmount);
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            validateLifecycle(status, createdAt, shippedAt, completedAt, cancelledAt);
        }
    }

    public record OrderPage(List<Order> rows, int page, int pageSize, int total) {
        public OrderPage {
            rows = List.copyOf(rows);
            pageData(rows.size(), page, pageSize, total);
        }
    }

    public record OrderItem(long id, long productId, String skuSnapshot, String nameSnapshot,
                            BigDecimal unitPrice, int quantity, BigDecimal subtotal) {
        public OrderItem {
            if (id < 1 || productId < 1 || blank(skuSnapshot) || blank(nameSnapshot) || quantity < 1) {
                throw new IllegalArgumentException("订单商品数据无效");
            }
            unitPrice = nonNegativeMoney(unitPrice);
            subtotal = nonNegativeMoney(subtotal);
            if (unitPrice.multiply(BigDecimal.valueOf(quantity)).compareTo(subtotal) != 0) {
                throw new IllegalArgumentException("订单商品金额无效");
            }
        }
    }

    public record OrderDetail(Order order, List<OrderItem> items) {
        public OrderDetail {
            order = Objects.requireNonNull(order, "order");
            items = List.copyOf(items);
            BigDecimal sum = items.stream().map(OrderItem::subtotal)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            if (sum.compareTo(order.totalAmount()) != 0) throw new IllegalArgumentException("订单总额无效");
        }
    }

    public record ImageChunk(long imageId, String mimeType, String sha256, long totalBytes,
                             int totalChunks, int chunkIndex, byte[] content) {
        public ImageChunk {
            if (imageId < 1 || blank(mimeType) || !HASH.matcher(sha256).matches()
                    || totalBytes < 1 || totalChunks < 1 || chunkIndex < 0 || chunkIndex >= totalChunks
                    || content == null || content.length == 0 || content.length > totalBytes) {
                throw new IllegalArgumentException("图片分块数据无效");
            }
            content = content.clone();
        }

        @Override public byte[] content() { return content.clone(); }
    }

    public record UploadTicket(String uploadId, long productId, long expectedBytes,
                               int chunkBytes, Instant expiresAt) {
        public UploadTicket {
            if (blank(uploadId) || productId < 1 || expectedBytes < 1 || chunkBytes < 1) {
                throw new IllegalArgumentException("上传凭据无效");
            }
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    public record ProductInput(Long productId, String name, String description, ShopCategory category,
                               BigDecimal price, boolean enabled) {
        public ProductInput {
            if (productId != null && productId < 1 || blank(name) || category == null) {
                throw new IllegalArgumentException("商品输入无效");
            }
            description = Objects.requireNonNullElse(description, "");
            price = nonNegativeMoney(price);
        }
    }

    public record ImagePlanItem(Long existingImageId, String uploadId, boolean cover) {
        public ImagePlanItem {
            if ((existingImageId == null) == (uploadId == null || uploadId.isBlank())
                    || existingImageId != null && existingImageId < 1) {
                throw new IllegalArgumentException("图片计划无效");
            }
            if (uploadId != null) uploadId = uploadId.trim();
        }
    }

    public static ProductPage productPage(ResponseMessage response) throws IOException {
        Map<String, String> data = data(response);
        int count = nonNegative(data, "count", "商品数据无效");
        int page = positive(data, "page", "商品分页数据无效");
        int pageSize = positive(data, "pageSize", "商品分页数据无效");
        int total = nonNegative(data, "total", "商品分页数据无效");
        List<Product> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) rows.add(product(data, "row." + index, true));
        rejectTrailingRows(data, count, "row.");
        return new ProductPage(rows, page, pageSize, total);
    }

    public static ProductDetail productDetail(ResponseMessage response) throws IOException {
        Map<String, String> data = data(response);
        Product product = product(data, "product", false);
        int count = nonNegative(data, "imageCount", "商品图片数据无效");
        if (count > 5) throw new IllegalArgumentException("商品图片数据无效");
        List<ImageRef> images = new ArrayList<>();
        for (int index = 0; index < count; index++) images.add(image(data, "image." + index));
        rejectTrailingRows(data, count, "image.");
        return new ProductDetail(product, images);
    }

    public static Cart cart(ResponseMessage response) throws IOException {
        Map<String, String> data = data(response);
        int count = nonNegative(data, "count", "购物车数据无效");
        List<CartItem> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<String> f = row(data, "row." + index, 10, "购物车数据无效");
            rows.add(new CartItem(id(f.get(0), "购物车数据无效"), f.get(1), f.get(2), f.get(3),
                    money(f.get(4)), positive(f.get(5), "购物车数据无效"),
                    nonNegative(f.get(6), "购物车数据无效"), bool(f.get(7), "购物车数据无效"),
                    money(f.get(8)), instant(f.get(9), "购物车数据无效")));
        }
        rejectTrailingRows(data, count, "row.");
        return new Cart(rows, money(required(data, "estimatedTotal", "购物车数据无效")));
    }

    public static CheckoutReceipt checkoutReceipt(ResponseMessage response) throws IOException {
        Map<String, String> data = data(response);
        return new CheckoutReceipt(id(required(data, "orderId", "订单数据无效"), "订单数据无效"),
                required(data, "orderNo", "订单数据无效"), money(required(data, "totalAmount", "订单数据无效")),
                status(required(data, "status", "订单数据无效")),
                data.containsKey("duplicate") && bool(data.get("duplicate"), "订单数据无效"));
    }

    public static OrderPage orderPage(ResponseMessage response) throws IOException {
        Map<String, String> data = data(response);
        int count = nonNegative(data, "count", "订单数据无效");
        List<Order> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) rows.add(order(row(data, "row." + index, 10, "订单数据无效")));
        rejectTrailingRows(data, count, "row.");
        return new OrderPage(rows, positive(data, "page", "订单分页数据无效"),
                positive(data, "pageSize", "订单分页数据无效"), nonNegative(data, "total", "订单分页数据无效"));
    }

    public static OrderDetail orderDetail(ResponseMessage response) throws IOException {
        Map<String, String> data = data(response);
        Order order = order(row(data, "order", 10, "订单数据无效"));
        int count = nonNegative(data, "count", "订单数据无效");
        List<OrderItem> items = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<String> f = row(data, "row." + index, 7, "订单商品数据无效");
            items.add(new OrderItem(id(f.get(0), "订单商品数据无效"), id(f.get(1), "订单商品数据无效"),
                    f.get(2), f.get(3), money(f.get(4)), positive(f.get(5), "订单商品数据无效"), money(f.get(6))));
        }
        rejectTrailingRows(data, count, "row.");
        return new OrderDetail(order, items);
    }

    public static ImageChunk imageChunk(ResponseMessage response) throws IOException {
        Map<String, String> data = data(response);
        String encoded = required(data, "contentBase64", "图片分块数据无效");
        byte[] content;
        try {
            content = Base64.getDecoder().decode(encoded);
            if (!Base64.getEncoder().encodeToString(content).equals(encoded)) {
                throw new IllegalArgumentException();
            }
        }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("图片分块数据无效", exception); }
        long totalBytes = positiveLong(required(data, "totalBytes", "图片分块数据无效"), "图片分块数据无效");
        if (totalBytes > MAX_IMAGE_BYTES) throw new IllegalArgumentException("图片分块数据无效");
        long totalChunks = positiveLong(required(data, "totalChunks", "图片分块数据无效"), "图片分块数据无效");
        if (totalChunks > Integer.MAX_VALUE) throw new IllegalArgumentException("图片分块数据无效");
        long expectedChunks = (totalBytes + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES;
        int chunkIndex = nonNegative(data, "chunkIndex", "图片分块数据无效");
        if (totalChunks != expectedChunks || chunkIndex >= totalChunks || content.length > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("图片分块数据无效");
        }
        long start = (long) chunkIndex * MAX_CHUNK_BYTES;
        int expectedLength = Math.toIntExact(Math.min(MAX_CHUNK_BYTES, totalBytes - start));
        if (content.length != expectedLength) throw new IllegalArgumentException("图片分块数据无效");
        return new ImageChunk(id(required(data, "imageId", "图片分块数据无效"), "图片分块数据无效"),
                required(data, "mimeType", "图片分块数据无效"), required(data, "sha256", "图片分块数据无效"),
                totalBytes, (int) totalChunks, chunkIndex, content);
    }

    public static UploadTicket uploadTicket(ResponseMessage response) throws IOException {
        Map<String, String> data = data(response);
        return new UploadTicket(required(data, "uploadId", "上传凭据无效"),
                id(required(data, "productId", "上传凭据无效"), "上传凭据无效"),
                positiveLong(required(data, "expectedBytes", "上传凭据无效"), "上传凭据无效"),
                positive(data, "chunkBytes", "上传凭据无效"),
                instant(required(data, "expiresAt", "上传凭据无效"), "上传凭据无效"));
    }

    public static List<ImageRef> imageRefs(ResponseMessage response) throws IOException {
        Map<String, String> data = data(response);
        int count = nonNegative(data, "count", "商品图片数据无效");
        if (count > 5) throw new IllegalArgumentException("商品图片数据无效");
        List<ImageRef> refs = new ArrayList<>();
        for (int index = 0; index < count; index++) refs.add(image(data, "image." + index));
        rejectTrailingRows(data, count, "image.");
        validateImages(refs);
        return List.copyOf(refs);
    }

    public static void require(ResponseMessage response) throws IOException { data(response); }

    static BigDecimal money(String value) {
        if (value == null || !MONEY.matcher(value).matches()) throw new IllegalArgumentException("金额格式无效");
        return nonNegativeMoney(new BigDecimal(value));
    }

    private static Product product(Map<String, String> data, String key, boolean pageRow) {
        List<String> f = row(data, key, 9, "商品数据无效");
        String categoryValue = data.get(key + ".category");
        ShopCategory category = categoryValue == null ? ShopCategory.OTHER : parseCategory(categoryValue);
        String coverId = data.get(key + ".coverImageId");
        String coverHash = data.get(key + ".coverHash");
        if ((coverId == null) != (coverHash == null)) throw new IllegalArgumentException("商品图片数据无效");
        ImageRef cover = coverId == null ? null : new ImageRef(id(coverId, "商品图片数据无效"), coverHash);
        if (!pageRow && data.containsKey("category")) category = parseCategory(data.get("category"));
        return new Product(id(f.get(0), "商品数据无效"), f.get(1), f.get(2), f.get(3), category,
                money(f.get(4)), nonNegative(f.get(5), "商品数据无效"), bool(f.get(6), "商品数据无效"),
                instant(f.get(7), "商品数据无效"), instant(f.get(8), "商品数据无效"), cover);
    }

    private static ImageRef image(Map<String, String> data, String key) {
        List<String> f = row(data, key, 6, "商品图片数据无效");
        return new ImageRef(id(f.get(0), "商品图片数据无效"), f.get(1),
                positiveLong(f.get(2), "商品图片数据无效"), f.get(3),
                nonNegative(f.get(4), "商品图片数据无效"), bool(f.get(5), "商品图片数据无效"));
    }

    private static Order order(List<String> f) {
        return new Order(id(f.get(0), "订单数据无效"), f.get(1), f.get(2), f.get(3), money(f.get(4)),
                status(f.get(5)), instant(f.get(6), "订单数据无效"), optionalInstant(f.get(7), "订单数据无效"),
                optionalInstant(f.get(8), "订单数据无效"), optionalInstant(f.get(9), "订单数据无效"));
    }

    private static Map<String, String> data(ResponseMessage response) throws IOException {
        if (response == null) throw new IOException("服务器未返回数据");
        if (!response.success()) throw new IOException(response.message());
        return response.data();
    }

    private static List<String> row(Map<String, String> data, String key, int size, String message) {
        try {
            List<String> fields = RowCodec.decode(required(data, key, message));
            if (fields.size() != size) throw new IllegalArgumentException(message);
            return fields;
        } catch (IllegalArgumentException exception) { throw new IllegalArgumentException(message, exception); }
    }

    private static String required(Map<String, String> data, String key, String message) {
        String value = data.get(key);
        if (value == null) throw new IllegalArgumentException(message);
        return value;
    }

    private static int positive(Map<String, String> data, String key, String message) { return positive(required(data, key, message), message); }
    private static int positive(String value, String message) { int parsed = nonNegative(value, message); if (parsed < 1) throw new IllegalArgumentException(message); return parsed; }
    private static int nonNegative(Map<String, String> data, String key, String message) { return nonNegative(required(data, key, message), message); }
    static int nonNegative(String value, String message) {
        try { int parsed = Integer.parseInt(value); if (parsed < 0) throw new NumberFormatException(); return parsed; }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(message, exception); }
    }
    static long id(String value, String message) { return positiveLong(value, message); }
    private static long positiveLong(String value, String message) {
        try { long parsed = Long.parseLong(value); if (parsed < 1) throw new NumberFormatException(); return parsed; }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(message, exception); }
    }
    private static boolean bool(String value, String message) {
        if (!"true".equals(value) && !"false".equals(value)) throw new IllegalArgumentException(message);
        return Boolean.parseBoolean(value);
    }
    private static ShopCategory parseCategory(String value) { try { return ShopCategory.parse(value); } catch (RuntimeException e) { throw new IllegalArgumentException("商品分类数据无效", e); } }
    private static ShopOrderStatus status(String value) { try { return ShopOrderStatus.valueOf(value); } catch (RuntimeException e) { throw new IllegalArgumentException("订单数据无效", e); } }
    private static Instant instant(String value, String message) { try { return Instant.parse(value); } catch (RuntimeException e) { throw new IllegalArgumentException(message, e); } }
    private static Instant optionalInstant(String value, String message) { return value.isEmpty() ? null : instant(value, message); }
    private static BigDecimal nonNegativeMoney(BigDecimal value) {
        if (value == null || value.scale() != 2 || value.signum() < 0
                || value.compareTo(MAX_MONEY) > 0) throw new IllegalArgumentException("金额格式无效");
        try { return value.setScale(2, RoundingMode.UNNECESSARY); } catch (ArithmeticException e) { throw new IllegalArgumentException("金额格式无效", e); }
    }
    private static void validateLifecycle(ShopOrderStatus status, Instant createdAt, Instant shippedAt,
                                          Instant completedAt, Instant cancelledAt) {
        switch (status) {
            case PAID -> requireLifecycle(shippedAt == null && completedAt == null && cancelledAt == null);
            case SHIPPED -> requireLifecycle(shippedAt != null && !shippedAt.isBefore(createdAt)
                    && completedAt == null && cancelledAt == null);
            case COMPLETED -> requireLifecycle(shippedAt != null && completedAt != null
                    && !shippedAt.isBefore(createdAt) && !completedAt.isBefore(shippedAt)
                    && cancelledAt == null);
            case CANCELLED -> requireLifecycle(cancelledAt != null && !cancelledAt.isBefore(createdAt)
                    && shippedAt == null && completedAt == null);
        }
    }
    private static void requireLifecycle(boolean valid) {
        if (!valid) throw new IllegalArgumentException("订单状态数据无效");
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void pageData(int rows, int page, int pageSize, int total) {
        if (page < 1 || pageSize < 1 || total < 0 || rows > pageSize || rows > total) throw new IllegalArgumentException("分页数据无效");
    }
    private static void validateImages(List<ImageRef> images) {
        if (images.size() > 5) throw new IllegalArgumentException("商品图片数据无效");
        Set<Long> ids = new HashSet<>(); Set<Integer> orders = new HashSet<>(); int covers = 0;
        for (int index = 0; index < images.size(); index++) {
            ImageRef image = images.get(index);
            if (!ids.add(image.id()) || !orders.add(image.sortOrder()) || image.sortOrder() != index
                    || image.sortOrder() > 4) throw new IllegalArgumentException("商品图片数据无效");
            if (image.cover()) covers++;
        }
        if (!images.isEmpty() && (covers != 1 || !images.getFirst().cover())) {
            throw new IllegalArgumentException("商品图片数据无效");
        }
    }
    private static List<ImageRef> orderedImages(List<ImageRef> images) {
        return images.stream().sorted(java.util.Comparator.comparingInt(ImageRef::sortOrder)).toList();
    }
    private static void rejectTrailingRows(Map<String, String> data, int count, String prefix) {
        for (String key : data.keySet()) if (key.startsWith(prefix)) {
            String rest = key.substring(prefix.length()); int dot = rest.indexOf('.'); String number = dot < 0 ? rest : rest.substring(0, dot);
            try { int index = Integer.parseInt(number); if (index < 0 || index >= count) throw new IllegalArgumentException("响应数据包含多余行"); }
            catch (NumberFormatException exception) { throw new IllegalArgumentException("响应数据行无效", exception); }
        }
    }
}
