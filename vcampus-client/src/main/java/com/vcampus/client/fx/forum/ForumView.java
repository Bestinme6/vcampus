package com.vcampus.client.fx.forum;

import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.kordamp.ikonli.feather.Feather;
import java.util.*;
import java.util.function.Consumer;

public final class ForumView extends BorderPane {
    private final Map<String,Button> navigation=new LinkedHashMap<>();
    private final TextField search=new TextField();
    public ForumView(boolean manager,Runnable publish,Runnable workspace,Consumer<String> navigate,Consumer<String> onSearch) {
        setId("forum-root");getStyleClass().add("forum-root");
        getStylesheets().add(Objects.requireNonNull(getClass().getResource("forum.css")).toExternalForm());
        var title=new VBox(6,ForumStyles.label("校园论坛","forum-page-title"),ForumStyles.label("发现校园新鲜事，与同学真诚交流","forum-muted"));
        title.setMinWidth(200);
        search.setId("forum-search");search.setPromptText("搜索帖子标题、作者或关键词");search.setPrefWidth(295);
        search.setOnAction(e->onSearch.accept(search.getText()));
        var searchButton=ForumStyles.button("搜索",Feather.SEARCH,()->onSearch.accept(search.getText()),"forum-quiet");
        var publishButton=ForumStyles.button("发布帖子",Feather.EDIT_3,publish,"forum-primary");publishButton.setId("forum-publish");
        searchButton.setMinWidth(Region.USE_PREF_SIZE);publishButton.setMinWidth(Region.USE_PREF_SIZE);
        var actions=ForumStyles.row(search,searchButton,publishButton);actions.setMinWidth(0);
        HBox.setHgrow(search,Priority.ALWAYS);search.setMinWidth(120);
        var heading=ForumStyles.row(title,ForumStyles.spacer(),actions);heading.getStyleClass().add("forum-heading");
        FlowPane tabs=new FlowPane(8,8);tabs.getStyleClass().add("forum-nav");
        for(String[] entry:new String[][]{{"HOME","论坛首页"},{"MINE","我的帖子"},{"BOOKMARKS","我的收藏"},{"ANNOUNCEMENTS","校园公告"},{"ADMIN","内容管理"}}){
            if(entry[0].equals("ADMIN")&&!manager)continue;
            var tab=ForumStyles.button(entry[1],null,()->navigate.accept(entry[0]),"forum-nav-button");
            tab.setId("forum-"+entry[0].toLowerCase());navigation.put(entry[0],tab);tabs.getChildren().add(tab);
        }
        var back=ForumStyles.button("返回工作台",Feather.ARROW_LEFT,workspace,"forum-text-button");
        tabs.getChildren().add(back);setTop(new VBox(heading,tabs));selected("HOME");
    }
    public void page(Node node){setCenter(node);}
    public void selected(String key){navigation.forEach((name,b)->{b.getStyleClass().remove("selected");if(name.equals(key))b.getStyleClass().add("selected");});}
    public void keyword(String value){search.setText(value);}
    public void loading(String title,String description){
        ProgressIndicator progress=new ProgressIndicator();progress.setMaxSize(32,32);
        var body=new VBox(16,progress,ForumStyles.label(title,"forum-section-title"),ForumStyles.label(description,"forum-muted"));
        body.getStyleClass().add("forum-empty");page(body);
    }
    public void message(String title,String description,Runnable retry){
        var body=new VBox(14,ForumStyles.label(title,"forum-section-title"),ForumStyles.label(description,"forum-muted"));
        body.getStyleClass().add("forum-empty");body.getChildren().add(ForumStyles.button("重试",Feather.REFRESH_CW,retry,"forum-quiet"));page(body);
    }
}
