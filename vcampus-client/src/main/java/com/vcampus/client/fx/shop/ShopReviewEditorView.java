package com.vcampus.client.fx.shop;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Create/edit form for a verified-purchase product review. */
final class ShopReviewEditorView extends ScrollPane {
    record ImageSelection(Long existingImageId, String draftKey) {
        ImageSelection {
            if ((existingImageId == null) == (draftKey == null)
                    || existingImageId != null && existingImageId < 1
                    || draftKey != null && draftKey.isBlank()) {
                throw new IllegalArgumentException("评价图片顺序无效");
            }
        }
    }

    record Submission(String operationId, long orderItemId, long productId, Long reviewId,
                      int rating, String content, List<ShopData.ReviewImageRef> retainedImages,
                      ShopReviewImageDraft draft, List<ImageSelection> imageOrder) {
        Submission {
            content = content == null ? null : content.strip();
            retainedImages = List.copyOf(retainedImages);
            Objects.requireNonNull(draft, "draft");
            imageOrder = List.copyOf(imageOrder);
            if (operationId == null || orderItemId < 1 || productId < 1
                    || reviewId != null && reviewId < 1 || rating < 1 || rating > 5
                    || content == null || content.length() < 5 || content.length() > 1000
                    || retainedImages.size() + draft.images().size() > 3
                    || imageOrder.size() != retainedImages.size() + draft.images().size()) {
                throw new IllegalArgumentException("评价内容无效");
            }
            var existing = new java.util.HashSet<Long>();
            retainedImages.forEach(image -> existing.add(image.id()));
            var drafts = new java.util.HashSet<String>();
            draft.images().forEach(image -> drafts.add(image.key()));
            for (ImageSelection selection : imageOrder) {
                boolean matched = selection.existingImageId() != null
                        ? existing.remove(selection.existingImageId())
                        : drafts.remove(selection.draftKey());
                if (!matched) throw new IllegalArgumentException("评价图片顺序无效");
            }
            if (!existing.isEmpty() || !drafts.isEmpty()) {
                throw new IllegalArgumentException("评价图片顺序无效");
            }
        }
    }

    private record FormState(int rating, String content, List<ImageSelection> imageOrder) { }

    interface Listener {
        void save(Submission submission);
        void delete(long reviewId);
        void cancel();
        default void selectImage(Path file) { }
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
    private final Map<Long, Image> retainedPreviews = new HashMap<>();
    private final List<ImageSelection> imageOrder = new ArrayList<>();
    private ShopReviewImageDraft draft = new ShopReviewImageDraft();
    private String operationId = UUID.randomUUID().toString();
    private long orderItemId;
    private long productId;
    private Long reviewId;
    private int rating;
    private boolean busy;
    private FormState savedState = new FormState(0, "", List.of());

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
        retainedPreviews.clear();
        imageOrder.clear();
        draft = new ShopReviewImageDraft();
        render("评价 “" + productName + "”");
        markSaved();
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
        retainedPreviews.clear();
        imageOrder.clear();
        review.images().forEach(image -> imageOrder.add(new ImageSelection(image.id(), null)));
        draft = new ShopReviewImageDraft();
        render("编辑 “" + productName + "” 的评价");
        markSaved();
    }

    void busy(boolean value) {
        busy = value;
        content.setDisable(value);
        stars.setDisable(value);
        addImage.setDisable(value || retained.size() + draft.images().size() >= 3);
        delete.setDisable(value);
        refreshValidation();
    }

    void addImage(ShopReviewImageDraft.FileInfo info) {
        if (retained.size() + draft.images().size() >= 3) {
            throw new IllegalStateException("每条评价最多三张图片");
        }
        ShopReviewImageDraft.DraftImage added = draft.add(info);
        imageOrder.add(new ImageSelection(null, added.key()));
        renderImages();
        refreshValidation();
    }

    void showRetainedImage(long imageId, Image image) {
        if (imageId < 1 || image == null || image.isError()) return;
        if (retained.stream().noneMatch(value -> value.id() == imageId)) return;
        retainedPreviews.put(imageId, image);
        renderImages();
    }

    Submission submission() {
        return new Submission(operationId, orderItemId, productId, reviewId, rating,
                content.getText(), retained, draft, imageOrder);
    }

    boolean isDirty() { return !formState().equals(savedState); }

    void markSaved() { savedState = formState(); }

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
        for (ImageSelection selection : List.copyOf(imageOrder)) {
            int index = imageOrder.indexOf(selection);
            String prefix;
            String label;
            Image preview;
            if (selection.existingImageId() != null) {
                ShopData.ReviewImageRef image = retained.stream()
                        .filter(value -> value.id() == selection.existingImageId()).findFirst().orElseThrow();
                prefix = "shop-review-editor-existing-" + image.id();
                label = "已上传图片 " + (index + 1);
                preview = retainedPreviews.get(image.id());
            } else {
                ShopReviewImageDraft.DraftImage image = draft.images().stream()
                        .filter(value -> value.key().equals(selection.draftKey())).findFirst().orElseThrow();
                prefix = "shop-review-editor-draft-" + image.key();
                label = image.displayName();
                preview = image.preview();
            }
            Button left = ShopUi.button("←", "shop-quiet", () -> move(selection, -1));
            left.setId(prefix + "-left");
            left.setDisable(index == 0 || busy);
            Button right = ShopUi.button("→", "shop-quiet", () -> move(selection, 1));
            right.setId(prefix + "-right");
            right.setDisable(index == imageOrder.size() - 1 || busy);
            Button remove = ShopUi.button("移除", "shop-quiet", () -> remove(selection));
            remove.setId(prefix + "-remove");
            remove.setDisable(busy);
            javafx.scene.Node previewNode;
            if (preview == null) {
                previewNode = ShopUi.label("预览加载中…", "shop-hint");
            } else {
                ImageView imageView = new ImageView(preview);
                imageView.setFitWidth(88);
                imageView.setFitHeight(66);
                imageView.setPreserveRatio(true);
                imageView.setSmooth(true);
                previewNode = imageView;
            }
            HBox item = new HBox(7, previewNode, ShopUi.label(label, "shop-hint"), left, right, remove);
            item.setId(prefix);
            nodes.add(item);
        }
        images.getChildren().setAll(nodes);
    }

    private void move(ImageSelection selection, int delta) {
        if (busy) return;
        int from = imageOrder.indexOf(selection);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= imageOrder.size()) return;
        java.util.Collections.swap(imageOrder, from, to);
        renderImages();
    }

    private void remove(ImageSelection selection) {
        if (busy || !imageOrder.remove(selection)) return;
        if (selection.existingImageId() != null) {
            retained.removeIf(image -> image.id() == selection.existingImageId());
        } else {
            draft.remove(selection.draftKey());
        }
        renderImages();
        refreshValidation();
    }

    private FormState formState() {
        String normalized = content.getText() == null ? "" : content.getText().strip();
        return new FormState(rating, normalized, List.copyOf(imageOrder));
    }

    private void chooseImage() {
        if (busy || getScene() == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择评价图片");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "JPG / PNG / WebP", "*.jpg", "*.jpeg", "*.png", "*.webp"));
        var file = chooser.showOpenDialog(getScene().getWindow());
        if (file != null) listener.selectImage(file.toPath());
    }

    private void submit() {
        if (!submit.isDisabled()) listener.save(submission());
    }
}
