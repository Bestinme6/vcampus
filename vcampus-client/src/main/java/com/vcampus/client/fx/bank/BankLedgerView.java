package com.vcampus.client.fx.bank;

import com.vcampus.common.model.BankLedgerType;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.function.Consumer;

final class BankLedgerView extends ScrollPane {
    private final VBox content=new VBox(18),rows=BankUi.card(),detail=BankUi.card();
    private final TextField keyword=new TextField(),username=new TextField();
    private final DatePicker from=new DatePicker(),to=new DatePicker();
    private final ComboBox<String> type=new ComboBox<>();
    private final Label totals=BankUi.label("","bank-muted"),paging=BankUi.label("","bank-muted");
    private final Consumer<BankData.Query> search;
    private final Consumer<String> order;
    private final Button previous,next;
    private boolean administrative;
    private String reference="";
    private int currentPage=1;
    BankLedgerView(Consumer<BankData.Query> search,Consumer<String> order){
        this.search=search;this.order=order;setId("bank-ledger-view");setContent(content);setFitToWidth(true);getStyleClass().add("bank-scroll");
        keyword.setPromptText("说明 / 业务编号");username.setPromptText("用户名 / 学号，留空查全部");
        type.getItems().add("全部类型");for(var t:BankLedgerType.values())type.getItems().add(BankUi.type(t));type.getSelectionModel().selectFirst();
        from.setPromptText("开始日期");to.setPromptText("结束日期");from.setEditable(false);to.setEditable(false);
        previous=BankUi.button("上一页","bank-ledger-prev",()->performSearch(currentPage-1));
        next=BankUi.button("下一页","bank-ledger-next",()->performSearch(currentPage+1));
        from.setPrefWidth(145);to.setPrefWidth(145);keyword.setPrefWidth(190);username.setPrefWidth(200);
    }
    void configure(BankData.Query q){
        administrative=q.administrative();reference=q.reference();username.setText(q.username());keyword.setText(q.keyword());
        from.setValue(q.from());to.setValue(q.to());type.getSelectionModel().select(q.type()==null?0:q.type().ordinal()+1);
        FlowPane filters=BankUi.actions(type,keyword,from,to,BankUi.primary("查询流水","bank-ledger-search",()->performSearch(1)));
        if(administrative)filters.getChildren().addFirst(username);
        detail.setManaged(false);detail.setVisible(false);rows.getChildren().setAll(BankUi.label("正在查询…","bank-muted"));
        content.getChildren().setAll(BankUi.label(administrative?"全量流水":"资金流水","bank-title"),filters,totals);
        if(!reference.isBlank())content.getChildren().add(BankUi.row(BankUi.label("业务编号："+reference,"bank-small"),
                BankUi.button("清除编号筛选",null,()->{reference="";performSearch(1);})));
        content.getChildren().addAll(rows,BankUi.row(previous,paging,next),detail);
    }
    BankData.Query query(int page){
        int selected=type.getSelectionModel().getSelectedIndex();
        return new BankData.Query(administrative,username.getText(),selected<=0?null:BankLedgerType.values()[selected-1],
                keyword.getText(),from.getValue(),to.getValue(),reference,page);
    }
    private void performSearch(int page){
        try{search.accept(query(page));}
        catch(IllegalArgumentException error){totals.setText(error.getMessage());}
    }
    void show(BankData.Ledger ledger){
        var page=ledger.entries();currentPage=page.page();
        totals.setText("筛选结果共 "+page.total()+" 笔   收入 "+BankUi.money(ledger.income())+"   支出 "+BankUi.money(ledger.expense()));
        paging.setText("第 "+page.page()+" / "+page.pages()+" 页");
        previous.setDisable(page.page()<=1);next.setDisable(page.page()>=page.pages());
        rows.getChildren().clear();
        if(page.rows().isEmpty())rows.getChildren().add(BankUi.label("没有符合条件的流水，请调整筛选条件。","bank-muted"));
        for(var entry:page.rows())rows.getChildren().add(BankUi.entry(entry,()->detail(entry)));
        if(!reference.isBlank()&&!page.rows().isEmpty())detail(page.rows().getFirst());
    }
    void detail(BankData.Entry e){
        detail.setVisible(true);detail.setManaged(true);
        detail.getChildren().setAll(BankUi.label("交易回执","bank-section-title"),
                BankUi.label(BankUi.signed(e),"bank-receipt-amount"),
                BankUi.detail("交易类型",BankUi.type(e.type())),BankUi.detail("交易后余额",BankUi.money(e.balanceAfter())),
                BankUi.detail("账户编号",Long.toString(e.accountId())),BankUi.detail("交易时间",BankUi.time(e.createdAt())),
                BankUi.detail("业务编号",e.reference()),BankUi.detail("系统说明",e.description()));
        if(!administrative&&(e.type()==BankLedgerType.SHOP_PAYMENT||e.type()==BankLedgerType.SHOP_REFUND))
            detail.getChildren().add(BankUi.button("查看关联订单","bank-order-link",()->order.accept(e.reference())));
    }
}
