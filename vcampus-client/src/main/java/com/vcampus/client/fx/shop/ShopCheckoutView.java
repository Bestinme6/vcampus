package com.vcampus.client.fx.shop;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

final class ShopCheckoutView extends BorderPane {
    enum Source { DIRECT, CART }

    record CheckoutLine(long productId, String name, BigDecimal unitPrice, int quantity,
                        int stock, boolean enabled) {
        CheckoutLine {
            if (productId < 1 || name == null || name.isBlank() || unitPrice == null
                    || unitPrice.signum() < 0 || quantity < 1 || quantity > 999 || stock < 0) {
                throw new IllegalArgumentException("确认商品无效");
            }
        }
        BigDecimal subtotal() { return unitPrice.multiply(BigDecimal.valueOf(quantity)); }
        boolean eligible() { return enabled && stock >= quantity; }
    }

    record CheckoutDraft(Source source, List<CheckoutLine> items, BigDecimal estimatedTotal,
                         BigDecimal balance, String operationId) {
        CheckoutDraft {
            source = Objects.requireNonNull(source);
            items = List.copyOf(items);
            balance = Objects.requireNonNull(balance);
            estimatedTotal = Objects.requireNonNull(estimatedTotal);
            if (items.isEmpty() || estimatedTotal.signum() < 0 || balance.signum() < 0
                    || operationId == null || operationId.isBlank()
                    || items.stream().map(CheckoutLine::subtotal).reduce(new BigDecimal("0.00"), BigDecimal::add)
                    .compareTo(estimatedTotal) != 0) throw new IllegalArgumentException("结算草稿无效");
        }
    }

    private final Consumer<CheckoutDraft> confirm;
    private final Runnable back;
    private final VBox body = new VBox(16);
    private final Button confirmButton;
    private final Label status = ShopUi.label("", "shop-status");
    private CheckoutDraft draft;

    ShopCheckoutView(Consumer<CheckoutDraft> confirm, Runnable back) {
        this.confirm = confirm;
        this.back = back;
        setId("shop-checkout");
        getStyleClass().add("shop-page");
        body.setPadding(new Insets(26));
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("shop-scroll");
        setCenter(scroll);
        confirmButton = ShopUi.button("确认支付", "shop-primary", this::submit);
        confirmButton.setId("shop-checkout-confirm");
    }

    void show(CheckoutDraft value) {
        draft = value;
        status.setText(""); status.setManaged(false); status.setVisible(false);
        VBox lines = new VBox(10);
        for (CheckoutLine item : value.items()) {
            lines.getChildren().add(ShopUi.row(ShopUi.label(item.name(), "shop-card-title"), ShopUi.space(),
                    ShopUi.label(item.quantity() + " × ¥" + item.unitPrice().toPlainString(), "shop-muted"),
                    ShopUi.label("¥" + item.subtotal().toPlainString(), "shop-price")));
        }
        confirmButton.setDisable(value.items().stream().anyMatch(item -> !item.eligible()));
        body.getChildren().setAll(ShopUi.button("← 返回", "shop-quiet", back),
                ShopUi.label(value.source() == Source.DIRECT ? "确认立即购买" : "确认购物车订单", "shop-detail-title"),
                ShopUi.label("提交前不会扣款，商品价格和库存将由服务器再次校验。", "shop-muted"), lines,
                ShopUi.label("预估总额  ¥" + value.estimatedTotal().toPlainString(), "shop-detail-price"),
                ShopUi.label("付款前账户余额  ¥" + value.balance().toPlainString(), "shop-muted"), status, confirmButton);
    }

    void failure(String message) {
        status.setText(message); status.setManaged(true); status.setVisible(true);
        status.getStyleClass().add("shop-error");
        confirmButton.setDisable(false);
    }

    void busy(boolean value) { confirmButton.setDisable(value); }
    CheckoutDraft draft() { return draft; }

    private void submit() {
        if (draft == null || confirmButton.isDisabled()) return;
        confirmButton.setDisable(true);
        confirm.accept(draft);
    }
}
