package com.vcampus.client.fx.shop;

import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import javafx.scene.control.Label;

final class SecondhandCatalogView extends VBox {
    private final FlowPane rows=new FlowPane(12,12);private final TextField keyword=new TextField();
    private final Map<Long,Label> ratings=new HashMap<>();
    SecondhandCatalogView(BiConsumer<String,Integer> search,LongConsumer open){this(search,open,()->{});}
    SecondhandCatalogView(BiConsumer<String,Integer> search,LongConsumer open,Runnable orders){super(14);keyword.setPromptText("搜索二手物品");keyword.setId("used-search-keyword");Button button=ShopUi.button("搜索","used-primary",()->search.accept(keyword.getText(),1));button.setId("used-search");Button mine=ShopUi.button("我的二手订单","shop-quiet",orders);mine.setId("used-my-orders");getChildren().addAll(new HBox(8,keyword,button,mine),rows);getStyleClass().add("used-content");this.open=open;}
    private final LongConsumer open;
    void show(SecondhandData.ListingPage page){rows.getChildren().clear();ratings.clear();for(var item:page.rows()){Button card=ShopUi.button(item.title()+"\n¥ "+item.price().toPlainString()+" · "+item.condition(),"used-card",()->open.accept(item.id()));card.setId("used-item-"+item.id());Label rating=ShopUi.label("正在读取卖家评分…","shop-muted");rating.setId("used-rating-"+item.id());ratings.put(item.id(),rating);VBox box=new VBox(5,card,rating);box.getStyleClass().add("used-card-box");rows.getChildren().add(box);}}
    void showRating(long itemId,BigDecimal average,int count){Label label=ratings.get(itemId);if(label!=null)label.setText(average==null?"暂无卖家评分":average.setScale(1).toPlainString()+" / 5 · "+count+" 条");}
    void loading(){rows.getChildren().setAll(ShopUi.label("正在读取二手物品…","shop-muted"));}
    void failure(String message){rows.getChildren().setAll(ShopUi.label(message,"shop-error"));}
}
