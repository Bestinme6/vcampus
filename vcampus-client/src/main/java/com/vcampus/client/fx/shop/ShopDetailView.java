package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.ImageRef;
import com.vcampus.client.fx.shop.ShopData.ProductDetail;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

final class ShopDetailView extends ScrollPane {
    record BuyNowSelection(long productId, int quantity) {
        BuyNowSelection {
            if (productId < 1 || quantity < 1 || quantity > 999) throw new IllegalArgumentException("购买参数无效");
        }
    }

    private final ShopView.Listener listener;
    private final VBox content = new VBox(22);
    private final StackPane mainFrame = new StackPane();
    private final ImageView mainImage = new ImageView();
    private final FlowPane thumbnails = new FlowPane(10, 10);
    private final Spinner<Integer> quantity = new Spinner<>();
    private final Button addCart;
    private final Button buyNow;
    private final Map<Long, byte[]> images = new HashMap<>();
    private ProductDetail detail;
    private long selectedImageId;

    ShopDetailView(ShopView.Listener listener) {
        this.listener = listener;
        setId("shop-detail");
        getStyleClass().add("shop-scroll");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        content.getStyleClass().add("shop-content");
        mainImage.setPreserveRatio(true);
        mainImage.setSmooth(true);
        mainImage.fitWidthProperty().bind(mainFrame.widthProperty());
        mainImage.fitHeightProperty().bind(mainFrame.heightProperty());
        mainFrame.getStyleClass().add("shop-detail-image");
        quantity.setId("shop-detail-quantity");
        quantity.setEditable(false);
        addCart = ShopUi.button("加入购物车", "shop-secondary", this::addToCart);
        addCart.setId("shop-add-cart");
        buyNow = ShopUi.button("立即购买", "shop-primary", this::buyNow);
        buyNow.setId("shop-buy-now");
    }

    void show(ProductDetail value) {
        detail = value;
        images.clear();
        selectedImageId = value.images().isEmpty() ? 0 : value.images().getFirst().id();
        var product = value.product();
        int maximum = Math.max(1, Math.min(product.stock(), 999));
        quantity.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, maximum, 1));
        boolean available = product.enabled() && product.stock() > 0;
        quantity.setDisable(!available);
        addCart.setDisable(!available);
        buyNow.setDisable(!available);
        showMainPlaceholder(value.images().isEmpty() ? "暂无商品图片" : "图片加载中…");

        thumbnails.getChildren().clear();
        for (ImageRef ref : value.images()) {
            Button thumb = new Button("图片 " + (ref.sortOrder() + 1));
            thumb.setId("shop-detail-thumbnail-" + ref.id());
            thumb.getStyleClass().add("shop-thumbnail");
            thumb.setOnAction(event -> selectImage(ref.id()));
            thumbnails.getChildren().add(thumb);
        }

        Label title = ShopUi.label(product.name(), "shop-detail-title");
        title.setWrapText(true);
        Label description = ShopUi.label(product.description().isBlank() ? "暂无商品介绍。" : product.description(),
                "shop-detail-description");
        description.setId("shop-detail-description");
        description.setWrapText(true);
        Label stock = ShopUi.label(!product.enabled() ? "商品已下架" : product.stock() > 0
                ? "现货 · 库存 " + product.stock() : "暂时售罄", available ? "shop-stock" : "shop-stock-empty");
        VBox information = new VBox(16, ShopUi.label(product.category().displayName(), "shop-category-badge"),
                title, ShopUi.label("¥" + product.price().toPlainString(), "shop-detail-price"), stock,
                ShopUi.label("数量", "shop-hint"), quantity, new HBox(10, addCart, buyNow));
        information.setMinWidth(280);
        HBox.setHgrow(information, Priority.ALWAYS);
        VBox gallery = new VBox(12, mainFrame, thumbnails);
        gallery.setMinWidth(0);
        HBox.setHgrow(gallery, Priority.ALWAYS);
        HBox head = new HBox(28, gallery, information);
        head.setAlignment(Pos.TOP_LEFT);
        head.getStyleClass().add("shop-detail-card");
        Button back = ShopUi.button("← 返回商品列表", "shop-quiet",
                () -> listener.search("", null, com.vcampus.common.model.ShopProductSort.NEWEST, 1));
        content.getChildren().setAll(back, head, ShopUi.label("商品详情", "shop-section-title"), description,
                ShopUi.label("商品价格与库存以提交订单时服务器校验结果为准。", "shop-note"));
        setContent(content);
    }

    void showImage(ImageRef ref, byte[] bytes) {
        if (detail == null || detail.images().stream().noneMatch(item -> item.id() == ref.id())) return;
        images.put(ref.id(), bytes.clone());
        Button thumbnail = (Button) lookup("#shop-detail-thumbnail-" + ref.id());
        if (thumbnail != null) thumbnail.setText("● " + (ref.sortOrder() + 1));
        if (selectedImageId == ref.id()) renderMain(bytes);
    }

    int thumbnailCount() { return thumbnails.getChildren().size(); }

    private void selectImage(long imageId) {
        selectedImageId = imageId;
        byte[] bytes = images.get(imageId);
        if (bytes == null) showMainPlaceholder("图片加载中…"); else renderMain(bytes);
    }

    private void renderMain(byte[] bytes) {
        try {
            Image image = new Image(new ByteArrayInputStream(bytes));
            if (image.isError() || image.getWidth() <= 0) throw new IllegalArgumentException("invalid image");
            mainImage.setImage(image);
            mainFrame.getChildren().setAll(mainImage);
        } catch (RuntimeException error) {
            showMainPlaceholder("图片暂不可用");
        }
    }

    private void showMainPlaceholder(String text) {
        Label placeholder = ShopUi.label(text, "shop-image-placeholder");
        placeholder.setId("shop-detail-image-placeholder");
        mainFrame.getChildren().setAll(placeholder);
    }

    private void addToCart() {
        if (detail != null && !addCart.isDisabled()) listener.addToCart(detail.product().id(), quantity.getValue());
    }

    private void buyNow() {
        if (detail != null && !buyNow.isDisabled()) {
            listener.buyNow(new BuyNowSelection(detail.product().id(), quantity.getValue()));
        }
    }
}
