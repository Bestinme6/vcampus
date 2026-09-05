package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.Product;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.ByteArrayInputStream;

final class ShopProductCard extends VBox {
    private final Product product;
    private final StackPane imageFrame = new StackPane();
    private final ImageView image = new ImageView();
    private final Label placeholder;

    ShopProductCard(Product product, ShopView.Listener listener) {
        super(13);
        this.product = product;
        setId("shop-product-" + product.id());
        getStyleClass().add("shop-product-card");
        setMaxWidth(Double.MAX_VALUE);
        setCursor(Cursor.HAND);
        setFocusTraversable(true);
        setAccessibleText(product.name() + "，价格 " + money(product.price()) + "，"
                + stockText(product));
        setOnMouseClicked(event -> listener.product(product.id()));
        setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER
                    || event.getCode() == javafx.scene.input.KeyCode.SPACE) listener.product(product.id());
        });

        image.setPreserveRatio(true);
        image.setSmooth(true);
        image.fitWidthProperty().bind(imageFrame.widthProperty());
        image.fitHeightProperty().bind(imageFrame.heightProperty());
        placeholder = ShopUi.label("暂无图片", "shop-image-placeholder");
        placeholder.setId("shop-image-placeholder-" + product.id());
        imageFrame.getChildren().addAll(placeholder, image);
        imageFrame.setAlignment(Pos.CENTER);
        imageFrame.getStyleClass().add("shop-card-image");

        Label category = ShopUi.label(product.category().displayName(), "shop-category-badge");
        Label title = ShopUi.label(product.name(), "shop-card-title");
        title.setWrapText(true);
        Label description = ShopUi.label(product.description().isBlank() ? "校园精选商品" : product.description(),
                "shop-card-description");
        description.setWrapText(true);
        description.setMaxHeight(42);
        Label price = ShopUi.label(money(product.price()), "shop-price");
        Label stock = ShopUi.label(stockText(product),
                product.enabled() && product.stock() > 0 ? "shop-stock" : "shop-stock-empty");
        getChildren().addAll(imageFrame, category, title, description, ShopUi.row(price, ShopUi.space(), stock));
    }

    Product product() { return product; }

    void showImage(byte[] bytes) {
        try {
            Image loaded = new Image(new ByteArrayInputStream(bytes));
            if (loaded.isError() || loaded.getWidth() <= 0) throw new IllegalArgumentException("invalid image");
            image.setImage(loaded);
            placeholder.setVisible(false);
            placeholder.setManaged(false);
        } catch (RuntimeException error) {
            image.setImage(null);
            placeholder.setText("图片暂不可用");
            placeholder.setVisible(true);
            placeholder.setManaged(true);
        }
    }

    private static String stockText(Product product) {
        if (!product.enabled()) return "已下架";
        return product.stock() > 0 ? "库存 " + product.stock() : "暂时售罄";
    }

    private static String money(java.math.BigDecimal value) { return "¥" + value.toPlainString(); }
}
