package com.vcampus.client.fx.shop;

import com.vcampus.common.model.ShopOrderStatus;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import java.util.Objects;
import java.util.function.LongConsumer;

final class SecondhandDetailView extends VBox {
    private final long userId;private final LongConsumer action;private final Runnable back;
    SecondhandDetailView(long userId,LongConsumer action){this(userId,action,()->{});}
    SecondhandDetailView(long userId,LongConsumer action,Runnable back){super(14);this.userId=userId;this.action=Objects.requireNonNull(action,"action");this.back=Objects.requireNonNull(back,"back");getStyleClass().addAll("used-content","used-detail");}
    void show(SecondhandData.Listing item){getChildren().clear();Button backButton=ShopUi.button("返回二手市场","shop-quiet",back);Label title=ShopUi.label(item.title(),"used-title");Label price=ShopUi.label("¥ "+item.price().toPlainString(),"used-price");Label meta=ShopUi.label(item.condition()+" · "+item.category()+" · 卖家 "+item.sellerName(),"shop-muted");Label description=ShopUi.label(item.description(),"used-description");description.setWrapText(true);getChildren().addAll(backButton,title,price,meta,description);if(item.sellerId()!=userId&&item.status()==com.vcampus.common.model.SecondhandItemStatus.AVAILABLE&&item.moderation()==com.vcampus.common.model.SecondhandModerationStatus.VISIBLE){Button buy=ShopUi.button("购买","used-primary",()->action.accept(item.id()));buy.setId("used-buy");getChildren().add(buy);}}
    void showOrder(SecondhandData.Order order){getChildren().clear();Button backButton=ShopUi.button("返回二手订单","shop-quiet",back);Label title=ShopUi.label(order.title(),"used-title");Label status=ShopUi.label("订单状态 · "+order.status().name(),"used-status");Label address=ShopUi.label(order.shippingAddress().recipient()+" · "+order.shippingAddress().phone()+" · "+order.shippingAddress().address(),"used-address");address.setId("used-order-address");address.setWrapText(true);getChildren().addAll(backButton,title,status,address);HBox actions=new HBox(8);if(order.buyerId()==userId&&order.status()==ShopOrderStatus.SHIPPED){Button confirm=ShopUi.button("确认收货","used-primary",()->action.accept(order.id()));confirm.setId("used-confirm");actions.getChildren().add(confirm);}if(order.buyerId()==userId&&order.status()==ShopOrderStatus.PAID){Button cancel=ShopUi.button("取消订单","shop-quiet",()->action.accept(order.id()));cancel.setId("used-cancel");actions.getChildren().add(cancel);}getChildren().add(actions);}
}
