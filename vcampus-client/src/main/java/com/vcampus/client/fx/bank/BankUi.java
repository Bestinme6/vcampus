package com.vcampus.client.fx.bank;

import com.vcampus.common.model.*;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class BankUi {
    private BankUi() { }
    static Label label(String text,String style) {
        Label label=new Label(text);label.getStyleClass().add(style);label.setWrapText(true);return label;
    }
    static Button button(String text,String id,Runnable action) {
        Button button=new Button(text);if(id!=null)button.setId(id);
        button.setOnAction(e->action.run());return button;
    }
    static Button primary(String text,String id,Runnable action) {
        Button b=button(text,id,action);b.getStyleClass().add("bank-primary");return b;
    }
    static HBox row(Node... nodes){HBox row=new HBox(12,nodes);row.setAlignment(Pos.CENTER_LEFT);return row;}
    static FlowPane actions(Node... nodes){return new FlowPane(10,10,nodes);}
    static Region spacer(){Region r=new Region();HBox.setHgrow(r,Priority.ALWAYS);return r;}
    static VBox card(Node... nodes){VBox b=new VBox(15,nodes);b.getStyleClass().add("bank-card");b.setMinWidth(0);return b;}
    static VBox field(String text,Node input){return new VBox(6,label(text,"bank-field-label"),input);}
    static ScrollPane scroll(Node content){ScrollPane s=new ScrollPane(content);s.setFitToWidth(true);s.getStyleClass().add("bank-scroll");return s;}
    static String money(BigDecimal amount){return "¥ "+String.format(java.util.Locale.ROOT,"%,.2f",amount);}
    static String time(Instant instant){return instant==null?"—":DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(instant);}
    static String type(BankLedgerType type){return switch(type){
        case ADMIN_TOPUP->"管理员充值";case TRANSFER_OUT->"转账支出";case TRANSFER_IN->"转账收入";
        case SHOP_PAYMENT->"商店支付";case SHOP_REFUND->"商店退款";};}
    static String signed(BankData.Entry e){return (e.direction()==BankLedgerDirection.CREDIT?"+":"−")+money(e.amount());}
    static Node entry(BankData.Entry e,Runnable detail){
        Label icon=label(switch(e.type()){case ADMIN_TOPUP->"充";case TRANSFER_IN->"收";case TRANSFER_OUT->"转";case SHOP_PAYMENT->"购";case SHOP_REFUND->"退";},"bank-entry-icon");
        VBox copy=new VBox(3,label(type(e.type()),"bank-entry-title"),label(e.description(),"bank-muted"),label(time(e.createdAt()),"bank-small"));
        copy.setMinWidth(0);HBox.setHgrow(copy,Priority.ALWAYS);
        Label amount=label(signed(e),e.direction()==BankLedgerDirection.CREDIT?"bank-credit":"bank-debit");
        amount.setMinWidth(Region.USE_PREF_SIZE);
        HBox row=row(icon,copy,amount,button("详情",null,detail));row.getStyleClass().add("bank-entry");return row;
    }
    static Node detail(String label,String value){return new VBox(3,BankUi.label(label,"bank-small"),BankUi.label(value,"bank-detail-value"));}
}
