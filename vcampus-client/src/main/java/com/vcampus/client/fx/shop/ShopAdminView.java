package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.Order;
import com.vcampus.client.fx.shop.ShopData.OrderPage;
import com.vcampus.client.fx.shop.ShopData.Product;
import com.vcampus.client.fx.shop.ShopData.ProductPage;
import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.ShopProductSort;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Dialog;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.concurrent.atomic.AtomicReference;

final class ShopAdminView extends BorderPane {
    record ProductQuery(String keyword, ShopCategory category, Boolean enabled, ShopProductSort sort, int page) { }
    record OrderQuery(String keyword, ShopOrderStatus status, int page) { }
    private record InventoryAdjustment(int delta, String reason) { }
    interface Listener {
        void searchProducts(ProductQuery query); void editProduct(long productId); void newProduct();
        void setEnabled(long productId, boolean enabled); void adjustInventory(long productId, int delta, String reason);
        void searchOrders(OrderQuery query); void ship(long orderId);
    }

    private final Listener listener;
    private final VBox body = new VBox(14);
    private final TextField keyword = new TextField();
    private final ComboBox<ShopCategory> category = new ComboBox<>();
    private final CheckBox enabled = new CheckBox("仅显示上架商品");
    private final TableView<Product> products = new TableView<>();
    private final TableView<Order> orders = new TableView<>();

    ShopAdminView(Listener listener) {
        this.listener = listener; setId("shop-admin"); getStyleClass().add("shop-page");
        body.setPadding(new Insets(24)); setCenter(body);
        keyword.setPromptText("商品名称或货号"); category.getItems().setAll(ShopCategory.values());
        category.setPromptText("全部分类");
        category.setConverter(new StringConverter<>() {
            @Override public String toString(ShopCategory value) {
                return value == null ? "全部分类" : value.displayName();
            }
            @Override public ShopCategory fromString(String value) { throw new UnsupportedOperationException(); }
        });
        enabled.setSelected(true);
        buildProductTable(); buildOrderTable();
    }

    void showProducts(ProductPage page) {
        products.getItems().setAll(page.rows());
        Button create = ShopUi.button("新建商品", "shop-primary", listener::newProduct);
        Button search = ShopUi.button("查询", "shop-secondary", () -> listener.searchProducts(query(1)));
        FlowPane filters = new FlowPane(10, 10, keyword, category, enabled, search, create);
        body.getChildren().setAll(navigation(true), ShopUi.label("商品管理", "shop-detail-title"), filters, products);
    }

    void showOrders(OrderPage page) {
        orders.getItems().setAll(page.rows());
        body.getChildren().setAll(navigation(false), ShopUi.label("订单管理", "shop-detail-title"), orders);
    }

    ProductQuery query(int page) {
        return new ProductQuery(keyword.getText().strip(), category.getValue(), enabled.isSelected() ? Boolean.TRUE : null,
                ShopProductSort.NEWEST, page);
    }

    void adjustInventory(long productId, int delta, String reason) {
        InventoryAdjustment adjustment = inventoryAdjustment(delta, reason);
        listener.adjustInventory(productId, adjustment.delta(), adjustment.reason());
    }

    private void buildProductTable() {
        products.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<Product, String> sku = new TableColumn<>("货号"); sku.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(v.getValue().sku()));
        TableColumn<Product, String> name = new TableColumn<>("商品"); name.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(v.getValue().name()));
        TableColumn<Product, String> group = new TableColumn<>("分类"); group.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(v.getValue().category().displayName()));
        TableColumn<Product, String> stock = new TableColumn<>("库存"); stock.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(String.valueOf(v.getValue().stock())));
        TableColumn<Product, Product> actions = new TableColumn<>("操作");
        actions.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(v.getValue()));
        actions.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(Product product, boolean empty) {
                super.updateItem(product, empty);
                if (empty || product == null) { setGraphic(null); return; }
                Button edit = ShopUi.button("编辑", "shop-quiet", () -> listener.editProduct(product.id()));
                edit.setId("shop-admin-edit-" + product.id());
                Button toggle = ShopUi.button(product.enabled() ? "下架" : "上架", "shop-quiet",
                        () -> listener.setEnabled(product.id(), !product.enabled()));
                toggle.setId("shop-admin-toggle-" + product.id());
                Button inventory = ShopUi.button("调库存", "shop-quiet", () -> inventoryDialog(product.id()));
                inventory.setId("shop-admin-inventory-" + product.id());
                setGraphic(new HBox(7, edit, toggle, inventory));
            }
        });
        products.getColumns().setAll(java.util.List.of(sku, name, group, stock, actions));
        products.setPrefHeight(520);
    }

    private void buildOrderTable() {
        orders.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<Order, String> number = new TableColumn<>("订单号"); number.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(v.getValue().orderNo()));
        TableColumn<Order, String> buyer = new TableColumn<>("购买者"); buyer.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(v.getValue().buyerDisplayName()));
        TableColumn<Order, String> status = new TableColumn<>("状态"); status.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(v.getValue().status().name()));
        TableColumn<Order, Order> action = new TableColumn<>("操作"); action.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(v.getValue()));
        action.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(Order order, boolean empty) {
                super.updateItem(order, empty);
                if (empty || order == null) { setGraphic(null); return; }
                Button ship = ShopUi.button("确认发货", "shop-primary", () -> listener.ship(order.id()));
                ship.setDisable(order.status() != ShopOrderStatus.PAID); ship.setId("shop-admin-ship-" + order.id());
                setGraphic(ship);
            }
        });
        orders.getColumns().setAll(java.util.List.of(number, buyer, status, action)); orders.setPrefHeight(520);
    }

    private HBox navigation(boolean productPage) {
        Button product = ShopUi.button("商品管理", productPage ? "shop-primary" : "shop-quiet",
                () -> listener.searchProducts(query(1)));
        Button order = ShopUi.button("订单发货", productPage ? "shop-quiet" : "shop-primary",
                () -> listener.searchOrders(new OrderQuery("", null, 1)));
        return new HBox(8, product, order);
    }

    private void inventoryDialog(long productId) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("调整商品库存"); dialog.setHeaderText("正数入库，负数扣减");
        Spinner<Integer> delta = new Spinner<>();
        delta.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(-9999, 9999, 1));
        delta.setEditable(true);
        TextField reason = new TextField(); reason.setPromptText("原因（2—255 字）");
        Label error = ShopUi.label("", "shop-status");
        error.setId("shop-inventory-error");
        error.getStyleClass().add("shop-error");
        error.setManaged(false); error.setVisible(false);
        dialog.getDialogPane().setContent(new VBox(10, ShopUi.label("调整数量", "shop-hint"), delta,
                ShopUi.label("调整原因", "shop-hint"), reason, error));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        AtomicReference<InventoryAdjustment> validated = new AtomicReference<>();
        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                validated.set(inventoryAdjustment(inventoryDelta(delta), reason.getText()));
                error.setText(""); error.setManaged(false); error.setVisible(false);
            } catch (IllegalArgumentException failure) {
                event.consume();
                error.setText(failure.getMessage()); error.setManaged(true); error.setVisible(true);
                dialog.getDialogPane().applyCss();
                dialog.getDialogPane().getScene().getWindow().sizeToScene();
            }
        });
        if (getScene() != null && getScene().getWindow() != null) dialog.initOwner(getScene().getWindow());
        dialog.showAndWait().filter(ButtonType.OK::equals)
                .map(ignored -> validated.get())
                .ifPresent(adjustment -> listener.adjustInventory(productId, adjustment.delta(), adjustment.reason()));
    }

    private static InventoryAdjustment inventoryAdjustment(int delta, String reason) {
        if (delta == 0) throw new IllegalArgumentException("库存调整数量不能为 0");
        String normalized = reason == null ? "" : reason.strip();
        if (normalized.length() < 2 || normalized.length() > 255) {
            throw new IllegalArgumentException("调整原因必须为 2 到 255 个字符");
        }
        return new InventoryAdjustment(delta, normalized);
    }

    private static int inventoryDelta(Spinner<Integer> spinner) {
        try {
            int value = Integer.parseInt(spinner.getEditor().getText().strip());
            var factory = (SpinnerValueFactory.IntegerSpinnerValueFactory) spinner.getValueFactory();
            if (value < factory.getMin() || value > factory.getMax()) throw new NumberFormatException();
            return value;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("库存调整数量必须是 -9999 到 9999 之间的整数", error);
        }
    }
}
