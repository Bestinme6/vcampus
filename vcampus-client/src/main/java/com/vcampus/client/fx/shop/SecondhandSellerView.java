package com.vcampus.client.fx.shop;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.function.LongConsumer;

/** Seller order surface. Address snapshots are rendered only for an order participant. */
final class SecondhandSellerView extends VBox {
    private final long userId;
    private final LongConsumer ship;

    SecondhandSellerView(long userId, LongConsumer ship) {
        super(12);
        this.userId=userId;
        this.ship=Objects.requireNonNull(ship,"ship");
        getStyleClass().add("used-content");
    }

    void showOrders(SecondhandData.OrderPage page, LongConsumer open) {
        getChildren().setAll(ShopUi.label("我的出售", "used-title"));
        if(page.rows().isEmpty())getChildren().add(ShopUi.label("还没有出售订单","shop-muted"));
        for(var order:page.rows()){
            Button row=ShopUi.button(order.title()+" · ¥ "+order.amount().toPlainString()+" · "+order.status(),
                    "used-card",()->open.accept(order.id()));
            row.setId("used-sale-order-"+order.id());
            getChildren().add(row);
        }
    }

    void showOrder(SecondhandData.Order order) {
        getChildren().clear();
        getChildren().addAll(ShopUi.label(order.title(),"used-title"),
                ShopUi.label("订单状态 · "+order.status(),"used-status"));
        boolean participant=order.buyerId()==userId||order.sellerId()==userId;
        if(participant){
            Label address=ShopUi.label(order.shippingAddress().recipient()+" · "+order.shippingAddress().phone()
                    +" · "+order.shippingAddress().address(),"used-address");
            address.setId("used-address");address.setWrapText(true);getChildren().add(address);
        }
        if(order.sellerId()==userId&&order.status()==com.vcampus.common.model.ShopOrderStatus.PAID){
            Button button=ShopUi.button("标记已发货","used-primary",()->ship.accept(order.id()));
            button.setId("used-ship");getChildren().add(button);
        }
    }
}
