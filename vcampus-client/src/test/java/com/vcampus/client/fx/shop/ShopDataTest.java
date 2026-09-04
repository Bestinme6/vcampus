package com.vcampus.client.fx.shop;

import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShopDataTest {
    private static final String NOW = "2026-09-01T08:00:00Z";
    private static final String HASH = "0123456789abcdef".repeat(4);

    @Test
    void legacyNineFieldProductRowUsesOtherAndNoCover() throws Exception {
        ShopData.ProductPage page = ShopData.productPage(success(Map.of(
                "page", "1", "pageSize", "10", "total", "1", "count", "1",
                "row.0", productRow("12.30"))));

        assertEquals(ShopCategory.OTHER, page.rows().getFirst().category());
        assertNull(page.rows().getFirst().cover());
        assertEquals(new BigDecimal("12.30"), page.rows().getFirst().price());
    }

    @Test
    void additiveCategoryAndCoverMetadataAreDecodedTogether() throws Exception {
        Map<String, String> data = pageData(productRow("12.30"));
        data.put("row.0.category", ShopCategory.CAMPUS_MERCH.name());
        data.put("row.0.coverImageId", "9");
        data.put("row.0.coverHash", HASH);

        ShopData.Product product = ShopData.productPage(success(data)).rows().getFirst();

        assertEquals(ShopCategory.CAMPUS_MERCH, product.category());
        assertEquals(9L, product.cover().id());
        assertEquals(HASH, product.cover().sha256());
    }

    @Test
    void incompleteCoverMetadataAndTrailingRowsAreRejected() {
        Map<String, String> incomplete = pageData(productRow("12.30"));
        incomplete.put("row.0.coverImageId", "9");
        assertThrows(IllegalArgumentException.class,
                () -> ShopData.productPage(success(incomplete)));

        Map<String, String> trailing = pageData(productRow("12.30"));
        trailing.put("row.1", productRow("1.00"));
        assertThrows(IllegalArgumentException.class,
                () -> ShopData.productPage(success(trailing)));
    }

    @Test
    void malformedOrNonScaleTwoMoneyIsRejected() {
        for (String malformed : List.of("money", "-1.00", "1", "1.0", "1.000")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ShopData.productPage(success(pageData(productRow(malformed)))), malformed);
        }
    }

    @Test
    void malformedHashDuplicateOrderAndMoreThanFiveImagesAreRejected() {
        Map<String, String> malformedHash = detailData(1);
        malformedHash.put("image.0", imageRow(1, "ABC", 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> ShopData.productDetail(success(malformedHash)));

        Map<String, String> duplicateOrder = detailData(2);
        duplicateOrder.put("image.0", imageRow(1, HASH, 0, true));
        duplicateOrder.put("image.1", imageRow(2, "f".repeat(64), 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> ShopData.productDetail(success(duplicateOrder)));

        Map<String, String> tooMany = detailData(6);
        for (int index = 0; index < 6; index++) {
            tooMany.put("image." + index,
                    imageRow(index + 1, Integer.toHexString(index).repeat(64), index, index == 0));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ShopData.productDetail(success(tooMany)));
    }

    @Test
    void cartSnapshotsMustMatchQuantityAndEstimatedTotal() throws Exception {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("count", "1");
        data.put("estimatedTotal", "24.60");
        data.put("row.0", RowCodec.encode("1", "SKU-1", "校徽", "说明", "12.30",
                "2", "8", "true", "24.60", NOW));

        ShopData.Cart cart = ShopData.cart(success(data));

        assertEquals(2, cart.items().getFirst().quantity());
        assertEquals(new BigDecimal("24.60"), cart.estimatedTotal());

        data.put("estimatedTotal", "24.61");
        assertThrows(IllegalArgumentException.class, () -> ShopData.cart(success(data)));
    }

    @Test
    void orderDetailPreservesServerSnapshotsAndRejectsWrongSubtotals() throws Exception {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("order", orderRow("24.60"));
        data.put("count", "1");
        data.put("row.0", RowCodec.encode("3", "1", "SKU-OLD", "历史名称",
                "12.30", "2", "24.60"));

        ShopData.OrderDetail detail = ShopData.orderDetail(success(data));

        assertEquals("历史名称", detail.items().getFirst().nameSnapshot());
        assertEquals(ShopOrderStatus.PAID, detail.order().status());

        data.put("row.0", RowCodec.encode("3", "1", "SKU-OLD", "历史名称",
                "12.30", "2", "24.61"));
        assertThrows(IllegalArgumentException.class, () -> ShopData.orderDetail(success(data)));
    }

    @Test
    void failedResponsesPropagateTheServerMessage() {
        IOException failure = assertThrows(IOException.class,
                () -> ShopData.productPage(ResponseMessage.failure("request", "库存不足")));
        assertEquals("库存不足", failure.getMessage());
    }

    @Test
    void imageChunkRejectsInvalidBase64AndCountInconsistency() throws Exception {
        Map<String, String> data = new LinkedHashMap<>(Map.of(
                "imageId", "8", "mimeType", "image/png", "sha256", HASH,
                "totalBytes", "3", "totalChunks", "1", "chunkIndex", "0",
                "contentBase64", "AQID"));
        assertEquals(3, ShopData.imageChunk(success(data)).content().length);

        data.put("contentBase64", "%%%invalid%%%");
        assertThrows(IllegalArgumentException.class, () -> ShopData.imageChunk(success(data)));

        data.put("contentBase64", "AQID");
        data.put("chunkIndex", "1");
        assertThrows(IllegalArgumentException.class, () -> ShopData.imageChunk(success(data)));
    }

    private static ResponseMessage success(Map<String, String> data) {
        return ResponseMessage.success("request", "ok", data);
    }

    private static Map<String, String> pageData(String row) {
        return new LinkedHashMap<>(Map.of(
                "page", "1", "pageSize", "10", "total", "1", "count", "1",
                "row.0", row));
    }

    private static Map<String, String> detailData(int count) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("product", productRow("12.30"));
        data.put("category", ShopCategory.OTHER.name());
        data.put("imageCount", Integer.toString(count));
        return data;
    }

    private static String productRow(String price) {
        return RowCodec.encode("1", "SKU-1", "校徽", "说明", price, "8", "true", NOW, NOW);
    }

    private static String imageRow(long id, String hash, int order, boolean cover) {
        return RowCodec.encode(Long.toString(id), "image/png", "3", hash,
                Integer.toString(order), Boolean.toString(cover));
    }

    private static String orderRow(String total) {
        return RowCodec.encode("7", "ORD-7", "student", "学生", total, "PAID", NOW,
                "", "", "");
    }
}
