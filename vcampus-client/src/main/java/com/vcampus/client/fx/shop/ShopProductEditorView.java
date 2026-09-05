package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.ProductDetail;
import com.vcampus.client.fx.shop.ShopData.ProductInput;
import com.vcampus.common.model.ShopCategory;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.TransferMode;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.util.StringConverter;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

final class ShopProductEditorView extends ScrollPane {
    @FunctionalInterface interface SaveListener { void save(ProductInput input, ShopImageDraft draft); }
    private final SaveListener save;
    private final Runnable cancel;
    private final Consumer<Path> uploadSelected;
    private final TextField sku = new TextField(), name = new TextField(), price = new TextField();
    private final ComboBox<ShopCategory> category = new ComboBox<>();
    private final TextArea description = new TextArea();
    private final CheckBox enabled = new CheckBox("上架销售");
    private final FlowPane gallery = new FlowPane(10, 10);
    private final Button choose;
    private final Button submit;
    private final Map<Long, byte[]> previews = new HashMap<>();
    private ShopImageDraft images = new ShopImageDraft(java.util.List.of());
    private Long productId;

    ShopProductEditorView(SaveListener save, Runnable cancel, Consumer<Path> uploadSelected) {
        this.save = save; this.cancel = cancel; this.uploadSelected = uploadSelected;
        setId("shop-product-editor"); setFitToWidth(true); setHbarPolicy(ScrollBarPolicy.NEVER);
        getStyleClass().add("shop-scroll");
        sku.setId("shop-editor-sku"); sku.setEditable(false); sku.setPromptText("保存后由服务器生成");
        name.setId("shop-editor-name"); price.setId("shop-editor-price");
        category.setId("shop-editor-category"); category.setItems(FXCollections.observableArrayList(ShopCategory.values()));
        category.setConverter(new StringConverter<>() {
            @Override public String toString(ShopCategory value) { return value == null ? "" : value.displayName(); }
            @Override public ShopCategory fromString(String value) { throw new UnsupportedOperationException(); }
        });
        description.setId("shop-editor-description"); description.setWrapText(true); description.setPrefRowCount(7);
        GridPane form = new GridPane(); form.setHgap(14); form.setVgap(12);
        form.add(ShopUi.label("货号", "shop-hint"), 0, 0); form.add(sku, 1, 0);
        form.add(ShopUi.label("商品名称", "shop-hint"), 0, 1); form.add(name, 1, 1);
        form.add(ShopUi.label("分类", "shop-hint"), 0, 2); form.add(category, 1, 2);
        form.add(ShopUi.label("价格", "shop-hint"), 0, 3); form.add(price, 1, 3);
        form.add(ShopUi.label("说明（最多 1000 字）", "shop-hint"), 0, 4); form.add(description, 1, 4);
        form.add(enabled, 1, 5);
        choose = ShopUi.button("选择 JPG / PNG", "shop-secondary", this::chooseImage);
        choose.setId("shop-editor-add-image");
        submit = ShopUi.button("保存商品", "shop-primary", this::submit);
        submit.setId("shop-editor-save");
        VBox body = new VBox(18, ShopUi.button("← 取消编辑", "shop-quiet", cancel),
                ShopUi.label("商品编辑", "shop-detail-title"), form,
                ShopUi.label("商品图片（最多五张）", "shop-section-title"), gallery, choose,
                ShopUi.row(submit, ShopUi.button("取消", "shop-quiet", cancel)));
        body.setPadding(new Insets(26)); setContent(body);
    }

    void showNew() {
        productId = null; sku.clear(); name.clear(); description.clear(); price.setText("0.00");
        category.setValue(ShopCategory.OTHER); enabled.setSelected(true);
        previews.clear(); images = new ShopImageDraft(java.util.List.of()); renderImages();
    }

    void show(ProductDetail detail) {
        var product = detail.product(); productId = product.id(); sku.setText(product.sku()); name.setText(product.name());
        description.setText(product.description()); price.setText(product.price().toPlainString());
        category.setValue(product.category()); enabled.setSelected(product.enabled());
        previews.clear(); images = ShopImageDraft.from(detail.images()); renderImages();
    }

    ProductInput values(String productName, String priceText, String descriptionText) {
        String cleanName = Objects.requireNonNull(productName).strip();
        String cleanDescription = Objects.requireNonNullElse(descriptionText, "").strip();
        if (cleanName.isEmpty() || cleanName.length() > 120 || cleanDescription.length() > 1000) {
            throw new IllegalArgumentException("商品名称或说明长度无效");
        }
        BigDecimal amount;
        try { amount = new BigDecimal(priceText.strip()).setScale(2); }
        catch (RuntimeException error) { throw new IllegalArgumentException("价格格式无效", error); }
        if (amount.signum() < 0) throw new IllegalArgumentException("价格不能为负数");
        return new ProductInput(productId, cleanName, cleanDescription,
                Objects.requireNonNull(category.getValue(), "请选择分类"), amount, enabled.isSelected());
    }

    ShopImageDraft draft() { return images; }
    void assignProductId(long id) { if (id < 1) throw new IllegalArgumentException("商品编号无效"); productId = id; }
    void busy(boolean value) { choose.setDisable(value); submit.setDisable(value); }
    void showImage(long key, byte[] bytes) { previews.put(key, bytes.clone()); renderImages(); }
    void showUploadPreview(Path file, byte[] bytes) {
        Path normalized = file.toAbsolutePath().normalize();
        images.images().stream().filter(image -> normalized.equals(image.file()))
                .forEach(image -> previews.put(image.key(), bytes.clone()));
        renderImages();
    }

    private void submit() { save.save(values(name.getText(), price.getText(), description.getText()), images); }

    private void chooseImage() {
        if (getScene() == null || getScene().getWindow() == null) return;
        FileChooser chooser = new FileChooser(); chooser.setTitle("选择商品图片");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JPG / PNG", "*.jpg", "*.jpeg", "*.png"));
        var file = chooser.showOpenDialog(getScene().getWindow());
        if (file != null) { Path path = file.toPath(); images.addUpload(path); renderImages(); uploadSelected.accept(path); }
    }

    private void renderImages() {
        gallery.getChildren().clear();
        var snapshot = images.images();
        for (int index = 0; index < snapshot.size(); index++) {
            var image = snapshot.get(index);
            Button cover = ShopUi.button(image.cover() ? "封面" : "设为封面", "shop-quiet", () -> {
                images.setCover(image.key()); renderImages();
            });
            Button remove = ShopUi.button("删除", "shop-quiet", () -> { images.remove(image.key()); renderImages(); });
            int current = index;
            Button left = ShopUi.button("←", "shop-quiet", () -> { if (current > 0) { images.move(current, current - 1); renderImages(); } });
            Button right = ShopUi.button("→", "shop-quiet", () -> { if (current + 1 < images.images().size()) { images.move(current, current + 1); renderImages(); } });
            left.setDisable(index == 0); right.setDisable(index + 1 == snapshot.size());
            VBox slot = new VBox(7, preview(image), ShopUi.label(image.kind() == ShopImageDraft.Kind.EXISTING
                    ? "已有图片 #" + image.existingImageId() : image.file().getFileName().toString(), "shop-muted"),
                    cover, new javafx.scene.layout.HBox(6, left, right, remove));
            slot.getStyleClass().add("shop-image-slot");
            slot.setOnDragDetected(event -> {
                var board = slot.startDragAndDrop(TransferMode.MOVE); ClipboardContent content = new ClipboardContent();
                content.putString(Long.toString(image.key())); board.setContent(content); event.consume();
            });
            slot.setOnDragOver(event -> { if (event.getDragboard().hasString()) event.acceptTransferModes(TransferMode.MOVE); event.consume(); });
            slot.setOnDragDropped(event -> {
                try {
                    long sourceKey = Long.parseLong(event.getDragboard().getString());
                    var currentImages = images.images(); int source = -1;
                    for (int i = 0; i < currentImages.size(); i++) if (currentImages.get(i).key() == sourceKey) source = i;
                    if (source >= 0 && source != current) { images.move(source, current); renderImages(); event.setDropCompleted(true); }
                } catch (RuntimeException ignored) { event.setDropCompleted(false); }
                event.consume();
            });
            gallery.getChildren().add(slot);
        }
        for (int index = snapshot.size(); index < 5; index++) {
            VBox empty = new VBox(ShopUi.label("图片位 " + (index + 1), "shop-image-placeholder"));
            empty.getStyleClass().add("shop-image-slot-empty"); gallery.getChildren().add(empty);
        }
    }

    private javafx.scene.Node preview(ShopImageDraft.DraftImage draft) {
        byte[] bytes = previews.get(draft.key());
        if (bytes == null) return ShopUi.label("图片加载中…", "shop-image-placeholder");
        Image image = new Image(new ByteArrayInputStream(bytes));
        if (image.isError() || image.getWidth() <= 0) return ShopUi.label("图片暂不可用", "shop-image-placeholder");
        ImageView view = new ImageView(image); view.setPreserveRatio(true); view.setSmooth(true);
        view.setFitWidth(150); view.setFitHeight(92); view.getStyleClass().add("shop-editor-preview");
        return view;
    }
}
