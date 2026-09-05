package com.vcampus.client.fx.forum;

import com.vcampus.client.ui.ForumViewData.*;
import com.vcampus.common.model.*;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;
import org.kordamp.ikonli.feather.Feather;
import java.util.*;
import java.util.function.*;

public final class ForumAdminView extends BorderPane {
    public record Query(ForumTargetType target,ForumContentStatus status,String keyword,int page){}
    private final TableView<AdminContentRow> table=new TableView<>();
    private final TableView<SectionRow> sections=new TableView<>();
    private final ListView<String> logs=new ListView<>();
    private final Label message=ForumStyles.label("","forum-error"),pageLabel=ForumStyles.label("","forum-caption");
    private final ComboBox<String> target=new ComboBox<>(),status=new ComboBox<>();
    private final TextField keyword=new TextField(),code=new TextField(),name=new TextField(),description=new TextField(),sort=new TextField("10");
    private final Map<ForumModerationAction,Button> buttons=new EnumMap<>(ForumModerationAction.class);
    private final Consumer<Query> refresh; private final BiConsumer<AdminContentRow,ForumModerationAction> moderate;
    private int page=1,logPage=1; private Long sectionId;
    private final Button previous,next,previousLog,nextLog;
    public ForumAdminView(Consumer<Query> refresh,BiConsumer<AdminContentRow,ForumModerationAction> moderate,
                          Consumer<Map<String,String>> save,BiConsumer<Long,Boolean> enable,IntConsumer loadLogs){
        this.refresh=refresh;this.moderate=moderate;getStyleClass().add("forum-admin-content");
        target.getItems().setAll("帖子","评论");target.setValue("帖子");
        status.getItems().setAll("全部状态","正常","已隐藏","已删除");status.setValue("全部状态");
        keyword.setPromptText("标题、正文或作者");keyword.setOnAction(e->{page=1;refresh.accept(query());});
        var search=ForumStyles.button("查询",Feather.SEARCH,()->{page=1;refresh.accept(query());},"forum-primary");
        var filters=new FlowPane(10,8,target,status,keyword,search);
        FlowPane operations=new FlowPane(8,8);
        action(operations,"隐藏",ForumModerationAction.HIDE);action(operations,"恢复",ForumModerationAction.RESTORE);
        action(operations,"锁定",ForumModerationAction.LOCK);action(operations,"解锁",ForumModerationAction.UNLOCK);
        action(operations,"置顶",ForumModerationAction.PIN);action(operations,"取消置顶",ForumModerationAction.UNPIN);
        action(operations,"精华",ForumModerationAction.FEATURE);action(operations,"取消精华",ForumModerationAction.UNFEATURE);
        action(operations,"设为公告",ForumModerationAction.ANNOUNCE);action(operations,"取消公告",ForumModerationAction.UNANNOUNCE);
        table.setId("forum-admin-table");table.setMinHeight(200);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column(table,"类型",r->r.targetType()==ForumTargetType.POST?"帖子":"评论",65);
        column(table,"状态",r->ForumStyles.status(r.status()),85);
        column(table,"板块",AdminContentRow::sectionName,100);column(table,"作者",AdminContentRow::authorDisplayName,100);
        column(table,"内容",r->r.title().isBlank()?r.content():r.title(),380);
        column(table,"标记",r->(r.pinned()?"置顶 ":"")+(r.featured()?"精华 ":"")+(r.locked()?"锁定":""),100);
        TextArea preview=new TextArea();preview.setEditable(false);preview.setWrapText(true);preview.setPrefRowCount(3);preview.setPromptText("选择一条记录查看完整内容");
        table.getSelectionModel().selectedItemProperty().addListener((o,a,r)->{preview.setText(r==null?"":r.content());updateActions();});
        previous=ForumStyles.button("上一页",Feather.CHEVRON_LEFT,()->{page--;refresh.accept(query());},"forum-quiet");
        next=ForumStyles.button("下一页",Feather.CHEVRON_RIGHT,()->{page++;refresh.accept(query());},"forum-quiet");
        var content=new VBox(14,filters,operations,table,preview,ForumStyles.row(previous,pageLabel,next));VBox.setVgrow(table,Priority.ALWAYS);
        table.setPlaceholder(ForumStyles.label("没有匹配的审核记录","forum-muted"));updateActions();
        sections.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column(sections,"代码",SectionRow::code,140);column(sections,"板块名称",SectionRow::name,140);
        column(sections,"说明",SectionRow::description,300);column(sections,"排序",r->""+r.sortOrder(),60);column(sections,"状态",r->r.enabled()?"已启用":"已停用",90);
        sections.getSelectionModel().selectedItemProperty().addListener((o,a,r)->{
            if(r==null)return;sectionId=r.id();code.setText(r.code());code.setDisable(true);name.setText(r.name());description.setText(r.description());sort.setText(""+r.sortOrder());
        });
        code.setPromptText("代码，如 CAMPUS");name.setPromptText("板块名称");description.setPromptText("板块说明");sort.setPromptText("排序");
        sort.setPrefWidth(80);
        var saveButton=ForumStyles.button("保存板块",Feather.SAVE,()->{
            Map<String,String> values=new LinkedHashMap<>(Map.of("code",code.getText(),"name",name.getText(),"description",description.getText(),"sortOrder",sort.getText()));
            if(sectionId!=null)values.put("sectionId",""+sectionId);save.accept(values);
        },"forum-primary");
        var fresh=ForumStyles.button("新增",Feather.PLUS,()->{sectionId=null;code.setDisable(false);code.clear();name.clear();description.clear();sort.setText("10");sections.getSelectionModel().clearSelection();},"forum-quiet");
        var toggle=ForumStyles.button("启用 / 停用",null,()->{var s=sections.getSelectionModel().getSelectedItem();if(s!=null)enable.accept(s.id(),!s.enabled());else error("请先选择板块");},"forum-quiet");
        var sectionPanel=new VBox(14,ForumStyles.label("代码是内部标识；排序数字越小越靠前。新增板块保存后需启用。","forum-muted"),
                new FlowPane(10,10,code,name,sort),description,new FlowPane(10,8,fresh,saveButton,toggle),sections);VBox.setVgrow(sections,Priority.ALWAYS);
        previousLog=ForumStyles.button("上一页",Feather.CHEVRON_LEFT,()->loadLogs.accept(--logPage),"forum-quiet");
        nextLog=ForumStyles.button("下一页",Feather.CHEVRON_RIGHT,()->loadLogs.accept(++logPage),"forum-quiet");
        var logPanel=new VBox(14,logs,ForumStyles.row(previousLog,nextLog));VBox.setVgrow(logs,Priority.ALWAYS);
        TabPane tabs=new TabPane(tab("内容审核",content),tab("板块管理",sectionPanel),tab("审核日志",logPanel));
        // TabPane does not propagate its selected content's minimum height to ScrollPane.
        // Keep room for the filter rows, a readable table, preview and paging controls.
        tabs.setMinHeight(620);
        ScrollPane scroll=new ScrollPane();ForumStyles.scroll(scroll,tabs);scroll.setFitToHeight(true);
        message.managedProperty().bind(message.textProperty().isNotEmpty());message.visibleProperty().bind(message.managedProperty());
        setTop(new VBox(8,ForumStyles.label("内容管理","forum-page-title"),
                ForumStyles.label("保留审核记录，让每一次管理都有据可查","forum-muted"),message));setCenter(scroll);
    }
    private Tab tab(String text,Node content){Tab tab=new Tab(text,content);tab.setClosable(false);return tab;}
    private <T> void column(TableView<T> table,String title,Function<T,String> value,int width){
        TableColumn<T,String> c=new TableColumn<>(title);c.setCellValueFactory(v->new SimpleStringProperty(value.apply(v.getValue())));c.setPrefWidth(width);table.getColumns().add(c);
    }
    private void action(FlowPane parent,String title,ForumModerationAction action){
        var button=ForumStyles.button(title,null,()->{var r=table.getSelectionModel().getSelectedItem();if(r!=null)moderate.accept(r,action);},"forum-quiet");
        button.setId("forum-admin-"+action.name().toLowerCase());buttons.put(action,button);parent.getChildren().add(button);
    }
    private void updateActions(){
        var r=table.getSelectionModel().getSelectedItem();
        buttons.forEach((action,b)->{
            boolean allowed=r!=null&&switch(action){
                case HIDE->r.status()==ForumContentStatus.NORMAL;
                case RESTORE->r.status()==ForumContentStatus.HIDDEN;
                case LOCK->r.targetType()==ForumTargetType.POST&&r.status()==ForumContentStatus.NORMAL&&!r.locked();
                case UNLOCK->r.targetType()==ForumTargetType.POST&&r.status()==ForumContentStatus.NORMAL&&r.locked();
                case PIN->r.targetType()==ForumTargetType.POST&&r.status()==ForumContentStatus.NORMAL&&!r.pinned();
                case UNPIN->r.targetType()==ForumTargetType.POST&&r.status()==ForumContentStatus.NORMAL&&r.pinned();
                case FEATURE->r.targetType()==ForumTargetType.POST&&r.status()==ForumContentStatus.NORMAL&&!r.featured();
                case UNFEATURE->r.targetType()==ForumTargetType.POST&&r.status()==ForumContentStatus.NORMAL&&r.featured();
                case ANNOUNCE,UNANNOUNCE->r.targetType()==ForumTargetType.POST&&r.status()==ForumContentStatus.NORMAL;
                default->false;
            };b.setDisable(!allowed);
        });
    }
    public Query query(){return new Query(target.getValue().equals("评论")?ForumTargetType.COMMENT:ForumTargetType.POST,
            switch(status.getValue()){case "正常"->ForumContentStatus.NORMAL;case "已隐藏"->ForumContentStatus.HIDDEN;case "已删除"->ForumContentStatus.DELETED;default->null;},keyword.getText(),page);}
    public void showContent(AdminContentPage result){table.getItems().setAll(result.rows());page=result.page();previous.setDisable(page<=1);next.setDisable((long)page*result.pageSize()>=result.total());pageLabel.setText("共 "+result.total()+" 条 · 第 "+page+" 页");updateActions();}
    public void showSections(List<SectionRow> rows){sections.getItems().setAll(rows);}
    public void showLogs(ModerationLogPage result){logPage=result.page();logs.getItems().setAll(result.rows().stream().map(r->ForumStyles.time(r.createdAt())+"  "+r.operatorDisplayName()+"  "+r.action()+"  "+r.reason()).toList());previousLog.setDisable(logPage<=1);nextLog.setDisable((long)logPage*result.pageSize()>=result.total());}
    public void error(String text){message.setText(text);}
    public void busy(boolean busy){getCenter().setDisable(busy);}
}
