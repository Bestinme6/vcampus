package com.vcampus.client.fx.forum;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import java.time.*;
import java.time.format.DateTimeFormatter;

final class ForumStyles {
    private ForumStyles(){}
    static Label label(String text,String style){Label l=new Label(text);l.getStyleClass().add(style);l.setWrapText(true);l.setMinWidth(0);return l;}
    static Button button(String text,Feather icon,Runnable action,String... styles){
        Button b=new Button(text,icon==null?null:FontIcon.of(icon,16));b.setOnAction(e->action.run());
        b.getStyleClass().addAll(styles);b.setAccessibleText(text);return b;
    }
    static HBox row(Node... nodes){HBox b=new HBox(10,nodes);b.setAlignment(Pos.CENTER_LEFT);return b;}
    static Region spacer(){Region r=new Region();HBox.setHgrow(r,Priority.ALWAYS);return r;}
    static VBox card(Node... nodes){VBox b=new VBox(14,nodes);b.getStyleClass().add("forum-card");b.setMinWidth(0);return b;}
    static String time(Instant value){return DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(value);}
    static String status(com.vcampus.common.model.ForumContentStatus s){return switch(s){case NORMAL->"正常";case HIDDEN->"已隐藏";case DELETED->"已删除";};}
    static void scroll(ScrollPane scroll,Node content){scroll.setFitToWidth(true);scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);scroll.getStyleClass().add("forum-scroll");scroll.setContent(content);}
}
