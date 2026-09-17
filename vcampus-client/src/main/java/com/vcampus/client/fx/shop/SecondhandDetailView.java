package com.vcampus.client.fx.shop;

import com.vcampus.common.model.SecondhandCategory;
import com.vcampus.common.model.SecondhandItemStatus;
import com.vcampus.common.model.SecondhandModerationStatus;
import com.vcampus.common.model.ShopOrderStatus;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongConsumer;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

final class SecondhandDetailView extends VBox {
    private final long userId;
    private final LongConsumer action;
    private final Runnable back;
    private final LongConsumer edit;
    private final LongConsumer sellerReviews;
    private final LongConsumer reviewOrder;
    private final Map<Long, ImageView> images = new HashMap<>();

    SecondhandDetailView(long userId, LongConsumer action) {
        this(userId, action, () -> { });
    }

    SecondhandDetailView(long userId, LongConsumer action, Runnable back) {
        this(userId, action, back, id -> { }, id -> { }, id -> { });
    }

    SecondhandDetailView(long userId, LongConsumer action, Runnable back, LongConsumer edit,
                         LongConsumer sellerReviews, LongConsumer reviewOrder) {
        super(14);
        this.userId = userId;
        this.action = Objects.requireNonNull(action, "action");
        this.back = Objects.requireNonNull(back, "back");
        this.edit = Objects.requireNonNull(edit, "edit");
        this.sellerReviews = Objects.requireNonNull(sellerReviews, "sellerReviews");
        this.reviewOrder = Objects.requireNonNull(reviewOrder, "reviewOrder");
        getStyleClass().addAll("used-content", "used-detail");
    }

    void show(SecondhandData.Listing item) {
        getChildren().clear();
        images.clear();
        Button backButton = ShopUi.button("返回二手市场", "shop-quiet", back);
        Label title = ShopUi.label(item.title(), "used-title");
        Label price = ShopUi.label("¥ " + item.price().toPlainString(), "used-price");
        Label meta = ShopUi.label(item.condition() + " · "
                + SecondhandCategory.fromStored(item.category()).displayName()
                + " · 卖家 " + item.sellerName(), "shop-muted");
        Label description = ShopUi.label(item.description(), "used-description");
        description.setWrapText(true);
        getChildren().addAll(backButton, title, price, meta);
        if (!item.images().isEmpty()) {
            FlowPane gallery = new FlowPane(10, 10);
            gallery.setId("used-detail-gallery");
            for (ShopData.ImageRef ref : item.images()) {
                ImageView view = new ImageView();
                view.setId("used-detail-image-" + ref.id());
                view.setFitWidth(230);
                view.setFitHeight(155);
                view.setPreserveRatio(true);
                view.setSmooth(true);
                view.getStyleClass().add("used-detail-image");
                images.put(ref.id(), view);
                gallery.getChildren().add(view);
            }
            getChildren().add(gallery);
        }
        getChildren().add(description);
        HBox actions = new HBox(8);
        Button reviews = ShopUi.button("查看卖家评价", "shop-quiet",
                () -> sellerReviews.accept(item.sellerId()));
        reviews.setId("used-seller-reviews");
        actions.getChildren().add(reviews);
        if (item.sellerId() != userId && item.status() == SecondhandItemStatus.AVAILABLE
                && item.moderation() == SecondhandModerationStatus.VISIBLE) {
            Button buy = ShopUi.button("购买", "used-primary", () -> action.accept(item.id()));
            buy.setId("used-buy");
            actions.getChildren().add(buy);
        }
        if (item.sellerId() == userId && item.status() == SecondhandItemStatus.AVAILABLE
                && item.moderation() == SecondhandModerationStatus.VISIBLE) {
            Button manage = ShopUi.button("编辑物品", "used-primary", () -> edit.accept(item.id()));
            manage.setId("used-edit");
            actions.getChildren().add(manage);
        }
        getChildren().add(actions);
    }

    void showImage(long imageId, byte[] bytes) {
        ImageView view = images.get(imageId);
        if (view == null || bytes == null) return;
        Image image = new Image(new ByteArrayInputStream(bytes));
        if (!image.isError() && image.getWidth() > 0) view.setImage(image);
    }

    void showOrder(SecondhandData.Order order) {
        getChildren().clear();
        images.clear();
        Button backButton = ShopUi.button("返回二手订单", "shop-quiet", back);
        Label title = ShopUi.label(order.title(), "used-title");
        Label status = ShopUi.label("订单状态 · " + order.status().name(), "used-status");
        Label address = ShopUi.label(order.shippingAddress().recipient() + " · "
                + order.shippingAddress().phone() + " · " + order.shippingAddress().address(), "used-address");
        address.setId("used-order-address");
        address.setWrapText(true);
        getChildren().addAll(backButton, title, status, address);
        HBox actions = new HBox(8);
        if (order.buyerId() == userId && order.status() == ShopOrderStatus.SHIPPED) {
            Button confirm = ShopUi.button("确认收货", "used-primary", () -> action.accept(order.id()));
            confirm.setId("used-confirm");
            actions.getChildren().add(confirm);
        }
        if (order.buyerId() == userId && order.status() == ShopOrderStatus.PAID) {
            Button cancel = ShopUi.button("取消订单", "shop-quiet", () -> action.accept(order.id()));
            cancel.setId("used-cancel");
            actions.getChildren().add(cancel);
        }
        if (order.buyerId() == userId && order.status() == ShopOrderStatus.COMPLETED) {
            Button review = ShopUi.button("评价本次交易", "used-primary",
                    () -> reviewOrder.accept(order.id()));
            review.setId("used-order-review");
            actions.getChildren().add(review);
        }
        getChildren().add(actions);
    }
}
