package com.vcampus.client.fx.library;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.client.ui.LibraryViewData;
import com.vcampus.common.model.*;
import com.vcampus.common.protocol.ResponseMessage;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** A session-scoped library. All public methods are confined to the FX thread. */
public final class LibraryController implements AutoCloseable,LibraryView.Listener {
    private final VCampusClient client;
    private final String token;
    private final Set<UserRole> roles;
    private final Executor executor;
    private final Runnable back,unreadRefresh;
    private final LibraryView view;
    private final Set<Dialog<?>> dialogs=new HashSet<>();
    private LibraryAdminView admin;
    private long version;
    private boolean closed,active=true;
    private FutureTask<?> pending;
    private String notice="";

    public LibraryController(VCampusClient client,String token,Set<UserRole> roles,Executor executor,Runnable back,Runnable unreadRefresh){
        requireFx();this.client=Objects.requireNonNull(client);this.token=Objects.requireNonNull(token);this.roles=Set.copyOf(roles);
        RoleCompositionPolicy.requireValid(this.roles);this.executor=Objects.requireNonNull(executor);this.back=back;this.unreadRefresh=unreadRefresh;
        view=new LibraryView(roles,this);
    }
    public Parent view(){return view;}
    @Override public void back(){if(!closed)back.run();}
    @Override public void open(String route){
        requireFx();if(closed)return;active=true;
        switch(route){
            case "library","catalog"->{if(LibraryAccessPolicy.canBorrow(roles))view.search(1);else openAdmin();}
            case "library-loans","loans"->loans("active",1);
            case "library-reservations","reservations"->reservations("",1);
            case "admin"->openAdmin();
            default->view.status("没有找到要打开的图书馆页面",true);
        }
    }
    private void openAdmin(){
        if(!LibraryAccessPolicy.canManage(roles)){view.status("当前账号没有图书管理权限",true);return;}
        invalidate();active=true;view.busy(false);view.status("",false);
        if(admin==null)admin=new LibraryAdminView(client,token,executor,()->{if(!closed&&active)unreadRefresh.run();});
        view.showAdmin(admin);admin.activate("inventory");
    }
    @Override public void search(String keyword,String category,LibrarySort sort,int page){
        if(!LibraryAccessPolicy.canBorrow(roles)){openAdmin();return;}
        load(()->LibraryData.books(client.searchLibraryCatalog(token,keyword,category,page,false,sort)),view::showCatalog,
                ()->search(keyword,category,sort,page));
    }
    public void openBook(long bookId){requireFx();if(closed)return;active=true;detail(bookId);}
    @Override public void detail(long bookId){
        if(bookId<1){view.status("图书编号无效",true);return;}
        load(()->LibraryData.detail(client.getLibraryCatalogItem(token,bookId)),view::showDetail,()->detail(bookId));
    }
    @Override public void loans(String scope,int page){
        if(!LibraryAccessPolicy.canBorrow(roles)){view.status("当前账号没有个人借阅页面",true);return;}
        load(()->{var response=client.myLibraryLoans(token,scope,page);LibraryData.require(response);return LibraryViewData.loanPage(response);},view::showLoans,()->loans(scope,page));
    }
    @Override public void reservations(String status,int page){
        if(!roles.contains(UserRole.STUDENT)){view.status("归还预约提醒仅面向学生开放",true);return;}
        load(()->LibraryData.reservations(client.myLibraryReservations(token,status,page)),view::showReservations,()->reservations(status,page));
    }
    @Override public void borrow(LibraryData.Book item){
        if(!LibraryAccessPolicy.canBorrow(roles)||!item.book().enabled()||item.book().availableCopies()<1)return;
        if(!confirm("确认借阅","借阅《"+item.book().title()+"》？\n借期、借阅额度和库存由服务端再次校验。"))return;
        mutate(()->client.borrowLibraryBook(token,item.book().bookId()),()->loans("active",1));
    }
    @Override public void reserve(LibraryData.Book item){
        if(!roles.contains(UserRole.STUDENT)||!item.book().enabled()||item.onLoanCopies()<=0)return;
        if(!confirm("设置归还提醒","预约《"+item.book().title()+"》？\n正常归还后，系统会通知所有仍在等待的学生；不会排队或保留图书。"))return;
        mutate(()->client.createLibraryReservation(token,item.book().bookId()),()->reservations("",1));
    }
    @Override public void renew(LibraryViewData.LoanRow loan){
        if(!loan.renewable()||loan.returnedAt()!=null||!LibraryAccessPolicy.canBorrow(roles))return;
        if(confirm("确认续借","续借《"+loan.title()+"》？\n新的应还日期以服务器回执为准。"))mutate(()->client.renewLibraryLoan(token,loan.loanId()),()->loans("active",1));
    }
    @Override public void returnLoan(LibraryViewData.LoanRow loan){
        if(loan.returnedAt()!=null||!LibraryAccessPolicy.canBorrow(roles))return;
        if(confirm("确认归还","正常归还《"+loan.title()+"》？\n破损或遗失请联系图书管理员办理。"))mutate(()->client.returnLibraryLoan(token,loan.loanId()),()->loans("active",1));
    }
    @Override public void cancel(LibraryData.Reservation reservation){
        if(!roles.contains(UserRole.STUDENT)||!reservation.status().equals("WAITING"))return;
        if(confirm("取消预约","取消《"+reservation.title()+"》的归还提醒？"))mutate(()->client.cancelLibraryReservation(token,reservation.id()),()->reservations("",1));
    }
    private <T> void load(Callable<T> work,Consumer<T> render,Runnable retry){
        if(closed||!active)return;view.showLoading();request(work,value->{render.accept(value);view.status(notice,false);notice="";},error->view.showFailure(error,retry));
    }
    private void mutate(Callable<ResponseMessage> work,Runnable refresh){
        if(closed||!active)return;
        request(()->{var response=work.call();LibraryData.require(response);return response;},response->{
            notice=response.message();unreadRefresh.run();refresh.run();
        },error->view.status(error,true));
    }
    private <T> void request(Callable<T> work,Consumer<T> success,Consumer<String> failure){
        requireFx();if(closed||!active)return;
        long generation=++version;if(pending!=null)pending.cancel(true);
        view.busy(true);view.status("正在处理，请稍候…",false);
        FutureTask<T> task=new FutureTask<>(work){@Override protected void done(){
            if(isCancelled())return;
            Platform.runLater(()->{
                if(closed||!active||generation!=version)return;
                view.busy(false);
                try{success.accept(get());}catch(Exception error){Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
                    String message=cause instanceof java.io.IOException?"无法连接图书馆服务器，请稍后重试":Objects.requireNonNullElse(cause.getMessage(),"操作失败，请稍后重试");
                    view.status("",false);failure.accept(message);}
            });
        }};pending=task;
        try{executor.execute(task);}catch(RejectedExecutionException error){view.busy(false);failure.accept("图书馆连接已关闭，请重新进入");}
    }
    private boolean confirm(String title,String content){
        if(closed||!active)return false;
        Alert dialog=new Alert(Alert.AlertType.CONFIRMATION,content,ButtonType.OK,ButtonType.CANCEL);dialog.setTitle(title);dialog.setHeaderText(title);
        if(view.getScene()!=null&&view.getScene().getWindow()!=null)dialog.initOwner(view.getScene().getWindow());
        dialog.getDialogPane().getStylesheets().addAll(view.getStylesheets());dialogs.add(dialog);
        try{return dialog.showAndWait().filter(ButtonType.OK::equals).isPresent()&&!closed&&active;}finally{dialogs.remove(dialog);}
    }
    public void deactivate(){requireFx();active=false;invalidate();if(admin!=null){admin.close();admin=null;}}
    private void invalidate(){++version;if(pending!=null){pending.cancel(true);pending=null;}for(Dialog<?> dialog:List.copyOf(dialogs))dialog.close();dialogs.clear();}
    @Override public void close(){requireFx();if(closed)return;closed=true;deactivate();}
    private static void requireFx(){if(!Platform.isFxApplicationThread())throw new IllegalStateException("图书馆界面必须在 JavaFX 线程操作");}
}
