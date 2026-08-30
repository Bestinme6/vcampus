package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.ResponseMessage;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
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

final class ShopModulePanel extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private final VCampusClient client;
    private final String token;
    private final JTabbedPane tabs = new JTabbedPane();
    private final ProductListPanel products = new ProductListPanel(false);
    private final CartPanel cart = new CartPanel();
    private final OrdersPanel orders = new OrdersPanel(false);
    private ProductListPanel adminProducts;
    private OrdersPanel adminOrders;

    ShopModulePanel(VCampusClient client, String sessionToken, Set<UserRole> roles,
                    Runnable backToWorkspace) {
        this.client = client;
        this.token = sessionToken;
        setLayout(new BorderLayout(0, 18));
        setBackground(Theme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(24, 28, 28, 28));
        add(heading(backToWorkspace), BorderLayout.NORTH);
        tabs.setFont(tabs.getFont().deriveFont(Font.BOLD, 14f));
        tabs.addTab("商品列表", products);
        tabs.addTab("购物车", cart);
        tabs.addTab("我的订单", orders);
        if (ShopViewData.showAdminTabs(roles)) {
            adminProducts = new ProductListPanel(true);
            adminOrders = new OrdersPanel(true);
            tabs.addTab("商品管理", adminProducts);
            tabs.addTab("订单管理", adminOrders);
        }
        add(tabs, BorderLayout.CENTER);
    }

    void activate() {
        products.refresh();
        cart.refresh();
        orders.refresh();
    }

    void openOrders() {
        int index = tabTitles().indexOf("我的订单");
        if (index >= 0) tabs.setSelectedIndex(index);
    }

    List<String> tabTitles() {
        List<String> result = new ArrayList<>(tabs.getTabCount());
        for (int index = 0; index < tabs.getTabCount(); index++) {
            result.add(tabs.getTitleAt(index));
        }
        return List.copyOf(result);
    }

    String selectedTabTitle() {
        return tabs.getTitleAt(tabs.getSelectedIndex());
    }

    private JPanel heading(Runnable backToWorkspace) {
        JPanel heading = transparent(new BorderLayout(16, 0));
        JPanel titles = transparent(new BorderLayout());
        JLabel title = new JLabel("校园商店");
        title.setForeground(Theme.TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        JLabel subtitle = new JLabel("商品浏览、购物车、订单与库存管理");
        subtitle.setForeground(Theme.MUTED);
        titles.add(title, BorderLayout.NORTH);
        titles.add(subtitle, BorderLayout.SOUTH);
        heading.add(titles, BorderLayout.WEST);
        if (backToWorkspace != null) {
            JButton back = quiet("返回工作台");
            back.addActionListener(event -> backToWorkspace.run());
            heading.add(back, BorderLayout.EAST);
        }
        return heading;
    }

    private final class ProductListPanel extends JPanel {
        private final boolean administrative;
        private final JTextField keyword = field(16);
        private final JComboBox<String> enabled = new JComboBox<>(
                new String[]{"全部状态", "已上架", "已下架"});
        private final DefaultTableModel model = tableModel(
                "ID", "货号", "商品名称", "价格", "库存", "状态");
        private final JTable table = table(model);
        private final JButton search = primary("查询商品");
        private final JLabel paging = new JLabel("第 1 页");
        private List<ShopViewData.ProductRow> currentRows = List.of();
        private int page = 1;
        private int total;

        private ProductListPanel(boolean administrative) {
            this.administrative = administrative;
            setLayout(new BorderLayout(0, 12));
            setOpaque(false);
            JPanel filters = transparent(new FlowLayout(FlowLayout.LEFT, 10, 4));
            filters.add(new JLabel("货号 / 商品名称"));
            filters.add(keyword);
            if (administrative) filters.add(enabled);
            filters.add(search);
            add(filters, BorderLayout.NORTH);
            add(new JScrollPane(table), BorderLayout.CENTER);
            JPanel actions = transparent(new FlowLayout(FlowLayout.LEFT, 8, 4));
            JButton previous = quiet("上一页");
            JButton next = quiet("下一页");
            actions.add(previous);
            actions.add(paging);
            actions.add(next);
            if (administrative) {
                JButton create = primary("新建商品");
                JButton edit = quiet("编辑选中商品");
                JButton toggle = quiet("上架 / 下架");
                JButton inventory = quiet("调整库存");
                actions.add(create);
                actions.add(edit);
                actions.add(toggle);
                actions.add(inventory);
                create.addActionListener(event -> editProduct(null));
                edit.addActionListener(event -> editSelected());
                toggle.addActionListener(event -> toggleSelected(toggle));
                inventory.addActionListener(event -> adjustSelected(inventory));
            } else {
                JTextField quantity = field(4);
                quantity.setText("1");
                JButton add = primary("加入购物车");
                actions.add(new JLabel("数量"));
                actions.add(quantity);
                actions.add(add);
                add.addActionListener(event -> addSelected(quantity, add));
            }
            add(actions, BorderLayout.SOUTH);
            search.addActionListener(event -> { page = 1; refresh(); });
            previous.addActionListener(event -> { if (page > 1) { page--; refresh(); } });
            next.addActionListener(event -> { if (page * 10 < total) { page++; refresh(); } });
        }

        private void refresh() {
            Boolean filter = administrative ? switch (enabled.getSelectedIndex()) {
                case 1 -> Boolean.TRUE;
                case 2 -> Boolean.FALSE;
                default -> null;
            } : Boolean.TRUE;
            busy(search, true);
            ShopAsync.run(() -> ShopViewData.productPage(client.searchShopProducts(
                    token, keyword.getText().trim(), filter, page)), result -> {
                total = result.total();
                currentRows = result.rows();
                model.setRowCount(0);
                for (ShopViewData.ProductRow row : result.rows()) {
                    model.addRow(new Object[]{row.id(), row.sku(), row.name(),
                            money(row.price()), row.stock(), row.enabled() ? "已上架" : "已下架"});
                }
                paging.setText("第 " + result.page() + " 页，共 " + result.total() + " 件商品");
                busy(search, false);
            }, error -> { busy(search, false); showError(error); });
        }

        private void addSelected(JTextField quantity, JButton button) {
            Long productId = selectedId(table);
            if (productId == null) return;
            int count;
            try { count = positiveInt(quantity.getText(), "请输入有效数量"); }
            catch (IllegalArgumentException error) { showError(error); return; }
            busy(button, true);
            ShopAsync.run(() -> ShopViewData.cart(
                    client.setShopCartQuantity(token, productId, count)), result -> {
                busy(button, false);
                UiDialogs.showSuccess(this, "已加入购物车");
                cart.render(result);
            }, error -> { busy(button, false); showError(error); });
        }

        private void editSelected() {
            int row = table.getSelectedRow();
            if (row < 0) { showError(new IllegalArgumentException("请先选择商品")); return; }
            ShopViewData.ProductRow selected = currentRows.get(table.convertRowIndexToModel(row));
            ProductDraft draft = new ProductDraft(selected.id(), selected.sku(), selected.name(),
                    selected.description(), money(selected.price()), selected.enabled());
            editProduct(draft);
        }

        private void editProduct(ProductDraft draft) {
            JTextField sku = field(18);
            JTextField name = field(18);
            JTextArea description = new JTextArea(4, 18);
            JTextField price = field(18);
            JCheckBox active = new JCheckBox("上架销售", true);
            sku.setEditable(false);
            if (draft != null) {
                sku.setText(draft.sku()); name.setText(draft.name());
                description.setText(draft.description()); price.setText(draft.price());
                active.setSelected(draft.enabled());
            } else {
                sku.setText("保存后自动生成");
            }
            JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
            form.add(new JLabel("货号")); form.add(sku);
            form.add(new JLabel("商品名称")); form.add(name);
            form.add(new JLabel("说明")); form.add(new JScrollPane(description));
            form.add(new JLabel("价格")); form.add(price);
            form.add(new JLabel("状态")); form.add(active);
            if (JOptionPane.showConfirmDialog(this, form, draft == null ? "新建商品" : "编辑商品",
                    JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            Long id = draft == null ? null : draft.id();
            ShopAsync.run(() -> ShopViewData.requireSuccess(client.saveShopProduct(token, id,
                    name.getText().trim(), description.getText().trim(),
                    price.getText().trim(), active.isSelected())), response -> {
                UiDialogs.showSuccess(this, response.message()); refresh(); products.refresh();
            }, ShopModulePanel::showError);
        }

        private void toggleSelected(JButton button) {
            int row = table.getSelectedRow();
            if (row < 0) { showError(new IllegalArgumentException("请先选择商品")); return; }
            long productId = (Long) model.getValueAt(row, 0);
            boolean target = !"已上架".equals(model.getValueAt(row, 5));
            busy(button, true);
            ShopAsync.run(() -> ShopViewData.requireSuccess(
                    client.setShopProductEnabled(token, productId, target)), response -> {
                busy(button, false); UiDialogs.showSuccess(this, response.message());
                refresh(); products.refresh();
            }, error -> { busy(button, false); showError(error); });
        }

        private void adjustSelected(JButton button) {
            Long productId = selectedId(table);
            if (productId == null) return;
            JTextField delta = field(10);
            JTextField reason = field(20);
            JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
            form.add(new JLabel("变动数量（可为负数）")); form.add(delta);
            form.add(new JLabel("原因")); form.add(reason);
            if (JOptionPane.showConfirmDialog(this, form, "调整库存",
                    JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            int amount;
            try {
                amount = Integer.parseInt(delta.getText().trim());
                if (amount == 0) throw new NumberFormatException();
            } catch (NumberFormatException error) { showError(new IllegalArgumentException("库存变动不能为零")); return; }
            busy(button, true);
            ShopAsync.run(() -> ShopViewData.requireSuccess(client.adjustShopInventory(
                    token, productId, amount, reason.getText().trim())), response -> {
                busy(button, false); UiDialogs.showSuccess(this, response.message());
                refresh(); products.refresh();
            }, error -> { busy(button, false); showError(error); });
        }
    }

    private final class CartPanel extends JPanel {
        private final DefaultTableModel model = tableModel(
                "商品ID", "货号", "商品名称", "单价", "数量", "库存", "小计", "状态");
        private final JTable table = table(model);
        private final JLabel total = new JLabel("合计：0.00 元");
        private final JButton refresh = quiet("刷新购物车");

        private CartPanel() {
            setLayout(new BorderLayout(0, 12)); setOpaque(false);
            add(new JScrollPane(table), BorderLayout.CENTER);
            JPanel actions = transparent(new FlowLayout(FlowLayout.LEFT, 8, 4));
            JTextField quantity = field(4);
            JButton update = quiet("修改数量");
            JButton remove = quiet("删除商品");
            JButton checkout = primary("结算并支付");
            total.setFont(total.getFont().deriveFont(Font.BOLD, 16f));
            actions.add(refresh); actions.add(new JLabel("新数量")); actions.add(quantity);
            actions.add(update); actions.add(remove); actions.add(checkout); actions.add(total);
            add(actions, BorderLayout.SOUTH);
            refresh.addActionListener(event -> refresh());
            update.addActionListener(event -> update(quantity, update));
            remove.addActionListener(event -> remove(remove));
            checkout.addActionListener(event -> checkout(checkout));
        }

        private void refresh() {
            busy(refresh, true);
            ShopAsync.run(() -> ShopViewData.cart(client.getShopCart(token)), result -> {
                render(result); busy(refresh, false);
            }, error -> { busy(refresh, false); showError(error); });
        }

        private void render(ShopViewData.CartView result) {
            model.setRowCount(0);
            for (ShopViewData.CartRow row : result.rows()) model.addRow(new Object[]{
                    row.productId(), row.sku(), row.name(), money(row.unitPrice()), row.quantity(),
                    row.stock(), money(row.subtotal()), row.enabled() ? "可购买" : "已下架"});
            total.setText("合计：" + money(result.estimatedTotal()) + " 元");
        }

        private void update(JTextField quantity, JButton button) {
            Long productId = selectedId(table); if (productId == null) return;
            int count;
            try { count = positiveInt(quantity.getText(), "请输入有效数量"); }
            catch (IllegalArgumentException error) { showError(error); return; }
            busy(button, true);
            ShopAsync.run(() -> ShopViewData.cart(client.setShopCartQuantity(
                    token, productId, count)), result -> { render(result); busy(button, false); },
                    error -> { busy(button, false); showError(error); });
        }

        private void remove(JButton button) {
            Long productId = selectedId(table); if (productId == null) return;
            busy(button, true);
            ShopAsync.run(() -> ShopViewData.cart(client.removeShopCartItem(token, productId)),
                    result -> { render(result); busy(button, false); },
                    error -> { busy(button, false); showError(error); });
        }

        private void checkout(JButton button) {
            String operationId = UUID.randomUUID().toString();
            busy(button, true);
            ShopAsync.run(() -> ShopViewData.requireSuccess(client.checkoutShop(token, operationId)),
                    response -> {
                        busy(button, false); UiDialogs.showSuccess(this, response.message());
                        refresh(); products.refresh(); orders.refresh();
                        if (adminProducts != null) adminProducts.refresh();
                        if (adminOrders != null) adminOrders.refresh();
                    }, error -> { busy(button, false); showError(error); });
        }
    }

    private final class OrdersPanel extends JPanel {
        private final boolean administrative;
        private final JTextField keyword = field(18);
        private final JComboBox<String> status = new JComboBox<>(orderStatuses());
        private final DefaultTableModel model = tableModel(
                "订单ID", "订单号", "买家", "金额", "状态", "下单时间");
        private final JTable table = table(model);
        private final JButton search = primary("查询订单");
        private final JButton stateAction = primary("执行订单操作");
        private List<ShopViewData.OrderRow> currentRows = List.of();
        private int page = 1;
        private int total;

        private OrdersPanel(boolean administrative) {
            this.administrative = administrative;
            setLayout(new BorderLayout(0, 12)); setOpaque(false);
            JPanel filters = transparent(new FlowLayout(FlowLayout.LEFT, 8, 4));
            if (administrative) {
                filters.add(new JLabel("订单号 / 用户名 / 学号"));
                filters.add(keyword);
            }
            filters.add(new JLabel("状态")); filters.add(status); filters.add(search);
            add(filters, BorderLayout.NORTH); add(new JScrollPane(table), BorderLayout.CENTER);
            JPanel actions = transparent(new FlowLayout(FlowLayout.LEFT, 8, 4));
            JButton previous = quiet("上一页"); JButton next = quiet("下一页");
            JButton detail = quiet("查看详情");
            actions.add(previous); actions.add(next); actions.add(detail);
            if (administrative) {
                stateAction.setText("发货"); actions.add(stateAction);
                stateAction.addActionListener(event -> ship(stateAction));
            } else {
                stateAction.setText("取消订单 / 确认收货"); actions.add(stateAction);
                stateAction.addActionListener(event -> mutateSelected(stateAction));
            }
            stateAction.setEnabled(false);
            add(actions, BorderLayout.SOUTH);
            search.addActionListener(event -> { page = 1; refresh(); });
            previous.addActionListener(event -> { if (page > 1) { page--; refresh(); } });
            next.addActionListener(event -> { if (page * 10 < total) { page++; refresh(); } });
            detail.addActionListener(event -> detail(detail));
            table.getSelectionModel().addListSelectionListener(event -> updateStateAction());
        }

        private void refresh() {
            String requestedKeyword = administrative ? keyword.getText().trim() : "";
            String selectedStatus = selectedEnum(status);
            busy(search, true);
            ShopAsync.run(() -> ShopViewData.orderPage(administrative
                    ? client.searchShopAdminOrders(token, requestedKeyword, selectedStatus, page)
                    : client.searchShopOrders(token, selectedStatus, page)), result -> {
                total = result.total(); currentRows = result.rows(); model.setRowCount(0);
                for (ShopViewData.OrderRow row : result.rows()) model.addRow(new Object[]{
                        row.id(), row.orderNo(), ShopViewData.buyerLabel(row),
                        money(row.totalAmount()), statusLabel(row.status()), TIME.format(row.createdAt())});
                updateStateAction();
                busy(search, false);
            }, error -> { busy(search, false); showError(error); });
        }

        private void detail(JButton button) {
            Long orderId = selectedId(table); if (orderId == null) return;
            busy(button, true);
            ShopAsync.run(() -> ShopViewData.orderDetail(client.getShopOrder(token, orderId)),
                    result -> { busy(button, false); showOrderDetail(result); },
                    error -> { busy(button, false); showError(error); });
        }

        private void mutate(JButton button, boolean cancel) {
            Long orderId = selectedId(table); if (orderId == null) return;
            busy(button, true);
            ShopAsync.run(() -> ShopViewData.requireSuccess(cancel
                    ? client.cancelShopOrder(token, orderId)
                    : client.confirmShopOrder(token, orderId)), response -> {
                busy(button, false); UiDialogs.showSuccess(this, response.message());
                refresh(); cart.refresh(); products.refresh();
                if (adminProducts != null) adminProducts.refresh();
                if (adminOrders != null) adminOrders.refresh();
            }, error -> { busy(button, false); showError(error); });
        }

        private void mutateSelected(JButton button) {
            ShopViewData.OrderRow selected = selectedOrder();
            if (selected == null) return;
            if (ShopViewData.canCancel(selected.status())) {
                mutate(button, true);
            } else if (ShopViewData.canConfirm(selected.status())) {
                mutate(button, false);
            }
        }

        private ShopViewData.OrderRow selectedOrder() {
            int selected = table.getSelectedRow();
            if (selected < 0) return null;
            int modelRow = table.convertRowIndexToModel(selected);
            return modelRow < currentRows.size() ? currentRows.get(modelRow) : null;
        }

        private void updateStateAction() {
            ShopViewData.OrderRow selected = selectedOrder();
            if (selected == null) {
                stateAction.setEnabled(false);
                return;
            }
            if (administrative) {
                stateAction.setText("发货");
                stateAction.setEnabled(ShopViewData.canShip(selected.status()));
            } else if (ShopViewData.canCancel(selected.status())) {
                stateAction.setText("取消订单");
                stateAction.setEnabled(true);
            } else if (ShopViewData.canConfirm(selected.status())) {
                stateAction.setText("确认收货");
                stateAction.setEnabled(true);
            } else {
                stateAction.setText("当前状态无可用操作");
                stateAction.setEnabled(false);
            }
        }

        private void ship(JButton button) {
            Long orderId = selectedId(table); if (orderId == null) return;
            busy(button, true);
            ShopAsync.run(() -> ShopViewData.requireSuccess(client.shipShopOrder(token, orderId)),
                    response -> { busy(button, false); UiDialogs.showSuccess(this, response.message()); refresh(); },
                    error -> { busy(button, false); showError(error); });
        }
    }

    private void showOrderDetail(ShopViewData.OrderDetail detail) {
        StringBuilder text = new StringBuilder("订单号：").append(detail.order().orderNo())
                .append("\n买家：").append(ShopViewData.buyerLabel(detail.order()))
                .append("\n状态：").append(statusLabel(detail.order().status()))
                .append("\n金额：").append(money(detail.order().totalAmount())).append(" 元\n\n商品快照：\n");
        for (ShopViewData.OrderItem item : detail.items()) {
            text.append(item.sku()).append("  ").append(item.name()).append("  ")
                    .append(money(item.unitPrice())).append(" × ").append(item.quantity())
                    .append(" = ").append(money(item.subtotal())).append("\n");
        }
        JTextArea area = new JTextArea(text.toString(), 14, 52);
        area.setEditable(false); area.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(area), "订单详情",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private static Long selectedId(JTable table) {
        int row = table.getSelectedRow();
        if (row < 0) { showError(new IllegalArgumentException("请先选择一条记录")); return null; }
        Object value = table.getModel().getValueAt(table.convertRowIndexToModel(row), 0);
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }

    private static JPanel transparent(java.awt.LayoutManager layout) {
        JPanel panel = new JPanel(layout); panel.setOpaque(false); return panel;
    }

    private static JButton primary(String text) {
        JButton button = new JButton(text); Theme.styleDarkTextPrimaryButton(button);
        button.setForeground(Color.BLACK); button.setFont(button.getFont().deriveFont(Font.BOLD));
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
        String key = "shop.originalText";
        if (value && button.getClientProperty(key) == null) {
            button.putClientProperty(key, button.getText());
        }
        button.setText(value ? "处理中…" : String.valueOf(button.getClientProperty(key)));
        button.setEnabled(!value);
    }

    private static void showError(Throwable error) {
        String message = error == null || error.getMessage() == null || error.getMessage().isBlank()
                ? "请求失败，请稍后重试" : error.getMessage();
        JOptionPane.showMessageDialog(null, message, "操作失败", JOptionPane.ERROR_MESSAGE);
    }

    private static int positiveInt(String value, String message) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            if (parsed < 1 || parsed > 999) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) { throw new IllegalArgumentException(message); }
    }

    private static long positiveLong(String value, String message) {
        try {
            long parsed = Long.parseLong(value == null ? "" : value.trim());
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) { throw new IllegalArgumentException(message); }
    }

    private static String[] orderStatuses() {
        ShopOrderStatus[] statuses = ShopOrderStatus.values();
        String[] result = new String[statuses.length + 1]; result[0] = "全部状态";
        for (int index = 0; index < statuses.length; index++) result[index + 1] = statuses[index].name();
        return result;
    }

    private static String selectedEnum(JComboBox<String> combo) {
        String selected = String.valueOf(combo.getSelectedItem());
        return selected.startsWith("全部") ? null : selected;
    }

    private static String statusLabel(ShopOrderStatus status) {
        return switch (status) {
            case PAID -> "已支付";
            case SHIPPED -> "已发货";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
        };
    }

    private static String money(BigDecimal value) { return value.setScale(2).toPlainString(); }

    private record ProductDraft(Long id, String sku, String name, String description,
                                String price, boolean enabled) {
    }
}
