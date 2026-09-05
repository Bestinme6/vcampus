package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.ProductPage;
import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.ShopProductSort;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.HashMap;
import java.util.Map;

final class ShopCatalogView extends ScrollPane {
    private final ShopView.Listener listener;
    private final VBox content = new VBox(22);
    private final TextField keyword = new TextField();
    private final ComboBox<ShopCategory> category = new ComboBox<>();
    private final ComboBox<ShopProductSort> sort = new ComboBox<>();
    private final TilePane grid = new TilePane(18, 18);
    private final HBox pager = new HBox(10);
    private final Label count = ShopUi.label("校园好物", "shop-section-title");
    private final Map<Long, ShopProductCard> cards = new HashMap<>();
    private ProductPage lastPage;
    private boolean changing;

    ShopCatalogView(ShopView.Listener listener) {
        this.listener = listener;
        setId("shop-catalog");
        getStyleClass().add("shop-scroll");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        content.getStyleClass().add("shop-content");
        setContent(content);

        VBox heroCopy = new VBox(8, ShopUi.label("校园生活，一站配齐", "shop-hero-title"),
                ShopUi.label("文具、日用、数码配件与校园纪念品，发现适合你的校园好物。", "shop-muted"));
        HBox hero = ShopUi.row(heroCopy, ShopUi.space(), ShopUi.label("VCAMPUS  STORE", "shop-hero-mark"));
        hero.getStyleClass().add("shop-hero");

        keyword.setId("shop-keyword");
        keyword.setPromptText("搜索商品名称或关键词");
        keyword.setPrefWidth(300);
        category.setId("shop-category");
        category.setPromptText("全部分类");
        category.setItems(FXCollections.observableArrayList(ShopCategory.values()));
        category.setConverter(new StringConverter<>() {
            @Override public String toString(ShopCategory value) { return value == null ? "全部分类" : value.displayName(); }
            @Override public ShopCategory fromString(String value) { throw new UnsupportedOperationException(); }
        });
        sort.setId("shop-sort");
        sort.setItems(FXCollections.observableArrayList(ShopProductSort.values()));
        sort.setValue(ShopProductSort.NEWEST);
        sort.setConverter(new StringConverter<>() {
            @Override public String toString(ShopProductSort value) {
                if (value == null) return "";
                return switch (value) {
                    case NEWEST -> "最新上架";
                    case PRICE_ASC -> "价格从低到高";
                    case PRICE_DESC -> "价格从高到低";
                    case NAME_ASC -> "名称排序";
                };
            }
            @Override public ShopProductSort fromString(String value) { throw new UnsupportedOperationException(); }
        });
        Button search = ShopUi.button("搜索", "shop-primary", () -> request(1));
        search.setId("shop-search");
        Button clear = ShopUi.button("清除筛选", "shop-quiet", () -> {
            changing = true;
            keyword.clear(); category.getSelectionModel().clearSelection(); sort.setValue(ShopProductSort.NEWEST);
            changing = false; request(1);
        });
        keyword.setOnAction(event -> request(1));
        category.setOnAction(event -> { if (!changing) request(1); });
        sort.setOnAction(event -> { if (!changing) request(1); });
        FlowPane filters = new FlowPane(10, 10, keyword, category, sort, search, clear);
        filters.getStyleClass().add("shop-toolbar");

        grid.setAlignment(Pos.TOP_LEFT);
        grid.setTileAlignment(Pos.TOP_LEFT);
        grid.setPrefColumns(4);
        grid.prefTileWidthProperty().bind(Bindings.createDoubleBinding(() -> {
            double width = Math.max(620, grid.getWidth());
            int columns = columnsFor(width);
            return (width - (columns - 1) * grid.getHgap()) / columns;
        }, grid.widthProperty()));
        content.getChildren().setAll(hero, filters, ShopUi.row(count, ShopUi.space(),
                ShopUi.label("点击商品卡片查看详情", "shop-hint")), grid, pager);
    }

    static int columnsFor(double width) {
        if (width >= 1180) return 4;
        if (width >= 900) return 3;
        return 2;
    }

    void request(int page) {
        listener.search(keyword.getText().strip(), category.getValue(),
                sort.getValue() == null ? ShopProductSort.NEWEST : sort.getValue(), page);
    }

    void showLoading() { state("shop-catalog-loading", "正在加载商品…", "请稍候，正在连接校园商店。", null); }

    void showFailure(String message, Runnable retry) {
        Button action = ShopUi.button("重新加载", "shop-primary", retry);
        action.setId("shop-catalog-retry");
        state("shop-catalog-failure", "暂时无法加载", message, action);
    }

    void show(ProductPage page) {
        lastPage = page;
        cards.clear();
        grid.getChildren().clear();
        count.setText("校园好物  ·  " + page.total() + " 件商品");
        for (var product : page.rows()) {
            ShopProductCard card = new ShopProductCard(product, listener);
            cards.put(product.id(), card);
            grid.getChildren().add(card);
        }
        if (page.rows().isEmpty()) {
            VBox empty = stateBox("shop-catalog-empty", "没有找到匹配商品", "换个关键词，或者清除分类筛选后再试试。");
            grid.getChildren().add(empty);
        }
        Button previous = ShopUi.button("上一页", "shop-quiet", () -> request(page.page() - 1));
        Button next = ShopUi.button("下一页", "shop-quiet", () -> request(page.page() + 1));
        previous.setDisable(page.page() <= 1);
        int pages = Math.max(1, (page.total() + page.pageSize() - 1) / page.pageSize());
        next.setDisable(page.page() >= pages);
        pager.setAlignment(Pos.CENTER_RIGHT);
        pager.getChildren().setAll(ShopUi.label("第 " + page.page() + " / " + pages + " 页", "shop-muted"),
                ShopUi.space(), previous, next);
        setContent(content);
    }

    void showThumbnail(long productId, byte[] bytes) {
        ShopProductCard card = cards.get(productId);
        if (card != null) card.showImage(bytes);
    }

    ProductPage lastPage() { return lastPage; }
    ComboBox<ShopCategory> categoryControl() { return category; }
    ComboBox<ShopProductSort> sortControl() { return sort; }

    private void state(String id, String title, String message, Node action) {
        VBox box = stateBox(id, title, message);
        if (action != null) box.getChildren().add(action);
        setContent(box);
    }

    private VBox stateBox(String id, String title, String message) {
        VBox box = new VBox(12, ShopUi.label(title, "shop-section-title"), ShopUi.label(message, "shop-muted"));
        box.setId(id);
        box.setPadding(new Insets(56, 32, 56, 32));
        return box;
    }
}
