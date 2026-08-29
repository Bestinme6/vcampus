package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationLibraryUiTest {
    @Test
    void messageCenterOffersLibrarySourceFilter() {
        NotificationPanel panel = new NotificationPanel(
                new VCampusClient("localhost", 1), "token", target -> { }, () -> { });

        assertTrue(buttonLabels(panel).contains("图书馆通知"));
    }

    @Test
    void libraryNotificationOffersMyLoansNavigation() {
        NotificationDetail detail = new NotificationDetail(
                1L, NotificationType.LIBRARY_DUE_SOON, NotificationSource.LIBRARY,
                "图书即将到期", "请及时归还", NotificationTarget.LIBRARY_LOANS,
                501L, false, null, Instant.parse("2026-08-27T00:00:00Z"));
        NotificationDetailDialog dialog = new NotificationDetailDialog(
                null, detail, target -> { });
        try {
            assertTrue(buttonLabels(dialog.getContentPane()).contains("查看我的借阅"));
        } finally {
            dialog.dispose();
        }
        assertTrue(new NotificationDestination(
                NotificationTarget.LIBRARY_LOANS, 501L).navigable());
    }

    private List<String> buttonLabels(Container container) {
        List<String> labels = new ArrayList<>();
        for (Component component : container.getComponents()) {
            if (component instanceof JButton button) {
                labels.add(button.getText());
            }
            if (component instanceof Container child) {
                labels.addAll(buttonLabels(child));
            }
        }
        return labels;
    }
}
