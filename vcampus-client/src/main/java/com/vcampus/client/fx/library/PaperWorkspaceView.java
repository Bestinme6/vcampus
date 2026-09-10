package com.vcampus.client.fx.library;

import com.vcampus.common.model.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.event.ActionEvent;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import java.awt.Desktop;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static com.vcampus.client.fx.library.LibraryUi.*;

/** Search, download and staff maintenance for the campus paper repository. */
public final class PaperWorkspaceView extends BorderPane implements AutoCloseable {
    private final PaperGateway gateway;private final boolean manage;private final Executor executor;
    private static final String ALL_KEYWORDS="全部关键词";
    private final TextField keyword=new TextField(),year=new TextField();
    private final ComboBox<String> tag=new ComboBox<>();
    private final CheckBox includeDisabled=new CheckBox("包含未上架");
    private final TableView<LibraryPaper> table=new TableView<>();
    private final VBox detail=new VBox(9);private final HBox paging=new HBox(10);
    private final Label status=label("","library-status");private final ProgressBar progress=new ProgressBar(0);
    private final Button previous,next,download,source,edit,upload,toggle;
    private int page=1,pages=1;private long generation,selectAfterLoad;private boolean closed,updatingTags;
    private String noticeAfterLoad="";
    private FutureTask<?> pending;private final Set<Dialog<?>> dialogs=new HashSet<>();

    public PaperWorkspaceView(PaperGateway gateway,Set<UserRole> roles,Executor executor){
        this.gateway=Objects.requireNonNull(gateway);this.manage=LibraryAccessPolicy.canManage(roles);this.executor=Objects.requireNonNull(executor);
        setId("paper-workspace");getStylesheets().add(Objects.requireNonNull(getClass().getResource("library.css")).toExternalForm());setStyle("-fx-background-color: #f5f8fd;");
        keyword.setPromptText("论文标题、作者或关键词");keyword.setPrefWidth(300);keyword.setId("paper-keyword");
        year.setPromptText("年份，如 2026");year.setPrefWidth(130);year.setId("paper-year");
        tag.getItems().setAll(ALL_KEYWORDS);tag.setValue(ALL_KEYWORDS);tag.setPromptText(ALL_KEYWORDS);tag.setPrefWidth(150);tag.setId("paper-tag");
        Button search=button("查询论文","library-primary",()->load(1));search.setId("paper-search");keyword.setOnAction(e->load(1));year.setOnAction(e->load(1));
        tag.setOnAction(e->{if(!updatingTags)load(1);});
        Button external=button("站外检索","library-quiet",this::externalSearch);external.setId("paper-external");
        FlowPane toolbar=new FlowPane(10,10,keyword,year,tag,search,external);toolbar.getStyleClass().add("library-toolbar");
        if(manage){includeDisabled.setSelected(true);includeDisabled.setOnAction(e->load(1));toolbar.getChildren().add(includeDisabled);}
        column("标题",280,LibraryPaper::title);column("作者",160,LibraryPaper::authors);column("年份",70,LibraryPaper::year);column("关键词",180,LibraryPaper::keywords);column("来源",160,LibraryPaper::venue);
        TableColumn<LibraryPaper,String> file=column("PDF",90,p->p.hasPdf()?size(p.byteSize()):"未收录");
        if(manage)column("状态",75,p->p.enabled()?"已上架":"未上架");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);table.getStyleClass().add("library-table");table.setPrefHeight(390);table.setId("paper-table");
        table.getSelectionModel().selectedItemProperty().addListener((o,a,b)->showDetail(b));
        previous=button("上一页","library-quiet",()->load(page-1));next=button("下一页","library-quiet",()->load(page+1));paging.setAlignment(Pos.CENTER_RIGHT);
        download=button("下载 PDF","library-primary",this::downloadSelected);download.setId("paper-download");
        source=button("打开原文网页","library-quiet",this::sourceSelected);source.setId("paper-source");
        edit=button("编辑信息","library-quiet",()->editSelected(false));edit.setId("paper-edit");
        upload=button("上传/替换 PDF","library-quiet",this::uploadSelected);upload.setId("paper-upload");
        toggle=button("上架/下架","library-quiet",this::toggleSelected);toggle.setId("paper-toggle");
        progress.setMaxWidth(Double.MAX_VALUE);progress.setVisible(false);progress.setManaged(false);
        FlowPane actions=new FlowPane(8,8,download,source);if(manage)actions.getChildren().addAll(edit,upload,toggle);
        detail.getChildren().addAll(label("选择论文查看详情","library-section-title"),actions,progress);detail.setPadding(new Insets(18));detail.setStyle("-fx-background-color: white; -fx-background-radius: 10;");
        VBox body=new VBox(16,toolbar,table,paging,detail,status);body.getStyleClass().add("library-content");body.setFillWidth(true);
        if(manage){Button add=button("+ 新建论文","library-primary",()->editSelected(true));add.setId("paper-new");body.getChildren().add(0,row(label("论文资源管理","library-section-title"),space(),add));}
        else body.getChildren().add(0,label("查询校内收录论文；没有找到时可继续站外检索。","library-note"));
        setCenter(scroll(body));showDetail(null);
    }
    public void activate(){if(!closed)load(1);}
    private TableColumn<LibraryPaper,String> column(String title,double width,Function<LibraryPaper,String> value){
        TableColumn<LibraryPaper,String> c=new TableColumn<>(title);c.setPrefWidth(width);c.setCellValueFactory(x->new ReadOnlyStringWrapper(value.apply(x.getValue())));table.getColumns().add(c);return c;
    }
    private void load(int requested){
        if(closed||requested<1)return;String key=keyword.getText().trim(),yr=year.getText().trim(),selectedTag=selectedTag();boolean hidden=manage&&includeDisabled.isSelected();
        request(()->gateway.search(key,yr,selectedTag,requested,hidden),result->{page=result.page();pages=Math.max(1,(int)Math.ceil((double)result.total()/result.pageSize()));
            updateTags(result.keywords(),selectedTag);
            table.getItems().setAll(result.rows());previous.setDisable(page<=1);next.setDisable(page>=pages);
            paging.getChildren().setAll(label("第 "+page+" / "+pages+" 页 · 共 "+result.total()+" 篇","library-muted"),space(),previous,next);
            if(!result.rows().isEmpty()){LibraryPaper selected=result.rows().stream().filter(p->p.id()==selectAfterLoad).findFirst().orElse(result.rows().getFirst());table.getSelectionModel().select(selected);}else showEmpty(key);
            selectAfterLoad=0;
            String notice=noticeAfterLoad;noticeAfterLoad="";
            message(!notice.isBlank()?notice:result.rows().isEmpty()?"校内论文库没有匹配结果，可使用“站外检索”继续查找。":"",false);
        });
    }
    private String selectedTag(){String value=tag.getValue();return value==null||ALL_KEYWORDS.equals(value)?"":value.trim();}
    private void updateTags(List<String> values,String selected){
        updatingTags=true;try{tag.getItems().setAll(ALL_KEYWORDS);tag.getItems().addAll(values);tag.setValue(!selected.isBlank()&&values.contains(selected)?selected:ALL_KEYWORDS);}finally{updatingTags=false;}
    }
    void refreshAfterCreate(long paperId){
        keyword.clear();year.clear();updatingTags=true;try{tag.setValue(ALL_KEYWORDS);}finally{updatingTags=false;}if(manage)includeDisabled.setSelected(true);selectAfterLoad=paperId;load(1);
    }
    private void showEmpty(String key){
        detail.getChildren().setAll(label("没有找到匹配论文","library-section-title"),label(key.isBlank()?"可以输入标题、作者或关键词；也可以直接使用站外检索。":"关键词：“"+key+"”","library-muted"),
                button("在 Google Scholar 中检索","library-quiet",()->browse(PaperLinks.searches(key).get("Google Scholar"))),
                button("在 arXiv 中检索","library-quiet",()->browse(PaperLinks.searches(key).get("arXiv"))));
    }
    private void showDetail(LibraryPaper p){
        boolean selected=p!=null;download.setDisable(!selected||!p.hasPdf());source.setDisable(!selected||PaperLinks.original(p).isEmpty());edit.setDisable(!selected);upload.setDisable(!selected);toggle.setDisable(!selected);
        FlowPane actions=new FlowPane(8,8,download,source);if(manage)actions.getChildren().addAll(edit,upload,toggle);
        if(p==null){detail.getChildren().setAll(label("选择论文查看详情","library-section-title"),actions,progress);return;}
        String metadata=String.join(" · ",list(p.authors(),p.year(),p.venue()));
        detail.getChildren().setAll(label(p.title(),"library-section-title"),label(metadata,"library-muted"),
                label(p.abstractText().isBlank()?"暂无摘要":p.abstractText(),"library-description"),
                label(p.keywords().isBlank()?"关键词未填写":"关键词："+p.keywords(),"library-hint"),
                label(p.doi().isBlank()?"DOI 未填写":"DOI："+p.doi(),"library-hint"),
                label(p.rights().isBlank()?"来源与分享说明未填写":"来源与分享说明："+p.rights(),"library-note"),actions,progress);
    }
    private static List<String> list(String... values){return Arrays.stream(values).filter(v->v!=null&&!v.isBlank()).toList();}
    private void externalSearch(){String q=keyword.getText().trim();if(q.isBlank()){message("请先输入论文标题、作者或关键词",true);return;}browse(PaperLinks.searches(q).get("Google Scholar"));}
    private void sourceSelected(){var p=table.getSelectionModel().getSelectedItem();if(p!=null)PaperLinks.original(p).ifPresent(this::browse);}
    private void browse(String url){request(()->{if(!Desktop.isDesktopSupported()||!Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))throw new java.io.IOException("当前系统无法打开浏览器");Desktop.getDesktop().browse(URI.create(url));return true;},x->message("已在浏览器中打开",false));}
    private void downloadSelected(){
        var p=table.getSelectionModel().getSelectedItem();if(p==null||!p.hasPdf())return;
        FileChooser chooser=new FileChooser();chooser.setTitle("保存论文 PDF");chooser.setInitialFileName(PaperLinks.safeFilename(p));chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF 文件","*.pdf"));
        var file=chooser.showSaveDialog(getScene()==null?null:getScene().getWindow());if(file==null)return;transfer(()->gateway.download(p,file.toPath(),this::transferProgress),"PDF 已保存到 "+file.toPath());
    }
    private void uploadSelected(){
        var p=table.getSelectionModel().getSelectedItem();if(p==null)return;
        if(p.rights().isBlank()){message("请先编辑论文并填写来源与分享说明",true);return;}
        FileChooser chooser=new FileChooser();chooser.setTitle("选择论文 PDF");chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF 文件","*.pdf"));
        var file=chooser.showOpenDialog(getScene()==null?null:getScene().getWindow());if(file==null)return;transfer(()->gateway.upload(p.id(),file.toPath(),this::transferProgress),"PDF 上传完成");
    }
    private void transfer(Checked work,String ok){setProgress(true,0);request(()->{work.run();return true;},x->{setProgress(false,1);message(ok,false);load(page);});}
    private void transferProgress(double value){Platform.runLater(()->{if(!closed)setProgress(true,value);});}
    private void setProgress(boolean visible,double value){progress.setProgress(value);progress.setManaged(visible);progress.setVisible(visible);}
    private void toggleSelected(){var p=table.getSelectionModel().getSelectedItem();if(p==null)return;if(!p.enabled()&&!p.hasPdf()){message("请先上传 PDF，再上架论文",true);return;}
        request(()->{gateway.setEnabled(p.id(),!p.enabled());return true;},x->{message(p.enabled()?"论文已下架":"论文已上架",false);load(page);});}
    private void editSelected(boolean create){
        LibraryPaper p=create?null:table.getSelectionModel().getSelectedItem();if(!create&&p==null)return;
        Dialog<ButtonType> dialog=new Dialog<>();dialog.setTitle(create?"新建论文":"编辑论文");dialog.setHeaderText(create?"填写论文信息，并可直接选择 PDF":"修改论文信息");
        if(getScene()!=null)dialog.initOwner(getScene().getWindow());dialog.getDialogPane().getStylesheets().addAll(getStylesheets());
        TextField title=field(p==null?"":p.title()),authors=field(p==null?"":p.authors()),yr=field(p==null?"":p.year()),venue=field(p==null?"":p.venue()),doi=field(p==null?"":p.doi()),url=field(p==null?"":p.sourceUrl());
        TextArea keywords=new TextArea(p==null?"":p.keywords()),summary=new TextArea(p==null?"":p.abstractText()),rights=new TextArea(p==null?"":p.rights());keywords.setPromptText("用逗号、分号、顿号或换行分隔，最多 12 个");keywords.setPrefRowCount(2);keywords.setWrapText(true);summary.setPrefRowCount(3);summary.setWrapText(true);rights.setPrefRowCount(2);rights.setWrapText(true);
        var selectedPdf=new java.util.concurrent.atomic.AtomicReference<Path>();TextField pdfName=field("");pdfName.setEditable(false);pdfName.setPromptText("尚未选择 PDF");
        Button choosePdf=button("选择 PDF","library-quiet",()->{FileChooser chooser=new FileChooser();chooser.setTitle("选择论文 PDF");chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF 文件","*.pdf"));var file=chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());if(file!=null){selectedPdf.set(file.toPath());pdfName.setText(file.getName());}});choosePdf.setId("paper-form-pdf");
        GridPane form=new GridPane();form.setHgap(10);form.setVgap(10);form.setPadding(new Insets(5));int r=0;
        add(form,r++,"标题 *",title);add(form,r++,"作者 *",authors);add(form,r++,"年份",yr);add(form,r++,"期刊/会议",venue);add(form,r++,"关键词（可填写多个）",keywords);add(form,r++,"DOI",doi);add(form,r++,"原文网址",url);add(form,r++,"摘要",summary);add(form,r++,"来源与分享说明",rights);if(create)add(form,r++,"论文 PDF（可选）",row(pdfName,choosePdf));
        Label formError=label("","library-error");formError.setWrapText(true);form.add(formError,0,r,2,1);
        dialog.getDialogPane().setContent(new ScrollPane(form));dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
        Button ok=(Button)dialog.getDialogPane().lookupButton(ButtonType.OK);ok.addEventFilter(ActionEvent.ACTION,e->{String error="";try{PaperKeywords.normalize(keywords.getText());}catch(IllegalArgumentException failure){error=failure.getMessage();}if(title.getText().isBlank())error="请填写论文标题";else if(authors.getText().isBlank())error="请填写论文作者";else if(selectedPdf.get()!=null&&rights.getText().isBlank())error="直接上传 PDF 时必须填写来源与分享说明";if(!error.isBlank()){formError.setText(error);e.consume();}});dialogs.add(dialog);
        try{if(dialog.showAndWait().filter(ButtonType.OK::equals).isEmpty()||closed)return;}finally{dialogs.remove(dialog);}
        String normalizedKeywords;try{normalizedKeywords=PaperKeywords.normalize(keywords.getText());}catch(IllegalArgumentException e){message(e.getMessage(),true);return;}
        Map<String,String> data=new LinkedHashMap<>();data.put("title",title.getText().trim());data.put("authors",authors.getText().trim());data.put("year",yr.getText().trim());data.put("venue",venue.getText().trim());data.put("keywords",normalizedKeywords);data.put("doi",doi.getText().trim());data.put("sourceUrl",url.getText().trim());data.put("abstract",summary.getText().trim());data.put("rights",rights.getText().trim());
        savePaper(p==null?0:p.id(),data,selectedPdf.get(),create);
    }
    void savePaper(long id,Map<String,String> data,Path pdf,boolean create){
        if(pdf!=null)setProgress(true,0);request(()->{long saved=gateway.save(id,data);if(pdf==null)return new SaveResult(saved,"");try{gateway.upload(saved,pdf,this::transferProgress);return new SaveResult(saved,"");}catch(java.io.IOException error){return new SaveResult(saved,Objects.requireNonNullElse(error.getMessage(),"上传失败"));}},result->{
            setProgress(false,1);if(create){noticeAfterLoad=result.uploadError().isBlank()?(pdf==null?"新论文已保存为未上架，并已显示在列表中。":"新论文及 PDF 已保存，并已显示在列表中。"):("论文信息已保存，但 PDF 上传失败："+result.uploadError()+"。记录已显示，可选中后重试。");refreshAfterCreate(result.id());}
            else{noticeAfterLoad="论文信息已保存";load(page);}
        });
    }
    private static TextField field(String value){var f=new TextField(value);f.setPrefWidth(480);return f;}
    private static void add(GridPane g,int row,String label,Node c){g.add(new Label(label),0,row);g.add(c,1,row);GridPane.setHgrow(c,Priority.ALWAYS);}
    private <T>void request(Callable<T> work,Consumer<T> success){
        if(closed)return;long version=++generation;if(pending!=null)pending.cancel(true);setDisable(true);message("正在处理，请稍候…",false);
        FutureTask<T> task=new FutureTask<>(work){@Override protected void done(){if(isCancelled())return;Platform.runLater(()->{if(closed||version!=generation)return;setDisable(false);try{success.accept(get());}catch(Exception e){Throwable c=e;while(c.getCause()!=null)c=c.getCause();message(Objects.requireNonNullElse(c.getMessage(),"操作失败"),true);}});}};pending=task;
        try{executor.execute(task);}catch(RejectedExecutionException e){setDisable(false);message("论文服务已关闭，请重新进入",true);}
    }
    private void message(String text,boolean error){status.setText(text);status.setManaged(!text.isBlank());status.setVisible(!text.isBlank());status.getStyleClass().remove("library-error");if(error)status.getStyleClass().add("library-error");}
    private static String size(long bytes){return bytes>=1024*1024?String.format(Locale.ROOT,"%.1f MB",bytes/1024d/1024):Math.max(1,bytes/1024)+" KB";}
    @Override public void close(){if(closed)return;closed=true;++generation;if(pending!=null)pending.cancel(true);for(var d:List.copyOf(dialogs))d.close();dialogs.clear();}
    private record SaveResult(long id,String uploadError){}
    @FunctionalInterface private interface Checked{void run()throws Exception;}
}
