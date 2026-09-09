package com.vcampus.client.fx.identity;

import com.vcampus.common.model.Gender;
import com.vcampus.common.model.StudentStatus;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;

final class StudentDialogs {
    private StudentDialogs(){}
    record ProfileSubmission(String fullName,Gender gender,String birthDate,long departmentId,long majorId,long classId,int enrollmentYear,String phone,String email,String address){}
    record ContactSubmission(String phone,String email,String address){}
    record StatusSubmission(StudentStatus status,String reason){}

    static Optional<ProfileSubmission> editProfile(Node owner,StudentData.Profile p,StudentData.References refs){
        Dialog<ProfileSubmission> dialog=dialog(owner,"编辑学生档案");TextField name=new TextField(p.fullName()),birth=new TextField(p.birthDate()),phone=new TextField(p.phone()),email=new TextField(p.email()),address=new TextField(p.address());ComboBox<Gender> gender=new ComboBox<>(FXCollections.observableArrayList(Gender.values()));gender.setValue(p.gender());ComboBox<StudentData.Reference> department=new ComboBox<>(FXCollections.observableArrayList(refs.departments()));ComboBox<StudentData.Reference> major=new ComboBox<>(),studentClass=new ComboBox<>();select(department,p.departmentId());
        Runnable majors=()->{StudentData.Reference selected=department.getValue();major.setItems(FXCollections.observableArrayList(refs.majors().stream().filter(x->selected!=null&&x.parentId()==selected.id()).toList()));if(major.getValue()==null||!major.getItems().contains(major.getValue()))major.getSelectionModel().selectFirst();};
        Runnable classes=()->{StudentData.Reference selected=major.getValue();studentClass.setItems(FXCollections.observableArrayList(refs.classes().stream().filter(x->selected!=null&&x.parentId()==selected.id()).toList()));if(studentClass.getValue()==null||!studentClass.getItems().contains(studentClass.getValue()))studentClass.getSelectionModel().selectFirst();};
        department.valueProperty().addListener((o,a,b)->{majors.run();classes.run();});major.valueProperty().addListener((o,a,b)->classes.run());majors.run();select(major,p.majorId());classes.run();select(studentClass,p.classId());
        GridPane form=form();add(form,0,"姓名",name);add(form,1,"性别",gender);add(form,2,"出生日期",birth);add(form,3,"学院",department);add(form,4,"专业",major);add(form,5,"行政班",studentClass);add(form,6,"电话",phone);add(form,7,"邮箱",email);add(form,8,"地址",address);dialog.getDialogPane().setContent(form);dialog.setResultConverter(type->{if(type!=ButtonType.OK)return null;StudentData.Reference d=department.getValue(),m=major.getValue(),c=studentClass.getValue();if(d==null||m==null||c==null){IdentityUi.showError(owner,"请选择完整的学院、专业和行政班");return null;}return new ProfileSubmission(name.getText().trim(),gender.getValue(),birth.getText().trim(),d.id(),m.id(),c.id(),c.year(),phone.getText().trim(),email.getText().trim(),address.getText().trim());});return dialog.showAndWait();
    }
    static Optional<ContactSubmission> editContact(Node owner,StudentData.Profile p){Dialog<ContactSubmission> dialog=dialog(owner,"修改联系方式");TextField phone=new TextField(p.phone()),email=new TextField(p.email()),address=new TextField(p.address());GridPane form=form();add(form,0,"电话",phone);add(form,1,"邮箱",email);add(form,2,"地址",address);dialog.getDialogPane().setContent(form);dialog.setResultConverter(type->type==ButtonType.OK?new ContactSubmission(phone.getText().trim(),email.getText().trim(),address.getText().trim()):null);return dialog.showAndWait();}
    static Optional<StatusSubmission> changeStatus(Node owner,StudentData.Profile p){Dialog<StatusSubmission> dialog=dialog(owner,"变更学籍状态");ComboBox<StudentStatus> status=new ComboBox<>(FXCollections.observableArrayList(StudentStatus.values()));status.setValue(p.status());TextArea reason=new TextArea();reason.setPrefRowCount(3);GridPane form=form();add(form,0,"当前状态",new Label(p.status().displayName()));add(form,1,"新状态",status);add(form,2,"变更原因",reason);dialog.getDialogPane().setContent(form);dialog.setResultConverter(type->type==ButtonType.OK?new StatusSubmission(status.getValue(),reason.getText().trim()):null);return dialog.showAndWait();}
    static void showHistory(Node owner,List<StudentData.History> rows){Dialog<Void> dialog=new Dialog<>();dialog.setTitle("学籍状态历史");if(IdentityUi.window(owner)!=null)dialog.initOwner(IdentityUi.window(owner));VBox timeline=new VBox(10);if(rows.isEmpty())timeline.getChildren().add(IdentityUi.emptyState("暂无状态记录","状态发生变化后会显示在这里",null));else for(StudentData.History item:rows)timeline.getChildren().add(IdentityUi.card(IdentityUi.sectionTitle(item.changedAt()),IdentityUi.detailRow("状态",(item.oldStatus().isBlank()?"建档":StudentStatus.valueOf(item.oldStatus()).displayName())+" → "+item.newStatus().displayName()),IdentityUi.detailRow("原因",item.reason()),IdentityUi.detailRow("操作人",item.operator())));dialog.getDialogPane().setContent(timeline);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());dialog.showAndWait();}
    private static <T>Dialog<T> dialog(Node owner,String title){Dialog<T> dialog=new Dialog<>();dialog.setTitle(title);if(IdentityUi.window(owner)!=null)dialog.initOwner(IdentityUi.window(owner));dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL,ButtonType.OK);if(owner.getScene()!=null)dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());return dialog;}
    private static GridPane form(){GridPane pane=new GridPane();pane.getStyleClass().add("identity-form");return pane;}
    private static void add(GridPane pane,int row,String label,Node field){pane.add(new Label(label),0,row);pane.add(field,1,row);}
    private static void select(ComboBox<StudentData.Reference> box,long id){box.getItems().stream().filter(x->x.id()==id).findFirst().ifPresent(box::setValue);}
}
