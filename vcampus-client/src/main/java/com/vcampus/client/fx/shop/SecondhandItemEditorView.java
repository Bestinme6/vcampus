package com.vcampus.client.fx.shop;

import com.vcampus.common.model.SecondhandCategory;
import com.vcampus.common.model.SecondhandItemStatus;
import com.vcampus.common.model.SecondhandModerationStatus;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

final class SecondhandItemEditorView extends ScrollPane {
    @FunctionalInterface
    interface SaveListener {
        void save(SecondhandData.ListingDraft listing, SecondhandImageDraft images);
    }

    private final long userId;
    private final SaveListener save;
    private final LongConsumer withdraw;
    private final Runnable back;
    private final Consumer<Path> imageSelected;
    private final VBox body = new VBox(16);
    private final TextField title = field("物品标题", "used-item-title");
    private final TextArea description = new TextArea();
    private final ComboBox<SecondhandCategory> category = new ComboBox<>();
    private final TextField condition = field("成色", "used-item-condition");
    private final TextField price = field("价格", "used-item-price");
    private final FlowPane gallery = new FlowPane(10, 10);
    private final Map<Long, byte[]> previews = new HashMap<>();
    private SecondhandImageDraft images = new SecondhandImageDraft(java.util.List.of());
    private SecondhandData.Listing current;
    private Long itemId;

    SecondhandItemEditorView(long userId, Consumer<SecondhandData.ListingDraft> save,
                             LongConsumer withdraw, Runnable back) {
        this(userId, (listing, ignored) -> save.accept(listing), withdraw, back, path -> { });
    }

    SecondhandItemEditorView(long userId, SaveListener save, LongConsumer withdraw,
                             Runnable back, Consumer<Path> imageSelected) {
        this.userId = userId;
        this.save = Objects.requireNonNull(save, "save");
        this.withdraw = Objects.requireNonNull(withdraw, "withdraw");
        this.back = Objects.requireNonNull(back, "back");
        this.imageSelected = Objects.requireNonNull(imageSelected, "imageSelected");
        setId("used-item-editor");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        getStyleClass().add("used-scroll");
        body.getStyleClass().add("used-content");
        body.setPadding(new Insets(24));
        setContent(body);

        description.setId("used-item-description");
        description.setPromptText("物品描述");
        description.setWrapText(true);
        description.setPrefRowCount(6);
        category.setId("used-item-category");
        category.setItems(FXCollections.observableArrayList(SecondhandCategory.values()));
        category.setConverter(new StringConverter<>() {
            @Override public String toString(SecondhandCategory value) {
                return value == null ? "" : value.displayName();
            }
            @Override public SecondhandCategory fromString(String value) {
                throw new UnsupportedOperationException();
            }
        });
        gallery.setId("used-item-gallery");
    }

    void showNew() {
        current = null;
        itemId = null;
        title.clear();
        description.clear();
        category.setValue(SecondhandCategory.OTHER);
        condition.clear();
        price.setText("0.00");
        previews.clear();
        images = new SecondhandImageDraft(java.util.List.of());
        render(true);
    }

    void show(SecondhandData.Listing listing) {
        current = Objects.requireNonNull(listing, "listing");
        itemId = listing.id();
        title.setText(listing.title());
        description.setText(listing.description());
        category.setValue(SecondhandCategory.fromStored(listing.category()));
        condition.setText(listing.condition());
        price.setText(listing.price().toPlainString());
        previews.clear();
        images = SecondhandImageDraft.from(listing.images());
        render(isEditable());
    }

    void assignItemId(long id) {
        if (id < 1) throw new IllegalArgumentException("物品编号无效");
        itemId = id;
    }

    void busy(boolean value) { body.setDisable(value); }
    SecondhandImageDraft draft() { return images; }

    void addImage(Path file, byte[] bytes) {
        var added = images.addUpload(file);
        if (bytes != null) previews.put(added.key(), bytes.clone());
        renderImages(true);
    }

    void showImage(long key, byte[] bytes) {
        previews.put(key, bytes.clone());
        renderImages(isEditable());
    }

    void showUploadPreview(Path file, byte[] bytes) {
        Path normalized = file.toAbsolutePath().normalize();
        images.images().stream().filter(image -> normalized.equals(image.file()))
                .forEach(image -> previews.put(image.key(), bytes.clone()));
        renderImages(isEditable());
    }

    private void render(boolean editable) {
        title.setEditable(editable);
        description.setEditable(editable);
        category.setDisable(!editable);
        condition.setEditable(editable);
        price.setEditable(editable);

        GridPane form = new GridPane();
        form.setHgap(14);
        form.setVgap(12);
        form.add(ShopUi.label("物品标题", "shop-hint"), 0, 0); form.add(title, 1, 0);
        form.add(ShopUi.label("商品分类", "shop-hint"), 0, 1); form.add(category, 1, 1);
        form.add(ShopUi.label("物品成色", "shop-hint"), 0, 2); form.add(condition, 1, 2);
        form.add(ShopUi.label("价格", "shop-hint"), 0, 3); form.add(price, 1, 3);
        form.add(ShopUi.label("物品描述", "shop-hint"), 0, 4); form.add(description, 1, 4);

        body.getChildren().setAll(ShopUi.label(current == null ? "发布二手物品" : "编辑二手物品", "used-title"),
                form, ShopUi.label("物品图片（最多五张）", "shop-section-title"), gallery);
        renderImages(editable);
        if (editable) {
            Button choose = ShopUi.button("选择 JPG / PNG", "shop-secondary", this::chooseImage);
            choose.setId("used-item-add-image");
            choose.setDisable(images.images().size() >= 5);
            body.getChildren().add(choose);
        }
        HBox actions = new HBox(8, ShopUi.button("返回", "shop-quiet", back));
        if (editable) {
            Button submit = ShopUi.button(current == null ? "发布" : "保存", "used-primary",
                    () -> listingDraft().ifPresent(value -> save.save(value, images)));
            submit.setId("used-item-save");
            actions.getChildren().add(submit);
            if (current != null) {
                Button withdrawButton = ShopUi.button("下架", "shop-quiet", () -> withdraw.accept(current.id()));
                withdrawButton.setId("used-item-withdraw");
                actions.getChildren().add(withdrawButton);
            }
        }
        body.getChildren().add(actions);
    }

    private void renderImages(boolean editable) {
        gallery.getChildren().clear();
        var snapshot = images.images();
        for (int index = 0; index < snapshot.size(); index++) {
            var image = snapshot.get(index);
            VBox slot = new VBox(7, preview(image), ShopUi.label(
                    image.kind() == SecondhandImageDraft.Kind.EXISTING
                            ? "已有图片 #" + image.existingImageId()
                            : image.file().getFileName().toString(), "shop-muted"));
            slot.getStyleClass().add("used-image-slot");
            if (editable) {
                int currentIndex = index;
                Button cover = ShopUi.button(image.cover() ? "封面" : "设为封面", "shop-quiet",
                        () -> { images.setCover(image.key()); renderImages(true); });
                Button left = ShopUi.button("←", "shop-quiet", () -> {
                    if (currentIndex > 0) { images.move(currentIndex, currentIndex - 1); renderImages(true); }
                });
                Button right = ShopUi.button("→", "shop-quiet", () -> {
                    if (currentIndex + 1 < images.images().size()) { images.move(currentIndex, currentIndex + 1); renderImages(true); }
                });
                Button remove = ShopUi.button("删除", "shop-quiet", () -> {
                    images.remove(image.key()); render(true);
                });
                left.setDisable(index == 0);
                right.setDisable(index + 1 == snapshot.size());
                slot.getChildren().addAll(cover, new HBox(6, left, right, remove));
            }
            gallery.getChildren().add(slot);
        }
        for (int index = snapshot.size(); index < 5; index++) {
            VBox empty = new VBox(ShopUi.label("图片位 " + (index + 1), "shop-image-placeholder"));
            empty.getStyleClass().add("used-image-slot-empty");
            gallery.getChildren().add(empty);
        }
    }

    private Node preview(SecondhandImageDraft.DraftImage draft) {
        byte[] bytes = previews.get(draft.key());
        if (bytes == null) return ShopUi.label("图片加载中…", "shop-image-placeholder");
        Image image = new Image(new ByteArrayInputStream(bytes));
        if (image.isError() || image.getWidth() <= 0) return ShopUi.label("图片暂不可用", "shop-image-placeholder");
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.setFitWidth(150);
        view.setFitHeight(96);
        view.getStyleClass().add("used-editor-preview");
        return view;
    }

    private java.util.Optional<SecondhandData.ListingDraft> listingDraft() {
        try {
            SecondhandCategory selected = Objects.requireNonNull(category.getValue(), "请选择分类");
            return java.util.Optional.of(new SecondhandData.ListingDraft(itemId, title.getText(),
                    description.getText(), selected.name(), condition.getText(),
                    new BigDecimal(price.getText().trim()).setScale(2)));
        } catch (RuntimeException invalid) {
            return java.util.Optional.empty();
        }
    }

    private boolean isEditable() {
        return current == null || current.sellerId() == userId
                && current.status() == SecondhandItemStatus.AVAILABLE
                && current.moderation() == SecondhandModerationStatus.VISIBLE;
    }

    private void chooseImage() {
        if (getScene() == null || getScene().getWindow() == null || images.images().size() >= 5) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择二手物品图片");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "JPG / PNG", "*.jpg", "*.jpeg", "*.png"));
        var selected = chooser.showOpenDialog(getScene().getWindow());
        if (selected != null) {
            Path path = selected.toPath();
            addImage(path, null);
            imageSelected.accept(path);
        }
    }

    private static TextField field(String prompt, String id) {
        TextField value = new TextField();
        value.setPromptText(prompt);
        value.setId(id);
        value.getStyleClass().add("shop-address-field");
        return value;
    }
}
