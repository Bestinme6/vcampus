package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.UserRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopModuleNavigationTest {
    @Test
    void ordinaryAndAdminTabsMatchTheApprovedDesign() {
        ShopModulePanel ordinary = panel(Set.of(UserRole.STUDENT));
        assertEquals(List.of("商品列表", "购物车", "我的订单"), ordinary.tabTitles());

        ShopModulePanel admin = panel(Set.of(UserRole.TEACHER, UserRole.SHOP_ADMIN));
        assertEquals(List.of("商品列表", "购物车", "我的订单", "商品管理", "订单管理"),
                admin.tabTitles());
    }

    @Test
    void notificationDeepLinkOpensMyOrders() {
        ShopModulePanel panel = panel(Set.of(UserRole.STUDENT));
        panel.openOrders();
        assertEquals("我的订单", panel.selectedTabTitle());
    }

    private ShopModulePanel panel(Set<UserRole> roles) {
        return new ShopModulePanel(new VCampusClient("localhost", 1), "token", roles, null);
    }
}
