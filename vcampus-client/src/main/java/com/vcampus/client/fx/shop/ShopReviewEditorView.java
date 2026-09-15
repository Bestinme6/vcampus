package com.vcampus.client.fx.shop;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Create/edit form for a verified-purchase product review. */
final class ShopReviewEditorView extends ScrollPane {
    record Submission(String operationId, long orderItemId, long productId, Long reviewId,
                      int rating, String content, List<ShopData.ReviewImageRef> retainedImages,
                      ShopReviewImageDraft draft) {
        Submission {
            content = content == null ? null : content.strip();
            retainedImages = List.copyOf(retainedImages);
            Objects.requireNonNull(draft, "draft");
            if (operationId == null || orderItemId < 1 || productId < 1
                    || reviewId != null && reviewId < 1 || rating < 1 || rating > 5
                    || content == null || content.length() < 5 || content.length() > 1000
                    || retainedImages.size() + draft.images().size() > 3) {
                throw new IllegalArgumentException("评价内容无效");
            }
        }
    }

    interface Listener {
        void save(Submission submission);
        void delete(long reviewId);
        void cancel();
    }

    private final Listener listener;
    private final VBox body = new VBox(16);
    private final HBox stars = new HBox(7);
    private final TextArea content = new TextArea();
    private final Label count = ShopUi.label("0 / 1000", "shop-hint");
    private final FlowPane images = new FlowPane(10, 10);
    private final Button submit;
    private final Button delete;
    private final Button addImage;
    private final List<ShopData.ReviewImageRef> retained = new ArrayList<>();
    private ShopReviewImageDraft draft = new ShopReviewImageDraft();
    private String operationId = UUID.randomUUID().toString();
    private long orderItemId;
    private long productId;
    private Long reviewId;
    private int rating;
    private boolean busy;

    ShopReviewEditorView(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        setId("shop-review-editor");
        getStyleClass().add("shop-scroll");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        body.setPadding(new Insets(24));
        body.getStyleClass().add("shop-content");
        for (int value = 1; value <= 5; value++) {
            int chosen = value;
            Button star = ShopUi.button("★", "shop-review-star", () -> chooseRating(chosen));
            star.setId("shop-review-star-" + value);
            stars.getChildren().add(star);
        }
        content.setId("shop-review-editor-content");
        content.setPromptText("分享真实使用体验（5—1000 字）");
        content.setWrapText(true);
        content.setPrefRowCount(8);
        content.textProperty().addListener((ignored, before, after) -> refreshValidation());
        count.setId("shop-review-editor-count");
        submit = ShopUi.button("提交评价", "shop-primary", this::submit);
        submit.setId("shop-review-editor-submit");
        delete = ShopUi.button("删除评价", "shop-quiet", () -> {
            if (reviewId != null && !busy) listener.delete(reviewId);
        });
        delete.setId("shop-review-editor-delete");
        addImage = ShopUi.button("添加图片", "shop-secondary", this::chooseImage);
        addImage.setId("shop-review-editor-add-image");
        setContent(body);
    }

    void showCreate(long valueOrderItemId, long valueProductId, String productName) {
        orderItemId = valueOrderItemId;
        productId = valueProductId;
        reviewId = null;
        operationId = UUID.randomUUID().toString();
        rating = 0;
        content.clear();
        retained.clear();
        draft = new ShopReviewImageDraft();
        render("评价 “" + productName + "”");
    }

    void showEdit(ShopData.Review review, String productName) {
        Objects.requireNonNull(review, "review");
        orderItemId = review.orderItemId();
        productId = review.productId();
        reviewId = review.id();
        operationId = UUID.randomUUID().toString();
        rating = review.rating();
        content.setText(review.content());
        retained.clear();
        retained.addAll(review.images());
        draft = new ShopReviewImageDraft();
        render("编辑 “" + productName + "” 的评价");
    }

    void busy(boolean value) {
        busy = value;
        content.setDisable(value);
        stars.setDisable(value);
        addImage.setDisable(value || retained.size() + draft.images().size() >= 3);
        delete.setDisable(value);
        refreshValidation();
    }

    void addImage(Path file) throws IOException {
        if (retained.size() + draft.images().size() >= 3) {
            throw new IllegalStateException("每条评价最多三张图片");
        }
        draft.add(file);
        renderImages();
        refreshValidation();
    }

    Submission submission() {
        return new Submission(operationId, orderItemId, productId, reviewId, rating,
                content.getText(), retained, draft);
    }

    private void render(String title) {
        delete.setManaged(reviewId != null);
        delete.setVisible(reviewId != null);
        renderImages();
        body.getChildren().setAll(ShopUi.button("← 返回订单", "shop-quiet", listener::cancel),
                ShopUi.label(title, "shop-detail-title"),
                ShopUi.label("购买已验证 · 请选择 1—5 星", "shop-muted"), stars,
                content, count, ShopUi.label("图片（可选，最多 3 张）", "shop-section-title"),
                images, addImage, new HBox(10, submit, delete));
        refreshValidation();
    }

    private void chooseRating(int value) {
        rating = value;
        refreshValidation();
    }

    private void refreshValidation() {
        String normalized = content.getText() == null ? "" : content.getText().strip();
        count.setText(normalized.length() + " / 1000");
        for (int index = 0; index < stars.getChildren().size(); index++) {
            Button star = (Button) stars.getChildren().get(index);
            star.getStyleClass().remove("shop-review-star-selected");
            if (index < rating) star.getStyleClass().add("shop-review-star-selected");
        }
        submit.setDisable(busy || rating < 1 || normalized.length() < 5 || normalized.length() > 1000);
        addImage.setDisable(busy || retained.size() + draft.images().size() >= 3);
    }

    private void renderImages() {
        List<javafx.scene.Node> nodes = new ArrayList<>();
        for (ShopData.ReviewImageRef image : List.copyOf(retained)) {
            Button remove = ShopUi.button("移除已上传图片 " + (image.position() + 1), "shop-quiet", () -> {
                retained.remove(image); renderImages(); refreshValidation();
            });
            remove.setId("shop-review-editor-existing-" + image.id());
            nodes.add(remove);
        }
        for (ShopReviewImageDraft.DraftImage image : draft.images()) {
            Button remove = ShopUi.button("移除 " + image.displayName(), "shop-quiet", () -> {
                draft.remove(image.key()); renderImages(); refreshValidation();
            });
            remove.setId("shop-review-editor-draft-" + image.key());
            nodes.add(remove);
        }
        images.getChildren().setAll(nodes);
    }

    private void chooseImage() {
        if (busy || getScene() == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择评价图片");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "JPG / PNG / WebP", "*.jpg", "*.jpeg", "*.png", "*.webp"));
        var file = chooser.showOpenDialog(getScene().getWindow());
        if (file != null) {
            try { addImage(file.toPath()); }
            catch (IOException | RuntimeException failure) { throw new IllegalArgumentException(failure.getMessage(), failure); }
        }
    }

    private void submit() {
        if (!submit.isDisabled()) listener.save(submission());
    }
}
