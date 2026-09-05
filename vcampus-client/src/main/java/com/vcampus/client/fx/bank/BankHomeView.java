package com.vcampus.client.fx.bank;

import com.vcampus.common.model.BankAccountStatus;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.function.Consumer;

final class BankHomeView extends ScrollPane {
    private final Consumer<String> navigate;
    private final Runnable refresh;
    private final VBox content=new VBox(20);
    BankHomeView(Consumer<String> navigate,Runnable refresh){
        this.navigate=navigate;this.refresh=refresh;setContent(content);setFitToWidth(true);
        setId("bank-home-view");getStyleClass().add("bank-scroll");
        content.getChildren().add(BankUi.label("正在读取账户…","bank-muted"));
    }
    void show(BankData.Account account,BankData.Ledger ledger){
        boolean frozen=account.status()==BankAccountStatus.FROZEN;
        Label status=BankUi.label(frozen?"● 账户已冻结":"● 账户正常",frozen?"bank-frozen":"bank-pill");
        Label amount=BankUi.label(BankUi.money(account.balance()),"bank-balance-amount");
        ToggleButton hide=new ToggleButton("隐藏金额");
        hide.setOnAction(e->{amount.setText(hide.isSelected()?"••••••":BankUi.money(account.balance()));hide.setText(hide.isSelected()?"显示金额":"隐藏金额");});
        VBox balance=new VBox(15,BankUi.row(BankUi.label("可用余额（元）","bank-balance-caption"),BankUi.spacer(),hide),amount,
                BankUi.label("校园虚拟账户 · "+account.username()+" · 更新于 "+BankUi.time(account.updatedAt()),"bank-balance-caption"));
        balance.getStyleClass().add("bank-balance");
        Button transfer=BankUi.primary("立即转账","bank-home-transfer",()->navigate.accept("bank-transfer"));transfer.setDisable(frozen);
        VBox entries=BankUi.card(BankUi.row(BankUi.label("最近流水","bank-section-title"),BankUi.spacer(),
                BankUi.button("查看全部",null,()->navigate.accept("bank-ledger"))));
        if(ledger.entries().rows().isEmpty())entries.getChildren().add(BankUi.label("暂无交易记录，发生收支后会显示在这里。","bank-muted"));
        ledger.entries().rows().stream().limit(5).forEach(e->entries.getChildren().add(BankUi.entry(e,()->navigate.accept("bank-reference/"+e.reference()))));
        content.getChildren().setAll(BankUi.row(BankUi.label("账户总览","bank-title"),BankUi.spacer(),status,
                BankUi.button("刷新账户",null,refresh)),balance,
                BankUi.actions(transfer,BankUi.button("查看流水",null,()->navigate.accept("bank-ledger")),
                        BankUi.button("商店订单",null,()->navigate.accept("shop-orders"))));
        if(frozen)content.getChildren().add(BankUi.label("账户已冻结，暂不能转账或付款；仍可收款、接收退款和查看流水。","bank-notice"));
        content.getChildren().add(entries);
    }
}
