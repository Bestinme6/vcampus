package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.client.ui.NotificationViewData.NotificationDetail;
import com.vcampus.client.ui.NotificationViewData.NotificationRow;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationBankUiTest {
    @Test
    void messageCenterOffersBankSourceFilterAndCardLabel() {
        NotificationPanel panel = new NotificationPanel(
                new VCampusClient("localhost", 1), "token", target -> { }, () -> { });
        NotificationCard card = new NotificationCard(new NotificationRow(
                1L, NotificationType.BANK_TRANSFER_RECEIVED, NotificationSource.BANK,
                "收到转账", "李老师向您转账 10.00 元",
                NotificationTarget.BANK_LEDGER, 21L, false,
                Instant.parse("2026-08-29T10:00:00Z")), () -> { });

        assertTrue(buttonLabels(panel).contains("银行通知"));
        assertTrue(labelTexts(card).contains("银行通知"));
    }

    @Test
    void bankNotificationOffersLedgerNavigation() {
        NotificationDetail detail = new NotificationDetail(
                1L, NotificationType.BANK_ACCOUNT_TOPPED_UP, NotificationSource.BANK,
                "账户已充值", "银行管理员已充值 50.00 元",
                NotificationTarget.BANK_LEDGER, 21L, false, null,
                Instant.parse("2026-08-29T10:00:00Z"));
        NotificationDetailDialog dialog = new NotificationDetailDialog(
                null, detail, target -> { });
        try {
            assertTrue(buttonLabels(dialog.getContentPane()).contains("查看银行流水"));
        } finally {
            dialog.dispose();
        }
        assertTrue(new NotificationDestination(
                NotificationTarget.BANK_LEDGER, 21L).navigable());
    }

    private List<String> buttonLabels(Container container) {
        List<String> labels = new ArrayList<>();
        for (Component component : container.getComponents()) {
            if (component instanceof JButton button) labels.add(button.getText());
            if (component instanceof Container child) labels.addAll(buttonLabels(child));
        }
        return labels;
    }

    private List<String> labelTexts(Container container) {
        List<String> labels = new ArrayList<>();
        for (Component component : container.getComponents()) {
            if (component instanceof JLabel label) labels.add(label.getText());
            if (component instanceof Container child) labels.addAll(labelTexts(child));
        }
        return labels;
    }
}
