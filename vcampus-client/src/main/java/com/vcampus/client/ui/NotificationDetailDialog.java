package com.vcampus.client.ui;

import com.vcampus.client.ui.NotificationViewData.NotificationDetail;
import com.vcampus.common.model.NotificationTarget;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

final class NotificationDetailDialog extends JDialog {
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    NotificationDetailDialog(
            Window owner, NotificationDetail detail,
            Consumer<NotificationDestination> targetNavigator) {
        super(owner, "消息详情", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(560, 390));
        setSize(620, 440);
        setLocationRelativeTo(owner);

        JPanel root = new JPanel(new BorderLayout(0, 18));
        root.setBackground(Theme.BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(24, 28, 22, 28));

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel source = new JLabel(sourceLabel(detail));
        source.setForeground(Theme.ACCENT);
        source.setFont(source.getFont().deriveFont(Font.BOLD, 13f));
        JLabel title = new JLabel(detail.title());
        title.setForeground(Theme.TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        JLabel time = new JLabel(TIME_FORMAT.format(detail.createdAt()));
        time.setForeground(Theme.MUTED);
        heading.add(source);
        heading.add(Box.createVerticalStrut(7));
        heading.add(title);
        heading.add(Box.createVerticalStrut(7));
        heading.add(time);
        root.add(heading, BorderLayout.NORTH);

        JTextArea content = new JTextArea(detail.content());
        content.setEditable(false);
        content.setLineWrap(true);
        content.setWrapStyleWord(true);
        content.setForeground(Theme.TEXT);
        content.setBackground(Theme.SURFACE_HOVER);
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        root.add(scroll, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actions.setOpaque(false);
        NotificationDestination destination = NotificationDestination.from(detail);
        String actionLabel = actionLabel(destination.target());
        if (actionLabel != null && destination.navigable()) {
            JButton open = new JButton(actionLabel);
            Theme.styleCommandButton(open);
            open.addActionListener(event -> {
                targetNavigator.accept(destination);
                dispose();
            });
            actions.add(open);
        }
        JButton close = new JButton("关闭");
        Theme.styleQuietButton(close);
        close.addActionListener(event -> dispose());
        actions.add(close);
        root.add(actions, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private String actionLabel(NotificationTarget target) {
        return switch (target) {
            case TEACHER_SCHEDULE -> "查看教师课表";
            case STUDENT_GRADES -> "查看我的成绩";
            case STUDENT_PROFILE -> "查看学籍信息";
            case LIBRARY_LOANS -> "查看我的借阅";
            case FORUM_POST -> "查看帖子";
            case BANK_LEDGER -> "查看银行流水";
            case SHOP_ORDERS -> "查看我的订单";
            case NONE -> null;
        };
    }

    private String sourceLabel(NotificationDetail detail) {
        return switch (detail.source()) {
            case ACADEMIC -> "教务通知";
            case STUDENT_STATUS -> "学籍通知";
            case ACCOUNT_SECURITY -> "账号安全";
            case LIBRARY -> "图书馆通知";
            case FORUM -> "校园论坛";
            case BANK -> "银行通知";
            case SHOP -> "商店通知";
        };
    }
}
