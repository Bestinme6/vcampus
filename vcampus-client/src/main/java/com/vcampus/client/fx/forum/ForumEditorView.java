package com.vcampus.client.fx.forum;

import com.vcampus.client.ui.ForumViewData.SectionRow;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import org.kordamp.ikonli.feather.Feather;
import java.util.*;

public final class ForumEditorView extends ScrollPane {
    @FunctionalInterface public interface Submit {void accept(long section,String title,String content);}
    @FunctionalInterface public interface ImageSubmit {void accept(long section,String title,String content,List<ForumImageDraft> images);}
    private final TextField title=new TextField();
    private final TextArea content=new TextArea();
    private final Label error=ForumStyles.label("","forum-error");
    private final Button publish; private final ComboBox<SectionRow> section=new ComboBox<>();
    private final FlowPane imageList=new FlowPane(10,10);private final List<ForumImageDraft> images=new ArrayList<>();private final Button addImage;
    public ForumEditorView(List<SectionRow> sections,Submit submit,Runnable cancel){
        this(sections,(section,title,content,images)->submit.accept(section,title,content),cancel);
    }
    public ForumEditorView(List<SectionRow> sections,ImageSubmit submit,Runnable cancel){
        var body=new VBox(20);body.getStyleClass().add("forum-reading");ForumStyles.scroll(this,body);
        body.getChildren().add(ForumStyles.button("返回论坛",Feather.ARROW_LEFT,cancel,"forum-text-button"));
        section.getItems().setAll(sections);section.setMaxWidth(Double.MAX_VALUE);
        section.setConverter(new StringConverter<>(){public String toString(SectionRow s){return s==null?"选择板块":s.name();}public SectionRow fromString(String s){return null;}});
        if(!sections.isEmpty())section.getSelectionModel().selectFirst();
        title.setId("forum-editor-title");title.setPromptText("一个清晰的标题，会更容易得到回应");
        content.setId("forum-editor-content");content.setWrapText(true);content.setPrefRowCount(13);content.setPromptText("分享你的发现、问题或经验…");
        addImage=ForumStyles.button("添加图片",Feather.IMAGE,this::chooseImages,"forum-quiet");addImage.setId("forum-image-add");
        Label count=ForumStyles.label("","forum-caption");count.textProperty().bind(title.textProperty().length().asString("标题 %d / 160 字").concat("    正文 ").concat(content.textProperty().length()).concat(" / 10000 字"));
        publish=ForumStyles.button("发布帖子",Feather.SEND,()->{
            String t=title.getText().strip(),c=content.getText().strip();
            if(section.getValue()==null){error("请选择发帖板块");return;}
            if(t.length()<4||t.length()>160||c.isEmpty()||c.length()>10000){error("标题需要4—160字，正文需要1—10000字");return;}
            publishBusy(true);submit.accept(section.getValue().id(),t,c,List.copyOf(images));
        },"forum-primary");publish.setId("forum-editor-submit");
        body.getChildren().add(ForumStyles.card(ForumStyles.label("分享你的校园生活","forum-detail-title"),
                ForumStyles.label("好的交流，从一篇认真的分享开始","forum-muted"),ForumStyles.label("发布到","forum-author"),section,
                ForumStyles.label("帖子标题","forum-author"),title,ForumStyles.label("正文","forum-author"),content,count,
                ForumStyles.label("配图（最多3张，每张不超过5 MiB）","forum-author"),addImage,imageList,error,
                ForumStyles.row(ForumStyles.label("请勿发布广告或他人隐私信息","forum-caption"),ForumStyles.spacer(),
                        ForumStyles.button("取消",null,cancel,"forum-quiet"),publish)));
    }
    private void chooseImages(){FileChooser chooser=new FileChooser();chooser.setTitle("选择帖子图片");chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("图片 (*.jpg, *.png, *.webp)","*.jpg","*.jpeg","*.png","*.webp"));var owner=getScene()==null?null:getScene().getWindow();List<java.io.File> chosen=chooser.showOpenMultipleDialog(owner);if(chosen!=null)for(var file:chosen){try{addFile(file.toPath());}catch(Exception e){error(e.getMessage());}}}
    void addFile(java.nio.file.Path path)throws java.io.IOException{if(images.size()>=3)throw new java.io.IOException("每篇帖子最多三张图片");long bytes=java.nio.file.Files.size(path);if(bytes<1||bytes>5L*1024*1024)throw new java.io.IOException("每张图片不能超过5 MiB");String lower=path.getFileName().toString().toLowerCase(Locale.ROOT);String mime=lower.endsWith(".png")?"image/png":lower.endsWith(".webp")?"image/webp":lower.endsWith(".jpg")||lower.endsWith(".jpeg")?"image/jpeg":null;if(mime==null)throw new java.io.IOException("仅支持 JPG、PNG 或 WebP 图片");java.awt.image.BufferedImage decoded=javax.imageio.ImageIO.read(path.toFile());if(decoded==null)throw new java.io.IOException("图片无法预览");var output=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(decoded,"png",output);Image preview=new Image(new java.io.ByteArrayInputStream(output.toByteArray()),150,100,true,true);images.add(new ForumImageDraft(path.toAbsolutePath().normalize(),path.getFileName().toString(),bytes,mime,preview));renderImages();}
    private void renderImages(){imageList.getChildren().clear();for(int i=0;i<images.size();i++){int index=i;ImageView preview=new ImageView(images.get(i).preview());preview.setFitWidth(140);preview.setFitHeight(90);preview.setPreserveRatio(true);Button left=ForumStyles.button("左移",Feather.ARROW_LEFT,()->move(index,-1),"forum-text-button");left.setId("forum-image-left-"+i);left.setDisable(i==0);Button right=ForumStyles.button("右移",Feather.ARROW_RIGHT,()->move(index,1),"forum-text-button");right.setId("forum-image-right-"+i);right.setDisable(i==images.size()-1);Button remove=ForumStyles.button("移除",Feather.X,()->{images.remove(index);renderImages();},"forum-text-button");remove.setId("forum-image-remove-"+i);imageList.getChildren().add(ForumStyles.card(preview,ForumStyles.label(images.get(i).displayName(),"forum-caption"),ForumStyles.row(left,right,remove)));}addImage.setDisable(images.size()>=3);}
    private void move(int index,int delta){Collections.swap(images,index,index+delta);renderImages();}
    public List<ForumImageDraft> images(){return List.copyOf(images);}
    public boolean dirty(){return !title.getText().isBlank()||!content.getText().isBlank()||!images.isEmpty();}
    public void error(String message){error.setText(message);publishBusy(false);}
    public void progress(double value){error.setText("正在上传图片 "+Math.round(value*100)+"%");}
    private void publishBusy(boolean busy){publish.setDisable(busy);title.setDisable(busy);content.setDisable(busy);section.setDisable(busy);imageList.setDisable(busy);addImage.setDisable(busy||images.size()>=3);}
}
