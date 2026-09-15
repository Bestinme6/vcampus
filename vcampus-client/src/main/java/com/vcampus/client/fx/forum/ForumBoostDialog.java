package com.vcampus.client.fx.forum;

import com.vcampus.common.model.ForumBoostTier;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

public final class ForumBoostDialog extends Dialog<Void> {
    private final Consumer<ForumBoostTier> purchase;
    private final Map<ForumBoostTier,Button> tierButtons=new EnumMap<>(ForumBoostTier.class);
    private final Label limits=ForumStyles.label("","forum-boost-limits");
    private final Label status=ForumStyles.label("","forum-boost-status");
    private ForumData.BoostState state;

    public ForumBoostDialog(ForumData.BoostState state,Consumer<ForumBoostTier> purchase) {
        this.purchase=purchase;
        setTitle("助力推荐");
        initModality(Modality.APPLICATION_MODAL);
        getDialogPane().setId("forum-boost-dialog");
        limits.setId("forum-boost-limits");
        status.setId("forum-boost-status");
        Label explanation=ForumStyles.label(
                "选择金额后立即增加对应初始热度；每 12 小时减半，支付 48 小时后失效。",
                "forum-muted");
        Label warning=ForumStyles.label("热度每 12 小时减半，48 小时后失效；点击具体金额即自动扣费；支付成功后不可退款。","forum-boost-warning");
        warning.setId("forum-boost-warning");
        FlowPane choices=new FlowPane(10,10);
        for(ForumBoostTier tier:ForumBoostTier.values()) {
            Button button=new Button("支付 "+tier.amount().stripTrailingZeros().toPlainString()+" 元并助力 · +"+tier.initialHeat()+" 热度");
            button.setId(id(tier));
            button.getStyleClass().add("forum-boost-tier");
            button.setOnAction(event->purchase.accept(tier));
            tierButtons.put(tier,button);choices.getChildren().add(button);
        }
        VBox content=new VBox(14,ForumStyles.label("让好内容被更多人看见","forum-section-title"),
                explanation,choices,limits,warning,status);
        content.getStyleClass().add("forum-boost-content");
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        update(state);
    }

    public void update(ForumData.BoostState value) {
        state=value;
        int operations=Math.max(0,3-value.userOperationsUsed());
        BigDecimal userRemaining=new BigDecimal("30.00").subtract(value.userAmountUsed()).max(BigDecimal.ZERO);
        BigDecimal postRemaining=new BigDecimal("200.00").subtract(value.postAmountUsed()).max(BigDecimal.ZERO);
        limits.setText("今日剩余 "+operations+" 次 · 个人剩余额度 "+money(userRemaining)+
                " 元 · 帖子剩余额度 "+money(postRemaining)+" 元 · 账户余额 "+money(value.balance())+" 元");
        for(var entry:tierButtons.entrySet())
            entry.getValue().setDisable(!value.eligible()||!value.availableTiers().contains(entry.getKey()));
        status.setText(value.eligible()?"请选择助力金额":value.reason());
    }

    public void busy(boolean value) {
        tierButtons.forEach((tier,button)->button.setDisable(value||!state.eligible()||
                !state.availableTiers().contains(tier)));
        if(value)status.setText("正在扣费并增加热度…");
    }

    public void success(ForumData.BoostReceipt receipt) {
        tierButtons.values().forEach(button->button.setDisable(true));
        status.setText("支付 "+money(receipt.amount())+" 元成功，增加 "+receipt.initialHeat()+
                " 初始热度；余额 "+money(receipt.balanceAfter())+" 元，流水号 "+receipt.bankReference());
    }

    public void failure(String message) {
        update(state);status.setText(message);
    }

    public void uncertain(String message,ForumBoostTier retryTier) {
        tierButtons.forEach((tier,button)->button.setDisable(tier!=retryTier));
        status.setText(message+"；可点击原档位重试，系统不会重复扣费");
    }

    private String money(BigDecimal value){return value.setScale(2).toPlainString();}
    private String id(ForumBoostTier tier){return switch(tier){
        case FIVE->"forum-boost-tier-five";case TEN->"forum-boost-tier-ten";case FIFTEEN->"forum-boost-tier-fifteen";};}
}
