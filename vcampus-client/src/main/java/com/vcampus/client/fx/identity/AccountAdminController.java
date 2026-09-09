package com.vcampus.client.fx.identity;

import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import javafx.scene.Parent;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class AccountAdminController implements AutoCloseable,AccountAdminView.Listener {
    private final AccountGateway gateway;private final Executor executor;private final AccountAdminView view;private final RequestGate gate=new RequestGate();private boolean active;private String keyword="";private UserRole identity;private Boolean enabled;private int page=1;private List<AccountData.Account> rows=List.of();private AccountData.Account selected;private AccountData.References references;
    public AccountAdminController(AccountGateway gateway,Executor executor,Runnable back){this.gateway=gateway;this.executor=executor;view=new AccountAdminView(back);view.setListener(this);}
    public Parent view(){return view;}AccountAdminView typedView(){return view;}
    public void activate(){active=true;if(references==null)async(()->gateway.references(),value->references=value,view::showError);search(keyword,identity,enabled,page);}
    public void deactivate(){active=false;gate.next();}
    @Override public void search(String keyword,UserRole identity,Boolean enabled,int page){this.keyword=keyword==null?"":keyword.trim();this.identity=identity;this.enabled=enabled;this.page=Math.max(1,page);long token=gate.next();async(()->gateway.search(this.keyword,this.identity,this.enabled,this.page),result->{if(!gate.accepts(token))return;this.page=result.page();rows=result.rows().stream().filter(row->row.baseIdentity()!=UserRole.SUPER_ADMIN).toList();view.showPage(new AccountData.Page(rows,result.page(),result.pageSize(),result.total()));if(selected!=null){rows.stream().filter(row->row.userId()==selected.userId()).findFirst().ifPresentOrElse(this::showSelected,()->{selected=null;view.clearDetail();});}},view::showError);}
    @Override public void select(long id){rows.stream().filter(row->row.userId()==id).findFirst().ifPresent(this::showSelected);}
    @Override public void create(){if(references==null){view.showError("基础数据尚未加载完成，请稍后重试");return;}AccountDialogs.create(view,references).ifPresent(values->mutate(()->gateway.create(values),"账号“"+values.get("number")+"”已创建",true));}
    @Override public void editRoles(){if(selected==null)return;AccountDialogs.roles(view,selected).ifPresent(roles->mutate(()->gateway.updateRoles(selected.userId(),roles),"管理员角色已更新",false));}
    @Override public void toggle(){if(selected==null||!AccountDialogs.confirmToggle(view,selected))return;boolean target=!selected.enabled();mutate(()->gateway.setEnabled(selected.userId(),target),target?"账号已启用":"账号已停用",false);}
    @Override public void resetPassword(){if(selected==null)return;AccountDialogs.password(view,selected).ifPresent(password->{char[] copy=Arrays.copyOf(password,password.length);Arrays.fill(password,'\0');mutate(()->{try{gateway.resetPassword(selected.userId(),copy);}finally{Arrays.fill(copy,'\0');}},"临时密码已重置",false);});}
    private void showSelected(AccountData.Account value){selected=value;view.showDetail(value);}
    private void mutate(IoAction action,String message,boolean created){async(()->{action.run();return null;},ignored->{view.showBanner(message);if(created){keyword="";identity=null;enabled=null;page=1;view.resetFilters();}search(keyword,identity,enabled,page);},view::showError);}
    private <T>void async(IoSupplier<T> work,Consumer<T> success,Consumer<String> failure){CompletableFuture.supplyAsync(()->{try{return work.get();}catch(Exception error){throw new RuntimeException(error);}},executor).whenComplete((value,error)->Platform.runLater(()->{if(!active)return;if(error==null)success.accept(value);else failure.accept(message(error));}));}
    @Override public void close(){active=false;gate.close();}
    private static String message(Throwable error){Throwable value=error;while(value.getCause()!=null)value=value.getCause();return value.getMessage()==null?"请求失败":value.getMessage();}
    @FunctionalInterface private interface IoSupplier<T>{T get()throws Exception;}@FunctionalInterface private interface IoAction{void run()throws Exception;}
}
