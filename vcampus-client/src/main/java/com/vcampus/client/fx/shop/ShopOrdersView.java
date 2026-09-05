package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.OrderPage;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

final class ShopOrdersView extends BorderPane {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private final LongConsumer open;
    private final IntConsumer page;
    private final VBox body = new VBox(14);

    ShopOrdersView(LongConsumer open, IntConsumer page) {
        this.open = open; this.page = page;
        setId("shop-orders"); getStyleClass().add("shop-page");
        body.setPadding(new Insets(24));
        ScrollPane scroll = new ScrollPane(body); scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); scroll.getStyleClass().add("shop-scroll");
        setCenter(scroll);
    }

    void show(OrderPage value) {
        body.getChildren().setAll(ShopUi.label("我的订单", "shop-section-title"));
        for (var order : value.rows()) {
            VBox copy = new VBox(7, ShopUi.label(order.orderNo(), "shop-card-title"),
                    ShopUi.label(DATE.format(order.createdAt()) + " · " + status(order.status()), "shop-muted"));
            HBox.setHgrow(copy, Priority.ALWAYS);
            Button detail = ShopUi.button("查看订单", "shop-quiet", () -> open.accept(order.id()));
            detail.setId("shop-order-open-" + order.id());
            HBox row = ShopUi.row(copy, ShopUi.label("¥" + order.totalAmount().toPlainString(), "shop-price"), detail);
            row.getStyleClass().add("shop-cart-row"); body.getChildren().add(row);
        }
        if (value.rows().isEmpty()) body.getChildren().add(ShopUi.label("暂无订单记录。", "shop-muted"));
        int pages = Math.max(1, (value.total() + value.pageSize() - 1) / value.pageSize());
        Button previous = ShopUi.button("上一页", "shop-quiet", () -> page.accept(value.page() - 1));
        Button next = ShopUi.button("下一页", "shop-quiet", () -> page.accept(value.page() + 1));
        previous.setDisable(value.page() <= 1); next.setDisable(value.page() >= pages);
        body.getChildren().add(ShopUi.row(ShopUi.space(), previous,
                ShopUi.label("第 " + value.page() + " / " + pages + " 页", "shop-muted"), next));
    }

    private static String status(com.vcampus.common.model.ShopOrderStatus status) {
        return switch (status) {
            case PAID -> "已支付"; case SHIPPED -> "已发货"; case COMPLETED -> "已完成"; case CANCELLED -> "已取消";
        };
    }
}
