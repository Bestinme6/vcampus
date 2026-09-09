package com.vcampus.client.fx.notification;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.client.ui.NotificationDestination;
import com.vcampus.client.ui.NotificationViewData.NotificationDetail;
import com.vcampus.common.model.NotificationSource;
import javafx.application.Platform;
import javafx.scene.Parent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class NotificationController implements AutoCloseable,NotificationView.Listener {
    private final NotificationGateway gateway;
    private final Executor executor;
    private final NotificationView view;
    private final Consumer<NotificationDestination> navigator;
    private final Runnable unreadRefresh;
    private boolean active;
    private String keyword="";
    private NotificationSource source;
    private Boolean read;
    private int page=1;
    private long listVersion,detailVersion,unreadVersion;

    public NotificationController(VCampusClient client,String token,Executor executor,Runnable back,Consumer<NotificationDestination> navigator,Runnable unreadRefresh){
        this(new SocketNotificationGateway(client,token),executor,back,navigator,unreadRefresh);
    }

    NotificationController(NotificationGateway gateway,Executor executor,Runnable back,Consumer<NotificationDestination> navigator,Runnable unreadRefresh){
        this.gateway=Objects.requireNonNull(gateway,"gateway");this.executor=Objects.requireNonNull(executor,"executor");
        this.navigator=Objects.requireNonNull(navigator,"navigator");this.unreadRefresh=Objects.requireNonNull(unreadRefresh,"unreadRefresh");
        view=new NotificationView(Objects.requireNonNull(back,"back"));view.setListener(this);
    }

    public Parent view(){return view;}
    NotificationView typedView(){return view;}

    public void activate(){active=true;loadUnread();search(keyword,source,read,page);}
    public void deactivate(){active=false;listVersion++;detailVersion++;unreadVersion++;}

    @Override public void search(String keyword,NotificationSource source,Boolean read,int page){
        this.keyword=keyword==null?"":keyword.trim();this.source=source;this.read=read;this.page=Math.max(1,page);
        long version=++listVersion;view.showStatus("",false);
        async(()->gateway.search(this.keyword,this.source,this.read,this.page),result->{
            if(version!=listVersion)return;this.page=result.page();view.showPage(result);
        },message->{if(version==listVersion)view.showStatus(message,true);});
    }

    @Override public void select(long notificationId){
        long version=++detailVersion;
        async(()->gateway.detail(notificationId),detail->{
            if(version!=detailVersion)return;view.showDetail(detail);if(!detail.read())markRead(detail);
        },message->{if(version==detailVersion)view.showStatus(message,true);});
    }

    @Override public void markAllRead(){
        view.busy(true);
        async(()->{gateway.markAllRead();return null;},ignored->{
            view.busy(false);view.markCurrentRead();view.showUnreadCount(0);view.showStatus("全部消息已标为已读",false);
            unreadRefresh.run();search(keyword,source,read,page);
        },message->{view.busy(false);view.showStatus(message,true);});
    }

    @Override public void navigate(NotificationDestination destination){if(destination.navigable())navigator.accept(destination);}

    private void markRead(NotificationDetail detail){
        async(()->{gateway.markRead(detail.id());return null;},ignored->{
            if(view.selectedId()==detail.id())view.markCurrentRead();unreadRefresh.run();loadUnread();
            if(Boolean.FALSE.equals(read))search(keyword,source,read,page);
        },message->view.showStatus(message,true));
    }

    private void loadUnread(){
        long version=++unreadVersion;
        async(gateway::unreadCount,count->{if(version==unreadVersion)view.showUnreadCount(count);},message->{if(version==unreadVersion)view.showStatus(message,true);});
    }

    private <T>void async(IoSupplier<T> work,Consumer<T> success,Consumer<String> failure){
        CompletableFuture.supplyAsync(()->{try{return work.get();}catch(Exception error){throw new RuntimeException(error);}},executor)
                .whenComplete((value,error)->Platform.runLater(()->{if(!active)return;if(error==null)success.accept(value);else failure.accept(message(error));}));
    }

    @Override public void close(){deactivate();}
    private static String message(Throwable error){Throwable value=error;while(value.getCause()!=null)value=value.getCause();return value.getMessage()==null?"请求失败":value.getMessage();}
    @FunctionalInterface private interface IoSupplier<T>{T get()throws Exception;}
}
