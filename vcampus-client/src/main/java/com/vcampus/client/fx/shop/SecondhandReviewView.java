package com.vcampus.client.fx.shop;

import com.vcampus.common.model.ShopOrderStatus;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.nio.file.Path;
import java.util.ArrayList;

final class SecondhandReviewView extends VBox {
    private final long userId;private final Consumer<SecondhandData.ReviewDraft> create;private final Consumer<Path> image;
    private final BiConsumer<Long,Boolean> like;private final ComboBox<Integer> rating=new ComboBox<>();
    private final TextArea content=new TextArea();private long orderId;private String operationId;private final List<String>uploads=new ArrayList<>();private final javafx.scene.control.Label uploadStatus=ShopUi.label("未选择图片","shop-muted");
    SecondhandReviewView(long userId,Consumer<SecondhandData.ReviewDraft> create,BiConsumer<Long,Boolean> like){this(userId,create,like,path->{});}
    SecondhandReviewView(long userId,Consumer<SecondhandData.ReviewDraft> create,BiConsumer<Long,Boolean> like,Consumer<Path>image){super(12);this.userId=userId;this.create=Objects.requireNonNull(create);this.like=Objects.requireNonNull(like);this.image=Objects.requireNonNull(image);rating.getItems().setAll(5,4,3,2,1);rating.setValue(5);rating.setId("used-review-rating");content.setId("used-review-content");content.setPromptText("说说本次二手交易体验（至少5个字）");content.setWrapText(true);getStyleClass().add("used-content");}
    void showOrder(SecondhandData.Order order,Long existingReviewId){getChildren().clear();uploads.clear();uploadStatus.setText("未选择图片");orderId=order.id();operationId=UUID.randomUUID().toString();getChildren().add(ShopUi.label("交易评价","used-title"));if(order.buyerId()==userId&&order.status()==ShopOrderStatus.COMPLETED&&existingReviewId==null){Button submit=ShopUi.button("提交评价","used-primary",this::submit);submit.setId("used-review-create");Button choose=ShopUi.button("添加图片","shop-quiet",this::chooseImage);choose.setId("used-review-image");getChildren().addAll(new HBox(8,ShopUi.label("评分","shop-muted"),rating),content,new HBox(8,choose,uploadStatus),submit);}else getChildren().add(ShopUi.label(existingReviewId==null?"订单完成后可以评价":"该订单已经评价","shop-muted"));}
    void showSeller(long sellerId,SecondhandData.ReviewPage page){getChildren().clear();String summary=page.summary().average()==null?"暂无评分":page.summary().average().toPlainString()+" / 5（"+page.summary().count()+" 条）";getChildren().addAll(ShopUi.label("卖家评价","used-title"),ShopUi.label(summary,"used-rating-summary"));for(var review:page.rows()){Button helpful=ShopUi.button((review.likedByViewer()?"取消有帮助":"有帮助")+" · "+review.likeCount(),"shop-quiet",()->like.accept(review.id(),!review.likedByViewer()));helpful.setId("used-review-like-"+review.id());VBox card=new VBox(6,ShopUi.label("★".repeat(review.rating())+" · "+review.authorName(),"used-status"),ShopUi.label(review.content(),"used-description"),helpful);card.getStyleClass().add("used-review-card");getChildren().add(card);}}
    void addUpload(String uploadId){if(uploads.size()<3&&!uploads.contains(uploadId)){uploads.add(uploadId);uploadStatus.setText("已上传 "+uploads.size()+" 张图片");}}
    void busy(boolean value){setDisable(value);}
    private void submit(){String text=content.getText()==null?"":content.getText().trim();if(text.length()<5||text.length()>1000)return;create.accept(new SecondhandData.ReviewDraft(operationId,orderId,rating.getValue(),text,List.copyOf(uploads)));}
    private void chooseImage(){if(uploads.size()>=3||getScene()==null)return;javafx.stage.FileChooser chooser=new javafx.stage.FileChooser();chooser.setTitle("选择评价图片");chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("JPG / PNG / WebP","*.jpg","*.jpeg","*.png","*.webp"));java.io.File selected=chooser.showOpenDialog(getScene().getWindow());if(selected!=null)image.accept(selected.toPath());}
}
