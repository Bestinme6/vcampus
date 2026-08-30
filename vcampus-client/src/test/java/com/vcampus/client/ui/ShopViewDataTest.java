package com.vcampus.client.ui;

import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopViewDataTest {
    @Test
    void parsesProductCartAndOrderSnapshots() {
        Map<String, String> products = page(RowCodec.encode("3", "BOOK-1", "Java 教材",
                "课程教材", "25.00", "8", "true",
                "2026-08-29T10:00:00Z", "2026-08-29T11:00:00Z"));
        ShopViewData.ProductRow product = ShopViewData.productPage(
                ResponseMessage.success("p", "ok", products)).rows().getFirst();
        assertEquals(new BigDecimal("25.00"), product.price());
        assertEquals(8, product.stock());

        Map<String, String> cart = new LinkedHashMap<>();
        cart.put("count", "1");
        cart.put("estimatedTotal", "50.00");
        cart.put("row.0", RowCodec.encode("3", "BOOK-1", "Java 教材", "课程教材",
                "25.00", "2", "8", "true", "50.00", "2026-08-29T11:10:00Z"));
        ShopViewData.CartView cartView = ShopViewData.cart(
                ResponseMessage.success("c", "ok", cart));
        assertEquals(2, cartView.rows().getFirst().quantity());
        assertEquals(new BigDecimal("50.00"), cartView.estimatedTotal());

        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("order", RowCodec.encode("9", "SO1", "11", "student", "张同学",
                "50.00", "PAID", "2026-08-29T12:00:00Z", "", "", ""));
        detail.put("count", "1");
        detail.put("row.0", RowCodec.encode("15", "3", "BOOK-1", "Java 教材",
                "25.00", "2", "50.00"));
        ShopViewData.OrderDetail order = ShopViewData.orderDetail(
                ResponseMessage.success("o", "ok", detail));
        assertEquals(ShopOrderStatus.PAID, order.order().status());
        assertEquals("Java 教材", order.items().getFirst().name());
        assertEquals(Instant.parse("2026-08-29T12:00:00Z"), order.order().createdAt());
    }

    @Test
    void adminVisibilityAndOrderActionsFollowRolesAndState() {
        assertFalse(ShopViewData.showAdminTabs(Set.of(UserRole.STUDENT)));
        assertTrue(ShopViewData.showAdminTabs(Set.of(UserRole.SHOP_ADMIN)));
        assertTrue(ShopViewData.canCancel(ShopOrderStatus.PAID));
        assertTrue(ShopViewData.canConfirm(ShopOrderStatus.SHIPPED));
        assertFalse(ShopViewData.canCancel(ShopOrderStatus.COMPLETED));
        assertFalse(ShopViewData.canConfirm(ShopOrderStatus.CANCELLED));
    }

    @Test
    void rejectsMalformedRows() {
        assertThrows(IllegalArgumentException.class, () -> ShopViewData.productPage(
                ResponseMessage.success("r", "ok", page(RowCodec.encode("too", "short")))));
    }

    private Map<String, String> page(String row) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("page", "1");
        data.put("pageSize", "10");
        data.put("total", "1");
        data.put("count", "1");
        data.put("row.0", row);
        return data;
    }
}
