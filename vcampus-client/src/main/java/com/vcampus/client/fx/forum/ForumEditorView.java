package com.vcampus.client.fx.forum;

import com.vcampus.client.ui.ForumViewData.SectionRow;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;
import org.kordamp.ikonli.feather.Feather;
import java.util.*;

public final class ForumEditorView extends ScrollPane {
    @FunctionalInterface public interface Submit {void accept(long section,String title,String content);}
    private final TextField title=new TextField();
    private final TextArea content=new TextArea();
    private final Label error=ForumStyles.label("","forum-error");
    private final Button publish; private final ComboBox<SectionRow> section=new ComboBox<>();
    public ForumEditorView(List<SectionRow> sections,Submit submit,Runnable cancel){
        var body=new VBox(20);body.getStyleClass().add("forum-reading");ForumStyles.scroll(this,body);
        body.getChildren().add(ForumStyles.button("返回论坛",Feather.ARROW_LEFT,cancel,"forum-text-button"));
        section.getItems().setAll(sections);section.setMaxWidth(Double.MAX_VALUE);
        section.setConverter(new StringConverter<>(){public String toString(SectionRow s){return s==null?"选择板块":s.name();}public SectionRow fromString(String s){return null;}});
        if(!sections.isEmpty())section.getSelectionModel().selectFirst();
        title.setId("forum-editor-title");title.setPromptText("一个清晰的标题，会更容易得到回应");
        content.setId("forum-editor-content");content.setWrapText(true);content.setPrefRowCount(13);content.setPromptText("分享你的发现、问题或经验…");
        Label count=ForumStyles.label("","forum-caption");count.textProperty().bind(title.textProperty().length().asString("标题 %d / 160 字").concat("    正文 ").concat(content.textProperty().length()).concat(" / 10000 字"));
        publish=ForumStyles.button("发布帖子",Feather.SEND,()->{
            String t=title.getText().strip(),c=content.getText().strip();
            if(section.getValue()==null){error("请选择发帖板块");return;}
            if(t.length()<4||t.length()>160||c.isEmpty()||c.length()>10000){error("标题需要4—160字，正文需要1—10000字");return;}
            publishBusy(true);submit.accept(section.getValue().id(),t,c);
        },"forum-primary");publish.setId("forum-editor-submit");
        body.getChildren().add(ForumStyles.card(ForumStyles.label("分享你的校园生活","forum-detail-title"),
                ForumStyles.label("好的交流，从一篇认真的分享开始","forum-muted"),ForumStyles.label("发布到","forum-author"),section,
                ForumStyles.label("帖子标题","forum-author"),title,ForumStyles.label("正文","forum-author"),content,count,error,
                ForumStyles.row(ForumStyles.label("请勿发布广告或他人隐私信息","forum-caption"),ForumStyles.spacer(),
                        ForumStyles.button("取消",null,cancel,"forum-quiet"),publish)));
    }
    public boolean dirty(){return !title.getText().isBlank()||!content.getText().isBlank();}
    public void error(String message){error.setText(message);publishBusy(false);}
    private void publishBusy(boolean busy){publish.setDisable(busy);title.setDisable(busy);content.setDisable(busy);section.setDisable(busy);}
}
