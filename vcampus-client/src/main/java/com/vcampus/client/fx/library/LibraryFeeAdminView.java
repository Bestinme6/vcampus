package com.vcampus.client.fx.library;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.*;
import com.vcampus.common.protocol.ResponseMessage;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.vcampus.client.fx.library.LibraryUi.*;

/** Staff bill and fee-policy workspace with its own stale-request guard. */
final class LibraryFeeAdminView extends BorderPane implements AutoCloseable {
    interface Gateway {
        ResponseMessage search(String keyword,String status,int page)throws Exception;
        ResponseMessage detail(long billId)throws Exception;
        ResponseMessage waive(long billId,String reason)throws Exception;
        ResponseMessage refund(long billId,String reason,String operationId)throws Exception;
        ResponseMessage getPolicy()throws Exception;
        ResponseMessage updatePolicy(Map<String,String> values)throws Exception;
    }

    private final Gateway gateway;
    private final Executor executor;
    private final Runnable onChanged;
    private final VBox content=new VBox(14);
    private final Label status=label("","library-status");
    private final TableView<LibraryData.FeeBill> table=new TableView<>();
    private final TextField keyword=new TextField();
    private final ComboBox<String> state=new ComboBox<>();
    private final Button detail=new Button("查看详情");
    private final Button billsTab=new Button("账单管理"),policyTab=new Button("收费规则");
    private final Set<Dialog<?>> dialogs=new HashSet<>();
    private final Map<Long,String> refundOperations=new HashMap<>();
    private int page=1,pages=1;
    private String mode="bills";
    private long generation;
    private boolean busy,closed;

    LibraryFeeAdminView(VCampusClient client,String token,Executor executor,Runnable onChanged){
        this(new Gateway(){
            public ResponseMessage search(String keyword,String status,int page)throws Exception{Map<String,String> f=new LinkedHashMap<>();f.put("keyword",keyword);f.put("page",Integer.toString(page));if(!status.isBlank())f.put("status",status);return client.searchLibraryFees(token,Map.copyOf(f));}
            public ResponseMessage detail(long billId)throws Exception{return client.getLibraryFee(token,billId);}
            public ResponseMessage waive(long billId,String reason)throws Exception{return client.waiveLibraryFee(token,billId,reason);}
            public ResponseMessage refund(long billId,String reason,String operationId)throws Exception{return client.refundLibraryFee(token,billId,reason,operationId);}
            public ResponseMessage getPolicy()throws Exception{return client.getLibraryFeePolicy(token);}
            public ResponseMessage updatePolicy(Map<String,String> values)throws Exception{return client.updateLibraryFeePolicy(token,values);}
        },executor,onChanged);
    }

    LibraryFeeAdminView(Gateway gateway,Executor executor,Runnable onChanged){
        this.gateway=Objects.requireNonNull(gateway);this.executor=Objects.requireNonNull(executor);this.onChanged=Objects.requireNonNull(onChanged);
        setId("library-fee-admin");getStyleClass().add("library-fee-admin");
        billsTab.setId("library-fee-admin-bills-tab");policyTab.setId("library-fee-admin-policy-tab");for(Button button:List.of(billsTab,policyTab))button.getStyleClass().add("library-quiet");
        billsTab.setOnAction(e->{mode="bills";page=1;showBills();});policyTab.setOnAction(e->{mode="policy";showPolicy();});setTop(new HBox(8,billsTab,policyTab));
        content.setPadding(new Insets(14,0,8,0));ScrollPane scroll=scroll(content);setCenter(scroll);status.setId("library-fee-admin-status");setBottom(status);BorderPane.setMargin(status,new Insets(8,0,0,0));
        keyword.setId("library-fee-admin-keyword");keyword.setPromptText("账单号、用户、图书或条码");keyword.setPrefWidth(280);
        state.setId("library-fee-admin-status-filter");state.getItems().setAll("", "PENDING","PAID","WAIVED","REFUNDED");state.setValue("");state.setConverter(new javafx.util.StringConverter<>(){public String toString(String value){return stateLabel(value);}public String fromString(String value){throw new UnsupportedOperationException();}});
        detail.setId("library-fee-admin-detail");detail.getStyleClass().add("library-quiet");detail.setOnAction(e->{var selected=table.getSelectionModel().getSelectedItem();if(selected!=null)loadDetail(selected.billId());});detail.setDisable(true);
        configureTable();
    }

    void activate(){if(closed)return;mode="bills";page=1;showBills();}

    private void configureTable(){
        table.setId("library-fee-admin-table");table.getStyleClass().add("library-table");table.setPrefHeight(420);table.setPlaceholder(label("暂无费用账单","library-muted"));
        column("账单号",140,LibraryData.FeeBill::billNo);column("借阅人",150,x->x.borrowerDisplayName()+" / "+x.borrowerUsername());column("图书",190,LibraryData.FeeBill::title);column("条码",120,LibraryData.FeeBill::barcode);column("状态",80,x->stateLabel(x.status().name()));column("合计",90,x->"¥"+money(x.totalAmount()));
        table.getSelectionModel().selectedItemProperty().addListener((o,a,b)->detail.setDisable(b==null||busy));
    }
    private void column(String title,double width,Function<LibraryData.FeeBill,String> value){TableColumn<LibraryData.FeeBill,String> c=new TableColumn<>(title);c.setPrefWidth(width);c.setSortable(false);c.setCellValueFactory(x->new ReadOnlyStringWrapper(value.apply(x.getValue())));table.getColumns().add(c);}

    private void showBills(){
        if(closed)return;mode="bills";content.getChildren().clear();Button search=new Button("查询");search.setId("library-fee-admin-search");search.getStyleClass().add("library-primary");search.setOnAction(e->{page=1;loadBills();});keyword.setOnAction(e->search.fire());
        Button previous=new Button("上一页"),next=new Button("下一页");previous.getStyleClass().add("library-quiet");next.getStyleClass().add("library-quiet");previous.setOnAction(e->{if(page>1){page--;loadBills();}});next.setOnAction(e->{if(page<pages){page++;loadBills();}});
        HBox pager=new HBox(8,previous,next);pager.setId("library-fee-admin-pager");
        content.getChildren().addAll(new FlowPane(10,10,keyword,state,search,detail),table,pager);VBox.setVgrow(table,Priority.ALWAYS);loadBills();
    }
    private void loadBills(){
        String wantedMode=mode,filter=Objects.requireNonNullElse(state.getValue(),"");int wantedPage=page;
        query(()->gateway.search(keyword.getText().trim(),filter,wantedPage),response->{if(!mode.equals(wantedMode))return;var result=LibraryData.fees(response);table.getItems().setAll(result.rows());page=result.page();pages=result.pages();setStatus("共 "+result.total()+" 笔费用账单",false);});
    }
    private void loadDetail(long billId){query(()->gateway.detail(billId),response->renderDetail(LibraryData.feeDetail(response)));}
    private void renderDetail(LibraryData.FeeDetail value){
        mode="detail";var bill=value.bill();content.getChildren().clear();Button back=new Button("← 返回账单");back.setId("library-fee-admin-back");back.getStyleClass().add("library-quiet");back.setOnAction(e->showBills());
        String snapshot=value.graceDays()==null?"收费规则快照未返回":"快照：宽限 "+value.graceDays()+" 天，每日 ¥"+money(value.dailyOverdueFee())+"，逾期上限 ¥"+money(value.overdueCap())+(value.conditionRate()==null?"":"，赔偿比例 "+value.conditionRate().movePointRight(2).stripTrailingZeros().toPlainString()+"%");
        VBox card=new VBox(10,label(bill.title(),"library-detail-title"),label(bill.borrowerDisplayName()+" / "+bill.borrowerUsername()+"  ·  "+bill.billNo(),"library-muted"),label("逾期 ¥"+money(bill.overdueAmount())+"  ·  破损 ¥"+money(bill.damageAmount())+"  ·  遗失 ¥"+money(bill.lostAmount()),"library-muted"),label("合计 ¥"+money(bill.totalAmount())+"  ·  "+stateLabel(bill.status().name()),"library-fee-payment-amount"),label(snapshot,"library-note"),label("原因："+(value.reason().isBlank()?"无":value.reason()),"library-note"));card.setId("library-fee-admin-detail-card");card.getStyleClass().add("library-detail-card");content.getChildren().addAll(back,card);
        HBox actions=new HBox(8);actions.setAlignment(Pos.CENTER_RIGHT);
        if(bill.status()==LibraryFeeStatus.PENDING){Button waive=new Button("免除费用");waive.setId("library-fee-admin-waive");waive.getStyleClass().add("library-quiet");waive.setOnAction(e->waive(bill));actions.getChildren().add(waive);}
        if(bill.status()==LibraryFeeStatus.PAID){Button refund=new Button("退回费用");refund.setId("library-fee-admin-refund");refund.getStyleClass().add("library-primary");refund.setOnAction(e->refund(bill));actions.getChildren().add(refund);}
        content.getChildren().add(actions);
    }

    private void waive(LibraryData.FeeBill bill){String reason=reasonDialog("免除费用","请填写免除原因");if(reason==null||!confirm("二次确认","确认免除账单 "+bill.billNo()+" 的全部费用？"))return;mutate(()->gateway.waive(bill.billId(),reason),()->{onChanged.run();showBills();});}
    private void refund(LibraryData.FeeBill bill){String reason=reasonDialog("退回费用","请填写退款原因");if(reason==null||!confirm("二次确认","确认将 ¥"+money(bill.totalAmount())+" 退回借阅人的校园银行账户？"))return;String operation=refundOperations.computeIfAbsent(bill.billId(),x->UUID.randomUUID().toString());mutate(()->gateway.refund(bill.billId(),reason,operation),()->{refundOperations.remove(bill.billId());onChanged.run();showBills();});}

    private void showPolicy(){mode="policy";content.getChildren().setAll(label("正在读取收费规则…","library-muted"));query(gateway::getPolicy,response->renderPolicy(LibraryData.feePolicy(response)));}
    private void renderPolicy(LibraryData.FeePolicy policy){
        if(!mode.equals("policy"))return;content.getChildren().clear();TextField grace=field("library-fee-policy-grace",Integer.toString(policy.graceDays())),daily=field("library-fee-policy-daily",money(policy.dailyOverdueFee())),cap=field("library-fee-policy-cap",money(policy.overdueCap())),minor=field("library-fee-policy-minor",policy.minorDamageRate().toPlainString()),major=field("library-fee-policy-major",policy.majorDamageRate().toPlainString()),lost=field("library-fee-policy-lost",policy.lostRate().toPlainString());
        GridPane form=new GridPane();form.setHgap(14);form.setVgap(12);add(form,0,"免费宽限（天）",grace);add(form,1,"每日逾期费",daily);add(form,2,"逾期费上限",cap);add(form,3,"轻微破损比例",minor);add(form,4,"严重破损比例",major);add(form,5,"遗失赔偿比例",lost);
        Label error=label("","library-error");error.setId("library-fee-policy-error");Button save=new Button("保存收费规则");save.setId("library-fee-policy-save");save.getStyleClass().add("library-primary");save.setOnAction(e->{try{int days=Integer.parseInt(grace.getText().trim());if(days<0)throw new IllegalArgumentException();Map<String,String> values=Map.of("graceDays",Integer.toString(days),"dailyOverdueFee",normalizeMoney(daily.getText()),"overdueCap",normalizeMoney(cap.getText()),"minorDamageRate",normalizeRate(minor.getText()),"majorDamageRate",normalizeRate(major.getText()),"lostRate",normalizeRate(lost.getText()));if(confirm("确认修改","新规则只影响之后生成的账单，确认保存？"))mutate(()->gateway.updatePolicy(values),()->{onChanged.run();showPolicy();});}catch(Exception failure){error.setText("请检查宽限天数、金额和赔偿比例。");}});content.getChildren().addAll(label("收费规则","library-section-title"),label("修改后仅影响新账单，历史账单保留生成时的快照。","library-note"),form,error,save);
    }

    private void query(Request request,Consumer<ResponseMessage> success){run(request,response->{try{success.accept(response);}catch(Exception e){setStatus(message(e),true);}});}
    private void mutate(Request request,Runnable success){run(request,response->{setStatus(response.message(),false);success.run();});}
    private void run(Request request,Consumer<ResponseMessage> success){if(closed||busy)return;long expected=++generation;busy=true;updateBusy();try{executor.execute(()->{ResponseMessage response=null;Exception failure=null;try{response=request.call();LibraryData.require(response);}catch(Exception e){failure=e;}ResponseMessage result=response;Exception error=failure;Platform.runLater(()->{if(closed||expected!=generation)return;busy=false;updateBusy();if(error!=null){setStatus(message(error),true);return;}success.accept(result);});});}catch(RuntimeException rejected){busy=false;updateBusy();setStatus("请求未能启动，请稍后重试。",true);}}
    private void updateBusy(){setDisable(busy||closed);detail.setDisable(busy||table.getSelectionModel().getSelectedItem()==null);}
    private void setStatus(String text,boolean error){status.setText(text);status.getStyleClass().remove("library-error");if(error)status.getStyleClass().add("library-error");}

    private String reasonDialog(String title,String prompt){TextField reason=field("library-fee-admin-reason","");reason.setPromptText(prompt);Label error=label("","library-error");Dialog<ButtonType> dialog=dialog(title,new VBox(10,reason,error));dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION,e->{try{requireReason(reason.getText());}catch(IllegalArgumentException x){error.setText(x.getMessage());e.consume();}});return show(dialog)?requireReason(reason.getText()):null;}
    private boolean confirm(String title,String text){return show(dialog(title,label(text,"library-note")));}
    private Dialog<ButtonType> dialog(String title,Node body){Dialog<ButtonType> d=new Dialog<>();d.setTitle(title);d.setHeaderText(title);d.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);d.getDialogPane().setContent(body);return d;}
    private boolean show(Dialog<ButtonType> dialog){if(closed)return false;dialogs.add(dialog);try{return dialog.showAndWait().filter(ButtonType.OK::equals).isPresent()&&!closed;}finally{dialogs.remove(dialog);}}

    @Override public void close(){if(closed)return;closed=true;generation++;for(Dialog<?> d:List.copyOf(dialogs))d.close();dialogs.clear();updateBusy();}
    static String requireReason(String raw){String value=Objects.requireNonNullElse(raw,"").trim();if(value.isBlank())throw new IllegalArgumentException("必须填写原因。");return value;}
    static String normalizeMoney(String raw){try{BigDecimal value=new BigDecimal(Objects.requireNonNullElse(raw,"").trim()).setScale(2,RoundingMode.UNNECESSARY);if(value.signum()<=0)throw new IllegalArgumentException();return value.toPlainString();}catch(Exception e){throw new IllegalArgumentException("金额必须大于 0 且最多两位小数。");}}
    static String normalizeRate(String raw){try{BigDecimal value=new BigDecimal(Objects.requireNonNullElse(raw,"").trim()).setScale(4,RoundingMode.UNNECESSARY);if(value.signum()<=0||value.compareTo(BigDecimal.ONE)>0)throw new IllegalArgumentException();return value.toPlainString();}catch(Exception e){throw new IllegalArgumentException("比例必须在 0 到 1 之间，最多四位小数。");}}
    private static TextField field(String id,String value){TextField field=new TextField(value);field.setId(id);field.getStyleClass().add("library-field");return field;}
    private static void add(GridPane form,int row,String title,Node value){form.add(label(title,"library-muted"),0,row);form.add(value,1,row);GridPane.setHgrow(value,Priority.ALWAYS);}
    private static String stateLabel(String value){return switch(Objects.requireNonNullElse(value,"")){case "PENDING"->"待缴费";case "PAID"->"已缴费";case "WAIVED"->"已免除";case "REFUNDED"->"已退款";default->"全部状态";};}
    private static String money(BigDecimal value){return value.setScale(2,RoundingMode.UNNECESSARY).toPlainString();}
    private static String message(Exception error){return error instanceof java.io.IOException?"无法连接图书馆服务器，请稍后重试":Objects.requireNonNullElse(error.getMessage(),"操作失败，请稍后重试");}
    @FunctionalInterface private interface Request{ResponseMessage call()throws Exception;}
}
