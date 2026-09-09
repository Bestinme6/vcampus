package com.vcampus.client.fx.identity;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

final class TeacherProfileView extends BorderPane {
    interface Listener{void reload();void saveContact(String phone,String email);}
    private Listener listener;private TeacherData profile;private final VBox content=new VBox(18);private final Label error=new Label();
    TeacherProfileView(Runnable back){getStyleClass().add("identity-page");IdentityUi.install(this);content.setMaxWidth(980);content.getChildren().add(IdentityUi.pageHeader("教师信息","查看任职资料并维护联系方式",back));error.getStyleClass().add("identity-error");error.setVisible(false);error.setManaged(false);content.getChildren().add(error);ScrollPane scroll=new ScrollPane(content);scroll.setFitToWidth(true);scroll.setStyle("-fx-background-color:transparent;-fx-background:transparent;");setCenter(scroll);}
    void setListener(Listener value){listener=value;}
    void showLoading(){replaceBody(IdentityUi.emptyState("正在加载教师信息","请稍候",null));}
    void showError(String message){error.setText(message);error.setVisible(true);error.setManaged(true);replaceBody(IdentityUi.emptyState("教师信息暂不可用","请检查连接后重试",()->{if(listener!=null)listener.reload();}));}
    void showProfile(TeacherData value){profile=value;error.setVisible(false);error.setManaged(false);VBox hero=new VBox(7);hero.getStyleClass().add("identity-hero");Label name=new Label(value.fullName());name.getStyleClass().add("identity-hero-name");Label meta=new Label(value.teacherNumber()+"  ·  "+value.departmentName());meta.getStyleClass().add("identity-hero-meta");hero.getChildren().addAll(name,meta);VBox work=IdentityUi.card(IdentityUi.sectionTitle("任职信息"),IdentityUi.detailRow("教师工号",value.teacherNumber()),IdentityUi.detailRow("学院",value.departmentName()),IdentityUi.detailRow("职称",value.professionalTitle()));Button edit=IdentityUi.primary("修改联系方式",this::editContact);edit.setId("teacher-contact-edit");HBox heading=new HBox(12,IdentityUi.sectionTitle("联系方式"),IdentityUi.spacer(),edit);heading.setAlignment(Pos.CENTER_LEFT);VBox contact=IdentityUi.card(heading,IdentityUi.detailRow("电话",value.phone()),IdentityUi.detailRow("邮箱",value.email()));GridPane cards=new GridPane();cards.setHgap(16);cards.add(work,0,0);cards.add(contact,1,0);GridPane.setHgrow(work,Priority.ALWAYS);GridPane.setHgrow(contact,Priority.ALWAYS);replaceBody(hero,cards);}
    String nameText(){return profile==null?"":profile.fullName();}
    private void editContact(){Dialog<Contact> dialog=new Dialog<>();dialog.setTitle("修改联系方式");if(IdentityUi.window(this)!=null)dialog.initOwner(IdentityUi.window(this));TextField phone=new TextField(profile.phone()),email=new TextField(profile.email());GridPane form=new GridPane();form.getStyleClass().add("identity-form");form.addRow(0,new Label("电话"),phone);form.addRow(1,new Label("邮箱"),email);dialog.getDialogPane().setContent(form);dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL,ButtonType.OK);dialog.getDialogPane().getStylesheets().addAll(getStylesheets());dialog.setResultConverter(type->type==ButtonType.OK?new Contact(phone.getText(),email.getText()):null);dialog.showAndWait().ifPresent(value->{if(listener!=null)listener.saveContact(value.phone(),value.email());});}
    private void replaceBody(javafx.scene.Node...nodes){while(content.getChildren().size()>2)content.getChildren().remove(2);content.getChildren().addAll(nodes);}
    private record Contact(String phone,String email){}
}
