package com.vcampus.client.ui;

import com.vcampus.client.ui.NotificationViewData.NotificationDetail;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationShopUiTest {
    @Test
    void shopOrderDestinationRequiresOrderId() {
        assertFalse(new NotificationDestination(
                NotificationTarget.SHOP_ORDERS, null).navigable());
        assertTrue(new NotificationDestination(
                NotificationTarget.SHOP_ORDERS, 73L).navigable());
    }

    @Test
    void shopNotificationOffersOrderDetailNavigation() {
        NotificationDetail detail = new NotificationDetail(
                1L, NotificationType.SHOP_ORDER_SHIPPED, NotificationSource.SHOP,
                "您的校园商店订单已发货", "订单 SO1 已发货，请注意查收。",
                NotificationTarget.SHOP_ORDERS, 73L, false, null,
                Instant.parse("2026-08-30T00:00:00Z"));
        NotificationDetailDialog dialog = new NotificationDetailDialog(
                null, detail, destination -> { });
        try {
            assertTrue(buttonLabels(dialog.getContentPane()).contains("查看订单详情"));
        } finally {
            dialog.dispose();
        }
    }

    private List<String> buttonLabels(Container container) {
        List<String> labels = new ArrayList<>();
        for (Component component : container.getComponents()) {
            if (component instanceof JButton button) labels.add(button.getText());
            if (component instanceof Container child) labels.addAll(buttonLabels(child));
        }
        return labels;
    }
}
