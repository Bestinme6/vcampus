package com.vcampus.client.fx.shop;

import com.vcampus.common.model.ShopAccessPolicy;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.UserRole;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Label;
import com.vcampus.common.model.SecondhandModerationStatus;
import com.vcampus.common.model.ShopReviewStatus;
import com.vcampus.common.model.SecondhandCategory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.function.IntConsumer;

final class SecondhandAdminView extends VBox {
    interface Listener {void moderateItem(long id,boolean hide,String reason);void resolve(long id,SecondhandData.AdminResolution resolution,String operationId,String reason);void moderateReview(long id,boolean hide,String reason);default void orders(){}default void order(long id){}default void items(String keyword,int page){}default void reviews(String keyword,int page){}}
    private final TextField keyword = new TextField();
    private final VBox rows = new VBox(10);
    private final HBox paging = new HBox(10);
    private boolean itemMode = true;
    private String appliedKeyword = "";
    private int currentPage = 1;
    private final Listener listener;private final TextArea reason=new TextArea();private final RadioButton refund=new RadioButton("退款并重新上架");private final RadioButton settle=new RadioButton("结算给卖家");private final CheckBox confirm=new CheckBox("我已核对订单并确认该处理不可撤销");private final Button apply;private SecondhandData.Order order;private String operationId;
    SecondhandAdminView(Set<UserRole>roles,Listener listener){super(12);if(!ShopAccessPolicy.canManage(roles))throw new IllegalStateException("当前账号没有二手市场管理权限");this.listener=Objects.requireNonNull(listener);getStyleClass().add("used-content");reason.setId("used-admin-reason");reason.setPromptText("处理原因（2–255字）");reason.setWrapText(true);ToggleGroup choices=new ToggleGroup();refund.setToggleGroup(choices);settle.setToggleGroup(choices);refund.setId("used-admin-refund");settle.setId("used-admin-settle");confirm.setId("used-admin-confirm");apply=ShopUi.button("确认处理","used-primary",this::resolve);apply.setId("used-admin-resolve");reason.textProperty().addListener((o,b,a)->update());choices.selectedToggleProperty().addListener((o,b,a)->update());confirm.selectedProperty().addListener((o,b,a)->update());showLanding();}
    void showLanding(){showList(true,"",1);}
    void showList(boolean items, String term, int page) {
        order = null;
        itemMode = items;
        appliedKeyword = term;
        currentPage = page;
        keyword.setText(term);
        keyword.setId("used-admin-keyword");
        keyword.setPromptText(items ? "搜索商品名称或卖家" : "搜索评价内容或评价人");
        keyword.setOnAction(event -> search());
        Button search = ShopUi.button("搜索", "used-primary", this::search);
        search.setId("used-admin-search");
        Button itemsTab = ShopUi.button("商品管理", "shop-tab", () -> listener.items("",1));
        itemsTab.setId("used-admin-items");
        Button reviewsTab = ShopUi.button("评价管理", "shop-tab", () -> listener.reviews("",1));
        reviewsTab.setId("used-admin-reviews");
        Button ordersTab = ShopUi.button("待处理异常订单", "shop-tab", listener::orders);
        ordersTab.setId("used-admin-orders");
        rows.getChildren().setAll(ShopUi.label("正在读取…", "shop-muted"));
        paging.getChildren().clear();
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("used-admin-scroll");
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);
        getChildren().setAll(ShopUi.label("二手市场管理 · " + (items ? "商品管理" : "评价管理"), "used-title"),
                new HBox(10, itemsTab, reviewsTab, ordersTab), new HBox(10, keyword, search), scroll, paging);
    }
    private void search() {
        String term = keyword.getText().trim();
        if (term.length() > 100) { failure("搜索内容最多100字"); return; }
        if (itemMode) listener.items(term, 1); else listener.reviews(term, 1);
    }
    void refresh() {
        if (itemMode) listener.items(appliedKeyword, currentPage);
        else listener.reviews(appliedKeyword, currentPage);
    }
    void failure(String message) {
        rows.getChildren().setAll(ShopUi.label(message, "shop-error"),
                ShopUi.button("重试", "shop-quiet", this::refresh));
        paging.getChildren().clear();
    }
    void showItems(SecondhandData.ListingPage page) {
        rows.getChildren().clear();
        for (var item : page.rows()) {
            Label description = ShopUi.label(item.description(), "shop-muted");
            description.setWrapText(true);
            VBox card = new VBox(8, ShopUi.label(item.title(), "shop-section-title"),
                    ShopUi.label("卖家：" + item.sellerName() + " · ¥ " + item.price().toPlainString()
                            + " · " + category(item.category()), "shop-muted"),
                    ShopUi.label("交易状态：" + itemStatus(item.status()) + " · 审核状态："
                            + (item.moderation()==SecondhandModerationStatus.HIDDEN ? "已隐藏" : "正常"), "used-status"),
                    description, moderationControls(item.id(), item.moderation()==SecondhandModerationStatus.HIDDEN, true));
            card.getStyleClass().add("used-admin-box");
            rows.getChildren().add(card);
        }
        finishPage(page.page(), page.pageSize(), page.total());
    }
    void showReviews(SecondhandData.ReviewPage page, Map<Long, SecondhandData.Listing> items) {
        rows.getChildren().clear();
        for (var review : page.rows()) {
            var item = items.get(review.itemId());
            Label content = ShopUi.label(review.content(), "shop-muted");
            content.setWrapText(true);
            VBox card = new VBox(8, ShopUi.label(item == null ? "商品信息暂不可用" : item.title(), "shop-section-title"),
                    ShopUi.label("卖家：" + (item == null ? "暂不可用" : item.sellerName())
                            + " · 评价人：" + review.authorName() + " · " + review.rating() + " 星", "shop-muted"),
                    ShopUi.label(review.status()==ShopReviewStatus.HIDDEN ? "已隐藏" : "正常", "used-status"),
                    content, moderationControls(review.id(), review.status()==ShopReviewStatus.HIDDEN, false));
            card.getStyleClass().add("used-admin-box");
            rows.getChildren().add(card);
        }
        finishPage(page.page(), page.pageSize(), page.total());
    }
    private VBox moderationControls(long id, boolean hidden, boolean item) {
        TextArea note = new TextArea();
        note.setId("used-admin-" + (item ? "item-" : "review-") + id + "-reason");
        note.setPromptText("审核原因（2–255字）");
        note.setPrefRowCount(2);
        note.setWrapText(true);
        Button action = ShopUi.button(hidden ? "恢复" : "隐藏", "shop-quiet", () -> {
            String text = note.getText().trim();
            if (text.length()<2 || text.length()>255) return;
            if (item) listener.moderateItem(id, !hidden, text);
            else listener.moderateReview(id, !hidden, text);
        });
        action.setId("used-admin-" + (item ? "item-" : "review-") + id + "-apply");
        action.setDisable(true);
        note.textProperty().addListener((o,b,a) -> action.setDisable(a.trim().length()<2 || a.trim().length()>255));
        return new VBox(6, note, action);
    }
    private void finishPage(int page, int size, int total) {
        if (rows.getChildren().isEmpty()) rows.getChildren().add(ShopUi.label("没有符合条件的记录", "shop-muted"));
        currentPage = page;
        int pages = Math.max(1, (int)((total + (long)size - 1) / size));
        IntConsumer go = p -> {if(itemMode)listener.items(appliedKeyword,p);else listener.reviews(appliedKeyword,p);};
        Button previous = ShopUi.button("上一页", "shop-quiet", () -> go.accept(page-1));
        Button next = ShopUi.button("下一页", "shop-quiet", () -> go.accept(page+1));
        previous.setId("used-admin-previous"); next.setId("used-admin-next");
        previous.setDisable(page<=1); next.setDisable(page>=pages);
        paging.getChildren().setAll(previous, ShopUi.label("第 " + page + " / " + pages + " 页 · 共 " + total + " 条", "shop-muted"), next);
    }
    private static String category(String value) {
        try { return SecondhandCategory.parse(value).displayName(); } catch (IllegalArgumentException invalid) { return value; }
    }
    private static String itemStatus(com.vcampus.common.model.SecondhandItemStatus status) {
        return switch(status) {case AVAILABLE -> "在售"; case RESERVED -> "交易中"; case SOLD -> "已售出"; case WITHDRAWN -> "已下架";};
    }
    void showOrders(SecondhandData.OrderPage page){order=null;getChildren().setAll(ShopUi.label("待处理异常订单","used-title"));if(page.rows().isEmpty())getChildren().add(ShopUi.label("当前没有待处理订单","shop-muted"));for(var row:page.rows()){Button button=ShopUi.button(row.orderNo()+" · "+row.title()+" · "+row.status(),"used-card",()->listener.order(row.id()));button.setId("used-admin-order-"+row.id());getChildren().add(button);}}
    void showOrder(SecondhandData.Order value){order=Objects.requireNonNull(value);operationId=UUID.randomUUID().toString();reason.clear();refund.setSelected(false);settle.setSelected(false);confirm.setSelected(false);getChildren().setAll(ShopUi.label("异常订单处理 · "+value.orderNo(),"used-title"),ShopUi.label(value.title()+" · "+value.status(),"used-status"),new HBox(12,refund,settle),reason,confirm,apply);update();}
    private void resolve(){if(order==null||apply.isDisabled())return;listener.resolve(order.id(),refund.isSelected()?SecondhandData.AdminResolution.REFUND:SecondhandData.AdminResolution.SETTLE,operationId,reason.getText().trim());}
    private void update(){String text=reason.getText()==null?"":reason.getText().trim();boolean valid=order!=null&&order.status()==ShopOrderStatus.SHIPPED&&(refund.isSelected()||settle.isSelected())&&text.length()>=2&&text.length()<=255&&confirm.isSelected();apply.setDisable(!valid);}
}
