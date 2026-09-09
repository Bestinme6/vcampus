package com.vcampus.client.fx.identity;

import com.vcampus.common.model.StudentStatus;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

final class StudentAdminView extends BorderPane {
    interface Listener{void search(String keyword,StudentStatus status,int page);void select(long id);void editProfile();void editContact();void changeStatus();void showHistory();}
    private Listener listener;private final TextField keyword=new TextField();private final ComboBox<StatusOption> status=new ComboBox<>();
    private final TableView<StudentData.Summary> table=new TableView<>();private final VBox detail=IdentityUi.card();private final Label pageLabel=new Label("第 1 页 · 共 0 条");
    private final Button previous=IdentityUi.secondary("上一页",()->page(-1)),next=IdentityUi.secondary("下一页",()->page(1));private int page=1,pageSize=8,total;private StudentData.Profile profile;
    StudentAdminView(Runnable back){getStyleClass().add("identity-page");IdentityUi.install(this);VBox root=new VBox(16,IdentityUi.pageHeader("学籍管理","查询学生档案、维护联系方式与学籍状态",back),toolbar(),body());VBox.setVgrow(root,Priority.ALWAYS);setCenter(root);configureTable();showNoSelection();}
    void setListener(Listener value){listener=value;}
    void showPage(StudentData.Page value){page=value.page();pageSize=value.pageSize();total=value.total();table.setItems(FXCollections.observableArrayList(value.rows()));pageLabel.setText("第 "+page+" 页 · 共 "+total+" 条");previous.setDisable(page<=1);next.setDisable((long)page*pageSize>=total);}
    void showDetail(StudentData.Profile value){profile=value;detail.getChildren().setAll(IdentityUi.sectionTitle(value.fullName()),IdentityUi.statusChip(value.status().displayName(),semantic(value.status())),IdentityUi.detailRow("学号",value.studentNumber()),IdentityUi.detailRow("性别",value.gender().displayName()),IdentityUi.detailRow("出生日期",value.birthDate()),IdentityUi.detailRow("学院",value.departmentName()),IdentityUi.detailRow("专业",value.majorName()),IdentityUi.detailRow("行政班",value.className()),IdentityUi.detailRow("入学年份",Integer.toString(value.enrollmentYear())),IdentityUi.detailRow("电话",value.phone()),IdentityUi.detailRow("邮箱",value.email()),IdentityUi.detailRow("地址",value.address()),actions());}
    void clearDetail(){profile=null;showNoSelection();}
    void showError(String message){Label error=new Label(message);error.getStyleClass().add("identity-error");detail.getChildren().setAll(error,IdentityUi.emptyState("加载失败","请稍后重试",null));}
    List<String> visibleNames(){return table.getItems().stream().map(StudentData.Summary::fullName).toList();}
    String detailStudentNumber(){return profile==null?"":profile.studentNumber();}
    StudentData.Profile selectedProfile(){return profile;}
    private HBox toolbar(){keyword.setPromptText("学号或姓名");status.getItems().add(new StatusOption(null,"全部状态"));for(StudentStatus value:StudentStatus.values())status.getItems().add(new StatusOption(value,value.displayName()));status.getSelectionModel().selectFirst();Button search=IdentityUi.primary("查询",()->{if(listener!=null)listener.search(keyword.getText().trim(),status.getValue().value(),1);});search.setId("student-admin-search");Button refresh=IdentityUi.secondary("刷新",()->{if(listener!=null)listener.search(keyword.getText().trim(),status.getValue().value(),page);});HBox row=new HBox(10,keyword,status,search,refresh);row.setAlignment(Pos.CENTER_LEFT);row.getStyleClass().add("identity-toolbar");HBox.setHgrow(keyword,Priority.ALWAYS);return row;}
    private SplitPane body(){VBox list=new VBox(10,table,new HBox(10,previous,pageLabel,next));VBox.setVgrow(table,Priority.ALWAYS);list.setMinWidth(560);detail.setMinWidth(300);SplitPane split=new SplitPane(list,detail);split.setDividerPositions(.66);split.getStyleClass().add("identity-split");VBox.setVgrow(split,Priority.ALWAYS);return split;}
    private HBox actions(){Button edit=IdentityUi.primary("编辑档案",()->{if(listener!=null)listener.editProfile();});edit.setId("student-admin-edit");Button contact=IdentityUi.secondary("联系方式",()->{if(listener!=null)listener.editContact();});Button state=IdentityUi.secondary("变更状态",()->{if(listener!=null)listener.changeStatus();});Button history=IdentityUi.secondary("状态历史",()->{if(listener!=null)listener.showHistory();});HBox row=new HBox(8,edit,contact,state,history);row.setAlignment(Pos.CENTER_LEFT);return row;}
    private void showNoSelection(){detail.getChildren().setAll(IdentityUi.emptyState("选择一名学生","右侧将显示完整学籍信息和可用操作",null));}
    private void page(int delta){int target=page+delta;if(listener!=null&&target>0&&(delta<0||(long)page*pageSize<total))listener.search(keyword.getText().trim(),status.getValue().value(),target);}
    private void configureTable(){table.setId("student-admin-table");table.getStyleClass().add("identity-table");table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);table.getColumns().add(column("学号",StudentData.Summary::studentNumber,125));table.getColumns().add(column("姓名",StudentData.Summary::fullName,100));table.getColumns().add(column("学院",StudentData.Summary::departmentName,140));table.getColumns().add(column("专业",StudentData.Summary::majorName,125));table.getColumns().add(column("行政班",StudentData.Summary::className,120));table.getColumns().add(column("状态",row->row.status().displayName(),76));table.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{if(b!=null&&listener!=null)listener.select(b.id());});}
    private static TableColumn<StudentData.Summary,String> column(String title,java.util.function.Function<StudentData.Summary,String> getter,double width){TableColumn<StudentData.Summary,String> column=new TableColumn<>(title);column.setCellValueFactory(cell->new ReadOnlyObjectWrapper<>(getter.apply(cell.getValue())));column.setPrefWidth(width);return column;}
    private static String semantic(StudentStatus value){return switch(value){case ENROLLED,GRADUATED->"positive";case SUSPENDED->"warning";case WITHDRAWN->"negative";};}
    private record StatusOption(StudentStatus value,String label){@Override public String toString(){return label;}}
}
