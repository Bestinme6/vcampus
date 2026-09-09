package com.vcampus.client.fx.notification;

import com.vcampus.client.ui.NotificationDestination;
import com.vcampus.client.ui.NotificationViewData.NotificationDetail;
import com.vcampus.client.ui.NotificationViewData.NotificationPage;
import com.vcampus.client.ui.NotificationViewData.NotificationRow;
import com.vcampus.common.model.NotificationSource;
import javafx.collections.FXCollections;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class NotificationView extends BorderPane {
    interface Listener {
        void search(String keyword,NotificationSource source,Boolean read,int page);
        void select(long notificationId);
        void markAllRead();
        void navigate(NotificationDestination destination);
    }

    private final TextField keyword=new TextField();
    private final ComboBox<ReadOption> readFilter=new ComboBox<>();
    private final ToggleGroup sourceGroup=new ToggleGroup();
    private final FlowPane sourceFilters=new FlowPane(8,8);
    private final Map<NotificationSource,ToggleButton> sourceButtons=new LinkedHashMap<>();
    private final ListView<NotificationRow> list=new ListView<>();
    private final Label unread=NotificationUi.label("0 条未读","notification-unread-count");
    private final Label total=NotificationUi.label("共 0 条消息","notification-count");
    private final Label pageLabel=NotificationUi.label("第 1 页 / 共 1 页","notification-muted");
    private final Label status=NotificationUi.label("","notification-status");
    private Listener listener;
    private final Button previous=NotificationUi.button("上一页","notification-secondary",()->page(-1));
    private final Button next=NotificationUi.button("下一页","notification-secondary",()->page(1));
    private final Button markAll=NotificationUi.button("全部标为已读","notification-secondary",()->{if(listener!=null)listener.markAllRead();});
    private final VBox detail=new VBox(16);
    private final SplitPane split=new SplitPane();
    private int page=1,pageSize=10,totalRows;
    private long selectedId;
    private String navigationText="";

    NotificationView(Runnable back){
        getStyleClass().add("notification-page");NotificationUi.install(this);
        VBox content=new VBox(14,header(back),filters(),status,body());
        status.setManaged(false);status.setVisible(false);VBox.setVgrow(split,Priority.ALWAYS);VBox.setVgrow(content,Priority.ALWAYS);
        setCenter(content);configureList();showEmptyDetail();
        widthProperty().addListener((o,a,b)->updateOrientation(b.doubleValue()));
    }

    void setListener(Listener value){listener=value;}
    void showUnreadCount(int count){unread.setText(count+" 条未读");markAll.setDisable(count==0);}
    void showPage(NotificationPage value){
        page=value.page();pageSize=value.pageSize();totalRows=value.total();
        total.setText("共 "+totalRows+" 条消息");pageLabel.setText("第 "+page+" 页 / 共 "+pages()+" 页");
        previous.setDisable(page<=1);next.setDisable(page>=pages());
        long keep=selectedId;list.setItems(FXCollections.observableArrayList(value.rows()));
        NotificationRow selected=value.rows().stream().filter(row->row.id()==keep).findFirst().orElse(null);
        if(selected!=null)list.getSelectionModel().select(selected);
        else{selectedId=0;list.getSelectionModel().clearSelection();showEmptyDetail();}
        showStatus("",false);
    }
    void showDetail(NotificationDetail value){
        selectedId=value.id();navigationText="";
        Label source=NotificationUi.label(NotificationUi.sourceLabel(value.source()),"notification-source-chip");
        Label read=NotificationUi.label(value.read()?"已读":"未读",value.read()?"notification-read":"notification-unread");read.setId("notification-read-state");
        HBox meta=new HBox(8,source,read,spacer(),NotificationUi.label(NotificationUi.detailTime(value.createdAt()),"notification-muted"));meta.setAlignment(Pos.CENTER_LEFT);
        Label title=NotificationUi.label(value.title(),"notification-detail-title");title.setWrapText(true);title.setId("notification-detail-title");
        Label content=NotificationUi.label(value.content(),"notification-detail-content");content.setWrapText(true);content.setMaxWidth(Double.MAX_VALUE);
        VBox message=new VBox(12,NotificationUi.label("消息正文","notification-section-title"),content);message.getStyleClass().add("notification-detail-card");
        detail.getChildren().setAll(meta,title,message);
        NotificationDestination destination=NotificationDestination.from(value);
        if(destination.navigable()){
            navigationText=NotificationUi.navigationLabel(destination.target());
            Button open=NotificationUi.button(navigationText,"notification-primary",()->{if(listener!=null)listener.navigate(destination);});
            open.setId("notification-open-target");detail.getChildren().add(open);
        }
    }
    void markCurrentRead(){
        Node node=detail.lookup("#notification-read-state");
        if(node instanceof Label label){label.setText("已读");label.getStyleClass().remove("notification-unread");if(!label.getStyleClass().contains("notification-read"))label.getStyleClass().add("notification-read");}
        for(int i=0;i<list.getItems().size();i++){
            NotificationRow row=list.getItems().get(i);if(row.id()!=selectedId)continue;
            list.getItems().set(i,new NotificationRow(row.id(),row.type(),row.source(),row.title(),row.summary(),row.target(),row.relatedEntityId(),true,row.createdAt()));break;
        }
    }
    void showStatus(String text,boolean error){status.setText(text);status.setManaged(!text.isBlank());status.setVisible(!text.isBlank());status.getStyleClass().remove("notification-error");if(error)status.getStyleClass().add("notification-error");}
    void busy(boolean value){markAll.setDisable(value||"0 条未读".equals(unread.getText()));}

    List<String> visibleTitles(){return list.getItems().stream().map(NotificationRow::title).toList();}
    String unreadText(){return unread.getText();}
    String detailTitle(){Node value=detail.lookup("#notification-detail-title");return value instanceof Label label?label.getText():"";}
    String navigationText(){return navigationText;}
    long selectedId(){return selectedId;}
    List<String> sourceLabels(){return sourceFilters.getChildren().stream().filter(ToggleButton.class::isInstance).map(ToggleButton.class::cast).map(ToggleButton::getText).toList();}
    Orientation layoutOrientation(){return split.getOrientation();}

    private Node header(Runnable back){
        Button backButton=NotificationUi.button("← 返回工作台","notification-back",back);
        VBox copy=new VBox(4,NotificationUi.label("消息中心","notification-title"),NotificationUi.label("集中查看校内业务提醒与办理入口","notification-subtitle"));
        HBox row=new HBox(14,backButton,copy,spacer(),unread);row.setAlignment(Pos.CENTER_LEFT);row.getStyleClass().add("notification-header");return row;
    }
    private Node filters(){
        keyword.setPromptText("搜索标题或正文");keyword.setPrefWidth(260);keyword.setOnAction(e->search(1));
        readFilter.setItems(FXCollections.observableArrayList(new ReadOption("全部状态",null),new ReadOption("仅未读",false),new ReadOption("仅已读",true)));readFilter.getSelectionModel().selectFirst();
        Button query=NotificationUi.button("查询","notification-primary",()->search(1));query.setId("notification-search");markAll.setId("notification-mark-all");
        FlowPane controls=new FlowPane(10,10,keyword,readFilter,query,markAll);controls.setAlignment(Pos.CENTER_LEFT);
        addSource("全部消息",null,true);for(NotificationSource source:NotificationSource.values())addSource(NotificationUi.sourceLabel(source),source,false);
        VBox box=new VBox(12,controls,sourceFilters);box.getStyleClass().add("notification-toolbar");return box;
    }
    private void addSource(String text,NotificationSource source,boolean selected){
        ToggleButton button=new ToggleButton(text);button.setUserData(source);button.setToggleGroup(sourceGroup);button.setSelected(selected);button.getStyleClass().add("notification-source-filter");
        button.setOnAction(e->{if(!button.isSelected())button.setSelected(true);search(1);});sourceFilters.getChildren().add(button);if(source!=null)sourceButtons.put(source,button);
    }
    private Node body(){
        HBox listHead=new HBox(total,spacer(),NotificationUi.label("最新消息优先","notification-muted"));listHead.setAlignment(Pos.CENTER_LEFT);
        HBox pager=new HBox(10,previous,pageLabel,next);pager.setAlignment(Pos.CENTER);
        VBox left=new VBox(10,listHead,list,pager);left.getStyleClass().add("notification-panel");VBox.setVgrow(list,Priority.ALWAYS);left.setMinWidth(0);
        detail.getStyleClass().add("notification-detail");detail.setFillWidth(true);
        ScrollPane detailScroll=new ScrollPane(detail);detailScroll.setFitToWidth(true);detailScroll.getStyleClass().add("notification-detail-scroll");detailScroll.setMinWidth(0);
        split.getItems().addAll(left,detailScroll);split.setDividerPositions(.56);split.getStyleClass().add("notification-split");updateOrientation(getWidth());return split;
    }
    private void configureList(){
        list.setId("notification-list");list.getStyleClass().add("notification-list");list.setPlaceholder(NotificationUi.label("暂无符合条件的消息","notification-empty"));
        list.setCellFactory(value->new MessageCell());
        list.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{if(b!=null&&b.id()!=selectedId){selectedId=b.id();if(listener!=null)listener.select(b.id());}});
    }
    private void showEmptyDetail(){navigationText="";detail.getChildren().setAll(NotificationUi.label("选择一条消息","notification-empty-title"),NotificationUi.label("完整正文和相关业务入口会显示在这里。","notification-muted"));}
    private void search(int target){if(listener!=null)listener.search(keyword.getText().trim(),selectedSource(),readFilter.getValue().value(),target);}
    private NotificationSource selectedSource(){return sourceGroup.getSelectedToggle()==null?null:(NotificationSource)sourceGroup.getSelectedToggle().getUserData();}
    private void page(int delta){int target=page+delta;if(target>=1&&target<=pages())search(target);}
    private int pages(){return Math.max(1,(int)((totalRows+(long)pageSize-1)/pageSize));}
    private void updateOrientation(double width){boolean narrow=width>0&&width<980;split.setOrientation(narrow?Orientation.VERTICAL:Orientation.HORIZONTAL);split.setDividerPositions(narrow?.52:.56);}
    private static Region spacer(){Region value=new Region();HBox.setHgrow(value,Priority.ALWAYS);return value;}

    private record ReadOption(String label,Boolean value){@Override public String toString(){return label;}}

    private static final class MessageCell extends ListCell<NotificationRow>{
        @Override protected void updateItem(NotificationRow row,boolean empty){
            super.updateItem(row,empty);getStyleClass().remove("notification-list-cell-unread");
            if(empty||row==null){setGraphic(null);setAccessibleText(null);return;}
            Label dot=NotificationUi.label("●","notification-dot");dot.setVisible(!row.read());dot.setManaged(!row.read());
            Label source=NotificationUi.label(NotificationUi.sourceLabel(row.source()),"notification-card-source");
            HBox meta=new HBox(7,dot,source,spacer(),NotificationUi.label(NotificationUi.listTime(row.createdAt()),"notification-card-time"));meta.setAlignment(Pos.CENTER_LEFT);
            Label title=NotificationUi.label(row.title(),"notification-card-title");title.setMaxWidth(Double.MAX_VALUE);
            Label summary=NotificationUi.label(row.summary(),"notification-card-summary");summary.setWrapText(false);summary.setMaxWidth(Double.MAX_VALUE);
            VBox card=new VBox(7,meta,title,summary);card.getStyleClass().add("notification-card");card.setMaxWidth(Double.MAX_VALUE);
            setGraphic(card);setAccessibleText((row.read()?"已读":"未读")+" "+row.title());if(!row.read())getStyleClass().add("notification-list-cell-unread");
        }
    }
}
