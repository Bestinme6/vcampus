package com.vcampus.client.fx.shop;

import com.vcampus.common.model.SecondhandItemStatus;
import com.vcampus.common.model.SecondhandModerationStatus;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

final class SecondhandItemEditorView extends VBox {
    private final long userId;private final Consumer<SecondhandData.ListingDraft> save;
    private final LongConsumer withdraw;private final Runnable back;private final Consumer<Path> image;
    private final TextField title=field("物品标题","used-item-title");
    private final TextArea description=new TextArea();
    private final TextField category=field("分类，如 BOOK","used-item-category");
    private final TextField condition=field("成色","used-item-condition");
    private final TextField price=field("价格","used-item-price");
    private SecondhandData.Listing current;

    SecondhandItemEditorView(long userId,Consumer<SecondhandData.ListingDraft> save,
                             LongConsumer withdraw,Runnable back){this(userId,save,withdraw,back,path->{});}
    SecondhandItemEditorView(long userId,Consumer<SecondhandData.ListingDraft> save,
                             LongConsumer withdraw,Runnable back,Consumer<Path> image){
        super(12);this.userId=userId;this.save=Objects.requireNonNull(save);this.withdraw=Objects.requireNonNull(withdraw);
        this.back=Objects.requireNonNull(back);this.image=Objects.requireNonNull(image);
        description.setId("used-item-description");description.setPromptText("物品描述");description.setWrapText(true);
        getStyleClass().add("used-content");
    }

    void showNew(){current=null;title.clear();description.clear();category.clear();condition.clear();price.clear();render(true);}
    void show(SecondhandData.Listing listing){current=Objects.requireNonNull(listing);title.setText(listing.title());description.setText(listing.description());category.setText(listing.category());condition.setText(listing.condition());price.setText(listing.price().toPlainString());boolean editable=listing.sellerId()==userId&&listing.status()==SecondhandItemStatus.AVAILABLE&&listing.moderation()==SecondhandModerationStatus.VISIBLE;render(editable);}
    void busy(boolean value){setDisable(value);}
    private void render(boolean editable){
        getChildren().setAll(ShopUi.label(current==null?"发布二手物品":"编辑二手物品","used-title"),title,description,new HBox(8,category,condition,price));
        title.setEditable(editable);description.setEditable(editable);category.setEditable(editable);condition.setEditable(editable);price.setEditable(editable);
        HBox actions=new HBox(8);actions.getChildren().add(ShopUi.button("返回","shop-quiet",back));
        if(editable){Button saveButton=ShopUi.button(current==null?"发布":"保存","used-primary",()->draft().ifPresent(save));saveButton.setId("used-item-save");actions.getChildren().add(saveButton);if(current!=null){Button imageButton=ShopUi.button("更换物品图片","shop-quiet",this::chooseImage);imageButton.setId("used-item-image");actions.getChildren().add(imageButton);Button withdrawButton=ShopUi.button("下架","shop-quiet",()->withdraw.accept(current.id()));withdrawButton.setId("used-item-withdraw");actions.getChildren().add(withdrawButton);}}
        getChildren().add(actions);
    }
    private java.util.Optional<SecondhandData.ListingDraft> draft(){try{return java.util.Optional.of(new SecondhandData.ListingDraft(current==null?null:current.id(),title.getText(),description.getText(),category.getText(),condition.getText(),new BigDecimal(price.getText().trim()).setScale(2)));}catch(RuntimeException invalid){return java.util.Optional.empty();}}
    private void chooseImage(){if(getScene()==null)return;javafx.stage.FileChooser chooser=new javafx.stage.FileChooser();chooser.setTitle("选择二手物品图片");chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("JPG / PNG","*.jpg","*.jpeg","*.png"));java.io.File selected=chooser.showOpenDialog(getScene().getWindow());if(selected!=null)image.accept(selected.toPath());}
    private static TextField field(String prompt,String id){TextField value=new TextField();value.setPromptText(prompt);value.setId(id);value.getStyleClass().add("shop-address-field");return value;}
}
