package com.vcampus.common.protocol;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopImageActionsTest {
    @Test
    void definesStableShopCatalogAndImageActions() {
        Map<String, String> actions = Map.of(
                "SHOP_PRODUCT_GET", Actions.SHOP_PRODUCT_GET,
                "SHOP_IMAGE_GET_CHUNK", Actions.SHOP_IMAGE_GET_CHUNK,
                "SHOP_BUY_NOW", Actions.SHOP_BUY_NOW,
                "SHOP_ADMIN_IMAGE_UPLOAD_START", Actions.SHOP_ADMIN_IMAGE_UPLOAD_START,
                "SHOP_ADMIN_IMAGE_UPLOAD_CHUNK", Actions.SHOP_ADMIN_IMAGE_UPLOAD_CHUNK,
                "SHOP_ADMIN_IMAGE_UPLOAD_COMPLETE", Actions.SHOP_ADMIN_IMAGE_UPLOAD_COMPLETE,
                "SHOP_ADMIN_IMAGE_COMMIT", Actions.SHOP_ADMIN_IMAGE_COMMIT);

        assertEquals("shop.product.get", actions.get("SHOP_PRODUCT_GET"));
        assertEquals("shop.image.getChunk", actions.get("SHOP_IMAGE_GET_CHUNK"));
        assertEquals("shop.buyNow", actions.get("SHOP_BUY_NOW"));
        assertEquals("shop.admin.image.upload.start", actions.get("SHOP_ADMIN_IMAGE_UPLOAD_START"));
        assertEquals("shop.admin.image.upload.chunk", actions.get("SHOP_ADMIN_IMAGE_UPLOAD_CHUNK"));
        assertEquals("shop.admin.image.upload.complete", actions.get("SHOP_ADMIN_IMAGE_UPLOAD_COMPLETE"));
        assertEquals("shop.admin.image.commit", actions.get("SHOP_ADMIN_IMAGE_COMMIT"));
        assertTrue(actions.values().stream().allMatch(value -> value.startsWith("shop.")));
    }
}
