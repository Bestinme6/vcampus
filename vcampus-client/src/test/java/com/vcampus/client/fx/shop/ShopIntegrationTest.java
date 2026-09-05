package com.vcampus.client.fx.shop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShopIntegrationTest {
    @Test void campusRoutesResolveToNativeShopPages() {
        assertEquals("catalog", ShopRoute.fromCampus("shop"));
        assertEquals("cart", ShopRoute.fromCampus("shop-cart"));
        assertEquals("orders", ShopRoute.fromCampus("shop-orders"));
        assertEquals("order/42", ShopRoute.fromCampus("shop-order/42"));
        assertNull(ShopRoute.fromCampus("bank"));
        assertNull(ShopRoute.fromCampus("shop-order/not-a-number"));
        assertNull(ShopRoute.fromCampus("shop-order/0"));
    }
}
