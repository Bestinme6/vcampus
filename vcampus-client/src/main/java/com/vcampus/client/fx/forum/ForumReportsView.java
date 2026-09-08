package com.vcampus.client.fx.forum;

import com.vcampus.common.model.*;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.kordamp.ikonli.feather.Feather;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public final class ForumReportsView extends VBox {
    public record Query(ForumReportStatus status,ForumTargetType target,
                        ForumReportReason reason,int page) { }

    private final Consumer<Query> refresh;
    private final BiConsumer<ForumData.ReportRow,ForumReportResolution> resolve;
    private final TableView<ForumData.ReportRow> table=new TableView<>();
    private final ComboBox<String> status=new ComboBox<>(),target=new ComboBox<>(),reason=new ComboBox<>();
    private final Label pageLabel=ForumStyles.label("","forum-caption");
    private final Button previous,next,dismiss,hide,delete;
    private int page=1;

    public ForumReportsView(Consumer<Query> refresh,
                            BiConsumer<ForumData.ReportRow,ForumReportResolution> resolve) {
        super(14);this.refresh=refresh;this.resolve=resolve;
        status.getItems().setAll("待处理","已驳回","已处置","全部状态");status.setValue("待处理");
        target.getItems().setAll("全部内容","帖子","评论");target.setValue("全部内容");
        reason.getItems().setAll("全部原因","垃圾广告","辱骂或骚扰","虚假或误导信息","泄露隐私","不当内容","其他");reason.setValue("全部原因");
        var search=ForumStyles.button("筛选",Feather.SEARCH,()->{page=1;refresh.accept(query());},"forum-primary");
        getChildren().add(new FlowPane(10,8,status,target,reason,search));
        dismiss=action("驳回举报",ForumReportResolution.DISMISS);
        hide=action("隐藏内容",ForumReportResolution.HIDE);
        delete=action("删除内容",ForumReportResolution.DELETE);
        getChildren().add(new FlowPane(8,8,dismiss,hide,delete));
        table.setId("forum-report-table");table.setMinHeight(260);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column("对象",r->r.targetType()==ForumTargetType.POST?"帖子":"评论",65);
        column("举报人",ForumData.ReportRow::reporterDisplayName,95);
        column("原因",r->reasonText(r.reason()),120);
        column("内容",r->r.content().replace('\n',' '),320);
        column("状态",r->statusText(r.status()),90);
        table.getSelectionModel().selectedItemProperty().addListener((o,a,b)->updateActions());
        table.setPlaceholder(ForumStyles.label("没有匹配的举报","forum-muted"));
        getChildren().add(table);VBox.setVgrow(table,Priority.ALWAYS);
        TextArea preview=new TextArea();preview.setEditable(false);preview.setWrapText(true);preview.setPrefRowCount(4);
        table.getSelectionModel().selectedItemProperty().addListener((o,a,r)->preview.setText(r==null?"":r.content()+"\n\n补充说明："+r.detail()));
        getChildren().add(preview);
        previous=ForumStyles.button("上一页",Feather.CHEVRON_LEFT,()->{page--;refresh.accept(query());},"forum-quiet");
        next=ForumStyles.button("下一页",Feather.CHEVRON_RIGHT,()->{page++;refresh.accept(query());},"forum-quiet");
        getChildren().add(ForumStyles.row(previous,pageLabel,next));updateActions();
    }

    private Button action(String text,ForumReportResolution resolution){
        Button button=ForumStyles.button(text,null,()->{var row=table.getSelectionModel().getSelectedItem();if(row!=null)resolve.accept(row,resolution);},"forum-quiet");
        button.setId("forum-report-"+resolution.name().toLowerCase());return button;
    }
    private void column(String title,Function<ForumData.ReportRow,String> value,int width){
        TableColumn<ForumData.ReportRow,String> column=new TableColumn<>(title);
        column.setCellValueFactory(cell->new SimpleStringProperty(value.apply(cell.getValue())));
        column.setPrefWidth(width);table.getColumns().add(column);
    }
    private void updateActions(){boolean enabled=table.getSelectionModel().getSelectedItem()!=null&&table.getSelectionModel().getSelectedItem().status()==ForumReportStatus.PENDING;dismiss.setDisable(!enabled);hide.setDisable(!enabled);delete.setDisable(!enabled);}
    public Query query(){return new Query(switch(status.getValue()){case "待处理"->ForumReportStatus.PENDING;case "已驳回"->ForumReportStatus.DISMISSED;case "已处置"->ForumReportStatus.ACTIONED;default->null;},switch(target.getValue()){case "帖子"->ForumTargetType.POST;case "评论"->ForumTargetType.COMMENT;default->null;},switch(reason.getValue()){case "垃圾广告"->ForumReportReason.SPAM;case "辱骂或骚扰"->ForumReportReason.HARASSMENT;case "虚假或误导信息"->ForumReportReason.MISLEADING;case "泄露隐私"->ForumReportReason.PRIVACY;case "不当内容"->ForumReportReason.INAPPROPRIATE;case "其他"->ForumReportReason.OTHER;default->null;},page);}
    public void show(ForumData.ReportPage result){table.getItems().setAll(result.rows());page=result.page();previous.setDisable(page<=1);next.setDisable((long)page*result.pageSize()>=result.total());pageLabel.setText("共 "+result.total()+" 条 · 第 "+page+" 页");updateActions();}
    private static String reasonText(ForumReportReason reason){return switch(reason){case SPAM->"垃圾广告";case HARASSMENT->"辱骂或骚扰";case MISLEADING->"虚假或误导信息";case PRIVACY->"泄露隐私";case INAPPROPRIATE->"不当内容";case OTHER->"其他";};}
    private static String statusText(ForumReportStatus status){return switch(status){case PENDING->"待处理";case DISMISSED->"已驳回";case ACTIONED->"已处置";};}
}
