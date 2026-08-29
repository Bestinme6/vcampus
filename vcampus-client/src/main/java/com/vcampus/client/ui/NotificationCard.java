package com.vcampus.client.ui;

import com.vcampus.client.ui.NotificationViewData.NotificationRow;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class NotificationCard extends RoundedPanel {
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final JLabel unreadDot = new JLabel("●");
    private boolean read;

    NotificationCard(NotificationRow row, Runnable openAction) {
        super(Theme.SURFACE_HOVER, 14);
        read = row.read();
        setLayout(new BorderLayout(16, 0));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(16, 16, 16, 18)));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        unreadDot.setForeground(Theme.DANGER);
        unreadDot.setFont(unreadDot.getFont().deriveFont(Font.BOLD, 16f));
        unreadDot.setToolTipText("未读消息");
        add(unreadDot, BorderLayout.WEST);

        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        JLabel source = new JLabel(sourceLabel(row));
        source.setForeground(Theme.ACCENT);
        source.setFont(source.getFont().deriveFont(Font.BOLD, 12f));
        JLabel title = new JLabel(row.title());
        title.setForeground(Theme.TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        JLabel summary = new JLabel(row.summary());
        summary.setForeground(Theme.MUTED);
        copy.add(source);
        copy.add(Box.createVerticalStrut(5));
        copy.add(title);
        copy.add(Box.createVerticalStrut(7));
        copy.add(summary);
        add(copy, BorderLayout.CENTER);

        JLabel time = new JLabel(TIME_FORMAT.format(row.createdAt()));
        time.setForeground(Theme.MUTED);
        add(time, BorderLayout.EAST);

        updateUnreadDot();
        MouseAdapter listener = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (isEnabled()) {
                    openAction.run();
                }
            }
        };
        attachListener(this, listener);
    }

    void markRead() {
        read = true;
        updateUnreadDot();
    }

    private void updateUnreadDot() {
        unreadDot.setVisible(!read);
    }

    private String sourceLabel(NotificationRow row) {
        return switch (row.source()) {
            case ACADEMIC -> "教务通知";
            case STUDENT_STATUS -> "学籍通知";
            case ACCOUNT_SECURITY -> "账号安全";
            case LIBRARY -> "图书馆通知";
            case FORUM -> "校园论坛";
            case BANK -> "银行通知";
            case SHOP -> "商店通知";
        };
    }

    private void attachListener(java.awt.Component component, MouseAdapter listener) {
        component.addMouseListener(listener);
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                attachListener(child, listener);
            }
        }
    }
}
