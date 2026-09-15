package com.vcampus.client.fx.library;

import com.vcampus.common.model.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.kordamp.ikonli.feather.Feather;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.vcampus.client.fx.library.LibraryUi.*;

/** Focused borrower fee UI. Networking remains in {@link LibraryController}. */
final class LibraryFeeView extends BorderPane {
    interface Listener {
        void list(String status,int page);
        void detail(long billId);
        void requestPayment(long billId);
        void confirmPayment(long billId,String operationId);
    }

    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
    private static final Map<String,String> FILTERS=new LinkedHashMap<>();
    static {FILTERS.put("","全部账单");FILTERS.put("PENDING","待缴费");FILTERS.put("PAID","已缴费");FILTERS.put("WAIVED","已免除");FILTERS.put("REFUNDED","已退款");}
    private final Listener listener;
    private final VBox content=new VBox(16);
    private final Label status=label("","library-status");
    private String selectedStatus="";
    private boolean busy;

    LibraryFeeView(Listener listener){
        this.listener=listener;setId("library-fees");getStyleClass().add("library-fees");
        content.getStyleClass().add("library-content");
        status.setId("library-fee-status");status.setManaged(false);status.setVisible(false);
        setCenter(scroll(content));setBottom(status);BorderPane.setMargin(status,new Insets(0,34,20,34));
    }

    void showList(LibraryData.Page<LibraryData.FeeBill> page){
        content.getChildren().clear();content.getChildren().add(row(label("待缴费用","library-section-title"),space(),label("共 "+page.total()+" 笔","library-muted")));
        FlowPane filters=new FlowPane(8,8);
        FILTERS.forEach((code,text)->filters.getChildren().add(button(text,code.equals(selectedStatus)?"library-primary":"library-quiet",()->{selectedStatus=code;listener.list(code,1);})));content.getChildren().add(filters);
        for(var bill:page.rows())content.getChildren().add(billCard(bill));
        if(page.rows().isEmpty())content.getChildren().add(empty("暂无费用账单",selectedStatus.equals("PENDING")?"当前没有需要缴纳的图书费用。":"没有找到符合条件的账单。"));
        HBox pager=new HBox(10);pager(pager,page.page(),page.pages(),page.total());content.getChildren().add(pager);message("",false);applyBusy();
    }

    private HBox billCard(LibraryData.FeeBill bill){
        Label state=label(statusLabel(bill.status()),bill.status()==LibraryFeeStatus.PENDING?"library-fee-pending":"library-badge-muted");state.setId("library-fee-state-"+bill.billId());
        Label items=label(itemText(bill),"library-muted");items.setId("library-fee-items-"+bill.billId());
        VBox info=new VBox(7,label(bill.title(),"library-book-title"),label("账单 "+bill.billNo()+"  ·  馆藏条码 "+bill.barcode(),"library-hint"),items,label("生成于 "+DATE.format(bill.createdAt()),"library-hint"));
        info.setMinWidth(0);HBox.setHgrow(info,Priority.ALWAYS);
        Label total=label("合计  ¥"+money(bill.totalAmount()),"library-fee-total");total.setId("library-fee-total-"+bill.billId());
        VBox actions=new VBox(8,state,total,button("查看详情","library-quiet",()->listener.detail(bill.billId())));actions.setAlignment(Pos.CENTER_RIGHT);
        if(bill.status()==LibraryFeeStatus.PENDING)actions.getChildren().add(button("立即缴费","library-primary",()->listener.requestPayment(bill.billId())));
        HBox card=row(icon(Feather.FILE_TEXT,28),info,actions);card.getStyleClass().add("library-list-card");return card;
    }

    void showDetail(LibraryData.FeeDetail detail){
        var bill=detail.bill();content.getChildren().clear();
        Button back=button("← 返回账单","library-quiet",()->listener.list(selectedStatus,1));
        VBox card=new VBox(13,label("费用账单详情","library-section-title"),label(bill.title(),"library-detail-title"),
                label("账单号 "+bill.billNo()+"  ·  馆藏条码 "+bill.barcode(),"library-muted"),
                detailLine("账单状态",statusLabel(bill.status())),detailLine("费用明细",itemText(bill)),detailLine("应缴合计","¥"+money(bill.totalAmount())));
        card.getStyleClass().add("library-detail-card");
        Label calculation=label("逾期 "+detail.overdueDays()+" 天，计费 "+detail.billableDays()+" 天  ·  归还状况："+conditionLabel(detail.condition())+(detail.damageLevel()==null?"":" / "+damageLabel(detail.damageLevel())),"library-note");calculation.setId("library-fee-calculation");
        String price=detail.replacementPrice()==null?"赔偿参考价未设置":"赔偿参考价快照 ¥"+money(detail.replacementPrice());
        String policy=detail.graceDays()==null?"":"；宽限 "+detail.graceDays()+" 天，每日 ¥"+money(detail.dailyOverdueFee())+"，逾期上限 ¥"+money(detail.overdueCap())+(detail.conditionRate()==null?"":"，赔偿比例 "+detail.conditionRate().movePointRight(2).stripTrailingZeros().toPlainString()+"%");
        Label snapshot=label(price+policy+"。费用使用账单生成时的价格和收费规则，后续调整不会改写本账单。","library-note");snapshot.setId("library-fee-snapshot");
        Label reason=label("备注："+(detail.reason().isBlank()?"无":detail.reason()),"library-muted");reason.setId("library-fee-reason");
        content.getChildren().addAll(back,card,calculation,snapshot,reason);
        if(!detail.paymentReference().isBlank())content.getChildren().add(label("缴费参考号："+detail.paymentReference(),"library-hint"));
        if(!detail.refundReference().isBlank())content.getChildren().add(label("退款参考号："+detail.refundReference(),"library-hint"));
        if(bill.status()==LibraryFeeStatus.PENDING){Button pay=button("立即缴费","library-primary",()->listener.requestPayment(bill.billId()));pay.setId("library-fee-pay-"+bill.billId());content.getChildren().add(pay);}
        message("",false);applyBusy();
    }

    void showPaymentPreview(LibraryData.PaymentPreview preview){
        var bill=preview.detail().bill();content.getChildren().clear();
        content.getChildren().addAll(label("确认缴费","library-section-title"),label("请核对账单和银行账户信息。确认后将从校园银行账户扣款。","library-note"));
        VBox card=new VBox(13,label(bill.title(),"library-book-title"),detailLine("账单号",bill.billNo()));card.getStyleClass().add("library-detail-card");
        Label total=label("本次应缴  ¥"+money(bill.totalAmount()),"library-fee-payment-amount");total.setId("library-fee-payment-total");
        Label balance=label("当前可用余额  ¥"+money(preview.balance()),"library-muted");balance.setId("library-fee-payment-balance");
        card.getChildren().addAll(total,balance);content.getChildren().add(card);
        boolean frozen=preview.accountStatus()==BankAccountStatus.FROZEN,insufficient=preview.balance().compareTo(bill.totalAmount())<0;
        Label warning=label(frozen?"银行账户已冻结，暂时无法缴费":insufficient?"余额不足，账单仍待缴":"余额充足，可以继续缴费。",frozen||insufficient?"library-fee-warning":"library-note");warning.setId("library-fee-payment-warning");content.getChildren().add(warning);
        Button cancel=button("返回账单","library-quiet",()->listener.detail(bill.billId()));
        Button confirm=button("确认扣款","library-primary",()->listener.confirmPayment(bill.billId(),""));confirm.setId("library-fee-payment-confirm");confirm.setDisable(frozen||insufficient);
        HBox actions=row(cancel,confirm);actions.setAlignment(Pos.CENTER_RIGHT);content.getChildren().add(actions);message("",false);applyBusy();
    }

    void message(String text,boolean error){status.setText(text);status.setManaged(!text.isBlank());status.setVisible(!text.isBlank());status.getStyleClass().remove("library-error");if(error)status.getStyleClass().add("library-error");}
    void busy(boolean value){busy=value;applyBusy();}
    private void applyBusy(){if(getCenter()!=null)getCenter().setDisable(busy);}
    private void pager(HBox box,int page,int pages,int total){Button previous=button("上一页","library-quiet",()->listener.list(selectedStatus,page-1)),next=button("下一页","library-quiet",()->listener.list(selectedStatus,page+1));previous.setDisable(page<=1);next.setDisable(page>=pages);box.setAlignment(Pos.CENTER_RIGHT);box.getChildren().setAll(label("第 "+page+" / "+pages+" 页  ·  共 "+total+" 项","library-muted"),space(),previous,next);}
    private static HBox detailLine(String name,String value){return row(label(name,"library-muted"),space(),label(value,"library-fee-value"));}
    private static String itemText(LibraryData.FeeBill bill){StringBuilder text=new StringBuilder();append(text,"逾期",bill.overdueAmount());append(text,"破损",bill.damageAmount());append(text,"遗失",bill.lostAmount());return text.isEmpty()?"无费用明细":text.toString();}
    private static void append(StringBuilder text,String name,BigDecimal amount){if(amount.signum()>0){if(!text.isEmpty())text.append("  ·  ");text.append(name).append(" ¥").append(money(amount));}}
    private static String money(BigDecimal value){return value.setScale(2).toPlainString();}
    private static String statusLabel(LibraryFeeStatus status){return switch(status){case PENDING->"待缴费";case PAID->"已缴费";case WAIVED->"已免除";case REFUNDED->"已退款";};}
    private static String conditionLabel(LibraryReturnCondition value){return switch(value){case NORMAL->"正常";case DAMAGED->"破损";case LOST->"遗失";};}
    private static String damageLabel(LibraryDamageLevel value){return value==LibraryDamageLevel.MINOR?"轻微破损":"严重破损";}
    private static VBox empty(String title,String text){VBox box=new VBox(10,icon(Feather.CHECK_CIRCLE,30),label(title,"library-section-title"),label(text,"library-muted"));box.setPadding(new Insets(35,20,35,20));return box;}
}
