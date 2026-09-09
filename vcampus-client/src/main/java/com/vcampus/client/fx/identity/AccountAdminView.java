package com.vcampus.client.fx.identity;

import com.vcampus.common.model.UserRole;
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
import java.util.stream.Collectors;

final class AccountAdminView extends BorderPane {
    interface Listener{void search(String keyword,UserRole identity,Boolean enabled,int page);void select(long id);void create();void editRoles();void toggle();void resetPassword();}
    private Listener listener;private final TextField keyword=new TextField();private final ComboBox<Filter<UserRole>> identity=new ComboBox<>();private final ComboBox<Filter<Boolean>> enabled=new ComboBox<>();private final TableView<AccountData.Account> table=new TableView<>();private final VBox detail=IdentityUi.card();private final Label pageLabel=new Label("第 1 页 · 共 0 条"),banner=new Label();private final Button previous=IdentityUi.secondary("上一页",()->page(-1)),next=IdentityUi.secondary("下一页",()->page(1));private int page=1,pageSize=8,total;private AccountData.Account selected;
    AccountAdminView(Runnable back){getStyleClass().add("identity-page");IdentityUi.install(this);banner.getStyleClass().add("identity-banner");banner.setVisible(false);banner.setManaged(false);VBox root=new VBox(14,IdentityUi.pageHeader("账号管理","创建学生或教师账号，维护角色、状态和登录安全",back),banner,toolbar(),body());VBox.setVgrow(root,Priority.ALWAYS);setCenter(root);configureTable();clearDetail();}
    void setListener(Listener value){listener=value;}
    void showPage(AccountData.Page value){page=value.page();pageSize=value.pageSize();total=value.total();table.setItems(FXCollections.observableArrayList(value.rows()));pageLabel.setText("第 "+page+" 页 · 共 "+total+" 条");previous.setDisable(page<=1);next.setDisable((long)page*pageSize>=total);}
    void showDetail(AccountData.Account value){selected=value;String roles=value.administrativeRoles().isEmpty()?"无":value.administrativeRoles().stream().map(IdentityUi::roleLabel).sorted().collect(Collectors.joining("、"));Label status=IdentityUi.statusChip(value.enabled()?"已启用":"已停用",value.enabled()?"positive":"negative");status.setAccessibleText("账号状态："+status.getText());detail.getChildren().setAll(IdentityUi.sectionTitle(value.displayName()),status,IdentityUi.detailRow("账号",value.username()),IdentityUi.detailRow("基础身份",IdentityUi.roleLabel(value.baseIdentity())),IdentityUi.detailRow("管理员角色",roles),IdentityUi.detailRow("首次改密",value.forcePasswordChange()?"需要":"已完成"),IdentityUi.detailRow("最后登录",value.lastLoginAt().isBlank()?"从未登录":value.lastLoginAt()),actions());}
    void clearDetail(){selected=null;detail.getChildren().setAll(IdentityUi.emptyState("选择一个账号","右侧将显示账号状态和可用操作",null));}
    void showBanner(String text){banner.setText(text);banner.setVisible(true);banner.setManaged(true);}
    void showError(String message){IdentityUi.showError(this,message);}
    List<String> visibleUsernames(){return table.getItems().stream().map(AccountData.Account::username).toList();}
    String detailName(){return selected==null?"":selected.displayName();}
    AccountData.Account selectedAccount(){return selected;}
    private HBox toolbar(){keyword.setPromptText("账号、学号/工号或姓名");identity.getItems().addAll(new Filter<UserRole>(null,"全部身份"),new Filter<>(UserRole.STUDENT,"学生"),new Filter<>(UserRole.TEACHER,"教师"));identity.getSelectionModel().selectFirst();enabled.getItems().addAll(new Filter<Boolean>(null,"全部状态"),new Filter<>(true,"已启用"),new Filter<>(false,"已停用"));enabled.getSelectionModel().selectFirst();Button search=IdentityUi.primary("查询",()->search(1));Button refresh=IdentityUi.secondary("刷新",()->search(page));Button create=IdentityUi.primary("＋ 创建账号",()->{if(listener!=null)listener.create();});create.setId("account-create");HBox row=new HBox(10,keyword,identity,enabled,search,refresh,IdentityUi.spacer(),create);row.setAlignment(Pos.CENTER_LEFT);row.getStyleClass().add("identity-toolbar");HBox.setHgrow(keyword,Priority.ALWAYS);return row;}
    private SplitPane body(){VBox list=new VBox(10,table,new HBox(10,previous,pageLabel,next));list.setMinWidth(590);VBox.setVgrow(table,Priority.ALWAYS);detail.setMinWidth(310);SplitPane split=new SplitPane(list,detail);split.setDividerPositions(.66);split.getStyleClass().add("identity-split");VBox.setVgrow(split,Priority.ALWAYS);return split;}
    private HBox actions(){Button roles=IdentityUi.primary("分配角色",()->{if(listener!=null)listener.editRoles();});roles.setId("account-roles");Button toggle=(selected!=null&&selected.enabled()?IdentityUi.danger("停用账号",()->{if(listener!=null)listener.toggle();}):IdentityUi.secondary("启用账号",()->{if(listener!=null)listener.toggle();}));Button reset=IdentityUi.secondary("重置密码",()->{if(listener!=null)listener.resetPassword();});HBox row=new HBox(8,roles,toggle,reset);row.setAlignment(Pos.CENTER_LEFT);return row;}
    private void search(int target){if(listener!=null)listener.search(keyword.getText().trim(),identity.getValue().value(),enabled.getValue().value(),target);}
    private void page(int delta){int target=page+delta;if(target>0&&(delta<0||(long)page*pageSize<total))search(target);}
    private void configureTable(){table.getStyleClass().add("identity-table");table.setId("account-admin-table");table.getColumns().add(column("账号",AccountData.Account::username,125));table.getColumns().add(column("姓名",AccountData.Account::displayName,105));table.getColumns().add(column("身份",a->IdentityUi.roleLabel(a.baseIdentity()),76));table.getColumns().add(column("管理员角色",a->a.administrativeRoles().stream().map(IdentityUi::roleLabel).sorted().collect(Collectors.joining("、")),190));table.getColumns().add(column("状态",a->a.enabled()?"启用":"停用",70));table.getColumns().add(column("改密",a->a.forcePasswordChange()?"待修改":"已完成",80));table.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{if(b!=null&&listener!=null)listener.select(b.userId());});}
    private static TableColumn<AccountData.Account,String> column(String title,java.util.function.Function<AccountData.Account,String> getter,double width){TableColumn<AccountData.Account,String> column=new TableColumn<>(title);column.setCellValueFactory(cell->new ReadOnlyObjectWrapper<>(getter.apply(cell.getValue())));column.setPrefWidth(width);return column;}
    private record Filter<T>(T value,String label){@Override public String toString(){return label;}}
}
