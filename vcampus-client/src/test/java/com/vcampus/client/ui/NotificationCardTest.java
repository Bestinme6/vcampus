package com.vcampus.client.ui;

import com.vcampus.client.ui.NotificationViewData.NotificationRow;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationCardTest {
    @Test
    void libraryNotificationShowsReadableSourceLabel() {
        NotificationRow row = new NotificationRow(
                1L, NotificationType.LIBRARY_DUE_SOON, NotificationSource.LIBRARY,
                "图书即将到期", "请及时归还", NotificationTarget.LIBRARY_LOANS,
                501L, false, Instant.parse("2026-08-27T00:00:00Z"));

        NotificationCard card = new NotificationCard(row, () -> { });

        assertTrue(labels(card).contains("图书馆通知"));
    }

    private List<String> labels(Container container) {
        List<String> values = new ArrayList<>();
        for (Component component : container.getComponents()) {
            if (component instanceof JLabel label) {
                values.add(label.getText());
            }
            if (component instanceof Container child) {
                values.addAll(labels(child));
            }
        }
        return values;
    }
}
