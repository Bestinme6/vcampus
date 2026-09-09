package com.vcampus.client.fx.identity;

import com.vcampus.common.model.StudentStatus;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

final class StudentProfileView extends BorderPane {
    interface Listener { void reload(); void saveContact(String phone,String email,String address); }
    private Listener listener; private StudentData.Profile profile;
    private final VBox content=new VBox(18); private final Label error=new Label();

    StudentProfileView(Runnable back){
        getStyleClass().add("identity-page"); IdentityUi.install(this);
        content.setMaxWidth(1080); content.getChildren().add(IdentityUi.pageHeader("我的学籍","查看个人档案、学业归属和学籍状态记录",back));
        error.getStyleClass().add("identity-error");error.setVisible(false);error.setManaged(false);content.getChildren().add(error);
        ScrollPane scroll=new ScrollPane(content);scroll.setFitToWidth(true);scroll.setStyle("-fx-background-color:transparent;-fx-background:transparent;");
        BorderPane.setMargin(scroll,new Insets(0));setCenter(scroll);
    }
    void setListener(Listener value){listener=value;}
    void showLoading(){error.setVisible(false);error.setManaged(false);replaceBody(IdentityUi.emptyState("正在加载学籍信息","请稍候",null));}
    void showError(String message){error.setText(message);error.setVisible(true);error.setManaged(true);replaceBody(IdentityUi.emptyState("学籍信息暂不可用","请检查连接后重试",()->{if(listener!=null)listener.reload();}));}
    void showProfile(StudentData.Profile value){profile=value;error.setVisible(false);error.setManaged(false);render(List.of());}
    void showHistory(List<StudentData.History> history){if(profile!=null)render(history);}
    String nameText(){return profile==null?"":profile.fullName();}
    String phoneText(){return profile==null?"":profile.phone();}

    private void render(List<StudentData.History> history){
        VBox hero=new VBox(7);hero.getStyleClass().add("identity-hero");
        Label name=new Label(profile.fullName());name.getStyleClass().add("identity-hero-name");
        Label meta=new Label(profile.studentNumber()+"  ·  "+profile.departmentName()+"  ·  "+profile.majorName());meta.getStyleClass().add("identity-hero-meta");
        HBox heroLine=new HBox(14,name,IdentityUi.statusChip(profile.status().displayName(),statusSemantic(profile.status())));heroLine.setAlignment(Pos.CENTER_LEFT);hero.getChildren().addAll(heroLine,meta);
        VBox basic=IdentityUi.card(IdentityUi.sectionTitle("基本信息"),IdentityUi.detailRow("姓名",profile.fullName()),IdentityUi.detailRow("性别",profile.gender().displayName()),IdentityUi.detailRow("出生日期",profile.birthDate()));
        VBox academic=IdentityUi.card(IdentityUi.sectionTitle("学业信息"),IdentityUi.detailRow("学院",profile.departmentName()),IdentityUi.detailRow("专业",profile.majorName()),IdentityUi.detailRow("行政班",profile.className()),IdentityUi.detailRow("入学年份",Integer.toString(profile.enrollmentYear())),IdentityUi.detailRow("学籍状态",profile.status().displayName()));
        Button edit=IdentityUi.primary("修改联系方式",this::editContact);edit.setId("student-contact-edit");
        VBox contact=IdentityUi.card(new HBox(12,IdentityUi.sectionTitle("联系方式"),IdentityUi.spacer(),edit),IdentityUi.detailRow("电话",profile.phone()),IdentityUi.detailRow("邮箱",profile.email()),IdentityUi.detailRow("地址",profile.address()));
        GridPane cards=new GridPane();cards.setHgap(16);cards.setVgap(16);cards.add(basic,0,0);cards.add(academic,1,0);cards.add(contact,0,1,2,1);GridPane.setHgrow(basic,Priority.ALWAYS);GridPane.setHgrow(academic,Priority.ALWAYS);
        VBox timeline=IdentityUi.card(IdentityUi.sectionTitle("学籍状态历史"));
        if(history.isEmpty())timeline.getChildren().add(IdentityUi.emptyState("暂无状态记录","状态发生变化后会显示在这里",null));
        else for(StudentData.History item:history)timeline.getChildren().add(IdentityUi.detailRow(item.changedAt(),(item.oldStatus().isBlank()?"建档":StudentStatus.valueOf(item.oldStatus()).displayName())+" → "+item.newStatus().displayName()+"\n"+IdentityUi.show(item.reason())+" · "+IdentityUi.show(item.operator())));
        replaceBody(hero,cards,timeline);
    }
    private void editContact(){
        Dialog<Contact> dialog=new Dialog<>();dialog.setTitle("修改联系方式");if(IdentityUi.window(this)!=null)dialog.initOwner(IdentityUi.window(this));
        TextField phone=new TextField(profile.phone()),email=new TextField(profile.email()),address=new TextField(profile.address());
        GridPane form=new GridPane();form.getStyleClass().add("identity-form");form.addRow(0,new Label("电话"),phone);form.addRow(1,new Label("邮箱"),email);form.addRow(2,new Label("地址"),address);
        dialog.getDialogPane().setContent(form);dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.CANCEL,javafx.scene.control.ButtonType.OK);dialog.getDialogPane().getStylesheets().addAll(getStylesheets());
        dialog.setResultConverter(type->type==javafx.scene.control.ButtonType.OK?new Contact(phone.getText(),email.getText(),address.getText()):null);
        dialog.showAndWait().ifPresent(value->{if(listener!=null)listener.saveContact(value.phone(),value.email(),value.address());});
    }
    private void replaceBody(javafx.scene.Node...nodes){while(content.getChildren().size()>2)content.getChildren().remove(2);content.getChildren().addAll(nodes);}
    private static String statusSemantic(StudentStatus status){return switch(status){case ENROLLED,GRADUATED->"positive";case SUSPENDED->"warning";case WITHDRAWN->"negative";};}
    private record Contact(String phone,String email,String address){}
}
