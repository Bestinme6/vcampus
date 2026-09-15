package com.vcampus.client.fx.shop;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Dialog;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.ByteArrayInputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Public product-review list. Rows are rendered in the exact order returned by the server. */
final class ShopReviewListView extends VBox {
    interface Listener {
        void page(long productId, int page);
        void like(long reviewId, boolean enabled);
        void openImage(ShopData.ReviewImageRef image);
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private final Listener listener;
    private final VBox rows = new VBox(14);
    private final Map<Long, ShopData.Review> reviews = new LinkedHashMap<>();
    private long productId;

    ShopReviewListView(Listener listener) {
        super(14);
        this.listener = Objects.requireNonNull(listener, "listener");
        setId("shop-review-list");
        getStyleClass().add("shop-review-list");
        getChildren().add(rows);
    }

    void showLoading(long valueProductId) {
        productId = valueProductId;
        reviews.clear();
        Label loading = ShopUi.label("正在读取商品评价…", "shop-muted");
        loading.setId("shop-review-loading");
        rows.getChildren().setAll(loading);
    }

    void showFailure(long valueProductId, String message, Runnable retry) {
        productId = valueProductId;
        reviews.clear();
        Label error = ShopUi.label(message, "shop-error-text");
        error.setId("shop-review-error");
        Button button = ShopUi.button("重新加载", "shop-secondary", retry);
        button.setId("shop-review-retry");
        rows.getChildren().setAll(new VBox(10, error, button));
    }

    void show(long valueProductId, ShopData.ReviewPage page) {
        if (valueProductId < 1) throw new IllegalArgumentException("商品编号无效");
        productId = valueProductId;
        reviews.clear();
        page.rows().forEach(review -> reviews.put(review.id(), review));
        if (page.rows().isEmpty()) {
            Label empty = ShopUi.label("还没有评价，确认收货后可以分享使用感受。", "shop-muted");
            empty.setId("shop-review-empty");
            rows.getChildren().setAll(empty);
            return;
        }
        List<Node> content = new ArrayList<>();
        page.rows().forEach(review -> content.add(card(review)));
        HBox pagination = pagination(page);
        if (!pagination.getChildren().isEmpty()) content.add(pagination);
        rows.getChildren().setAll(content);
    }

    void showImage(ShopData.ReviewImageRef ref, byte[] bytes) {
        showImage(ref, decode(bytes));
    }

    void showImage(ShopData.ReviewImageRef ref, Image loaded) {
        StackPane frame = (StackPane) lookup("#shop-review-image-" + ref.id());
        if (frame == null) return;
        try {
            if (loaded.isError() || loaded.getWidth() < 1) throw new IllegalArgumentException("invalid image");
            ImageView image = new ImageView(loaded);
            image.setFitWidth(132);
            image.setFitHeight(96);
            image.setPreserveRatio(true);
            image.setSmooth(true);
            frame.getChildren().setAll(image);
        } catch (RuntimeException failure) {
            frame.getChildren().setAll(ShopUi.label("图片暂不可用", "shop-hint"));
        }
    }

    void showDetail(ShopData.ReviewImageRef ref, Image loaded) {
        if (loaded == null || loaded.isError() || loaded.getWidth() < 1) {
            throw new IllegalArgumentException("评价图片暂不可用");
        }
        ImageView image = new ImageView(loaded);
        image.setFitWidth(900);
        image.setFitHeight(650);
        image.setPreserveRatio(true);
        image.setSmooth(true);
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("评价图片");
        dialog.setHeaderText("图片 #" + ref.id());
        dialog.getDialogPane().setContent(image);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        if (getScene() != null && getScene().getWindow() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        dialog.show();
    }

    private static Image decode(byte[] bytes) {
        return new Image(new ByteArrayInputStream(bytes));
    }

    void setLikeBusy(long reviewId, boolean busy) {
        Button button = (Button) lookup("#shop-review-like-" + reviewId);
        if (button != null) button.setDisable(busy);
    }

    void applyLike(ShopData.LikeResult result) {
        ShopData.Review old = reviews.get(result.reviewId());
        if (old == null) return;
        ShopData.Review changed = new ShopData.Review(old.id(), old.productId(), old.orderItemId(),
                old.authorDisplayName(), old.rating(), old.content(), old.repeatPurchase(),
                result.likeCount(), result.enabled(), old.createdAt(), old.updatedAt(), old.images());
        reviews.put(changed.id(), changed);
        Button button = (Button) lookup("#shop-review-like-" + changed.id());
        if (button != null) styleLike(button, changed);
    }

    List<Node> cards() { return List.copyOf(rows.getChildren().stream()
            .filter(node -> node.getId() != null && node.getId().startsWith("shop-review-card-"))
            .toList()); }

    private VBox card(ShopData.Review review) {
        Label author = ShopUi.label(review.authorDisplayName(), "shop-review-author");
        Label stars = ShopUi.label("★".repeat(review.rating()) + "☆".repeat(5 - review.rating()),
                "shop-review-stars");
        HBox header = new HBox(10, author, stars, ShopUi.space(),
                ShopUi.label(TIME.format(review.createdAt()), "shop-hint"));
        header.setAlignment(Pos.CENTER_LEFT);

        String badgesText = "已购" + (review.repeatPurchase() ? " · 回购" : "");
        Label badges = ShopUi.label(badgesText, "shop-review-badge");
        badges.setId("shop-review-badges-" + review.id());
        Label edited = ShopUi.label("已编辑", "shop-hint");
        edited.setId("shop-review-edited-" + review.id());
        boolean wasEdited = review.updatedAt().isAfter(review.createdAt());
        edited.setManaged(wasEdited);
        edited.setVisible(wasEdited);

        Label body = ShopUi.label(review.content(), "shop-review-content");
        body.setWrapText(true);
        FlowPane images = new FlowPane(10, 10);
        for (ShopData.ReviewImageRef ref : review.images()) {
            StackPane frame = new StackPane(ShopUi.label("图片加载中…", "shop-hint"));
            frame.setId("shop-review-image-" + ref.id());
            frame.getStyleClass().addAll("shop-review-image", "shop-review-image-frame");
            frame.setOnMouseClicked(event -> listener.openImage(ref));
            images.getChildren().add(frame);
        }
        Button like = ShopUi.button("", "shop-review-like",
                () -> listener.like(review.id(), !reviews.get(review.id()).likedByViewer()));
        like.setId("shop-review-like-" + review.id());
        styleLike(like, review);
        VBox card = new VBox(10, header, new HBox(8, badges, edited), body, images, like);
        card.setId("shop-review-card-" + review.id());
        card.getStyleClass().add("shop-review-card");
        return card;
    }

    private HBox pagination(ShopData.ReviewPage page) {
        HBox controls = new HBox(10);
        controls.setAlignment(Pos.CENTER_RIGHT);
        if (page.page() > 1) {
            Button previous = ShopUi.button("上一页", "shop-quiet",
                    () -> listener.page(productId, page.page() - 1));
            previous.setId("shop-review-previous");
            controls.getChildren().add(previous);
        }
        int last = Math.max(1, (page.total() + page.pageSize() - 1) / page.pageSize());
        if (page.page() < last) {
            Button next = ShopUi.button("下一页", "shop-quiet",
                    () -> listener.page(productId, page.page() + 1));
            next.setId("shop-review-next");
            controls.getChildren().add(next);
        }
        HBox.setHgrow(controls, Priority.ALWAYS);
        return controls;
    }

    private static void styleLike(Button button, ShopData.Review review) {
        button.setText("有帮助 · " + review.likeCount());
        button.getStyleClass().remove("shop-review-like-selected");
        if (review.likedByViewer()) button.getStyleClass().add("shop-review-like-selected");
        button.setDisable(false);
    }
}
