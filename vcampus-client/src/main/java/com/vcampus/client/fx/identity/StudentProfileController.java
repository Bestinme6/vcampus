package com.vcampus.client.fx.identity;

import javafx.application.Platform;
import javafx.scene.Parent;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class StudentProfileController implements AutoCloseable,StudentProfileView.Listener {
    private final StudentGateway gateway;private final Executor executor;private final StudentProfileView view;private final RequestGate gate=new RequestGate();private boolean active;
    public StudentProfileController(StudentGateway gateway,Executor executor,Runnable back){this.gateway=gateway;this.executor=executor;this.view=new StudentProfileView(back);this.view.setListener(this);}
    public Parent view(){return view;}
    public void activate(){active=true;reload();}
    public void deactivate(){active=false;gate.next();}
    @Override public void reload(){long token=gate.next();view.showLoading();CompletableFuture.supplyAsync(()->{try{return new Loaded(gateway.self(),gateway.history(null));}catch(Exception e){throw new RuntimeException(e);}},executor).whenComplete((data,error)->Platform.runLater(()->{if(!active||!gate.accepts(token))return;if(error!=null)view.showError(message(error));else{view.showProfile(data.profile());view.showHistory(data.history());}}));}
    @Override public void saveContact(String phone,String email,String address){long token=gate.next();CompletableFuture.runAsync(()->{try{gateway.updateOwnContact(phone,email,address);}catch(Exception e){throw new RuntimeException(e);}},executor).whenComplete((ignored,error)->Platform.runLater(()->{if(!active||!gate.accepts(token))return;if(error!=null)IdentityUi.showError(view,message(error));else{IdentityUi.showSuccess(view,"联系方式已更新");reload();}}));}
    @Override public void close(){active=false;gate.close();}
    private static String message(Throwable error){Throwable value=error;while(value.getCause()!=null)value=value.getCause();return value.getMessage()==null?"请求失败":value.getMessage();}
    private record Loaded(StudentData.Profile profile,List<StudentData.History> history){}
}
