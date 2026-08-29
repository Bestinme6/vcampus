package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.client.ui.NotificationViewData.NotificationDetail;
import com.vcampus.client.ui.NotificationViewData.NotificationPage;
import com.vcampus.client.ui.NotificationViewData.NotificationRow;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.protocol.ResponseMessage;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

final class NotificationPanel extends JPanel {
    private final VCampusClient client;
    private final String sessionToken;
    private final Consumer<NotificationDestination> targetNavigator;
    private final Runnable unreadChanged;
    private final NotificationFilterPolicy policy = new NotificationFilterPolicy();
    private final JTextField keyword = new JTextField();
    private final JComboBox<ReadOption> readFilter = new JComboBox<>(new ReadOption[]{
            new ReadOption("全部状态", null),
            new ReadOption("仅未读", Boolean.FALSE),
            new ReadOption("仅已读", Boolean.TRUE)
    });
    private final JPanel list = new JPanel();
    private final JLabel pageLabel = new JLabel("第 1 页 / 共 1 页", SwingConstants.CENTER);
    private final JButton previous = new JButton("上一页");
    private final JButton next = new JButton("下一页");
    private final Map<NotificationSource, JButton> sourceButtons = new LinkedHashMap<>();
    private final JButton allSources = new JButton("全部消息");
    private long requestVersion;

    NotificationPanel(
            VCampusClient client,
            String sessionToken,
            Consumer<NotificationDestination> targetNavigator,
            Runnable unreadChanged) {
        this.client = client;
        this.sessionToken = sessionToken;
        this.targetNavigator = targetNavigator;
        this.unreadChanged = unreadChanged;
        setLayout(new BorderLayout(0, 18));
        setBackground(Theme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(28, 34, 28, 34));
        add(createHeader(), BorderLayout.NORTH);
        add(createList(), BorderLayout.CENTER);
        add(createFooter(), BorderLayout.SOUTH);
        updateSourceStyles();
        updatePagingControls();
    }

    void activate() {
        policy.goToPage(1);
        loadPage(null);
    }

    void refreshCurrentPage() {
        loadPage(null);
    }

    private JPanel createHeader() {
        JPanel wrapper = new JPanel();
        wrapper.setOpaque(false);
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));

        JPanel titleRow = new JPanel(new BorderLayout(18, 0));
        titleRow.setOpaque(false);
        JLabel title = new JLabel("全部消息");
        title.setForeground(Theme.TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        titleRow.add(title, BorderLayout.WEST);

        JPanel search = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        search.setOpaque(false);
        keyword.setPreferredSize(new Dimension(240, 40));
        keyword.setToolTipText("输入标题或正文关键词");
        Theme.styleField(keyword);
        readFilter.setPreferredSize(new Dimension(120, 40));
        readFilter.setForeground(Theme.TEXT);
        JButton query = new JButton("查询");
        Theme.styleCommandButton(query);
        query.addActionListener(event -> {
            policy.changeKeyword(keyword.getText());
            policy.changeRead(((ReadOption) readFilter.getSelectedItem()).value());
            loadPage(query);
        });
        keyword.addActionListener(event -> query.doClick());
        search.add(keyword);
        search.add(readFilter);
        search.add(query);
        titleRow.add(search, BorderLayout.EAST);
        wrapper.add(titleRow);
        wrapper.add(Box.createVerticalStrut(18));

        JPanel filterRow = new JPanel(new BorderLayout());
        filterRow.setOpaque(false);
        JPanel sources = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        sources.setOpaque(false);
        addSourceButton(sources, allSources, null);
        addSourceButton(sources, new JButton("教务通知"), NotificationSource.ACADEMIC);
        addSourceButton(sources, new JButton("学籍通知"), NotificationSource.STUDENT_STATUS);
        addSourceButton(sources, new JButton("账号安全"), NotificationSource.ACCOUNT_SECURITY);
        addSourceButton(sources, new JButton("图书馆通知"), NotificationSource.LIBRARY);
        addSourceButton(sources, new JButton("论坛通知"), NotificationSource.FORUM);
        addSourceButton(sources, new JButton("银行通知"), NotificationSource.BANK);
        filterRow.add(sources, BorderLayout.WEST);

        JButton markAll = new JButton("一键已读");
        Theme.styleQuietButton(markAll);
        markAll.setForeground(Theme.TEXT);
        markAll.setFont(markAll.getFont().deriveFont(Font.BOLD));
        markAll.addActionListener(event -> markAllRead(markAll));
        filterRow.add(markAll, BorderLayout.EAST);
        wrapper.add(filterRow);
        return wrapper;
    }

    private void addSourceButton(
            JPanel parent, JButton button, NotificationSource source) {
        Theme.styleQuietButton(button);
        button.setFont(button.getFont().deriveFont(Font.BOLD));
        button.addActionListener(event -> {
            policy.changeSource(source);
            updateSourceStyles();
            loadPage(button);
        });
        if (source != null) {
            sourceButtons.put(source, button);
        }
        parent.add(button);
    }

    private JScrollPane createList() {
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(
                list,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scroll.setOpaque(false);
        scroll.getViewport().setBackground(Theme.BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        return scroll;
    }

    private JPanel createFooter() {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 0));
        footer.setOpaque(false);
        Theme.styleQuietButton(previous);
        Theme.styleQuietButton(next);
        previous.setForeground(Theme.TEXT);
        next.setForeground(Theme.TEXT);
        previous.setFont(previous.getFont().deriveFont(Font.BOLD));
        next.setFont(next.getFont().deriveFont(Font.BOLD));
        previous.addActionListener(event -> {
            policy.goToPage(policy.page() - 1);
            loadPage(previous);
        });
        next.addActionListener(event -> {
            policy.goToPage(policy.page() + 1);
            loadPage(next);
        });
        pageLabel.setForeground(Theme.TEXT);
        footer.add(previous);
        footer.add(pageLabel);
        footer.add(next);
        return footer;
    }

    private void loadPage(JButton initiatingButton) {
        long version = ++requestVersion;
        if (initiatingButton != null) {
            initiatingButton.setEnabled(false);
        }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        CompletableFuture.supplyAsync(() -> {
            try {
                ResponseMessage response = client.searchNotifications(
                        sessionToken, policy.keyword(), policy.source(), policy.read(), policy.page());
                return NotificationPage.parse(response);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((page, error) -> SwingUtilities.invokeLater(() -> {
            if (initiatingButton != null) {
                initiatingButton.setEnabled(true);
            }
            if (version != requestVersion) {
                return;
            }
            setCursor(Cursor.getDefaultCursor());
            if (error != null) {
                showError(message(error));
                return;
            }
            policy.applyTotal(page.total(), page.pageSize());
            if (policy.page() != page.page()) {
                loadPage(null);
                return;
            }
            render(page);
        }));
    }

    private void render(NotificationPage page) {
        list.removeAll();
        if (page.rows().isEmpty()) {
            JLabel empty = new JLabel("暂无消息", SwingConstants.CENTER);
            empty.setForeground(Theme.MUTED);
            empty.setBorder(BorderFactory.createEmptyBorder(80, 20, 80, 20));
            empty.setAlignmentX(CENTER_ALIGNMENT);
            list.add(empty);
        } else {
            for (NotificationRow row : page.rows()) {
                NotificationCard card = new NotificationCard(row, () -> openDetail(row));
                card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 112));
                card.setAlignmentX(LEFT_ALIGNMENT);
                list.add(card);
                list.add(Box.createVerticalStrut(10));
            }
        }
        list.revalidate();
        list.repaint();
        updatePagingControls();
    }

    private void openDetail(NotificationRow row) {
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        CompletableFuture.supplyAsync(() -> {
            try {
                return NotificationDetail.parse(
                        client.getNotification(sessionToken, row.id()));
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((detail, error) -> SwingUtilities.invokeLater(() -> {
            setCursor(Cursor.getDefaultCursor());
            if (error != null) {
                showError(message(error));
                return;
            }
            Window owner = SwingUtilities.getWindowAncestor(this);
            new NotificationDetailDialog(owner, detail, targetNavigator).setVisible(true);
            if (!detail.read()) {
                markRead(row.id());
            }
        }));
    }

    private void markRead(long notificationId) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return client.markNotificationRead(sessionToken, notificationId);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((response, error) -> SwingUtilities.invokeLater(() -> {
            if (error != null) {
                showError(message(error));
            } else if (!response.success()) {
                showError(response.message());
            } else {
                unreadChanged.run();
                refreshCurrentPage();
            }
        }));
    }

    private void markAllRead(JButton button) {
        button.setEnabled(false);
        CompletableFuture.supplyAsync(() -> {
            try {
                return client.markAllNotificationsRead(sessionToken);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((response, error) -> SwingUtilities.invokeLater(() -> {
            button.setEnabled(true);
            if (error != null) {
                showError(message(error));
            } else if (!response.success()) {
                showError(response.message());
            } else {
                unreadChanged.run();
                refreshCurrentPage();
            }
        }));
    }

    private void updateSourceStyles() {
        boolean allSelected = policy.source() == null;
        allSources.setForeground(allSelected ? Theme.ACCENT : Theme.TEXT);
        allSources.setBackground(allSelected ? Theme.SECONDARY : Theme.SURFACE_HOVER);
        sourceButtons.forEach((source, button) -> {
            boolean selected = source == policy.source();
            button.setForeground(selected ? Theme.ACCENT : Theme.TEXT);
            button.setBackground(selected ? Theme.SECONDARY : Theme.SURFACE_HOVER);
        });
    }

    private void updatePagingControls() {
        pageLabel.setText("第 " + policy.page() + " 页 / 共 " + policy.totalPages() + " 页");
        previous.setEnabled(policy.canGoPrevious());
        next.setEnabled(policy.canGoNext());
    }

    private String message(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null || cause.getMessage().isBlank()
                ? "消息请求失败" : cause.getMessage();
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "加载失败", JOptionPane.ERROR_MESSAGE);
    }

    private record ReadOption(String label, Boolean value) {
        @Override
        public String toString() {
            return label;
        }
    }
}
