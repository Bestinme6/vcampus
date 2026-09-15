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
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        AtomicReference<NotificationDestination> opened=new AtomicReference<>();
        NotificationDetail detail = new NotificationDetail(
                1L, NotificationType.BANK_FORUM_BOOST_PAID, NotificationSource.BANK,
                "论坛助力扣费成功", "你为帖子《校园运动会组队》支付 5.00 元，增加 50 初始热度。账户余额 95.00 元，流水号 FB-test。",
                NotificationTarget.BANK_LEDGER, 21L, false, null,
                Instant.parse("2026-08-29T10:00:00Z"));
        NotificationDetailDialog dialog = new NotificationDetailDialog(
                null, detail, opened::set);
        try {
            assertTrue(buttonLabels(dialog.getContentPane()).contains("查看银行流水"));
            assertTrue(labelTexts(dialog.getContentPane()).contains("银行通知"));
            String content=String.join("\n",textAreaTexts(dialog.getContentPane()));
            assertTrue(content.contains("5.00")&&content.contains("50")&&content.contains("FB-test"));
            findButton(dialog.getContentPane(),"查看银行流水").doClick();
            assertEquals(new NotificationDestination(NotificationTarget.BANK_LEDGER,21L),opened.get());
        } finally {
            dialog.dispose();
        }
        assertTrue(new NotificationDestination(
                NotificationTarget.BANK_LEDGER, 21L).navigable());
    }

    private JButton findButton(Container container,String text){
        for(Component component:container.getComponents()){
            if(component instanceof JButton button&&text.equals(button.getText()))return button;
            if(component instanceof Container child){JButton found=findButton(child,text);if(found!=null)return found;}
        }
        return null;
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
    private List<String> textAreaTexts(Container container){
        List<String> values=new ArrayList<>();
        for(Component component:container.getComponents()){
            if(component instanceof JTextArea area)values.add(area.getText());
            if(component instanceof Container child)values.addAll(textAreaTexts(child));
        }
        return values;
    }
}
