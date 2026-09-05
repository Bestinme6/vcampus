package com.vcampus.client.fx.bank;

import com.vcampus.common.model.BankAccountStatus;
import javafx.scene.control.*;
import javafx.scene.layout.*;

final class BankAdminView extends ScrollPane {
    interface Listener{
        void search(String keyword,BankAccountStatus status,int page);
        void lookup(String username);
        void topUp(BankData.Recipient recipient,String amount);
        void status(BankData.Recipient recipient,boolean frozen);
        void ledger(String username);
    }
    private final VBox content=new VBox(18),rows=BankUi.card(),selection=BankUi.card();
    private final TextField keyword=new TextField(),target=new TextField(),amount=new TextField();
    private final ComboBox<String> status=new ComboBox<>();
    private final Listener listener;
    private int page=1;
    private final Label paging=BankUi.label("","bank-muted");
    private final Button previous,next;
    BankAdminView(Listener listener){
        this.listener=listener;setId("bank-admin-view");setContent(content);setFitToWidth(true);getStyleClass().add("bank-scroll");
        keyword.setPromptText("用户名 / 显示名");target.setPromptText("精确用户名 / 学号");
        amount.setPromptText("0.00");amount.setId("bank-admin-amount");
        status.getItems().addAll("全部状态","正常","已冻结");status.getSelectionModel().selectFirst();
        previous=BankUi.button("上一页",null,()->search(page-1));next=BankUi.button("下一页",null,()->search(page+1));
        VBox list=new VBox(18,rows,BankUi.row(previous,paging,next),
                BankUi.card(BankUi.label("按账号办理","bank-section-title"),
                        BankUi.label("未开户用户也可由管理员充值或设置账户状态。","bank-muted"),
                        BankUi.field("用户名 / 学号",target),
                        BankUi.button("核对账号","bank-admin-lookup",()->listener.lookup(target.getText()))));
        TilePane columns=new TilePane(18,18,list,selection);
        columns.setTileAlignment(javafx.geometry.Pos.TOP_LEFT);
        content.widthProperty().addListener((observable,oldWidth,newWidth)->{
            double width=newWidth.doubleValue();boolean wide=width>=780;
            columns.setPrefColumns(wide?2:1);columns.setPrefTileWidth(wide?(width-18)/2:width);
        });
        content.getChildren().setAll(BankUi.label("账户管理","bank-title"),
                BankUi.actions(keyword,status,BankUi.primary("查询账户","bank-account-search",()->search(1))),columns);
        clearSelection();
    }
    private void search(int page){
        int index=status.getSelectionModel().getSelectedIndex();
        listener.search(keyword.getText(),index==0?null:index==1?BankAccountStatus.ACTIVE:BankAccountStatus.FROZEN,page);
    }
    void show(BankData.Page<BankData.Account> data){
        page=data.page();paging.setText("第 "+page+" / "+data.pages()+" 页 · "+data.total()+" 个账户");
        previous.setDisable(page<=1);next.setDisable(page>=data.pages());rows.getChildren().clear();
        if(data.rows().isEmpty())rows.getChildren().add(BankUi.label("暂无符合条件的账户，可在下方按账号办理。","bank-muted"));
        for(var a:data.rows()){
            Button select=BankUi.button(a.displayName()+" · "+a.username()+"    "+BankUi.money(a.balance())
                    +"    "+(a.status()==BankAccountStatus.ACTIVE?"正常":"已冻结"),null,
                    ()->select(new BankData.Recipient(a.username(),a.displayName(),false),a));
            select.setMaxWidth(Double.MAX_VALUE);select.setWrapText(true);select.getStyleClass().add("bank-account-row");rows.getChildren().add(select);
        }
    }
    void clearSelection(){selection.getChildren().setAll(BankUi.label("请先从列表选择账户，或核对完整账号。","bank-muted"));}
    void select(BankData.Recipient recipient,BankData.Account account){
        amount.clear();target.setText(recipient.username());
        selection.getChildren().setAll(BankUi.label("选中账户","bank-section-title"),
                BankUi.label(recipient.displayName()+" · "+recipient.username(),"bank-entry-title"));
        if(account!=null)selection.getChildren().add(BankUi.detail("当前余额 / 状态",BankUi.money(account.balance())
                +" · "+(account.status()==BankAccountStatus.ACTIVE?"正常":"已冻结")));
        else selection.getChildren().add(BankUi.label("账号已核对；余额与状态请在账户列表查询。","bank-muted"));
        selection.getChildren().addAll(BankUi.field("充值金额（元）",amount),
                BankUi.actions(BankUi.primary("核对充值信息","bank-admin-topup",()->listener.topUp(recipient,amount.getText())),
                        BankUi.button("冻结账户",null,()->confirmStatus(recipient,true)),
                        BankUi.button("解冻账户",null,()->confirmStatus(recipient,false)),
                        BankUi.button("查看该账户流水",null,()->listener.ledger(recipient.username()))));
    }
    private void confirmStatus(BankData.Recipient recipient,boolean frozen){
        selection.getChildren().setAll(BankUi.label(frozen?"确认冻结账户":"确认解冻账户","bank-section-title"),
                BankUi.detail("操作对象",recipient.displayName()+" · "+recipient.username()),
                BankUi.label("冻结后不能主动转账或付款，仍可收款、退款和查询流水。","bank-notice"),
                BankUi.actions(BankUi.button("取消",null,()->select(recipient,null)),
                        BankUi.primary("确认"+(frozen?"冻结":"解冻"),"bank-admin-status-submit",()->listener.status(recipient,frozen))));
    }
    void confirmTopUp(BankOperation operation,Runnable submit,Runnable cancel){
        selection.getChildren().setAll(BankUi.label("确认充值","bank-section-title"),
                BankUi.detail("操作对象",operation.displayName()+" · "+operation.username()),
                BankUi.detail("充值金额",BankUi.money(operation.amount())),
                BankUi.actions(BankUi.button("取消",null,cancel),BankUi.primary("确认充值","bank-admin-topup-submit",submit)));
    }
}
