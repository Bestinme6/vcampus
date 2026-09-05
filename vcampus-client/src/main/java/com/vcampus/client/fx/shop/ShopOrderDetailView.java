package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.OrderDetail;
import com.vcampus.common.model.ShopOrderStatus;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

final class ShopOrderDetailView extends BorderPane {
    interface Listener { void back(); void cancel(long orderId); void confirm(long orderId); }
    private final Listener listener;
    private final VBox body = new VBox(16);

    ShopOrderDetailView(Listener listener) {
        this.listener = listener;
        setId("shop-order-detail"); getStyleClass().add("shop-page"); body.setPadding(new Insets(24));
        ScrollPane scroll = new ScrollPane(body); scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); scroll.getStyleClass().add("shop-scroll");
        setCenter(scroll);
    }

    void show(OrderDetail value) {
        var order = value.order();
        Button cancel = ShopUi.button("取消并退款", "shop-quiet", () -> listener.cancel(order.id()));
        cancel.setId("shop-order-cancel"); cancel.setDisable(order.status() != ShopOrderStatus.PAID);
        Button confirm = ShopUi.button("确认收货", "shop-primary", () -> listener.confirm(order.id()));
        confirm.setId("shop-order-confirm"); confirm.setDisable(order.status() != ShopOrderStatus.SHIPPED);
        body.getChildren().setAll(ShopUi.button("← 返回订单", "shop-quiet", listener::back),
                ShopUi.label("订单 " + order.orderNo(), "shop-detail-title"),
                ShopUi.label("订单金额  ¥" + order.totalAmount().toPlainString(), "shop-detail-price"),
                ShopUi.row(cancel, confirm), ShopUi.label("商品快照", "shop-section-title"));
        for (var item : value.items()) {
            VBox placeholder = new VBox(ShopUi.label("暂无图片", "shop-image-placeholder"));
            placeholder.getStyleClass().add("shop-cart-image");
            var name = ShopUi.label(item.nameSnapshot(), "shop-card-title"); name.setId("shop-order-item-" + item.id());
            VBox copy = new VBox(6, name, ShopUi.label(item.skuSnapshot(), "shop-hint"),
                    ShopUi.label(item.quantity() + " × ¥" + item.unitPrice().toPlainString(), "shop-muted"));
            HBox.setHgrow(copy, Priority.ALWAYS);
            HBox row = ShopUi.row(placeholder, copy, ShopUi.label("¥" + item.subtotal().toPlainString(), "shop-price"));
            row.getStyleClass().add("shop-cart-row"); body.getChildren().add(row);
        }
    }
}
