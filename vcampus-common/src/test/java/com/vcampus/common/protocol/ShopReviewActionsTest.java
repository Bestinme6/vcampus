package com.vcampus.common.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ShopReviewActionsTest {
    @Test
    void reviewActionsUseDedicatedShopPrefixes() {
        assertEquals("shop.review.list", Actions.SHOP_REVIEW_LIST);
        assertEquals("shop.review.create", Actions.SHOP_REVIEW_CREATE);
        assertEquals("shop.review.update", Actions.SHOP_REVIEW_UPDATE);
        assertEquals("shop.review.delete", Actions.SHOP_REVIEW_DELETE);
        assertEquals("shop.review.like.set", Actions.SHOP_REVIEW_LIKE_SET);
        assertEquals("shop.review.image.upload.start", Actions.SHOP_REVIEW_IMAGE_UPLOAD_START);
        assertEquals("shop.review.image.upload.chunk", Actions.SHOP_REVIEW_IMAGE_UPLOAD_CHUNK);
        assertEquals("shop.review.image.upload.complete", Actions.SHOP_REVIEW_IMAGE_UPLOAD_COMPLETE);
        assertEquals("shop.review.image.getChunk", Actions.SHOP_REVIEW_IMAGE_GET_CHUNK);
        assertEquals("shop.admin.review.search", Actions.SHOP_ADMIN_REVIEW_SEARCH);
        assertEquals("shop.admin.review.hide", Actions.SHOP_ADMIN_REVIEW_HIDE);
        assertEquals("shop.admin.review.restore", Actions.SHOP_ADMIN_REVIEW_RESTORE);
    }
}
