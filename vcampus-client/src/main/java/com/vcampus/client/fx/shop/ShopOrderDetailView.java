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

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

final class ShopOrderDetailView extends BorderPane {
    interface Listener {
        void back();
        void cancel(long orderId);
        void confirm(long orderId);
        default void review(long orderId, long orderItemId, Long reviewId) { }
    }
    private final Listener listener;
    private final VBox body = new VBox(16);
    private final Map<Long, VBox> pictures = new LinkedHashMap<>();

    ShopOrderDetailView(Listener listener) {
        this.listener = listener;
        setId("shop-order-detail"); getStyleClass().add("shop-page"); body.setPadding(new Insets(24));
        ScrollPane scroll = new ScrollPane(body); scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); scroll.getStyleClass().add("shop-scroll");
        setCenter(scroll);
    }

    void show(OrderDetail value) {
        var order = value.order();
        pictures.clear();
        Button cancel = ShopUi.button("取消并退款", "shop-quiet", () -> listener.cancel(order.id()));
        cancel.setId("shop-order-cancel"); cancel.setDisable(order.status() != ShopOrderStatus.PAID);
        Button confirm = ShopUi.button("确认收货", "shop-primary", () -> listener.confirm(order.id()));
        confirm.setId("shop-order-confirm"); confirm.setDisable(order.status() != ShopOrderStatus.SHIPPED);
        body.getChildren().setAll(ShopUi.button("← 返回订单", "shop-quiet", listener::back),
                ShopUi.label("订单 " + order.orderNo(), "shop-detail-title"),
                ShopUi.label("订单金额  ¥" + order.totalAmount().toPlainString(), "shop-detail-price"),
                ShopUi.row(cancel, confirm), shipping(value),
                ShopUi.label("商品快照", "shop-section-title"));
        for (var item : value.items()) {
            VBox placeholder = new VBox(ShopUi.label("暂无图片", "shop-image-placeholder"));
            placeholder.getStyleClass().add("shop-cart-image");
            placeholder.setId("shop-order-image-" + item.id());
            pictures.put(item.id(), placeholder);
            var name = ShopUi.label(item.nameSnapshot(), "shop-card-title"); name.setId("shop-order-item-" + item.id());
            VBox copy = new VBox(6, name, ShopUi.label(item.skuSnapshot(), "shop-hint"),
                    ShopUi.label(item.quantity() + " × ¥" + item.unitPrice().toPlainString(), "shop-muted"));
            HBox.setHgrow(copy, Priority.ALWAYS);
            HBox actions = new HBox(8);
            if (item.reviewable() || item.reviewId() != null) {
                Button review = ShopUi.button(item.reviewId() == null ? "去评价" : "查看评价",
                        item.reviewId() == null ? "shop-primary" : "shop-secondary",
                        () -> listener.review(order.id(), item.id(), item.reviewId()));
                review.setId("shop-order-review-" + item.id());
                actions.getChildren().add(review);
            }
            VBox amount = new VBox(8, ShopUi.label("¥" + item.subtotal().toPlainString(), "shop-price"), actions);
            HBox row = ShopUi.row(placeholder, copy, amount);
            row.getStyleClass().add("shop-cart-row"); body.getChildren().add(row);
        }
    }

    void showThumbnail(long orderItemId, byte[] bytes) {
        VBox picture = pictures.get(orderItemId);
        if (picture == null) return;
        try {
            var image = new javafx.scene.image.Image(new ByteArrayInputStream(bytes), 64, 64, true, true);
            if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new IllegalArgumentException("Invalid image");
            }
            var imageView = new javafx.scene.image.ImageView(image);
            imageView.setId("shop-order-thumbnail-" + orderItemId);
            imageView.setFitWidth(64);
            imageView.setFitHeight(64);
            imageView.setPreserveRatio(true);
            picture.getChildren().setAll(imageView);
        } catch (RuntimeException error) {
            imageStatus(orderItemId, "图片暂不可用");
        }
    }

    void imageStatus(long orderItemId, String message) {
        VBox picture = pictures.get(orderItemId);
        if (picture != null) {
            picture.getChildren().setAll(ShopUi.label(message, "shop-image-placeholder"));
        }
    }

    private VBox shipping(OrderDetail value) {
        VBox box = new VBox(5);
        box.setId("shop-order-shipping");
        box.getStyleClass().add("shop-address-card");
        box.getChildren().add(ShopUi.label("收货地址", "shop-section-title"));
        if (value.shippingAddress() == null) {
            box.getChildren().add(ShopUi.label("历史订单无收货地址", "shop-muted"));
        } else {
            box.getChildren().addAll(
                    ShopUi.label(value.shippingAddress().recipient() + "  "
                            + value.shippingAddress().phone(), "shop-card-title"),
                    ShopUi.label(value.shippingAddress().address(), "shop-muted"));
        }
        return box;
    }
}
