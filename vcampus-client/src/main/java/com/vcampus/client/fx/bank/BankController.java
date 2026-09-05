package com.vcampus.client.fx.bank;

import com.vcampus.common.model.*;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.layout.VBox;
import java.math.BigDecimal;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Session-scoped coordinator. Query generations never discard a submitted payment's outcome. */
public final class BankController implements AutoCloseable {
    private final BankGateway gateway;
    private final Set<UserRole> roles;
    private final Executor executor;
    private final Consumer<String> external;
    private final Runnable changed;
    private final BankView view;
    private final BankHomeView home;
    private final BankTransferView transfer;
    private final BankLedgerView ledger;
    private final BankAdminView admin;
    private BankOperation operation;
    private BankData.Receipt receipt;
    private long generation;
    private boolean active=true,closed;
    private String route="bank";

    public BankController(BankGateway gateway,Set<UserRole> roles,Executor executor,Runnable back,
                          Consumer<String> external,Runnable changed){
        requireFx();this.gateway=java.util.Objects.requireNonNull(gateway);this.roles=Set.copyOf(roles);
        this.executor=java.util.Objects.requireNonNull(executor);this.external=external;this.changed=changed;
        view=new BankView(roles,this::open,back);
        home=new BankHomeView(this::navigate,()->open("bank"));
        transfer=new BankTransferView(this::prepareTransfer);
        ledger=new BankLedgerView(this::searchLedger,reference->query(()->gateway.order(reference),
                id->external.accept("shop-order/"+id)));
        admin=new BankAdminView(new BankAdminView.Listener(){
            public void search(String keyword,BankAccountStatus status,int page){searchAccounts(keyword,status,page);}
            public void lookup(String username){query(()->gateway.recipient(username),r->admin.select(r,null));}
            public void topUp(BankData.Recipient r,String amount){prepareTopUp(r,amount);}
            public void status(BankData.Recipient r,boolean frozen){setFrozen(r,frozen);}
            public void ledger(String username){searchLedger(new BankData.Query(true,username,null,"",null,null,"",1));}
        });
    }
    public Parent view(){requireFx();return view;}
    public void open(String requested){
        requireFx();if(closed)return;active=true;++generation;view.busy(false);view.status("",false);
        route=requested==null?"bank":requested;
        if(route.startsWith("bank-admin")&&!BankAccessPolicy.canManage(roles)){view.status("当前账号无权管理银行",true);return;}
        if(route.equals("bank-transfer")){
            view.page(transfer,route);
            if(operation!=null&&!operation.terminal())showPending();
            else{transfer.form();}
        }else if(route.equals("bank-ledger")){
            searchLedger(BankData.Query.mine());
        }else if(route.startsWith("bank-reference/")){
            searchLedger(new BankData.Query(false,"",null,"",null,null,route.substring("bank-reference/".length()),1));
        }else if(route.equals("bank-admin")){
            admin.clearSelection();view.page(admin,route);searchAccounts("",null,1);
        }else if(route.equals("bank-admin-ledger")){
            searchLedger(new BankData.Query(true,"",null,"",null,null,"",1));
        }else{
            route="bank";view.page(home,route);
            query(()->new HomeData(gateway.account(),gateway.ledger(BankData.Query.mine())),data->{
                home.show(data.account(),data.ledger());pendingNotice();
            });
        }
    }
    private void navigate(String route){if(route.startsWith("bank"))open(route);else external.accept(route);}
    void prepareTransfer(String username,String amount){
        requireFx();if(!available()||resumeOutstanding())return;
        final BigDecimal value;
        try{value=MoneyPolicy.parsePositive(amount);if(username==null||username.isBlank())throw new IllegalArgumentException("请填写收款账号");}
        catch(IllegalArgumentException error){view.status(error.getMessage(),true);return;}
        route="bank-transfer";view.page(transfer,route);
        query(()->new Preview(gateway.account(),gateway.recipient(username.trim())),p->{
            if(p.recipient().self()){view.status("不能向自己转账",true);return;}
            if(p.account().status()==BankAccountStatus.FROZEN){view.status("账户已冻结，不能转账",true);return;}
            if(p.account().balance().compareTo(value)<0){view.status("余额不足，请调整金额",true);return;}
            operation=new BankOperation(BankOperation.Kind.TRANSFER,p.recipient().username(),p.recipient().displayName(),value.toPlainString());
            receipt=null;
            transfer.confirm(operation,p.account().balance(),this::submitOperation,()->{operation=null;transfer.form();});
        });
    }
    private void prepareTopUp(BankData.Recipient target,String amount){
        requireFx();if(!manager()||resumeOutstanding())return;
        try{
            var draft=new BankOperation(BankOperation.Kind.TOPUP,target.username(),target.displayName(),amount);
            // Recheck the exact username before showing the final administrative confirmation.
            query(()->gateway.recipient(draft.username()),r->{
                operation=new BankOperation(BankOperation.Kind.TOPUP,r.username(),r.displayName(),draft.amount().toPlainString());receipt=null;
                admin.confirmTopUp(operation,this::submitOperation,()->{operation=null;admin.select(r,null);});
            });
        }catch(IllegalArgumentException error){view.status(error.getMessage(),true);}
    }
    void submitOperation(){
        requireFx();if(!available()||operation==null)return;
        boolean previouslyUnknown=operation.state()==BankOperation.State.UNKNOWN;
        if(!operation.begin())return;
        BankOperation submitted=operation;
        ++generation;view.busy(false);route="bank-transfer";view.page(transfer,route);showPending();
        try{
            executor.execute(()->{
                try{
                    BankData.Receipt result=submitted.kind()==BankOperation.Kind.TRANSFER
                            ?gateway.transfer(submitted.username(),submitted.amount().toPlainString(),submitted.id())
                            :gateway.topUp(submitted.username(),submitted.amount().toPlainString(),submitted.id());
                    if(!submitted.id().equals(result.reference()))throw new IllegalArgumentException("业务编号不匹配");
                    Platform.runLater(()->{
                        if(closed)return;submitted.complete();receipt=result;
                        changed.run();
                        if(active&&route.equals("bank-transfer"))showReceipt();
                        else if(active&&route.equals("bank"))open("bank");
                        else if(active) view.status("资金操作已成功，可在流水中查看业务编号 "+submitted.id(),false);
                    });
                }catch(Exception error){
                    Platform.runLater(()->{
                        if(closed)return;
                        if(error instanceof BankData.Rejected&&!previouslyUnknown){
                            submitted.reject();
                            if(active){view.status(message(error),true);if(route.equals("bank-transfer"))transfer.form();}
                        }else{
                            submitted.unknown();
                            if(active){
                                if(route.equals("bank-transfer"))showPending();
                                view.status("交易结果待确认，请进入校园转账保留业务编号并查询流水或使用原编号重试。",true);
                            }
                        }
                    });
                }
            });
        }catch(java.util.concurrent.RejectedExecutionException error){
            submitted.unknown();showPending();view.status("请求未能执行，请使用原编号重试。",true);
        }
    }
    private void showReceipt(){
        view.status("",false);view.busy(false);view.page(transfer,"bank-transfer");
        transfer.receipt(operation,receipt,this::operationLedger,()->open("bank"));
    }
    private void showPending(){
        route="bank-transfer";view.page(transfer,route);view.busy(false);
        if(operation.state()==BankOperation.State.READY){
            // A confirmed draft revisited after navigation must still get a fresh user confirmation.
            VBox card=BankUi.card(BankUi.label("待确认资金操作","bank-section-title"),
                    BankUi.detail("类型",operation.kind()==BankOperation.Kind.TRANSFER?"转账":"充值"),
                    BankUi.detail("对象",operation.displayName()+" · "+operation.username()),
                    BankUi.detail("金额",BankUi.money(operation.amount())),
                    BankUi.actions(BankUi.button("取消草稿",null,()->{operation=null;open("bank");}),
                            BankUi.primary("确认提交","bank-operation-confirm",this::submitOperation)));
            view.page(BankUi.scroll(card),route);
        }else transfer.pending(operation,this::submitOperation,this::operationLedger);
    }
    private void operationLedger(){
        BankOperation current=operation;
        searchLedger(new BankData.Query(current.kind()==BankOperation.Kind.TOPUP,current.username(),null,"",null,null,current.id(),1));
    }
    private boolean resumeOutstanding(){
        if(operation!=null&&!operation.terminal()){showPending();return true;}
        return false;
    }
    private void pendingNotice(){
        if(operation!=null&&!operation.terminal())view.status("有一笔资金操作尚未处理完，请进入“校园转账”继续核对。业务编号："+operation.id(),true);
    }
    private void searchLedger(BankData.Query q){
        requireFx();if(!available()||q.administrative()&&!manager())return;
        route=q.administrative()?"bank-admin-ledger":"bank-ledger";
        ledger.configure(q);view.page(ledger,route);
        query(()->gateway.ledger(q),data->{
            ledger.show(data);
            if(operation!=null&&operation.state()==BankOperation.State.UNKNOWN
                    &&q.reference().equals(operation.id())
                    &&(operation.kind()==BankOperation.Kind.TRANSFER&&!q.administrative()
                    ||operation.kind()==BankOperation.Kind.TOPUP&&q.administrative()&&q.username().equals(operation.username()))){
                var expected=operation.kind()==BankOperation.Kind.TRANSFER?BankLedgerType.TRANSFER_OUT:BankLedgerType.ADMIN_TOPUP;
                var match=data.entries().rows().stream().filter(e->e.type()==expected&&e.reference().equals(operation.id())
                        &&e.amount().compareTo(operation.amount())==0).findFirst();
                if(match.isPresent()){operation.complete();receipt=new BankData.Receipt(match.get().balanceAfter(),operation.id(),true);view.status("已从流水确认本笔操作成功。",false);}
                else view.status("暂未查到本笔流水，不能据此确定失败；可使用原编号重试。",true);
            }
        });
    }
    private void searchAccounts(String keyword,BankAccountStatus status,int page){
        if(!available()||!manager())return;route="bank-admin";admin.clearSelection();view.page(admin,route);
        query(()->gateway.accounts(keyword,status,page),admin::show);
    }
    private void setFrozen(BankData.Recipient target,boolean frozen){
        if(!available()||!manager())return;
        query(()->{gateway.frozen(target.username(),frozen);return target;},r->{
            admin.clearSelection();view.status("已"+(frozen?"冻结":"解冻")+"账户 "+r.username()+"，请刷新账户列表。",false);changed.run();
        });
    }
    private <T>void query(Callable<T> task,Consumer<T> success){
        if(!available())return;long ticket=++generation;view.busy(true);view.status("正在读取银行数据…",false);
        try{
            executor.execute(()->{
                T value=null;Exception failure=null;
                try{value=task.call();}catch(Exception error){failure=error;}
                T result=value;Exception error=failure;
                Platform.runLater(()->{
                    if(!available()||generation!=ticket)return;
                    view.busy(false);
                    if(error!=null){view.status(message(error),true);return;}
                    view.status("",false);
                    try{success.accept(result);}catch(RuntimeException invalid){view.status(message(invalid),true);}
                });
            });
        }catch(java.util.concurrent.RejectedExecutionException error){view.busy(false);view.status("请求队列已关闭，请重新进入银行。",true);}
    }
    private boolean manager(){if(!BankAccessPolicy.canManage(roles)){view.status("当前账号没有银行管理权限",true);return false;}return true;}
    private boolean available(){return active&&!closed;}
    public void deactivate(){requireFx();active=false;++generation;view.busy(false);}
    public void close(){requireFx();closed=true;active=false;++generation;operation=null;receipt=null;}
    private static void requireFx(){if(!Platform.isFxApplicationThread())throw new IllegalStateException("银行界面必须在 JavaFX 线程调用");}
    private static String message(Throwable e){return e instanceof IOException?"连接失败，请检查服务器连接后重试":e.getMessage()==null?"银行请求失败":e.getMessage();}
    private record HomeData(BankData.Account account,BankData.Ledger ledger){}
    private record Preview(BankData.Account account,BankData.Recipient recipient){}
}
