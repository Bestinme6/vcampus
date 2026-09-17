package com.vcampus.client.fx.shop;

import com.vcampus.common.model.SecondhandCategory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

final class SecondhandCatalogView extends VBox {
    @FunctionalInterface
    interface SearchListener {
        void search(String keyword, SecondhandCategory category, int page);
    }

    private final FlowPane rows = new FlowPane(14, 14);
    private final javafx.scene.control.TextField keyword = new javafx.scene.control.TextField();
    private final ComboBox<SecondhandCategory> category = new ComboBox<>();
    private final Map<Long, Label> ratings = new HashMap<>();
    private final Map<Long, ImageView> covers = new HashMap<>();
    private final LongConsumer open;

    SecondhandCatalogView(BiConsumer<String, Integer> search, LongConsumer open) {
        this((keyword, ignored, page) -> search.accept(keyword, page), open, () -> { });
    }

    SecondhandCatalogView(SearchListener search, LongConsumer open, Runnable orders) {
        super(14);
        this.open = open;
        keyword.setPromptText("搜索二手物品");
        keyword.setId("used-search-keyword");
        category.setId("used-search-category");
        category.setItems(FXCollections.observableArrayList(SecondhandCategory.values()));
        category.setPromptText("全部分类");
        category.setConverter(new StringConverter<>() {
            @Override public String toString(SecondhandCategory value) {
                return value == null ? "全部分类" : value.displayName();
            }
            @Override public SecondhandCategory fromString(String value) {
                throw new UnsupportedOperationException();
            }
        });
        Button clear = ShopUi.button("全部分类", "shop-quiet", () -> category.setValue(null));
        clear.setId("used-search-category-clear");
        Button button = ShopUi.button("搜索", "used-primary",
                () -> search.search(keyword.getText().strip(), category.getValue(), 1));
        button.setId("used-search");
        Button mine = ShopUi.button("我的二手订单", "shop-quiet", orders);
        mine.setId("used-my-orders");
        HBox filters = new HBox(8, keyword, category, clear, button, mine);
        filters.getStyleClass().add("used-filters");
        getChildren().addAll(filters, rows);
        getStyleClass().add("used-content");
    }

    void show(SecondhandData.ListingPage page) {
        rows.getChildren().clear();
        ratings.clear();
        covers.clear();
        for (var item : page.rows()) {
            ImageView cover = new ImageView();
            cover.setFitWidth(190);
            cover.setFitHeight(116);
            cover.setPreserveRatio(true);
            cover.setSmooth(true);
            cover.getStyleClass().add("used-card-image");
            covers.put(item.id(), cover);
            Label categoryLabel = ShopUi.label(
                    SecondhandCategory.fromStored(item.category()).displayName(), "used-category-chip");
            Button card = ShopUi.button(item.title() + "\n¥ " + item.price().toPlainString()
                    + " · " + item.condition(), "used-card", () -> open.accept(item.id()));
            card.setId("used-item-" + item.id());
            Label rating = ShopUi.label("正在读取卖家评分…", "shop-muted");
            rating.setId("used-rating-" + item.id());
            ratings.put(item.id(), rating);
            VBox box = new VBox(7, cover, categoryLabel, card, rating);
            box.getStyleClass().add("used-card-box");
            rows.getChildren().add(box);
        }
    }

    void showImage(long itemId, byte[] bytes) {
        ImageView view = covers.get(itemId);
        if (view == null || bytes == null) return;
        Image image = new Image(new ByteArrayInputStream(bytes));
        if (!image.isError() && image.getWidth() > 0) view.setImage(image);
    }

    void showRating(long itemId, BigDecimal average, int count) {
        Label label = ratings.get(itemId);
        if (label != null) label.setText(average == null ? "暂无卖家评分"
                : average.setScale(1).toPlainString() + " / 5 · " + count + " 条");
    }

    void loading() {
        rows.getChildren().setAll(ShopUi.label("正在读取二手物品…", "shop-muted"));
    }

    void failure(String message) {
        rows.getChildren().setAll(ShopUi.label(message, "shop-error"));
    }
}
