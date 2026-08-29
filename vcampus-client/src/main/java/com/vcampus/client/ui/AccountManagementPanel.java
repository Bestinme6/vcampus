package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.ResponseMessage;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

final class AccountManagementPanel extends JPanel {
    private static final String[] COLUMNS = {
            "账号", "姓名", "基础身份", "管理员角色", "状态", "首次改密", "最后登录"
    };

    private final VCampusClient client;
    private final String sessionToken;
    private final JTextField keyword = new JTextField(18);
    private final JComboBox<FilterChoice> identity = new JComboBox<>(new FilterChoice[]{
            new FilterChoice("", "全部身份"), new FilterChoice("STUDENT", "学生"),
            new FilterChoice("TEACHER", "教师")
    });
    private final JComboBox<FilterChoice> enabled = new JComboBox<>(new FilterChoice[]{
            new FilterChoice("", "全部状态"), new FilterChoice("true", "已启用"),
            new FilterChoice("false", "已停用")
    });
    private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JLabel pageLabel = new JLabel("第 1 页 · 共 0 条");
    private final JButton previous = commandButton("上一页");
    private final JButton next = commandButton("下一页");
    private final JButton create = commandButton("创建账号");
    private final JButton roles = commandButton("分配角色");
    private final JButton toggle = commandButton("停用/启用");
    private final JButton reset = commandButton("重置密码");
    private final JButton refresh = commandButton("刷新");
    private int page = 1;
    private int total;
    private int pageSize = 8;
    private List<AccountViewData.AccountRow> rows = List.of();
    private AccountViewData.ReferenceData references;

    AccountManagementPanel(VCampusClient client, String sessionToken) {
        super(new BorderLayout(0, 18));
        this.client = client;
        this.sessionToken = sessionToken;
        setBackground(Theme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(30, 36, 34, 36));
        add(header(), BorderLayout.NORTH);
        add(tableArea(), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        wireActions();
        loadReferences();
        loadAccounts();
    }

    void activate() {
        loadAccounts();
    }

    private JPanel header() {
        JPanel root = new JPanel(new BorderLayout(0, 16));
        root.setOpaque(false);
        JPanel titles = new JPanel(new GridLayout(0, 1, 0, 5));
        titles.setOpaque(false);
        JLabel title = new JLabel("账号管理");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 30f));
        title.setForeground(Theme.TEXT);
        JLabel subtitle = new JLabel("创建学生/教师账号，并管理其业务管理员角色和登录状态");
        subtitle.setForeground(Theme.MUTED);
        titles.add(title);
        titles.add(subtitle);
        root.add(titles, BorderLayout.NORTH);

        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        filters.setOpaque(false);
        keyword.setToolTipText("输入账号或姓名");
        Theme.styleField(keyword);
        JButton search = commandButton("查询");
        search.addActionListener(event -> { page = 1; loadAccounts(); });
        filters.add(keyword);
        filters.add(identity);
        filters.add(enabled);
        filters.add(search);
        root.add(filters, BorderLayout.SOUTH);
        return root;
    }

    private JScrollPane tableArea() {
        table.setRowHeight(34);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getTableHeader().setReorderingAllowed(false);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        return scroll;
    }

    private JPanel actions() {
        JPanel root = new JPanel(new BorderLayout());
        root.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 9, 0));
        left.setOpaque(false);
        left.add(create);
        left.add(roles);
        left.add(toggle);
        left.add(reset);
        left.add(refresh);
        JPanel paging = new JPanel(new FlowLayout(FlowLayout.RIGHT, 9, 0));
        paging.setOpaque(false);
        paging.add(previous);
        paging.add(pageLabel);
        paging.add(next);
        root.add(left, BorderLayout.WEST);
        root.add(paging, BorderLayout.EAST);
        return root;
    }

    private void wireActions() {
        previous.addActionListener(event -> { if (page > 1) { page--; loadAccounts(); } });
        next.addActionListener(event -> {
            if ((long) page * pageSize < total) { page++; loadAccounts(); }
        });
        refresh.addActionListener(event -> loadAccounts());
        create.addActionListener(event -> createAccount());
        roles.addActionListener(event -> selectedAccount().ifPresent(this::updateRoles));
        toggle.addActionListener(event -> selectedAccount().ifPresent(this::toggleEnabled));
        reset.addActionListener(event -> selectedAccount().ifPresent(this::resetPassword));
    }

    private void loadReferences() {
        runAsync(() -> client.accountReferenceData(sessionToken), response -> {
            references = AccountViewData.references(response);
            create.setEnabled(true);
        });
    }

    private void loadAccounts() {
        setBusy(true);
        FilterChoice identityValue = (FilterChoice) identity.getSelectedItem();
        FilterChoice enabledValue = (FilterChoice) enabled.getSelectedItem();
        runAsync(() -> client.searchAccounts(
                sessionToken, keyword.getText().trim(), identityValue.value,
                enabledValue.value, page), response -> {
            AccountViewData.AccountPage result = AccountViewData.accounts(response);
            rows = result.rows();
            page = result.page();
            pageSize = result.pageSize();
            total = result.total();
            model.setRowCount(0);
            for (AccountViewData.AccountRow row : rows) {
                model.addRow(new Object[]{
                        row.username(), row.displayName(), identityLabel(row.baseIdentity()),
                        rolesLabel(row.administrativeRoles()), row.enabled() ? "已启用" : "已停用",
                        row.forcePasswordChange() ? "是" : "否",
                        row.lastLoginAt().isBlank() ? "从未登录" : row.lastLoginAt()
                });
            }
            pageLabel.setText("第 " + page + " 页 · 共 " + total + " 条");
            previous.setEnabled(page > 1);
            next.setEnabled((long) page * pageSize < total);
            setBusy(false);
        });
    }

    private void createAccount() {
        if (references == null) {
            JOptionPane.showMessageDialog(this, "基础数据尚未加载完成，请稍后重试。",
                    "暂不可用", JOptionPane.WARNING_MESSAGE);
            return;
        }
        AccountCreateDialog.showDialog(SwingUtilities.getWindowAncestor(this), references)
                .ifPresent(values -> runMutation(
                        () -> client.createAccount(sessionToken, values), true));
    }

    private void updateRoles(AccountViewData.AccountRow account) {
        AccountRolesDialog.showDialog(SwingUtilities.getWindowAncestor(this), account)
                .ifPresent(selected -> runMutation(
                        () -> client.updateAccountRoles(sessionToken, account.userId(), selected), false));
    }

    private void toggleEnabled(AccountViewData.AccountRow account) {
        boolean target = !account.enabled();
        int result = JOptionPane.showConfirmDialog(this,
                "确定要" + (target ? "启用" : "停用") + "账号 “" + account.username() + "” 吗？",
                target ? "启用账号" : "停用账号", JOptionPane.OK_CANCEL_OPTION);
        if (result == JOptionPane.OK_OPTION) {
            runMutation(() -> client.setAccountEnabled(sessionToken, account.userId(), target), false);
        }
    }

    private void resetPassword(AccountViewData.AccountRow account) {
        JPasswordField first = new JPasswordField(18);
        JPasswordField second = new JPasswordField(18);
        JPanel form = new JPanel(new GridLayout(0, 2, 10, 10));
        form.add(new JLabel("新临时密码"));
        form.add(first);
        form.add(new JLabel("确认密码"));
        form.add(second);
        int result = JOptionPane.showConfirmDialog(
                this, form, "重置 “" + account.username() + "” 的密码",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        char[] secret = first.getPassword();
        char[] repeated = second.getPassword();
        try {
            if (result != JOptionPane.OK_OPTION) {
                return;
            }
            if (secret.length < 8 || secret.length > 128) {
                showError("临时密码长度必须为 8—128 位");
                return;
            }
            if (!Arrays.equals(secret, repeated)) {
                showError("两次输入的密码不一致");
                return;
            }
            char[] requestPassword = Arrays.copyOf(secret, secret.length);
            runMutation(() -> {
                try {
                    return client.resetAccountPassword(
                            sessionToken, account.userId(), requestPassword);
                } finally {
                    Arrays.fill(requestPassword, '\0');
                }
            }, false);
        } finally {
            Arrays.fill(secret, '\0');
            Arrays.fill(repeated, '\0');
            first.setText("");
            second.setText("");
        }
    }

    private Optional<AccountViewData.AccountRow> selectedAccount() {
        int selected = table.getSelectedRow();
        if (selected < 0) {
            showError("请先在表格中选择一个账号");
            return Optional.empty();
        }
        return Optional.of(rows.get(table.convertRowIndexToModel(selected)));
    }

    private void runMutation(IoOperation operation, boolean reloadReferences) {
        setBusy(true);
        runAsync(operation, response -> {
            UiDialogs.showSuccess(this, response.message());
            if (reloadReferences) {
                loadReferences();
            }
            loadAccounts();
        });
    }

    private void runAsync(IoOperation operation, java.util.function.Consumer<ResponseMessage> success) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return operation.run();
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((response, error) -> SwingUtilities.invokeLater(() -> {
            if (error != null) {
                setBusy(false);
                showError("无法连接服务器：" + rootMessage(error));
            } else if (!response.success()) {
                setBusy(false);
                showError(response.message());
            } else {
                try {
                    success.accept(response);
                } catch (RuntimeException exception) {
                    setBusy(false);
                    showError(exception.getMessage());
                }
            }
        }));
    }

    private void setBusy(boolean busy) {
        refresh.setEnabled(!busy);
        roles.setEnabled(!busy);
        toggle.setEnabled(!busy);
        reset.setEnabled(!busy);
        if (busy) {
            previous.setEnabled(false);
            next.setEnabled(false);
        }
        create.setEnabled(!busy && references != null);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "操作失败", JOptionPane.ERROR_MESSAGE);
    }

    private static String identityLabel(UserRole role) {
        return role == UserRole.STUDENT ? "学生" : "教师";
    }

    private static String rolesLabel(Set<UserRole> values) {
        if (values.isEmpty()) {
            return "无";
        }
        return values.stream().map(AccountRolesDialog::roleLabel).sorted()
                .collect(Collectors.joining("、"));
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "未知错误" : current.getMessage();
    }

    private static JButton commandButton(String text) {
        JButton button = new JButton(text);
        Theme.styleCommandButton(button);
        return button;
    }

    private record FilterChoice(String value, String text) {
        @Override public String toString() { return text; }
    }

    @FunctionalInterface
    private interface IoOperation {
        ResponseMessage run() throws IOException;
    }
}
