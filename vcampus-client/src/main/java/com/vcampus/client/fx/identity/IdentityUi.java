package com.vcampus.client.fx.identity;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import com.vcampus.common.model.UserRole;

final class IdentityUi {
    private static final String CSS=IdentityUi.class.getResource("identity.css").toExternalForm();
    private IdentityUi(){}

    static VBox card(Node... children){VBox box=new VBox(14,children);box.getStyleClass().add("identity-card");box.setMinWidth(0);return box;}
    static HBox detailRow(String name,String value){Label label=new Label(name);label.getStyleClass().add("identity-detail-label");Label content=new Label(show(value));content.setWrapText(true);content.getStyleClass().add("identity-detail-value");HBox.setHgrow(content,Priority.ALWAYS);HBox row=new HBox(14,label,content);row.setAlignment(Pos.TOP_LEFT);row.getStyleClass().add("identity-detail-row");return row;}
    static Label statusChip(String text,String semantic){Label chip=new Label(show(text));chip.getStyleClass().addAll("identity-status","identity-status-"+semantic);chip.setAccessibleText("学籍状态："+show(text));return chip;}
    static HBox pageHeader(String title,String subtitle,Runnable back){Label heading=new Label(title);heading.getStyleClass().add("identity-title");Label hint=new Label(subtitle);hint.getStyleClass().add("identity-subtitle");VBox titles=new VBox(5,heading,hint);Region spacer=new Region();HBox.setHgrow(spacer,Priority.ALWAYS);HBox row=new HBox(16,titles,spacer);row.setAlignment(Pos.CENTER_LEFT);if(back!=null){Button button=secondary("← 返回工作台",back);button.setId("identity-back");row.getChildren().add(button);}row.getStyleClass().add("identity-header");return row;}
    static VBox emptyState(String title,String detail,Runnable retry){Label heading=new Label(title);heading.getStyleClass().add("identity-empty-title");Label hint=new Label(detail);hint.setWrapText(true);hint.getStyleClass().add("identity-subtitle");VBox box=new VBox(8,heading,hint);box.setAlignment(Pos.CENTER);box.getStyleClass().add("identity-empty");if(retry!=null)box.getChildren().add(primary("重试",retry));return box;}
    static Button primary(String text,Runnable action){return button(text,action,"identity-primary");}
    static Button secondary(String text,Runnable action){return button(text,action,"identity-secondary");}
    static Button danger(String text,Runnable action){return button(text,action,"identity-danger");}
    static Label sectionTitle(String text){Label label=new Label(text);label.getStyleClass().add("identity-section-title");return label;}
    static Region spacer(){Region spacer=new Region();HBox.setHgrow(spacer,Priority.ALWAYS);return spacer;}
    static void install(Parent root){if(!root.getStylesheets().contains(CSS))root.getStylesheets().add(CSS);}
    static void showError(Node owner,String message){alert(owner,Alert.AlertType.ERROR,"操作失败",message);}
    static void showSuccess(Node owner,String message){alert(owner,Alert.AlertType.INFORMATION,"操作成功",message);}
    static String show(String value){return value==null||value.isBlank()?"—":value;}
    static Window window(Node node){return node.getScene()==null?null:node.getScene().getWindow();}
    static String roleLabel(UserRole role){return switch(role){case STUDENT->"学生";case TEACHER->"教师";case SUPER_ADMIN->"超级管理员";case STUDENT_ADMIN->"学籍管理员";case ACADEMIC_ADMIN->"教务管理员";case LIBRARY_ADMIN->"图书管理员";case SHOP_ADMIN->"商店管理员";case BANK_ADMIN->"银行管理员";case FORUM_ADMIN->"论坛管理员";};}
    private static Button button(String text,Runnable action,String style){Button button=new Button(text);button.getStyleClass().add(style);button.setOnAction(event->action.run());return button;}
    private static void alert(Node owner,Alert.AlertType type,String title,String message){Alert alert=new Alert(type);alert.setTitle(title);alert.setHeaderText(null);alert.setContentText(message==null||message.isBlank()?"请求失败":message);Window window=window(owner);if(window!=null)alert.initOwner(window);alert.showAndWait();}
}
