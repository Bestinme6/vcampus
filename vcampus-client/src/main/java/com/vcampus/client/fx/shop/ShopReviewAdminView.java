package com.vcampus.client.fx.shop;

import com.vcampus.common.model.ShopReviewStatus;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Objects;

/** Shop-manager review search and reason-gated moderation page. */
final class ShopReviewAdminView extends ScrollPane {
    record Query(String keyword, ShopReviewStatus status, int page) {
        Query { keyword = Objects.requireNonNullElse(keyword, "").strip(); if (page < 1) throw new IllegalArgumentException("页码无效"); }
    }
    interface Listener {
        void search(Query query);
        void hide(long reviewId, String reason);
        void restore(long reviewId, String reason);
    }

    private final Listener listener;
    private final VBox body = new VBox(14);
    private final VBox rows = new VBox(12);
    private final TextField keyword = new TextField();
    private final ComboBox<ShopReviewStatus> status = new ComboBox<>();

    ShopReviewAdminView(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        setId("shop-review-admin");
        getStyleClass().add("shop-scroll");
        setFitToWidth(true);
        body.setPadding(new Insets(24));
        keyword.setPromptText("评价内容、展示名或评价编号");
        status.getItems().setAll(ShopReviewStatus.values());
        status.setPromptText("全部状态");
        Button search = ShopUi.button("查询", "shop-secondary", () -> listener.search(query(1)));
        body.getChildren().setAll(ShopUi.label("评价管理", "shop-detail-title"),
                new FlowPane(10, 10, keyword, status, search), rows);
        setContent(body);
    }

    Query query(int page) { return new Query(keyword.getText(), status.getValue(), page); }

    void show(ShopData.AdminReviewPage page) {
        java.util.List<javafx.scene.Node> content = new java.util.ArrayList<>();
        if (page.rows().isEmpty()) {
            content.add(ShopUi.label("没有符合条件的评价", "shop-muted"));
        } else {
            content.addAll(page.rows().stream().map(this::row).toList());
        }
        content.add(pagination(page));
        rows.getChildren().setAll(content);
    }

    void moderate(long reviewId, ShopReviewStatus current, String reason) {
        String normalized = reason == null ? "" : reason.strip();
        if (normalized.length() < 2 || normalized.length() > 255) {
            throw new IllegalArgumentException("审核原因必须为 2 到 255 个字符");
        }
        if (current == ShopReviewStatus.NORMAL) listener.hide(reviewId, normalized);
        else if (current == ShopReviewStatus.HIDDEN) listener.restore(reviewId, normalized);
        else throw new IllegalArgumentException("已删除评价不可审核");
    }

    private VBox row(ShopData.AdminReview value) {
        ShopData.Review review = value.review();
        HBox actions = new HBox(8);
        if (value.status() == ShopReviewStatus.NORMAL) {
            Button hide = ShopUi.button("隐藏", "shop-quiet", () -> reasonDialog(review.id(), value.status()));
            hide.setId("shop-review-admin-hide-" + review.id());
            actions.getChildren().add(hide);
        } else if (value.status() == ShopReviewStatus.HIDDEN) {
            Button restore = ShopUi.button("恢复", "shop-secondary", () -> reasonDialog(review.id(), value.status()));
            restore.setId("shop-review-admin-restore-" + review.id());
            actions.getChildren().add(restore);
        }
        VBox row = new VBox(8,
                ShopUi.label("评价 #" + review.id() + " · 商品 #" + review.productId()
                        + " · " + review.authorDisplayName(), "shop-review-author"),
                ShopUi.label("★".repeat(review.rating()) + "  " + value.status().name(), "shop-review-stars"),
                ShopUi.label(review.content(), "shop-review-content"), actions);
        row.getStyleClass().add("shop-review-card");
        row.setId("shop-review-admin-row-" + review.id());
        return row;
    }

    private HBox pagination(ShopData.AdminReviewPage page) {
        Button previous = ShopUi.button("上一页", "shop-quiet",
                () -> listener.search(query(page.page() - 1)));
        previous.setId("shop-review-admin-previous");
        previous.setDisable(page.page() <= 1);
        Button next = ShopUi.button("下一页", "shop-quiet",
                () -> listener.search(query(page.page() + 1)));
        next.setId("shop-review-admin-next");
        next.setDisable((long) page.page() * page.pageSize() >= page.total());
        int pages = Math.max(1, Math.ceilDiv(page.total(), page.pageSize()));
        Label position = ShopUi.label("第 " + page.page() + " / " + pages + " 页 · 共 "
                + page.total() + " 条", "shop-muted");
        HBox result = new HBox(10, previous, position, next);
        result.setId("shop-review-admin-pagination");
        return result;
    }

    private void reasonDialog(long reviewId, ShopReviewStatus current) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle(current == ShopReviewStatus.NORMAL ? "隐藏评价" : "恢复评价");
        dialog.setHeaderText("请输入审核原因（2—255 字）");
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.showAndWait().ifPresent(reason -> moderate(reviewId, current, reason));
    }
}
