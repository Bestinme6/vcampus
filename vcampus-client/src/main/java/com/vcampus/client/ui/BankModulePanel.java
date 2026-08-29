package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.BankAccountStatus;
import com.vcampus.common.model.BankLedgerType;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.ResponseMessage;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class BankModulePanel extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private final VCampusClient client;
    private final String token;
    private final JTabbedPane tabs = new JTabbedPane();
    private final AccountHomePanel accountHome = new AccountHomePanel();
    private final TransferPanel transfer = new TransferPanel();
    private final LedgerPanel personalLedger = new LedgerPanel(false);
    private final List<Runnable> adminRefreshers = new ArrayList<>();

    BankModulePanel(VCampusClient client, String sessionToken, Set<UserRole> roles,
                    Runnable backToWorkspace) {
        this.client = client;
        this.token = sessionToken;
        setLayout(new BorderLayout(0, 18));
        setBackground(Theme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(24, 28, 28, 28));
        add(heading(backToWorkspace), BorderLayout.NORTH);
        tabs.setFont(tabs.getFont().deriveFont(Font.BOLD, 14f));
        tabs.addTab("账户首页", accountHome);
        tabs.addTab("转账", transfer);
        tabs.addTab("流水明细", personalLedger);
        if (BankViewData.showAdminTabs(roles)) {
            AdminAccountsPanel accounts = new AdminAccountsPanel();
            AdminControlsPanel controls = new AdminControlsPanel();
            LedgerPanel allLedger = new LedgerPanel(true);
            tabs.addTab("账户管理", accounts);
            tabs.addTab("充值与冻结", controls);
            tabs.addTab("全量流水", allLedger);
            adminRefreshers.add(accounts::refresh);
            adminRefreshers.add(allLedger::refresh);
        }
        add(tabs, BorderLayout.CENTER);
    }

    void activate() {
        accountHome.refresh();
        personalLedger.refresh();
    }

    void openLedger() {
        int index = tabTitles().indexOf("流水明细");
        if (index >= 0) tabs.setSelectedIndex(index);
    }

    List<String> tabTitles() {
        List<String> result = new ArrayList<>(tabs.getTabCount());
        for (int index = 0; index < tabs.getTabCount(); index++) result.add(tabs.getTitleAt(index));
        return List.copyOf(result);
    }

    String selectedTabTitle() {
        return tabs.getTitleAt(tabs.getSelectedIndex());
    }

    private JPanel heading(Runnable backToWorkspace) {
        JPanel heading = transparent(new BorderLayout(16, 0));
        JPanel titles = transparent(new BorderLayout());
        JLabel title = new JLabel("虚拟银行");
        title.setForeground(Theme.TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        JLabel subtitle = new JLabel("账户余额、校园转账与资金流水");
        subtitle.setForeground(Theme.MUTED);
        titles.add(title, BorderLayout.NORTH);
        titles.add(subtitle, BorderLayout.SOUTH);
        heading.add(titles, BorderLayout.WEST);
        if (backToWorkspace != null) {
            JButton back = quiet("← 返回工作台");
            back.addActionListener(event -> backToWorkspace.run());
            heading.add(back, BorderLayout.EAST);
        }
        return heading;
    }

    private final class AccountHomePanel extends JPanel {
        private final JLabel owner = value("正在加载…", 18f);
        private final JLabel balance = value("--", 34f);
        private final JLabel status = value("--", 18f);
        private final JButton refresh = primary("刷新账户");

        private AccountHomePanel() {
            setLayout(new BorderLayout(0, 18));
            setOpaque(false);
            JPanel cards = transparent(new GridLayout(1, 3, 18, 0));
            cards.add(metric("账户", owner));
            cards.add(metric("可用余额（元）", balance));
            cards.add(metric("账户状态", status));
            add(cards, BorderLayout.NORTH);
            JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT));
            refresh.addActionListener(event -> refresh());
            actions.add(refresh);
            add(actions, BorderLayout.SOUTH);
        }

        private void refresh() {
            busy(refresh, true);
            BankAsync.run(() -> BankViewData.account(client.getBankAccount(token)), account -> {
                owner.setText(account.displayName() + "（" + account.username() + "）");
                balance.setText(format(account.balance()));
                status.setText(account.status() == BankAccountStatus.ACTIVE ? "正常" : "已冻结");
                transfer.setAccountStatus(account.status());
                busy(refresh, false);
            }, error -> { busy(refresh, false); showError(error); });
        }
    }

    private final class TransferPanel extends JPanel {
        private final JTextField recipient = field(18);
        private final JTextField amount = field(12);
        private final JButton submit = primary("确认转账");
        private final JLabel hint = new JLabel("请核对收款用户名，转账成功后不可撤回");
        private BankAccountStatus accountStatus = BankAccountStatus.ACTIVE;

        private TransferPanel() {
            setLayout(new BorderLayout());
            setOpaque(false);
            JPanel form = transparent(new GridLayout(0, 2, 12, 16));
            form.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.BORDER),
                    BorderFactory.createEmptyBorder(28, 32, 28, 32)));
            form.add(new JLabel("收款用户名")); form.add(recipient);
            form.add(new JLabel("转账金额（元）")); form.add(amount);
            hint.setForeground(Theme.MUTED); form.add(hint); form.add(submit);
            submit.addActionListener(event -> submit());
            JPanel wrapper = transparent(new BorderLayout());
            wrapper.add(form, BorderLayout.NORTH);
            add(wrapper, BorderLayout.CENTER);
        }

        private void setAccountStatus(BankAccountStatus status) {
            accountStatus = status;
            submit.setEnabled(BankViewData.canTransfer(status));
            hint.setText(status == BankAccountStatus.FROZEN
                    ? "账户已冻结，不能转账；仍可查看余额和接收款项"
                    : "请核对收款用户名，转账成功后不可撤回");
        }

        private void submit() {
            if (!BankViewData.canTransfer(accountStatus)) return;
            String operationId = UUID.randomUUID().toString();
            busy(submit, true);
            BankAsync.run(() -> BankViewData.requireSuccess(client.transferBank(
                    token, recipient.getText().trim(), amount.getText().trim(), operationId)), response -> {
                busy(submit, false);
                UiDialogs.showSuccess(this, response.message());
                amount.setText("");
                accountHome.refresh();
                personalLedger.refresh();
            }, error -> { busy(submit, false); showError(error); });
        }
    }

    private final class LedgerPanel extends JPanel {
        private final boolean administrative;
        private final JTextField userId = field(8);
        private final JComboBox<String> type = new JComboBox<>(ledgerTypes());
        private final DefaultTableModel model = tableModel(
                "时间", "类型", "收支", "金额", "余额", "说明", "业务编号");
        private final JTable table = table(model);
        private final JLabel paging = new JLabel("第 1 页");
        private final JButton search = primary("查询流水");
        private int page = 1;
        private int total;

        private LedgerPanel(boolean administrative) {
            this.administrative = administrative;
            setLayout(new BorderLayout(0, 12));
            setOpaque(false);
            JPanel filters = transparent(new FlowLayout(FlowLayout.LEFT, 10, 4));
            if (administrative) { filters.add(new JLabel("用户ID（留空查全部）")); filters.add(userId); }
            filters.add(new JLabel("类型")); filters.add(type); filters.add(search);
            add(filters, BorderLayout.NORTH);
            add(new JScrollPane(table), BorderLayout.CENTER);
            JPanel bottom = transparent(new FlowLayout(FlowLayout.LEFT));
            JButton previous = quiet("上一页");
            JButton next = quiet("下一页");
            bottom.add(previous); bottom.add(paging); bottom.add(next);
            add(bottom, BorderLayout.SOUTH);
            search.addActionListener(event -> { page = 1; refresh(); });
            previous.addActionListener(event -> { if (page > 1) { page--; refresh(); } });
            next.addActionListener(event -> { if (page * 10 < total) { page++; refresh(); } });
        }

        private void refresh() {
            Long target = null;
            if (administrative && !userId.getText().isBlank()) {
                try {
                    target = positiveLong(userId.getText(), "请输入有效的用户ID");
                } catch (IllegalArgumentException exception) {
                    model.setRowCount(0);
                    paging.setText("用户ID格式不正确");
                    return;
                }
            }
            Long requestedUser = target;
            String selectedType = selectedEnum(type);
            busy(search, true);
            BankAsync.run(() -> BankViewData.ledgerPage(
                    client.searchBankLedger(token, requestedUser, selectedType, page)), result -> {
                total = result.total();
                model.setRowCount(0);
                for (BankViewData.LedgerRow row : result.rows()) model.addRow(new Object[]{
                        TIME.format(row.createdAt()), ledgerType(row.type()),
                        row.direction().name().equals("CREDIT") ? "收入" : "支出",
                        format(row.amount()), format(row.balanceAfter()), row.description(),
                        row.referenceNo()});
                paging.setText("第 " + result.page() + " 页，共 " + result.total() + " 条");
                busy(search, false);
            }, error -> { busy(search, false); showError(error); });
        }
    }

    private final class AdminAccountsPanel extends JPanel {
        private final JTextField keyword = field(16);
        private final JComboBox<String> status = new JComboBox<>(new String[]{"全部状态", "ACTIVE", "FROZEN"});
        private final DefaultTableModel model = tableModel(
                "用户ID", "用户名", "姓名", "余额", "状态", "更新时间");
        private final JButton search = primary("查询账户");

        private AdminAccountsPanel() {
            setLayout(new BorderLayout(0, 12)); setOpaque(false);
            JPanel filters = transparent(new FlowLayout(FlowLayout.LEFT, 10, 4));
            filters.add(new JLabel("用户名 / 姓名")); filters.add(keyword);
            filters.add(status); filters.add(search); add(filters, BorderLayout.NORTH);
            add(new JScrollPane(table(model)), BorderLayout.CENTER);
            search.addActionListener(event -> refresh());
        }

        private void refresh() {
            busy(search, true);
            BankAsync.run(() -> BankViewData.accountPage(client.searchBankAccounts(token,
                    keyword.getText().trim(), selectedEnum(status), 1)), result -> {
                model.setRowCount(0);
                for (BankViewData.AccountRow row : result.rows()) model.addRow(new Object[]{
                        row.userId(), row.username(), row.displayName(), format(row.balance()),
                        row.status() == BankAccountStatus.ACTIVE ? "正常" : "冻结",
                        TIME.format(row.updatedAt())});
                busy(search, false);
            }, error -> { busy(search, false); showError(error); });
        }
    }

    private final class AdminControlsPanel extends JPanel {
        private final JTextField userId = field(10);
        private final JTextField amount = field(12);
        private final JButton topUp = primary("充值");
        private final JButton freeze = primary("冻结账户");
        private final JButton unfreeze = primary("解冻账户");

        private AdminControlsPanel() {
            setLayout(new BorderLayout()); setOpaque(false);
            JPanel form = transparent(new GridLayout(0, 2, 12, 16));
            form.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.BORDER),
                    BorderFactory.createEmptyBorder(28, 32, 28, 32)));
            form.add(new JLabel("目标用户ID")); form.add(userId);
            form.add(new JLabel("充值金额（元）")); form.add(amount);
            JPanel actions = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
            actions.add(topUp); actions.add(freeze); actions.add(unfreeze);
            form.add(new JLabel("账户操作")); form.add(actions);
            JPanel wrapper = transparent(new BorderLayout()); wrapper.add(form, BorderLayout.NORTH);
            add(wrapper, BorderLayout.CENTER);
            topUp.addActionListener(event -> topUp());
            freeze.addActionListener(event -> setFrozen(true));
            unfreeze.addActionListener(event -> setFrozen(false));
        }

        private void topUp() {
            long target;
            try { target = positiveLong(userId.getText(), "请输入有效的用户ID"); }
            catch (IllegalArgumentException error) { showError(error); return; }
            String operationId = UUID.randomUUID().toString();
            busy(topUp, true);
            BankAsync.run(() -> BankViewData.requireSuccess(client.topUpBankAccount(
                            token, target, amount.getText().trim(), operationId)),
                    response -> mutationDone(topUp, response),
                    error -> { busy(topUp, false); showError(error); });
        }

        private void setFrozen(boolean frozenValue) {
            long target;
            try { target = positiveLong(userId.getText(), "请输入有效的用户ID"); }
            catch (IllegalArgumentException error) { showError(error); return; }
            JButton button = frozenValue ? freeze : unfreeze;
            busy(button, true);
            BankAsync.run(() -> BankViewData.requireSuccess(
                            client.setBankAccountFrozen(token, target, frozenValue)),
                    response -> mutationDone(button, response),
                    error -> { busy(button, false); showError(error); });
        }

        private void mutationDone(JButton button, ResponseMessage response) {
            busy(button, false);
            UiDialogs.showSuccess(this, response.message());
            for (Runnable refresher : adminRefreshers) refresher.run();
        }
    }

    private static JPanel metric(String label, JLabel value) {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBackground(Theme.SURFACE_HOVER);
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(22, 24, 22, 24)));
        JLabel heading = new JLabel(label); heading.setForeground(Theme.MUTED);
        panel.add(heading, BorderLayout.NORTH); panel.add(value, BorderLayout.CENTER);
        return panel;
    }

    private static JLabel value(String text, float size) {
        JLabel label = new JLabel(text); label.setForeground(Theme.TEXT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, size)); return label;
    }

    private static JButton primary(String text) {
        JButton button = new JButton(text);
        Theme.styleDarkTextPrimaryButton(button);
        button.setForeground(Color.BLACK);
        button.setFont(button.getFont().deriveFont(Font.BOLD));
        return button;
    }

    private static JButton quiet(String text) {
        JButton button = new JButton(text); Theme.styleQuietButton(button);
        button.setForeground(Color.BLACK); button.setFont(button.getFont().deriveFont(Font.BOLD));
        return button;
    }

    private static JTextField field(int columns) {
        JTextField field = new JTextField(columns); Theme.styleField(field); return field;
    }

    private static JPanel transparent(java.awt.LayoutManager layout) {
        JPanel panel = new JPanel(layout); panel.setOpaque(false); return panel;
    }

    private static DefaultTableModel tableModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }

    private static JTable table(DefaultTableModel model) {
        JTable table = new JTable(model); table.setRowHeight(30); table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false); return table;
    }

    private static void busy(JButton button, boolean value) {
        if (value) {
            if (button.getClientProperty("bank.originalText") == null) {
                button.putClientProperty("bank.originalText", button.getText());
            }
            button.setText("处理中…");
        } else {
            Object original = button.getClientProperty("bank.originalText");
            if (original != null) button.setText(original.toString());
        }
        button.setEnabled(!value);
    }

    private static void showError(Throwable error) {
        String message = error == null || error.getMessage() == null || error.getMessage().isBlank()
                ? "请求失败，请稍后重试" : error.getMessage();
        JOptionPane.showMessageDialog(null, message, "操作失败", JOptionPane.ERROR_MESSAGE);
    }

    private static long positiveLong(String value, String error) {
        try {
            long parsed = Long.parseLong(value == null ? "" : value.trim());
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(error);
        }
    }

    private static String selectedEnum(JComboBox<String> combo) {
        String selected = String.valueOf(combo.getSelectedItem());
        return selected.startsWith("全部") ? null : selected;
    }

    private static String[] ledgerTypes() {
        BankLedgerType[] values = BankLedgerType.values();
        String[] result = new String[values.length + 1]; result[0] = "全部类型";
        for (int index = 0; index < values.length; index++) result[index + 1] = values[index].name();
        return result;
    }

    private static String ledgerType(BankLedgerType type) {
        return switch (type) {
            case ADMIN_TOPUP -> "管理员充值";
            case TRANSFER_OUT -> "转账支出";
            case TRANSFER_IN -> "转账收入";
            case SHOP_PAYMENT -> "商店支付";
            case SHOP_REFUND -> "商店退款";
        };
    }

    private static String format(BigDecimal value) { return value.setScale(2).toPlainString(); }
}
