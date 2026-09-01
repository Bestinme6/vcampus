package com.vcampus.client.fx.library;

import com.vcampus.client.ui.LibraryViewData;
import com.vcampus.common.model.*;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;
import org.kordamp.ikonli.feather.Feather;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.vcampus.client.fx.library.LibraryUi.*;

final class LibraryView extends BorderPane {
    interface Listener {
        void open(String route);void back();void search(String keyword,String category,LibrarySort sort,int page);
        void detail(long id);void borrow(LibraryData.Book item);void reserve(LibraryData.Book item);
        void loans(String scope,int page);void reservations(String status,int page);
        void renew(LibraryViewData.LoanRow loan);void returnLoan(LibraryViewData.LoanRow loan);void cancel(LibraryData.Reservation value);
    }
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneId.of("Asia/Shanghai"));
    private final Listener listener;
    private final Set<UserRole> roles;
    private final HBox navigation=new HBox(8);
    private final Map<String,Button> tabs=new LinkedHashMap<>();
    private final Label status=label("","library-status");
    private final TextField keyword=new TextField(),category=new TextField();
    private final ComboBox<LibrarySort> sort=new ComboBox<>();
    private final FlowPane filters=new FlowPane(10,10);
    private final TilePane grid=new TilePane(18,18);
    private final VBox catalog=new VBox(22);
    private final HBox catalogPager=new HBox(10);
    private final ScrollPane catalogScroll;
    private final Label catalogCount=label("馆藏目录","library-section-title");
    private String loanScope="active",reservationScope="";

    LibraryView(Set<UserRole> roles,Listener listener){
        this.roles=Set.copyOf(roles);this.listener=listener;setId("library-view");getStyleClass().add("library-root");
        getStylesheets().add(Objects.requireNonNull(getClass().getResource("library.css")).toExternalForm());
        Label brand=label("图书馆","library-title");
        Label caption=label("VCAMPUS  /  LIBRARY","library-eyebrow");
        Button back=button("返回工作台","library-quiet",listener::back);back.setGraphic(icon(Feather.ARROW_LEFT,16));
        VBox header=new VBox(18,row(new VBox(5,caption,brand),space(),back),navigation,status);
        header.getStyleClass().add("library-header");status.setManaged(false);status.setVisible(false);
        if(LibraryAccessPolicy.canBorrow(roles)){addTab("catalog","图书检索");addTab("loans","我的借阅");}
        if(roles.contains(UserRole.STUDENT))addTab("reservations","我的预约");
        if(LibraryAccessPolicy.canManage(roles))addTab("admin","管理工作台");
        setTop(header);
        VBox heroText=new VBox(9,label("在书页之间，发现新的可能。","library-hero-title"),label("检索校园馆藏，让下一本好书触手可及。","library-muted"));
        HBox hero=row(heroText,space(),icon(Feather.BOOK_OPEN,48));hero.getStyleClass().add("library-hero");
        keyword.setPromptText("书名、作者、ISBN 或书目编号");keyword.setPrefWidth(300);keyword.setId("library-keyword");
        category.setPromptText("分类筛选");category.setPrefWidth(135);category.setId("library-category");
        sort.getItems().setAll(LibrarySort.values());sort.setValue(LibrarySort.CODE_ASC);sort.setPrefWidth(155);sort.setId("library-sort");
        sort.setConverter(new StringConverter<>(){public String toString(LibrarySort value){return value==null?"":sortLabel(value);}public LibrarySort fromString(String s){throw new UnsupportedOperationException();}});
        Button search=button("搜索馆藏","library-primary",()->search(1));search.setGraphic(icon(Feather.SEARCH,16));search.setId("library-search");
        keyword.setOnAction(e->search(1));category.setOnAction(e->search(1));sort.setOnAction(e->search(1));
        filters.getChildren().addAll(keyword,category,sort,search);filters.getStyleClass().add("library-toolbar");
        grid.setAlignment(Pos.TOP_LEFT);grid.setTileAlignment(Pos.TOP_LEFT);grid.setPrefColumns(3);
        grid.prefTileWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(()->{
            double width=Math.max(620,grid.getWidth());int columns=width>=1020?3:2;return (width-(columns-1)*18)/columns;
        },grid.widthProperty()));
        catalog.getStyleClass().add("library-content");catalog.getChildren().addAll(hero,filters,row(catalogCount,space(),label("分类封面 · 非出版社封面","library-hint")),grid,catalogPager);
        catalogScroll=scroll(catalog);
    }
    private void addTab(String route,String text){Button b=button(text,"library-tab",()->listener.open(route));b.setId("library-tab-"+route);tabs.put(route,b);navigation.getChildren().add(b);}
    void select(String route){tabs.forEach((key,b)->{b.getStyleClass().remove("library-tab-selected");if(key.equals(route))b.getStyleClass().add("library-tab-selected");});}
    void search(int page){listener.search(keyword.getText().trim(),category.getText().trim(),sort.getValue(),page);}
    void busy(boolean value){navigation.setDisable(value);if(getCenter()!=null)getCenter().setDisable(value);}
    void status(String text,boolean error){status.setText(text);status.setManaged(!text.isBlank());status.setVisible(!text.isBlank());status.getStyleClass().remove("library-error");if(error)status.getStyleClass().add("library-error");}
    void showLoading(){setCenter(scroll(empty("正在读取馆藏…","请稍候，正在连接校园图书馆。")));}
    void showFailure(String message,Runnable retry){VBox box=empty("暂时无法加载",message);box.getChildren().add(button("重新加载","library-primary",retry));setCenter(scroll(box));}
    void showCatalog(LibraryData.Page<LibraryData.Book> page){
        select("catalog");grid.getChildren().clear();catalogCount.setText("馆藏目录  ·  "+page.total()+" 本书目");
        for(var item:page.rows())grid.getChildren().add(card(item));
        if(page.rows().isEmpty())grid.getChildren().add(empty("没有找到匹配的图书","试试其他关键词，或清空分类筛选。"));
        pager(catalogPager,page.page(),page.pages(),page.total(),p->search(p));catalogScroll.setDisable(false);setCenter(catalogScroll);
    }
    private Node card(LibraryData.Book item){
        var book=item.book();Label title=label(book.title(),"library-book-title");title.setMinHeight(Region.USE_PREF_SIZE);
        VBox copy=new VBox(8,title,label(book.authors(),"library-muted"),label(book.category()+"  ·  "+book.catalogCode(),"library-hint"));copy.setMinWidth(0);HBox.setHgrow(copy,Priority.ALWAYS);
        Label available=label(book.enabled()?"可借 "+book.availableCopies():"书目停用",book.enabled()&&book.availableCopies()>0?"library-badge-green":"library-badge-muted");
        String count=item.borrowCount()<0?"借阅次数待更新":"累计借阅 "+item.borrowCount()+" 次";
        VBox card=new VBox(16,row(cover(item,false),copy),row(available,label("已借出 "+(item.onLoanCopies()<0?"—":item.onLoanCopies()),"library-badge-muted")),
                label(count,"library-hint"),bookActions(item,false));
        card.getStyleClass().add("library-book-card");card.setMaxWidth(Double.MAX_VALUE);return card;
    }
    private HBox bookActions(LibraryData.Book item,boolean detail){
        HBox actions=new HBox(8);actions.setAlignment(Pos.CENTER_LEFT);
        if(!detail){Button view=button("详情","library-quiet",()->listener.detail(item.book().bookId()));view.setId("library-detail-"+item.book().bookId());actions.getChildren().add(view);}
        if(roles.contains(UserRole.STUDENT)){
            Button reserve=button("预约提醒","library-quiet",()->listener.reserve(item));reserve.setId("library-reserve-"+item.book().bookId());
            reserve.setDisable(!item.book().enabled()||item.onLoanCopies()<=0);reserve.setTooltip(new Tooltip("存在已借出馆藏时可预约；归还后通知，不保留图书。"));actions.getChildren().add(reserve);
        }
        if(LibraryAccessPolicy.canBorrow(roles)){
            Button borrow=button("借阅","library-primary",()->listener.borrow(item));borrow.setId("library-borrow-"+item.book().bookId());
            borrow.setDisable(!item.book().enabled()||item.book().availableCopies()<=0);actions.getChildren().add(borrow);
        }
        return actions;
    }
    void showDetail(LibraryData.Book item){
        select(LibraryAccessPolicy.canBorrow(roles)?"catalog":"admin");var b=item.book();
        VBox copy=new VBox(12,label(b.category(),"library-eyebrow"),label(b.title(),"library-detail-title"),label(b.authors(),"library-muted"),
                label(b.publisher()+"  /  "+(b.publishYear()==null?"出版年份未录入":b.publishYear()),"library-muted"),
                label("书目编号 "+b.catalogCode()+"  ·  ISBN "+(b.isbn().isBlank()?"未录入":b.isbn()),"library-hint"),
                row(label("可借 "+b.availableCopies(),"library-badge-green"),label("已借出 "+(item.onLoanCopies()<0?"—":item.onLoanCopies())+" / 总馆藏 "+b.totalCopies(),"library-badge-muted")),
                label("累计借阅 "+(item.borrowCount()<0?"—":item.borrowCount())+" 次","library-muted"),bookActions(item,true));copy.setMinWidth(0);HBox.setHgrow(copy,Priority.ALWAYS);
        HBox head=row(cover(item,true),copy);head.setAlignment(Pos.TOP_LEFT);head.setSpacing(30);head.getStyleClass().add("library-detail-card");
        VBox body=new VBox(22,button("← 返回目录","library-quiet",()->listener.open(LibraryAccessPolicy.canBorrow(roles)?"catalog":"admin")),head,
                label("内容介绍","library-section-title"),label(b.description().isBlank()?"暂无内容介绍。":b.description(),"library-description"),
                label("预约仅提供归还提醒，不排队或保留馆藏；是否可借以操作时的实际库存为准。","library-note"));
        body.getStyleClass().add("library-content");setCenter(scroll(body));
    }
    void showLoans(LibraryViewData.LoanPage page){
        select("loans");VBox body=new VBox(18);body.getStyleClass().add("library-content");
        FlowPane scopes=new FlowPane(8,8);for(String value:List.of("active","overdue","history","all")){
            Button b=button(switch(value){case "active"->"当前借阅";case "overdue"->"已逾期";case "history"->"借阅历史";default->"全部记录";},value.equals(loanScope)?"library-primary":"library-quiet",()->{loanScope=value;listener.loans(value,1);});scopes.getChildren().add(b);
        }
        body.getChildren().addAll(row(label("我的借阅","library-section-title"),space(),label("共 "+page.total()+" 条","library-muted")),scopes);
        if(page.initialLoanDays()!=null)body.getChildren().add(label("初始借期 "+page.initialLoanDays()+" 天 · 最多同时借阅 "+page.maxLoans()+" 本 · 续借以系统校验为准","library-note"));
        for(var loan:page.rows()){
            VBox info=new VBox(7,label(loan.title(),"library-book-title"),label("馆藏条码 "+loan.barcode()+" · 借于 "+DATE.format(loan.borrowedAt()),"library-hint"),
                    label(loan.returnedAt()==null?"应还日期  "+DATE.format(loan.dueAt()):"归还日期  "+DATE.format(loan.returnedAt()),loan.overdue()?"library-overdue":"library-muted"));info.setMinWidth(0);HBox.setHgrow(info,Priority.ALWAYS);
            VBox actions=new VBox(8);String state=loan.returnedAt()!=null?"已归还":loan.overdue()?"已逾期":"借阅中";actions.getChildren().add(label(state,"library-badge-muted"));
            if(loan.returnedAt()==null){Button renew=button("续借","library-quiet",()->listener.renew(loan));renew.setDisable(!loan.renewable());renew.setId("library-renew-"+loan.loanId());
                Button returned=button("归还","library-primary",()->listener.returnLoan(loan));returned.setId("library-return-"+loan.loanId());actions.getChildren().addAll(renew,returned);}
            HBox row=row(icon(Feather.BOOK_OPEN,28),info,actions);row.getStyleClass().add("library-list-card");body.getChildren().add(row);
        }
        if(page.rows().isEmpty())body.getChildren().add(empty("没有相关借阅记录","借阅图书后，可在这里查看归还日期和续借。"));
        HBox paging=new HBox(10);pager(paging,page.page(),Math.max(1,(int)((page.total()+(long)page.pageSize()-1)/page.pageSize())),page.total(),p->listener.loans(loanScope,p));body.getChildren().add(paging);setCenter(scroll(body));
    }
    void showReservations(LibraryData.Page<LibraryData.Reservation> page){
        select("reservations");VBox body=new VBox(18);body.getStyleClass().add("library-content");
        FlowPane scopes=new FlowPane(8,8);for(String value:List.of("","WAITING","NOTIFIED","CANCELLED"))scopes.getChildren().add(button(reservationLabel(value),value.equals(reservationScope)?"library-primary":"library-quiet",()->{reservationScope=value;listener.reservations(value,1);}));
        body.getChildren().addAll(label("我的预约","library-section-title"),label("图书正常归还后，消息中心会提醒你。预约不锁定馆藏，也不保证优先借阅。","library-note"),scopes);
        for(var reservation:page.rows()){
            VBox info=new VBox(7,label(reservation.title(),"library-book-title"),label(reservation.catalogCode()+" · 预约于 "+DATE.format(reservation.createdAt()),"library-hint"),
                    label(reservation.notifiedAt()==null?reservationLabel(reservation.status()):"已于 "+DATE.format(reservation.notifiedAt())+" 发送归还提醒","library-muted"));info.setMinWidth(0);HBox.setHgrow(info,Priority.ALWAYS);
            VBox actions=new VBox(8,button("查看图书","library-quiet",()->listener.detail(reservation.bookId())));
            if(reservation.status().equals("WAITING")){Button cancel=button("取消预约","library-quiet",()->listener.cancel(reservation));cancel.setId("library-cancel-"+reservation.id());actions.getChildren().add(cancel);}
            HBox row=row(icon(Feather.BELL,26),info,actions);row.getStyleClass().add("library-list-card");body.getChildren().add(row);
        }
        if(page.rows().isEmpty())body.getChildren().add(empty("暂无预约记录","在图书检索中，对存在已借出馆藏的书目设置归还提醒。"));
        HBox paging=new HBox(10);pager(paging,page.page(),page.pages(),page.total(),p->listener.reservations(reservationScope,p));body.getChildren().add(paging);setCenter(scroll(body));
    }
    private String reservationLabel(String state){return switch(state){case "WAITING"->"等待归还";case "NOTIFIED"->"已通知";case "CANCELLED"->"已取消";default->"全部预约";};}
    void showAdmin(Node admin){select("admin");setCenter(admin);}
    private void pager(HBox box,int page,int pages,int total,java.util.function.IntConsumer go){
        Button prev=button("上一页","library-quiet",()->go.accept(page-1)),next=button("下一页","library-quiet",()->go.accept(page+1));prev.setDisable(page<=1);next.setDisable(page>=pages);
        box.setAlignment(Pos.CENTER_RIGHT);box.getChildren().setAll(label("第 "+page+" / "+pages+" 页  ·  共 "+total+" 项","library-muted"),space(),prev,next);
    }
    private VBox empty(String title,String text){VBox box=new VBox(12,icon(Feather.BOOK_OPEN,32),label(title,"library-section-title"),label(text,"library-muted"));box.setPadding(new Insets(45,24,45,24));return box;}
}
