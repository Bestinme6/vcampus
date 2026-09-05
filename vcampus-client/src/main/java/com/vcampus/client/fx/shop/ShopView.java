package com.vcampus.client.fx.shop;

import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.ShopProductSort;
import com.vcampus.common.model.ShopAccessPolicy;
import com.vcampus.common.model.UserRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.Set;

final class ShopView extends BorderPane {
    interface Listener {
        void back();
        void search(String keyword, ShopCategory category, ShopProductSort sort, int page);
        void product(long productId);
        void addToCart(long productId, int quantity);
        void buyNow(ShopDetailView.BuyNowSelection selection);
        default void cart() { }
        default void orders() { }
        default void admin() { }
    }

    private final Label status = ShopUi.label("", "shop-status");
    private final ShopCatalogView catalog;
    private final ShopDetailView detail;

    ShopView(Set<UserRole> roles, Listener listener) {
        setId("shop-view");
        getStyleClass().add("shop-root");
        getStylesheets().add(Objects.requireNonNull(getClass().getResource("shop.css")).toExternalForm());
        catalog = new ShopCatalogView(listener);
        detail = new ShopDetailView(listener);
        Label eyebrow = ShopUi.label("VCAMPUS  /  STORE", "shop-eyebrow");
        Label title = ShopUi.label("校园商店", "shop-title");
        Button home = ShopUi.button("商品首页", "shop-tab", () -> listener.search("", null, ShopProductSort.NEWEST, 1));
        home.setId("shop-tab-catalog");
        Button cart = ShopUi.button("购物车", "shop-tab", listener::cart);
        cart.setId("shop-tab-cart");
        Button orders = ShopUi.button("我的订单", "shop-tab", listener::orders);
        orders.setId("shop-tab-orders");
        Button back = ShopUi.button("返回工作台", "shop-quiet", listener::back);
        HBox headerLine = ShopUi.row(new VBox(4, eyebrow, title), ShopUi.space(), back);
        HBox navigation = new HBox(8, home, cart, orders);
        if (ShopAccessPolicy.canManage(roles)) {
            Button admin = ShopUi.button("商店管理", "shop-tab", listener::admin);
            admin.setId("shop-tab-admin"); navigation.getChildren().add(admin);
        }
        VBox header = new VBox(14, headerLine, navigation, status);
        header.getStyleClass().add("shop-header");
        status.setManaged(false);
        status.setVisible(false);
        setTop(header);
    }

    ShopCatalogView catalog() { return catalog; }
    ShopDetailView detail() { return detail; }
    void showCatalog() { setCenter(catalog); }
    void showDetail() { setCenter(detail); }

    void status(String text, boolean error) {
        status.setText(text);
        status.setManaged(!text.isBlank());
        status.setVisible(!text.isBlank());
        status.getStyleClass().remove("shop-error");
        if (error) status.getStyleClass().add("shop-error");
    }

    void busy(boolean busy) { if (getCenter() != null) getCenter().setDisable(busy); }

    void showComingSoon(String message) {
        VBox box = new VBox(12, ShopUi.label(message, "shop-section-title"),
                ShopUi.label("商品浏览与详情已经可用，结算功能正在接入。", "shop-muted"));
        box.getStyleClass().add("shop-content");
        setCenter(box);
    }

    void page(Node node) { setCenter(node); }
}
