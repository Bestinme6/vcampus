package com.vcampus.client.fx.library;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

final class LibraryUi {
    private LibraryUi() {}
    static Label label(String text,String style){Label value=new Label(text);value.getStyleClass().add(style);value.setWrapText(true);return value;}
    static FontIcon icon(Feather icon,int size){return FontIcon.of(icon,size);}
    static Button button(String text,String style,Runnable action){Button b=new Button(text);b.getStyleClass().add(style);b.setOnAction(e->action.run());return b;}
    static HBox row(Node... nodes){HBox row=new HBox(12,nodes);row.setAlignment(Pos.CENTER_LEFT);return row;}
    static Region space(){Region r=new Region();HBox.setHgrow(r,Priority.ALWAYS);return r;}
    static ScrollPane scroll(Node content){ScrollPane p=new ScrollPane(content);p.setFitToWidth(true);p.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);p.getStyleClass().add("library-scroll");return p;}
    static VBox cover(LibraryData.Book item,boolean large){
        var book=item.book();
        Label category=label(book.category().isBlank()?"VCAMPUS LIBRARY":book.category(),"library-cover-category");
        Label title=label(book.title(),"library-cover-title");title.setMaxHeight(large?180:75);
        Region gap=new Region();VBox.setVgrow(gap,Priority.ALWAYS);
        VBox cover=new VBox(10,category,gap,title,label("VC · 校园馆藏","library-cover-footer"));
        cover.getStyleClass().addAll("library-cover","library-cover-"+Math.floorMod(book.bookId(),5));
        cover.setPrefSize(large?180:88,large?250:124);cover.setMinSize(large?180:88,large?250:124);cover.setMaxSize(large?180:88,large?250:124);
        return cover;
    }
    static String sortLabel(com.vcampus.common.model.LibrarySort sort){return switch(sort){
        case CODE_ASC->"书目编号 ↑";case CODE_DESC->"书目编号 ↓";case TITLE_ASC->"书名 ↑";case TITLE_DESC->"书名 ↓";
        case CATEGORY_ASC->"分类 ↑";case CATEGORY_DESC->"分类 ↓";case BORROW_COUNT_ASC->"借阅次数 ↑";case BORROW_COUNT_DESC->"借阅次数 ↓";};}
}
