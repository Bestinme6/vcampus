package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.Cart;
import com.vcampus.client.fx.shop.ShopData.CartItem;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class ShopCartView extends BorderPane {
    interface Listener {
        void quantity(long productId, int quantity);
        void remove(long productId);
        void checkout(Set<Long> selectedProductIds);
    }

    private final Listener listener;
    private final VBox rows = new VBox(12);
    private final VBox summary = new VBox(14);
    private final Label total = ShopUi.label("¥0.00", "shop-detail-price");
    private final Button checkout;
    private final Map<Long, CheckBox> selections = new LinkedHashMap<>();
    private Cart cart = new Cart(java.util.List.of(), new BigDecimal("0.00"));

    ShopCartView(Listener listener) {
        this.listener = listener;
        setId("shop-cart");
        getStyleClass().add("shop-page");
        rows.setPadding(new Insets(24));
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("shop-scroll");
        setCenter(scroll);
        checkout = ShopUi.button("去结算", "shop-primary", this::checkoutSelected);
        checkout.setId("shop-cart-checkout");
        Button all = ShopUi.button("全选可结算商品", "shop-quiet", this::selectAll);
        summary.getStyleClass().add("shop-summary");
        summary.getChildren().addAll(ShopUi.label("结算汇总", "shop-section-title"),
                ShopUi.label("已选商品金额", "shop-muted"), total, all, checkout);
        setRight(summary);
        widthProperty().addListener((ignored, old, width) -> responsive(width.doubleValue()));
    }

    void show(Cart value) {
        cart = value;
        selections.clear();
        rows.getChildren().clear();
        rows.getChildren().add(ShopUi.label("我的购物车", "shop-section-title"));
        for (CartItem item : value.items()) rows.getChildren().add(row(item));
        if (value.items().isEmpty()) rows.getChildren().add(ShopUi.label("购物车还是空的，去挑选喜欢的商品吧。", "shop-muted"));
        updateSummary();
    }

    void selectAll() {
        selections.values().forEach(box -> { if (!box.isDisabled()) box.setSelected(true); });
        updateSummary();
    }

    Set<Long> selectedProductIds() {
        Set<Long> result = new LinkedHashSet<>();
        selections.forEach((id, box) -> { if (box.isSelected() && !box.isDisabled()) result.add(id); });
        return Set.copyOf(result);
    }

    BigDecimal selectedTotal() {
        Set<Long> selected = selectedProductIds();
        return cart.items().stream().filter(item -> selected.contains(item.productId()))
                .map(CartItem::subtotal).reduce(new BigDecimal("0.00"), BigDecimal::add);
    }

    void changeQuantity(long productId, int quantity) { listener.quantity(productId, quantity); }
    void checkoutSelected() { if (!selectedProductIds().isEmpty()) listener.checkout(selectedProductIds()); }

    private HBox row(CartItem item) {
        boolean eligible = item.enabled() && item.stock() > 0 && item.quantity() <= item.stock();
        CheckBox selected = new CheckBox();
        selected.setId("shop-cart-select-" + item.productId());
        selected.setDisable(!eligible);
        selected.setOnAction(event -> updateSummary());
        selections.put(item.productId(), selected);
        VBox picture = new VBox(ShopUi.label("暂无图片", "shop-image-placeholder"));
        picture.getStyleClass().add("shop-cart-image");
        Label name = ShopUi.label(item.name(), "shop-card-title");
        Label reason = ShopUi.label(eligible ? "库存充足" : unavailable(item), eligible ? "shop-stock" : "shop-stock-empty");
        VBox copy = new VBox(7, name, ShopUi.label(item.sku(), "shop-hint"), reason);
        HBox.setHgrow(copy, Priority.ALWAYS);
        int max = Math.max(item.quantity(), Math.max(1, Math.min(item.stock(), 999)));
        Spinner<Integer> quantity = new Spinner<>();
        quantity.setId("shop-cart-quantity-" + item.productId());
        quantity.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, max, item.quantity()));
        quantity.valueProperty().addListener((ignored, old, next) -> {
            if (!next.equals(old)) listener.quantity(item.productId(), next);
        });
        Button remove = ShopUi.button("移除", "shop-quiet", () -> listener.remove(item.productId()));
        remove.setId("shop-cart-remove-" + item.productId());
        VBox price = new VBox(7, ShopUi.label("¥" + item.subtotal().toPlainString(), "shop-price"), quantity, remove);
        price.setAlignment(Pos.CENTER_RIGHT);
        HBox row = new HBox(14, selected, picture, copy, price);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("shop-cart-row");
        return row;
    }

    private void updateSummary() {
        total.setText("¥" + selectedTotal().toPlainString());
        checkout.setDisable(selectedProductIds().isEmpty());
    }

    private void responsive(double width) {
        if (width > 0 && width < 850) { setRight(null); setBottom(summary); }
        else { setBottom(null); setRight(summary); }
    }

    private static String unavailable(CartItem item) {
        if (!item.enabled()) return "商品已下架，暂不可结算";
        if (item.stock() == 0) return "商品缺货，暂不可结算";
        return "购买数量超过库存，请先调整";
    }
}
