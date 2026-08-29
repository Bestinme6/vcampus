package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.ForumContentStatus;
import com.vcampus.common.model.ForumModerationAction;
import com.vcampus.common.model.ForumTargetType;
import com.vcampus.common.protocol.ResponseMessage;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ForumAdminPanel extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final VCampusClient client;
    private final String token;
    private final JTabbedPane tabs = new JTabbedPane();
    private final SectionPane sectionPane = new SectionPane();
    private final ContentPane contentPane = new ContentPane();
    private final LogPane logPane = new LogPane();

    ForumAdminPanel(VCampusClient client, String token) {
        this.client = client;
        this.token = token;
        setLayout(new BorderLayout());
        setOpaque(false);
        tabs.addTab("板块管理", sectionPane);
        tabs.addTab("内容审核", contentPane);
        tabs.addTab("操作日志", logPane);
        tabs.addChangeListener(event -> refreshActive());
        add(tabs, BorderLayout.CENTER);
    }

    void activate() {
        refreshActive();
    }

    private void refreshActive() {
        switch (tabs.getSelectedIndex()) {
            case 0 -> sectionPane.refresh();
            case 1 -> contentPane.refresh();
            case 2 -> logPane.refresh();
            default -> { }
        }
    }

    private void failure(ResponseMessage response) {
        JOptionPane.showMessageDialog(this, response.message(), "论坛管理",
                JOptionPane.WARNING_MESSAGE);
    }

    private void error(Throwable error) {
        JOptionPane.showMessageDialog(this, "无法连接论坛服务：" + error.getMessage(),
                "论坛管理", JOptionPane.ERROR_MESSAGE);
    }

    private JTable table(AbstractTableModel model) {
        JTable table = new JTable(model);
        table.setRowHeight(30);
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        return table;
    }

    private final class SectionPane extends JPanel {
        private final SectionModel model = new SectionModel();
        private final JTable table = table(model);
        private final JButton refresh = command("刷新", this::refresh);

        private SectionPane() {
            setLayout(new BorderLayout(0, 10));
            setBorder(BorderFactory.createEmptyBorder(14, 10, 10, 10));
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            JButton create = command("新建板块", () -> edit(null));
            JButton edit = command("编辑", () -> edit(selected()));
            JButton toggle = command("启用 / 停用", this::toggle);
            actions.add(create);
            actions.add(edit);
            actions.add(toggle);
            actions.add(refresh);
            add(actions, BorderLayout.NORTH);
            add(new JScrollPane(table), BorderLayout.CENTER);
        }

        private void refresh() {
            refresh.setEnabled(false);
            ForumAsync.run(() -> client.listForumSections(token, true), response -> {
                refresh.setEnabled(true);
                if (!response.success()) { failure(response); return; }
                model.setRows(ForumViewData.sections(response));
            }, cause -> { refresh.setEnabled(true); error(cause); });
        }

        private ForumViewData.SectionRow selected() {
            int row = table.getSelectedRow();
            return row < 0 ? null : model.row(table.convertRowIndexToModel(row));
        }

        private void edit(ForumViewData.SectionRow row) {
            JTextField code = new JTextField(row == null ? "" : row.code());
            JTextField name = new JTextField(row == null ? "" : row.name());
            JTextArea description = new JTextArea(row == null ? "" : row.description(), 4, 28);
            description.setLineWrap(true);
            description.setWrapStyleWord(true);
            JSpinner order = new JSpinner(new SpinnerNumberModel(
                    row == null ? 0 : row.sortOrder(), 0, 9999, 1));
            JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
            form.add(new JLabel("板块代码")); form.add(code);
            form.add(new JLabel("板块名称")); form.add(name);
            form.add(new JLabel("排序值")); form.add(order);
            form.add(new JLabel("简介")); form.add(new JScrollPane(description));
            if (JOptionPane.showConfirmDialog(this, form,
                    row == null ? "新建板块" : "编辑板块",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                    != JOptionPane.OK_OPTION) return;
            Map<String, String> values = new LinkedHashMap<>();
            if (row != null) values.put("sectionId", Long.toString(row.id()));
            values.put("code", code.getText().strip());
            values.put("name", name.getText().strip());
            values.put("description", description.getText().strip());
            values.put("sortOrder", order.getValue().toString());
            ForumAsync.run(() -> client.saveForumSection(token, values), response -> {
                if (!response.success()) { failure(response); return; }
                refresh();
            }, ForumAdminPanel.this::error);
        }

        private void toggle() {
            ForumViewData.SectionRow row = selected();
            if (row == null) {
                JOptionPane.showMessageDialog(this, "请先选择一个板块");
                return;
            }
            ForumAsync.run(() -> client.setForumSectionEnabled(token, row.id(), !row.enabled()),
                    response -> {
                        if (!response.success()) { failure(response); return; }
                        refresh();
                    }, ForumAdminPanel.this::error);
        }
    }

    private final class ContentPane extends JPanel {
        private final ContentModel model = new ContentModel();
        private final JTable table = table(model);
        private final JComboBox<TargetChoice> target = new JComboBox<>(TargetChoice.values());
        private final JComboBox<StatusChoice> status = new JComboBox<>(StatusChoice.values());
        private final JTextField keyword = new JTextField();
        private final JLabel summary = new JLabel("共 0 条");
        private int page = 1;
        private int total;

        private ContentPane() {
            setLayout(new BorderLayout(0, 10));
            setBorder(BorderFactory.createEmptyBorder(14, 10, 10, 10));
            JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            keyword.setPreferredSize(new Dimension(200, 36));
            keyword.setToolTipText("标题、正文或作者");
            Theme.styleField(keyword);
            JButton search = command("查询", () -> { page = 1; refresh(); });
            filters.add(target); filters.add(status); filters.add(keyword); filters.add(search);
            filters.add(command("隐藏", () -> moderate(ForumModerationAction.HIDE)));
            filters.add(command("恢复", () -> moderate(ForumModerationAction.RESTORE)));
            filters.add(command("锁定/解锁", this::toggleLock));
            filters.add(command("置顶/取消", this::togglePin));
            filters.add(command("精华/取消", this::toggleFeature));
            add(filters, BorderLayout.NORTH);
            add(new JScrollPane(table), BorderLayout.CENTER);
            JPanel pager = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            pager.add(summary);
            pager.add(command("上一页", () -> { if (page > 1) { page--; refresh(); } }));
            pager.add(command("下一页", () -> { if (page * 10 < total) { page++; refresh(); } }));
            add(pager, BorderLayout.SOUTH);
        }

        private void refresh() {
            TargetChoice selectedTarget = (TargetChoice) target.getSelectedItem();
            StatusChoice selectedStatus = (StatusChoice) status.getSelectedItem();
            ForumAsync.run(() -> client.searchForumAdminContent(token,
                    selectedTarget == null ? ForumTargetType.POST : selectedTarget.value,
                    selectedStatus == null ? null : selectedStatus.value,
                    keyword.getText().strip(), page), response -> {
                if (!response.success()) { failure(response); return; }
                ForumViewData.AdminContentPage result = ForumViewData.adminContentPage(response);
                model.setRows(result.rows());
                total = result.total();
                summary.setText("第 " + result.page() + " 页 · 共 " + total + " 条");
            }, ForumAdminPanel.this::error);
        }

        private ForumViewData.AdminContentRow selected() {
            int row = table.getSelectedRow();
            if (row < 0) {
                JOptionPane.showMessageDialog(this, "请先选择一条内容");
                return null;
            }
            return model.row(table.convertRowIndexToModel(row));
        }

        private void moderate(ForumModerationAction action) {
            ForumViewData.AdminContentRow row = selected();
            if (row == null) return;
            String reason = "";
            if (ForumModerationReasonPolicy.requiresInput(action)) {
                String actionName = action == ForumModerationAction.HIDE ? "隐藏" : "恢复";
                reason = JOptionPane.showInputDialog(this, "请输入" + actionName + "原因", "内容审核",
                        JOptionPane.PLAIN_MESSAGE);
                if (reason == null) return;
            }
            final String moderationReason = reason;
            ForumAsync.run(() -> row.targetType() == ForumTargetType.POST
                            ? client.moderateForumPost(token, row.id(), action, moderationReason)
                            : client.moderateForumComment(token, row.id(), action, moderationReason),
                    response -> {
                        if (!response.success()) { failure(response); return; }
                        refresh();
                    }, ForumAdminPanel.this::error);
        }

        private void toggleLock() {
            ForumViewData.AdminContentRow row = selectedPost();
            if (row != null) moderate(row.locked()
                    ? ForumModerationAction.UNLOCK : ForumModerationAction.LOCK);
        }

        private void togglePin() {
            ForumViewData.AdminContentRow row = selectedPost();
            if (row != null) moderate(row.pinned()
                    ? ForumModerationAction.UNPIN : ForumModerationAction.PIN);
        }

        private void toggleFeature() {
            ForumViewData.AdminContentRow row = selectedPost();
            if (row != null) moderate(row.featured()
                    ? ForumModerationAction.UNFEATURE : ForumModerationAction.FEATURE);
        }

        private ForumViewData.AdminContentRow selectedPost() {
            ForumViewData.AdminContentRow row = selected();
            if (row != null && row.targetType() != ForumTargetType.POST) {
                JOptionPane.showMessageDialog(this, "该操作仅适用于帖子");
                return null;
            }
            return row;
        }
    }

    private final class LogPane extends JPanel {
        private final LogModel model = new LogModel();

        private LogPane() {
            setLayout(new BorderLayout(0, 10));
            setBorder(BorderFactory.createEmptyBorder(14, 10, 10, 10));
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            actions.add(command("刷新", this::refresh));
            add(actions, BorderLayout.NORTH);
            add(new JScrollPane(table(model)), BorderLayout.CENTER);
        }

        private void refresh() {
            ForumAsync.run(() -> client.searchForumModerationLogs(token, 1), response -> {
                if (!response.success()) { failure(response); return; }
                model.setRows(ForumViewData.moderationLogPage(response).rows());
            }, ForumAdminPanel.this::error);
        }
    }

    private JButton command(String text, Runnable action) {
        JButton button = new JButton(text);
        Theme.styleQuietButton(button);
        button.addActionListener(event -> action.run());
        return button;
    }

    private enum TargetChoice {
        POSTS("帖子", ForumTargetType.POST), COMMENTS("评论", ForumTargetType.COMMENT);
        private final String label;
        private final ForumTargetType value;
        TargetChoice(String label, ForumTargetType value) { this.label = label; this.value = value; }
        @Override public String toString() { return label; }
    }

    private enum StatusChoice {
        ALL("全部状态", null), NORMAL("正常", ForumContentStatus.NORMAL),
        HIDDEN("已隐藏", ForumContentStatus.HIDDEN), DELETED("已删除", ForumContentStatus.DELETED);
        private final String label;
        private final ForumContentStatus value;
        StatusChoice(String label, ForumContentStatus value) { this.label = label; this.value = value; }
        @Override public String toString() { return label; }
    }

    private abstract static class RowsModel<T> extends AbstractTableModel {
        private List<T> rows = List.of();
        void setRows(List<T> values) { rows = List.copyOf(values); fireTableDataChanged(); }
        T row(int index) { return rows.get(index); }
        @Override public int getRowCount() { return rows.size(); }
        @Override public boolean isCellEditable(int row, int column) { return false; }
    }

    private static final class SectionModel extends RowsModel<ForumViewData.SectionRow> {
        private static final String[] COLUMNS = {"ID", "代码", "名称", "简介", "排序", "状态"};
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }
        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            var row = row(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.id(); case 1 -> row.code(); case 2 -> row.name();
                case 3 -> row.description(); case 4 -> row.sortOrder();
                default -> row.enabled() ? "启用" : "停用";
            };
        }
    }

    private static final class ContentModel extends RowsModel<ForumViewData.AdminContentRow> {
        private static final String[] COLUMNS = {"ID", "类型", "板块", "作者", "标题/内容", "状态", "标记", "时间"};
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }
        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            var row = row(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.id(); case 1 -> row.targetType() == ForumTargetType.POST ? "帖子" : "评论";
                case 2 -> row.sectionName(); case 3 -> row.authorDisplayName();
                case 4 -> row.title().isBlank() ? abbreviate(row.content()) : row.title();
                case 5 -> row.status();
                case 6 -> (row.locked() ? "锁定 " : "") + (row.pinned() ? "置顶 " : "")
                        + (row.featured() ? "精华" : "");
                default -> TIME.format(row.createdAt());
            };
        }
        private String abbreviate(String value) { return value.length() <= 40 ? value : value.substring(0, 40) + "…"; }
    }

    private static final class LogModel extends RowsModel<ForumViewData.ModerationLogRow> {
        private static final String[] COLUMNS = {"时间", "管理员", "对象", "对象ID", "动作", "原因"};
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }
        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            var row = row(rowIndex);
            return switch (columnIndex) {
                case 0 -> TIME.format(row.createdAt()); case 1 -> row.operatorDisplayName();
                case 2 -> row.targetType(); case 3 -> row.targetId();
                case 4 -> row.action(); default -> row.reason();
            };
        }
    }
}
