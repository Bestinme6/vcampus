package com.vcampus.client.fx.library;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.client.ui.LibraryViewData;
import com.vcampus.client.ui.LibraryViewData.*;
import com.vcampus.common.model.*;
import com.vcampus.common.protocol.ResponseMessage;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/** Library staff workspace. All UI state is confined to the FX thread. */
public final class LibraryAdminView extends BorderPane implements AutoCloseable {
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final VCampusClient client;
    private final String token;
    private final Executor executor;
    private final Runnable onChanged;
    private final Label heading=label("馆藏管理","library-title");
    private final Label subtitle=label("维护书目与实体馆藏，让每一本书的流转清晰可查。","library-muted");
    private final Label status=label("请选择管理业务","library-status");
    private final Label receipt=label("","library-status");
    private final VBox content=new VBox(14);
    private final ToggleGroup managementNavigation=new ToggleGroup();
    private final ToggleButton inventoryNavigation=managementTab("library-admin-nav-inventory","馆藏管理","inventory");
    private final ToggleButton circulationNavigation=managementTab("library-admin-nav-circulation","借还办理","circulation");
    private final ToggleButton loansNavigation=managementTab("library-admin-nav-loans","借阅查询","loans");
    private final Set<Dialog<?>> dialogs=new HashSet<>();
    private volatile boolean closed;
    private volatile long requestVersion;
    private long viewVersion;
    private boolean readBusy,mutationBusy;
    private String activeTab="",inventoryTab="books";

    private final TableView<LibraryData.Book> books=table("library-admin-books");
    private final TableView<CopyRow> copies=table("library-admin-copies");
    private final TableView<LoanRow> loans=table("library-admin-loans");
    private final TextField bookKeyword=field("library-admin-book-keyword","书名、作者、ISBN 或书目编号");
    private final TextField category=field("library-admin-category","全部分类（输入分类筛选）");
    private final ComboBox<LibrarySort> sort=combo("library-admin-sort",LibrarySort.values(),LibraryAdminView::sortLabel);
    private final TextField copyKeyword=field("library-admin-copy-keyword","搜索条码或书名");
    private final TextField loanKeyword=field("library-admin-loan-keyword","用户名、图书或条码");
    private final ComboBox<String> activeFilter=combo("library-admin-active",new String[]{"全部借阅状态","借阅中","已归还"},Function.identity());
    private final ComboBox<String> overdueFilter=combo("library-admin-overdue",new String[]{"全部逾期状态","已逾期","未逾期"},Function.identity());
    private final Pager bookPager=new Pager(this::loadBooks),copyPager=new Pager(this::loadCopies),loanPager=new Pager(this::loadLoans);
    private Button editBook,addCopy,toggleBook,changeCopyStatus;

    private final TextField username=field("library-admin-username","借阅人用户名");
    private final TextField barcode=field("library-admin-barcode","扫描或输入馆藏条码");
    private final TextField returnReason=field("library-admin-return-reason","破损或遗失归还必须填写原因");
    private final ComboBox<LibraryCirculationOperation> operation=combo("library-admin-operation",LibraryCirculationOperation.values(),v->v==LibraryCirculationOperation.BORROW?"办理借阅":"办理归还");
    private final ComboBox<LibraryReturnCondition> condition=combo("library-admin-condition",LibraryReturnCondition.values(),LibraryAdminView::conditionLabel);
    private final VBox previewCard=new VBox(12);
    private final Button execute=button("library-admin-execute","确认办理","library-primary",this::executeCirculation);
    private BorrowerPreview preview;
    private long previewVersion;
    private FlowPane returnOptions;

    public LibraryAdminView(VCampusClient client,String token,Executor executor,Runnable onChanged) {
        requireFx();
        this.client=Objects.requireNonNull(client);this.token=Objects.requireNonNull(token);
        this.executor=Objects.requireNonNull(executor);this.onChanged=Objects.requireNonNull(onChanged);
        getStyleClass().add("library-admin");setId("library-admin");
        setPadding(new Insets(20));setStyle("-fx-background-color: #f5f8fd;");
        heading.setStyle("-fx-font-size: 25px; -fx-font-weight: bold; -fx-text-fill: #172b4d;");
        subtitle.setWrapText(true);status.setWrapText(true);status.setId("library-admin-status");
        receipt.setWrapText(true);receipt.setId("library-admin-receipt");receipt.setVisible(false);receipt.setManaged(false);
        HBox managementTabs=new HBox(8,inventoryNavigation,circulationNavigation,loansNavigation);
        managementTabs.getStyleClass().add("library-admin-navigation");
        VBox header=new VBox(7,heading,subtitle,managementTabs);header.setSpacing(10);header.setPadding(new Insets(0,0,18,0));setTop(header);
        content.setFillWidth(true);content.setPadding(new Insets(18));
        content.setStyle("-fx-background-color: white; -fx-background-radius: 12;");
        ScrollPane scroll=new ScrollPane(content);scroll.setFitToWidth(true);scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        setCenter(scroll);
        VBox footer=new VBox(5,receipt,status);footer.setPadding(new Insets(12,0,0,0));setBottom(footer);
        configureTables();
        sort.setValue(LibrarySort.CODE_ASC);
        username.textProperty().addListener((o,a,b)->invalidatePreview());
        barcode.textProperty().addListener((o,a,b)->invalidatePreview());
        operation.valueProperty().addListener((o,a,b)->{invalidatePreview();updateReturnOptions();});
        execute.setDisable(true);
    }

    /** Does not fetch other pages. The host owns navigation and this view owns selected-page loading. */
    public void activate(String tab) {
        requireFx();if(closed)return;
        String next=Set.of("inventory","circulation","loans").contains(tab)?tab:"inventory";
        switch(next){case "circulation"->circulationNavigation.setSelected(true);case "loans"->loansNavigation.setSelected(true);default->inventoryNavigation.setSelected(true);}
        activeTab=next;viewVersion++;requestVersion++;readBusy=false;invalidatePreview();
        content.getChildren().clear();
        switch(next) {
            case "circulation" -> {heading.setText("借还办理");subtitle.setText("先校验借阅人和馆藏，再确认办理。异常归还会暂停馆藏流通。");showCirculation();}
            case "loans" -> {heading.setText("借阅查询");subtitle.setText("查询全馆借阅记录、归还情况与逾期状态。");showLoans();}
            default -> {heading.setText("馆藏管理");subtitle.setText("维护书目与实体馆藏，让每一本书的流转清晰可查。");showInventory();}
        }
        updateBusy();loadActive();
    }

    private void configureTables() {
        column(books,"书目编号",125,x->x.book().catalogCode());column(books,"书名",220,x->x.book().title());
        column(books,"作者",150,x->x.book().authors());column(books,"ISBN",145,x->dash(x.book().isbn()));
        column(books,"分类",100,x->x.book().category());column(books,"启用",65,x->x.book().enabled()?"启用":"停用");
        column(books,"可借 / 总藏",105,x->x.book().availableCopies()+" / "+x.book().totalCopies());
        column(books,"借出",65,x->x.onLoanCopies()<0?"—":Integer.toString(x.onLoanCopies()));
        column(books,"借阅次数",85,x->x.borrowCount()<0?"—":Long.toString(x.borrowCount()));
        books.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        books.getSelectionModel().selectedItemProperty().addListener((o,a,b)->updateSelection());
        column(copies,"馆藏 ID",85,x->Long.toString(x.copyId()));column(copies,"条码",160,CopyRow::barcode);
        column(copies,"书名",220,CopyRow::title);column(copies,"书架位置",135,CopyRow::shelfLocation);
        column(copies,"状态",85,x->statusLabel(x.status()));column(copies,"状态原因",190,x->dash(x.statusReason()));
        column(copies,"更新时间",150,x->date(x.updatedAt()));
        copies.getSelectionModel().selectedItemProperty().addListener((o,a,b)->updateSelection());
        column(loans,"借阅人",155,x->x.borrowerDisplayName()+" / "+x.borrowerUsername());
        column(loans,"图书",200,LoanRow::title);column(loans,"条码",145,LoanRow::barcode);
        column(loans,"借阅时间",150,x->date(x.borrowedAt()));column(loans,"应还时间",150,x->date(x.dueAt()));
        column(loans,"归还时间",150,x->date(x.returnedAt()));column(loans,"续借",60,x->Integer.toString(x.renewalCount()));
        column(loans,"渠道",85,x->x.channel()==LibraryLoanChannel.SELF_SERVICE?"自助":"馆员办理");
        column(loans,"状态",105,x->x.returnedAt()!=null?"已归还 · "+conditionLabel(x.returnCondition()):x.overdue()?"已逾期":"借阅中");
    }

    private void showInventory() {
        ToggleGroup group=new ToggleGroup();
        ToggleButton catalog=new ToggleButton("书目管理"),physical=new ToggleButton("实体馆藏");
        catalog.setToggleGroup(group);physical.setToggleGroup(group);
        catalog.getStyleClass().add("library-quiet");physical.getStyleClass().add("library-quiet");
        catalog.setId("library-admin-catalog-tab");physical.setId("library-admin-copies-tab");
        catalog.setSelected(inventoryTab.equals("books"));physical.setSelected(inventoryTab.equals("copies"));
        catalog.setOnAction(e->selectInventory("books"));physical.setOnAction(e->selectInventory("copies"));
        content.getChildren().add(new HBox(8,catalog,physical));
        if(inventoryTab.equals("copies"))showCopies();else showBooks();
    }

    private void selectInventory(String tab) {
        if(closed||mutationBusy)return;inventoryTab=tab;activate("inventory");
    }

    private void showBooks() {
        Button search=button("library-admin-book-search","查询","library-primary",()->{bookPager.page=1;loadBooks();});
        bookKeyword.setOnAction(e->search.fire());category.setOnAction(e->search.fire());
        bookKeyword.setPrefWidth(280);category.setPrefWidth(200);sort.setPrefWidth(180);
        content.getChildren().add(toolbar(bookKeyword,category,sort,search));
        Button create=button("library-admin-book-create","＋ 新建书目","library-primary",()->bookDialog(null));
        addCopy=button("library-admin-copy-create","新增馆藏","library-quiet",()->{var selected=books.getSelectionModel().getSelectedItem();if(selected!=null)copyDialog(selected.book());});
        editBook=button("library-admin-book-edit","编辑书目","library-quiet",()->{var selected=books.getSelectionModel().getSelectedItem();if(selected!=null)bookDialog(selected.book());});
        toggleBook=button("library-admin-book-toggle","启用 / 停用","library-quiet",this::toggleBook);
        content.getChildren().addAll(toolbar(create,addCopy,editBook,toggleBook),books,bookPager.view);
        VBox.setVgrow(books,Priority.ALWAYS);updateSelection();
    }

    private void showCopies() {
        Button search=button("library-admin-copy-search","查询","library-primary",()->{copyPager.page=1;loadCopies();});
        copyKeyword.setOnAction(e->search.fire());copyKeyword.setPrefWidth(320);
        changeCopyStatus=button("library-admin-copy-status","变更状态","library-quiet",this::copyStatusDialog);
        content.getChildren().addAll(toolbar(copyKeyword,search,changeCopyStatus),
                label("新增馆藏：请在「书目管理」中选中书目并点击「新增馆藏」。借出中的馆藏须通过归还办理变更状态。","library-muted"),copies,copyPager.view);
        VBox.setVgrow(copies,Priority.ALWAYS);updateSelection();
    }

    private void showLoans() {
        Button search=button("library-admin-loan-search","查询","library-primary",()->{loanPager.page=1;loadLoans();});
        loanKeyword.setOnAction(e->search.fire());loanKeyword.setPrefWidth(300);
        content.getChildren().addAll(toolbar(loanKeyword,activeFilter,overdueFilter,search),loans,loanPager.view);
        VBox.setVgrow(loans,Priority.ALWAYS);
    }

    private void showCirculation() {
        username.setPrefWidth(220);barcode.setPrefWidth(260);
        Button check=button("library-admin-preview","校验信息","library-primary",this::loadPreview);
        username.setOnAction(e->check.fire());barcode.setOnAction(e->check.fire());
        content.getChildren().addAll(toolbar(label("借阅人","library-muted"),username,label("条码","library-muted"),barcode,operation,check));
        previewCard.setMinHeight(240);previewCard.setPadding(new Insets(22));
        previewCard.setStyle("-fx-background-color: #f0f6ff; -fx-background-radius: 12;");
        showPreviewHint();
        returnOptions=toolbar(label("归还状态","library-muted"),condition,label("原因","library-muted"),returnReason);
        returnReason.setPrefWidth(360);
        content.getChildren().addAll(previewCard,returnOptions,execute);
        VBox.setVgrow(previewCard,Priority.ALWAYS);updateReturnOptions();
    }

    private void loadActive() {
        if(closed||mutationBusy)return;
        switch(activeTab) {
            case "inventory" -> {if(inventoryTab.equals("copies"))loadCopies();else loadBooks();}
            case "loans" -> loadLoans();
            case "circulation" -> setStatus("请输入借阅人和条码，点击「校验信息」。",false);
            default -> { }
        }
    }

    private void loadBooks() {
        if(!activeTab.equals("inventory")||!inventoryTab.equals("books"))return;
        String keyword=bookKeyword.getText().trim(),cat=category.getText().trim();
        int page=bookPager.page;LibrarySort order=sort.getValue();
        query(()->client.searchLibraryCatalog(token,keyword,cat,page,true,order),response->{
            var data=LibraryData.books(response);books.getItems().setAll(data.rows());
            bookPager.show(data.page(),data.pageSize(),data.total());updateSelection();
            setStatus(data.total()==0?"未找到符合条件的书目，可修改关键词或分类重试。":"共 "+data.total()+" 个书目 · 排序作用于全部检索结果",false);
        });
    }

    private void loadCopies() {
        if(!activeTab.equals("inventory")||!inventoryTab.equals("copies"))return;
        Map<String,String> filters=Map.of("keyword",copyKeyword.getText().trim(),"page",Integer.toString(copyPager.page),"newestFirst","true");
        query(()->client.searchLibraryCopies(token,filters),response->{
            var page=LibraryViewData.copyPage(response);copies.getItems().setAll(page.rows());
            copyPager.show(page.page(),page.pageSize(),page.total());updateSelection();setStatus("共 "+page.total()+" 条实体馆藏",false);
        });
    }

    private void loadLoans() {
        if(!activeTab.equals("loans"))return;
        Map<String,String> filters=new LinkedHashMap<>();filters.put("keyword",loanKeyword.getText().trim());filters.put("page",Integer.toString(loanPager.page));
        if(activeFilter.getSelectionModel().getSelectedIndex()>0)filters.put("active",Boolean.toString(activeFilter.getSelectionModel().getSelectedIndex()==1));
        if(overdueFilter.getSelectionModel().getSelectedIndex()>0)filters.put("overdue",Boolean.toString(overdueFilter.getSelectionModel().getSelectedIndex()==1));
        query(()->client.searchLibraryLoans(token,Map.copyOf(filters)),response->{
            var page=LibraryViewData.loanPage(response);loans.getItems().setAll(page.rows());
            loanPager.show(page.page(),page.pageSize(),page.total());setStatus("共 "+page.total()+" 条借阅记录",false);
        });
    }

    private void invalidatePreview() {
        preview=null;previewVersion++;execute.setDisable(true);
        if(activeTab.equals("circulation"))showPreviewHint();
    }

    private void showPreviewHint() {
        previewCard.getChildren().setAll(label("等待校验","library-title"),
                label("校验后将在这里显示借阅人身份、借阅额度、馆藏状态及办理条件。","library-muted"));
    }

    private void updateReturnOptions() {
        boolean returning=operation.getValue()==LibraryCirculationOperation.RETURN;
        if(returnOptions!=null){returnOptions.setVisible(returning);returnOptions.setManaged(returning);}
        execute.setText(returning?"确认办理归还":"确认办理借阅");
    }

    private void loadPreview() {
        if(closed||mutationBusy||!activeTab.equals("circulation"))return;
        invalidatePreview();String user=username.getText().trim(),code=barcode.getText().trim();
        LibraryCirculationOperation op=operation.getValue();long version=previewVersion;
        if(user.isBlank()||code.isBlank()){setStatus("请填写借阅人用户名与馆藏条码。",true);return;}
        query(()->client.previewLibraryCirculation(token,user,code,op),response->{
            if(version!=previewVersion)return;
            preview=LibraryViewData.borrowerPreview(response);
            var identity=preview.baseIdentity()==UserRole.STUDENT?"学生":preview.baseIdentity()==UserRole.TEACHER?"教师":preview.baseIdentity().name();
            previewCard.getChildren().setAll(label(preview.displayName()+" · "+preview.username(),"library-title"),
                label("身份："+identity+"    当前借阅："+preview.activeLoans()+" / "+preview.maxLoans()+"    逾期："+(preview.overdue()?"是":"否"),"library-muted"),
                new Separator(),label("《"+preview.title()+"》","library-title"),
                label("馆藏条码："+preview.barcode()+"    状态："+statusLabel(preview.copyStatus()),"library-muted"),
                label(preview.message(),"library-status"));
            execute.setDisable(!preview.allowed());setStatus(preview.allowed()?"校验通过，请核对信息后确认办理。":preview.message(),!preview.allowed());
        });
    }

    private void executeCirculation() {
        if(closed||mutationBusy||preview==null||!preview.allowed())return;
        BorrowerPreview checked=preview;long previewAt=previewVersion;
        LibraryCirculationOperation op=operation.getValue();LibraryReturnCondition returned=condition.getValue();
        String reason=returnReason.getText().trim();
        if(op==LibraryCirculationOperation.RETURN&&returned!=LibraryReturnCondition.NORMAL&&reason.isBlank()){
            setStatus(conditionLabel(returned)+"归还必须填写原因。",true);return;
        }
        String message=op==LibraryCirculationOperation.BORROW?"确认替 "+checked.displayName()+"（"+checked.username()+"）借出《"+checked.title()+"》？":
            switch(returned){case NORMAL->"确认正常归还《"+checked.title()+"》？";case DAMAGED->"确认将《"+checked.title()+"》登记为破损并暂停流通？";case LOST->"确认将《"+checked.title()+"》登记为遗失并关闭借阅？";};
        if(!confirm("确认借还办理",message)||previewAt!=previewVersion||preview!=checked)return;
        invalidatePreview();
        mutate(()->op==LibraryCirculationOperation.BORROW?client.adminBorrowLibraryCopy(token,checked.username(),checked.barcode()):
                client.adminReturnLibraryCopy(token,checked.barcode(),returned,reason),response->{
            var result=LibraryViewData.receipt(response);
            showReceipt((op==LibraryCirculationOperation.BORROW?"借阅办理成功":"归还办理成功")+" · "+checked.barcode()+
                (result.dueAt()==null?"":" · 应还 "+date(result.dueAt())));
        });
    }

    private void bookDialog(CatalogRow book) {
        if(closed||mutationBusy)return;
        TextField isbn=field("library-admin-form-isbn","ISBN（选填）"),title=field("library-admin-form-title","书名"),
            authors=field("library-admin-form-authors","作者"),publisher=field("library-admin-form-publisher","出版社"),
            year=field("library-admin-form-year","出版年（选填）"),classification=field("library-admin-form-category","分类");
        TextArea description=new TextArea();description.setId("library-admin-form-description");description.setWrapText(true);description.setPrefRowCount(4);
        if(book!=null){isbn.setText(book.isbn());title.setText(book.title());authors.setText(book.authors());publisher.setText(book.publisher());
            year.setText(book.publishYear()==null?"":book.publishYear().toString());classification.setText(book.category());description.setText(book.description());}
        Label error=label("","library-status");error.setStyle("-fx-text-fill: #ba3434;");
        GridPane form=form();int row=0;
        if(book!=null)addRow(form,row++,"书目编号",label(book.catalogCode(),"library-muted"));
        addRow(form,row++,"ISBN（选填）",isbn);addRow(form,row++,"书名 *",title);addRow(form,row++,"作者 *",authors);
        addRow(form,row++,"出版社 *",publisher);addRow(form,row++,"出版年",year);addRow(form,row++,"分类 *",classification);addRow(form,row,"简介",description);
        Dialog<ButtonType> dialog=dialog(book==null?"新建书目":"编辑书目",new VBox(12,form,error));
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION,event->{
            if(title.getText().isBlank()||authors.getText().isBlank()||publisher.getText().isBlank()||classification.getText().isBlank()){
                error.setText("请填写书名、作者、出版社和分类。");event.consume();
            }else if(!year.getText().isBlank()&&!year.getText().trim().matches("[1-9][0-9]{3}")){
                error.setText("出版年应为四位数字，或留空。");event.consume();
            }
        });
        if(!showDialog(dialog))return;
        Map<String,String> values=new LinkedHashMap<>();values.put("isbn",isbn.getText().trim());values.put("title",title.getText().trim());
        values.put("authors",authors.getText().trim());values.put("publisher",publisher.getText().trim());values.put("publishYear",year.getText().trim());
        values.put("category",classification.getText().trim());values.put("description",description.getText().trim());
        if(book!=null)values.put("bookId",Long.toString(book.bookId()));
        mutate(()->book==null?client.createLibraryBook(token,Map.copyOf(values)):client.updateLibraryBook(token,Map.copyOf(values)),response->{
            if(book==null)bookPager.page=1;
            showReceipt(book==null?"书目创建成功 · 编号："+response.data().getOrDefault("catalogCode","—"):"书目更新成功");
        });
    }

    private void toggleBook() {
        var selected=books.getSelectionModel().getSelectedItem();if(selected==null||closed||mutationBusy)return;
        CatalogRow book=selected.book();boolean enabled=!book.enabled();
        if(!confirm(enabled?"启用书目":"停用书目","确认"+(enabled?"启用":"停用")+"《"+book.title()+"》？"+(!enabled?"停用后不可新增借阅，已有借阅仍可归还。":"")))return;
        mutate(()->client.setLibraryBookEnabled(token,book.bookId(),enabled),response->showReceipt("《"+book.title()+"》已"+(enabled?"启用":"停用")));
    }

    private void copyDialog(CatalogRow book) {
        if(closed||mutationBusy)return;
        TextField shelf=field("library-admin-form-shelf","例如：A 区 3 排 2 层");
        GridPane form=form();addRow(form,0,"书目",label(book.catalogCode()+" · "+book.title(),"library-title"));
        addRow(form,1,"馆藏条码",label("由服务器自动生成","library-muted"));addRow(form,2,"书架位置 *",shelf);
        Label error=label("","library-status");error.setStyle("-fx-text-fill: #ba3434;");
        Dialog<ButtonType> dialog=dialog("新增实体馆藏",new VBox(12,form,error));
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION,event->{
            if(shelf.getText().isBlank()){error.setText("请填写馆藏书架位置。");event.consume();}
        });
        if(!showDialog(dialog))return;
        Map<String,String> values=Map.of("bookId",Long.toString(book.bookId()),"barcode","","shelfLocation",shelf.getText().trim());
        mutate(()->client.createLibraryCopy(token,values),response->{copyPager.page=1;showReceipt("馆藏创建成功 · 条码："+response.data().getOrDefault("barcode","—"));});
    }

    private void copyStatusDialog() {
        CopyRow copy=copies.getSelectionModel().getSelectedItem();if(copy==null||closed||mutationBusy)return;
        if(copy.status()==LibraryCopyStatus.ON_LOAN){setStatus("借出中的馆藏不能手工变更状态，请通过借还办理归还。",true);return;}
        ComboBox<LibraryCopyStatus> next=combo("library-admin-form-copy-status",new LibraryCopyStatus[]{LibraryCopyStatus.AVAILABLE,LibraryCopyStatus.DAMAGED,LibraryCopyStatus.LOST,LibraryCopyStatus.WITHDRAWN},LibraryAdminView::statusLabel);
        next.setValue(copy.status());TextField reason=field("library-admin-form-copy-reason","除恢复可借外，必须填写变更原因");
        GridPane form=form();addRow(form,0,"馆藏",label(copy.barcode()+" · "+copy.title(),"library-muted"));
        addRow(form,1,"当前状态",label(statusLabel(copy.status()),"library-muted"));addRow(form,2,"新状态",next);addRow(form,3,"原因",reason);
        Label error=label("","library-status");error.setStyle("-fx-text-fill: #ba3434;");
        Dialog<ButtonType> dialog=dialog("变更馆藏状态",new VBox(12,form,error));
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION,event->{
            if(next.getValue()!=LibraryCopyStatus.AVAILABLE&&reason.getText().isBlank()){error.setText("该状态必须填写原因。");event.consume();}
        });
        if(!showDialog(dialog))return;
        LibraryCopyStatus value=next.getValue();String why=reason.getText().trim();
        mutate(()->client.setLibraryCopyStatus(token,copy.copyId(),value.name(),why),response->showReceipt(copy.barcode()+" 已更新为「"+statusLabel(value)+"」"));
    }

    private boolean confirm(String title,String message) {return showDialog(dialog(title,label(message,"library-muted")));}

    private Dialog<ButtonType> dialog(String title,Node form) {
        Dialog<ButtonType> dialog=new Dialog<>();dialog.setTitle(title);dialog.setHeaderText(title);
        if(getScene()!=null&&getScene().getWindow()!=null)dialog.initOwner(getScene().getWindow());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
        ((Button)dialog.getDialogPane().lookupButton(ButtonType.OK)).setText("确认");
        ((Button)dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("取消");
        ScrollPane scroll=new ScrollPane(form);scroll.setFitToWidth(true);scroll.setPrefViewportWidth(530);scroll.setMaxHeight(520);
        scroll.setStyle("-fx-background-color: white; -fx-background: white;");dialog.getDialogPane().setContent(scroll);
        if(getScene()!=null)dialog.getDialogPane().getStylesheets().addAll(getScene().getStylesheets());
        dialog.getDialogPane().getStyleClass().add("library-admin-dialog");dialog.setResizable(true);return dialog;
    }

    /** Nested modal event loops may process logout/navigation; never continue those stale forms. */
    private boolean showDialog(Dialog<ButtonType> dialog) {
        if(closed||mutationBusy)return false;long version=viewVersion;dialogs.add(dialog);
        try{return dialog.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK&&!closed&&!mutationBusy&&version==viewVersion;}
        finally{dialogs.remove(dialog);}
    }

    private void query(Request request,Consumer<ResponseMessage> success) {
        if(closed||mutationBusy)return;long version=++requestVersion;
        readBusy=true;updateBusy();setStatus("正在读取服务器数据…",false);
        submit(()->{
            if(closed||version!=requestVersion)return;
            ResponseMessage response=null;Exception error=null;
            try{response=request.call();LibraryData.require(response);}catch(Exception e){error=e;}
            ResponseMessage result=response;Exception failure=error;
            Platform.runLater(()->{
                if(closed||version!=requestVersion)return;
                readBusy=false;updateBusy();
                if(failure!=null){setStatus(errorMessage(failure),true);return;}
                try{success.accept(result);}catch(Exception e){setStatus(errorMessage(e),true);}
            });
        },()->{if(!closed&&version==requestVersion){readBusy=false;updateBusy();setStatus("请求未能启动，请稍后重试。",true);}});
    }

    private void mutate(Request request,Consumer<ResponseMessage> success) {
        if(closed||mutationBusy)return;mutationBusy=true;readBusy=false;requestVersion++;updateBusy();setStatus("正在提交，请勿重复操作…",false);
        submit(()->{
            if(closed)return;ResponseMessage response=null;Exception error=null;
            try{response=request.call();LibraryData.require(response);}catch(Exception e){error=e;}
            ResponseMessage result=response;Exception failure=error;
            Platform.runLater(()->{
                if(closed)return;mutationBusy=false;updateBusy();
                if(failure!=null){setStatus(errorMessage(failure),true);return;}
                try{success.accept(result);}catch(Exception e){setStatus(errorMessage(e),true);return;}
                onChanged.run();if(!closed)loadActive();
            });
        },()->{if(!closed){mutationBusy=false;updateBusy();setStatus("请求未能启动，请稍后重试。",true);}});
    }

    private void submit(Runnable work,Runnable rejected) {
        try{executor.execute(()->{if(Platform.isFxApplicationThread())Thread.ofVirtual().start(work);else work.run();});}
        catch(RuntimeException e){rejected.run();}
    }

    private void updateBusy() {content.setDisable(readBusy||mutationBusy||closed);updateSelection();}
    private void updateSelection() {
        boolean noBook=books.getSelectionModel().getSelectedItem()==null;
        if(editBook!=null)editBook.setDisable(noBook);if(addCopy!=null)addCopy.setDisable(noBook);
        if(toggleBook!=null){toggleBook.setDisable(noBook);var selected=books.getSelectionModel().getSelectedItem();toggleBook.setText(selected==null?"启用 / 停用":selected.book().enabled()?"停用书目":"启用书目");}
        if(changeCopyStatus!=null){var copy=copies.getSelectionModel().getSelectedItem();changeCopyStatus.setDisable(copy==null||copy.status()==LibraryCopyStatus.ON_LOAN);}
    }
    private void setStatus(String message,boolean error) {status.setText(message);status.setStyle(error?"-fx-text-fill: #ba3434;":"-fx-text-fill: #52637a;");}
    private void showReceipt(String message){receipt.setText(message);receipt.setStyle("-fx-text-fill: #126d55;");receipt.setVisible(true);receipt.setManaged(true);}

    @Override public void close() {
        requireFx();if(closed)return;closed=true;requestVersion++;viewVersion++;previewVersion++;preview=null;
        for(Dialog<?> dialog:List.copyOf(dialogs))dialog.close();dialogs.clear();setDisable(true);execute.setDisable(true);
    }

    private final class Pager {
        int page=1,pages=1;final Label label=label("尚未加载","library-muted");final Button previous,next;final HBox view;
        Pager(Runnable refresh){previous=button(null,"上一页","library-quiet",()->{if(page>1){page--;refresh.run();}});
            next=button(null,"下一页","library-quiet",()->{if(page<pages){page++;refresh.run();}});
            previous.setDisable(true);next.setDisable(true);view=new HBox(12,previous,label,next);view.setAlignment(Pos.CENTER_LEFT);}
        void show(int current,int pageSize,int total){page=current;pages=Math.max(1,(int)((total+(long)pageSize-1)/pageSize));
            label.setText("第 "+page+" / "+pages+" 页 · 共 "+total+" 条");previous.setDisable(page<=1);next.setDisable(page>=pages);}
    }
    @FunctionalInterface private interface Request {ResponseMessage call()throws Exception;}
    private static void requireFx(){if(!Platform.isFxApplicationThread())throw new IllegalStateException("Library UI must be accessed on the JavaFX thread");}
    private static String errorMessage(Exception error){return error instanceof java.io.IOException?"无法连接服务器，请检查网络后重试。":error.getMessage()==null?"操作未完成，请重试。":error.getMessage();}
    private static String date(Instant value){return value==null?"—":DATE.format(value);}
    private static String dash(String value){return value==null||value.isBlank()?"—":value;}
    private static String conditionLabel(LibraryReturnCondition condition){return condition==null?"正常":switch(condition){case NORMAL->"正常";case DAMAGED->"破损";case LOST->"遗失";};}
    private static String statusLabel(LibraryCopyStatus value){return switch(value){case AVAILABLE->"可借";case ON_LOAN->"借出中";case DAMAGED->"破损";case LOST->"遗失";case WITHDRAWN->"下架";};}
    private static String sortLabel(LibrarySort value){return switch(value){case CODE_ASC->"书目编号 ↑";case CODE_DESC->"书目编号 ↓";case TITLE_ASC->"书名 ↑";case TITLE_DESC->"书名 ↓";case CATEGORY_ASC->"分类 ↑";case CATEGORY_DESC->"分类 ↓";case BORROW_COUNT_ASC->"借阅次数 ↑";case BORROW_COUNT_DESC->"借阅次数 ↓";};}
    private static Label label(String text,String style){Label label=new Label(text);label.getStyleClass().add(style);label.setWrapText(true);return label;}
    private static TextField field(String id,String prompt){TextField field=new TextField();field.setId(id);field.setPromptText(prompt);field.getStyleClass().add("library-field");field.setMinWidth(150);return field;}
    private static Button button(String id,String text,String style,Runnable action){Button button=new Button(text);button.setId(id);button.getStyleClass().add(style);button.setMinWidth(Region.USE_PREF_SIZE);button.setOnAction(e->action.run());return button;}
    private ToggleButton managementTab(String id,String text,String route){ToggleButton button=new ToggleButton(text);button.setId(id);button.setToggleGroup(managementNavigation);button.getStyleClass().add("library-tab");button.setOnAction(e->{if(!closed)activate(route);});return button;}
    private static FlowPane toolbar(Node...nodes){FlowPane bar=new FlowPane(10,10,nodes);bar.setAlignment(Pos.CENTER_LEFT);bar.getStyleClass().add("library-toolbar");return bar;}
    private static <T>ComboBox<T> combo(String id,T[] values,Function<T,String> label){ComboBox<T> combo=new ComboBox<>();combo.setId(id);combo.getItems().addAll(values);combo.getSelectionModel().selectFirst();
        combo.getStyleClass().add("library-field");combo.setConverter(new StringConverter<>(){public String toString(T value){return value==null?"":label.apply(value);}public T fromString(String text){throw new UnsupportedOperationException();}});return combo;}
    private static <T>TableView<T> table(String id){TableView<T> table=new TableView<>();table.setId(id);table.getStyleClass().add("library-table");table.setPlaceholder(label("暂无记录","library-muted"));table.setMinHeight(260);table.setPrefHeight(440);table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);return table;}
    private static <T>void column(TableView<T> table,String title,double width,Function<T,String> value){TableColumn<T,String> column=new TableColumn<>(title);column.setCellValueFactory(data->new ReadOnlyStringWrapper(value.apply(data.getValue())));column.setPrefWidth(width);column.setSortable(false);table.getColumns().add(column);}
    private static GridPane form(){GridPane form=new GridPane();form.setHgap(16);form.setVgap(12);form.setPadding(new Insets(14));ColumnConstraints label=new ColumnConstraints();label.setMinWidth(90);ColumnConstraints input=new ColumnConstraints();input.setHgrow(Priority.ALWAYS);form.getColumnConstraints().addAll(label,input);return form;}
    private static void addRow(GridPane grid,int row,String title,Node input){grid.add(label(title,"library-muted"),0,row);grid.add(input,1,row);GridPane.setHgrow(input,Priority.ALWAYS);if(input instanceof Region region)region.setMaxWidth(Double.MAX_VALUE);}
}
