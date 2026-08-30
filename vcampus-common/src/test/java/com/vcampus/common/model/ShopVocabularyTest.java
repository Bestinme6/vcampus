package com.vcampus.common.model;

import com.vcampus.common.protocol.Actions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopVocabularyTest {
    @Test
    void shopWireVocabularyIsStable() {
        assertEquals("CANCELLED", ShopOrderStatus.CANCELLED.name());
        assertEquals("ORDER_CANCEL", ShopInventoryMovementType.ORDER_CANCEL.name());
        assertEquals("shop.checkout", Actions.SHOP_CHECKOUT);
        assertEquals("shop.admin.order.ship", Actions.SHOP_ADMIN_ORDER_SHIP);
        assertEquals("SHOP", NotificationSource.SHOP.name());
        assertEquals("SHOP_ORDERS", NotificationTarget.SHOP_ORDERS.name());
        assertEquals("SHOP_ORDER_SHIPPED", NotificationType.SHOP_ORDER_SHIPPED.name());
    }

    @Test
    void onlyShopAndSuperAdministratorsCanManageShop() {
        assertFalse(ShopAccessPolicy.canManage(Set.of(UserRole.STUDENT)));
        assertFalse(ShopAccessPolicy.canManage(Set.of(UserRole.BANK_ADMIN)));
        assertTrue(ShopAccessPolicy.canManage(Set.of(UserRole.SHOP_ADMIN)));
        assertTrue(ShopAccessPolicy.canManage(Set.of(UserRole.SUPER_ADMIN)));
    }

    @Test
    void shopReceiptsHaveDistinctNotificationTypes() {
        var types = Arrays.stream(NotificationType.values()).map(Enum::name).toList();

        assertTrue(types.contains("SHOP_ORDER_PAID"));
        assertTrue(types.contains("SHOP_ORDER_REFUNDED"));
    }
}
