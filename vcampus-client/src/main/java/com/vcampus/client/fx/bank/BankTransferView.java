package com.vcampus.client.fx.bank;

import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.math.BigDecimal;
import java.util.function.BiConsumer;

final class BankTransferView extends ScrollPane {
    private final VBox content=new VBox(22);
    private final TextField username=new TextField(),amount=new TextField();
    private final BiConsumer<String,String> prepare;
    BankTransferView(BiConsumer<String,String> prepare){
        this.prepare=prepare;setId("bank-transfer-view");setContent(content);setFitToWidth(true);getStyleClass().add("bank-scroll");
        username.setId("bank-recipient");username.setPromptText("收款账号 / 学号");
        amount.setId("bank-amount");amount.setPromptText("0.00");
    }
    void form(){
        VBox card=BankUi.card(BankUi.label("填写转账信息","bank-section-title"),
                BankUi.field("收款账号 / 学号",username),BankUi.field("转账金额（元）",amount),
                BankUi.label("下一步核对收款人及金额，确认后才会扣款。","bank-muted"),
                BankUi.primary("下一步：核对信息","bank-transfer-next",()->prepare.accept(username.getText(),amount.getText())));
        card.setMaxWidth(660);show(1,card);
    }
    void confirm(BankOperation op,BigDecimal balance,Runnable submit,Runnable edit){
        VBox card=BankUi.card(BankUi.label("核对收款信息","bank-section-title"),
                BankUi.detail("收款人",op.displayName()),BankUi.detail("收款账号",op.username()),
                BankUi.detail("转账金额",BankUi.money(op.amount())),
                BankUi.detail("预计转账后余额",BankUi.money(balance.subtract(op.amount()))),
                BankUi.label("请核对收款账号与金额，转账成功后不能撤回。","bank-notice"),
                BankUi.actions(BankUi.button("返回修改","bank-transfer-edit",edit),
                        BankUi.primary("确认转账","bank-transfer-submit",submit)));
        card.setMaxWidth(660);show(2,card);
    }
    void receipt(BankOperation op,BankData.Receipt receipt,Runnable ledger,Runnable home){
        VBox card=BankUi.card(BankUi.label("✓ "+(receipt.duplicate()?"该笔交易已处理":"交易成功"),"bank-success"),
                BankUi.label(BankUi.money(op.amount()),"bank-receipt-amount"),
                BankUi.detail(op.kind()==BankOperation.Kind.TRANSFER?"收款人":"充值对象",op.displayName()+" · "+op.username()),
                BankUi.detail("业务编号",receipt.reference()),BankUi.detail("交易后余额",BankUi.money(receipt.balanceAfter())),
                BankUi.actions(BankUi.button("返回总览",null,home),BankUi.primary("查看流水回执",null,ledger)));
        card.setId("bank-receipt");card.setMaxWidth(660);show(3,card);
    }
    void pending(BankOperation op,Runnable retry,Runnable ledger){
        boolean submitting=op.state()==BankOperation.State.SUBMITTING;
        Button button=BankUi.primary("使用原业务编号重试","bank-operation-retry",retry);button.setDisable(submitting);
        VBox card=BankUi.card(BankUi.label(submitting?"正在提交，请稍候":"交易结果待确认","bank-section-title"),
                BankUi.detail("操作",op.kind()==BankOperation.Kind.TRANSFER?"转账":"充值"),
                BankUi.detail("目标账号",op.username()),BankUi.detail("金额",BankUi.money(op.amount())),
                BankUi.detail("业务编号",op.id()),
                BankUi.label("请先核对这笔交易的流水；重试会复用原业务编号，防止重复入账。","bank-notice"),
                BankUi.actions(button,BankUi.button("查询本笔流水",null,ledger)));
        card.setMaxWidth(660);show(2,card);
    }
    private void show(int step,VBox card){
        HBox steps=new HBox(12);
        String[] names={"1  填写信息","2  核对确认","3  转账结果"};
        for(int i=0;i<3;i++){Label label=BankUi.label(names[i],i+1==step?"bank-step-active":"bank-step");label.setMaxWidth(Double.MAX_VALUE);HBox.setHgrow(label,Priority.ALWAYS);steps.getChildren().add(label);}
        content.getChildren().setAll(BankUi.label("校园转账","bank-title"),steps,card);
    }
}
