package com.vcampus.client.fx.identity;

import com.vcampus.common.model.StudentStatus;
import javafx.application.Platform;
import javafx.scene.Parent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class StudentAdminController implements AutoCloseable,StudentAdminView.Listener {
    private final StudentGateway gateway;private final Executor executor;private final StudentAdminView view;private final RequestGate listGate=new RequestGate(),detailGate=new RequestGate();private boolean active;private String keyword="";private StudentStatus status;private int page=1;private StudentData.References references;private StudentData.Profile selected;
    public StudentAdminController(StudentGateway gateway,Executor executor,Runnable back){this.gateway=gateway;this.executor=executor;view=new StudentAdminView(back);view.setListener(this);}
    public Parent view(){return view;}StudentAdminView typedView(){return view;}
    public void activate(){active=true;if(references==null)async(()->gateway.references(),value->references=value,view::showError);search(keyword,status,page);}
    public void deactivate(){active=false;listGate.next();detailGate.next();}
    @Override public void search(String keyword,StudentStatus status,int page){this.keyword=keyword==null?"":keyword.trim();this.status=status;this.page=Math.max(1,page);long token=listGate.next();async(()->gateway.search(this.keyword,this.status,this.page),result->{if(!active||!listGate.accepts(token))return;this.page=result.page();view.showPage(result);if(selected!=null&&result.rows().stream().noneMatch(row->row.id()==selected.id())){selected=null;view.clearDetail();}},view::showError);}
    @Override public void select(long id){long token=detailGate.next();async(()->gateway.get(id),profile->{if(active&&detailGate.accepts(token)){selected=profile;view.showDetail(profile);}},view::showError);}
    @Override public void editProfile(){if(selected==null||references==null)return;StudentDialogs.editProfile(view,selected,references).ifPresent(value->{Map<String,String> fields=new LinkedHashMap<>();fields.put("studentId",Long.toString(selected.id()));fields.put("fullName",value.fullName());fields.put("gender",value.gender().name());fields.put("birthDate",value.birthDate());fields.put("departmentId",Long.toString(value.departmentId()));fields.put("majorId",Long.toString(value.majorId()));fields.put("classId",Long.toString(value.classId()));fields.put("enrollmentYear",Integer.toString(value.enrollmentYear()));fields.put("phone",value.phone());fields.put("email",value.email());fields.put("address",value.address());mutate(()->gateway.updateProfile(fields));});}
    @Override public void editContact(){if(selected==null)return;StudentDialogs.editContact(view,selected).ifPresent(value->mutate(()->gateway.updateContact(selected.id(),value.phone(),value.email(),value.address())));}
    @Override public void changeStatus(){if(selected==null)return;StudentDialogs.changeStatus(view,selected).ifPresent(value->mutate(()->gateway.changeStatus(selected.id(),value.status(),value.reason())));}
    @Override public void showHistory(){if(selected==null)return;async(()->gateway.history(selected.id()),rows->StudentDialogs.showHistory(view,rows),view::showError);}
    private void mutate(IoAction action){long id=selected.id();async(()->{action.run();return id;},ignored->{IdentityUi.showSuccess(view,"学生档案已更新");search(keyword,status,page);select(id);},message->IdentityUi.showError(view,message));}
    private <T>void async(IoSupplier<T> work,Consumer<T> success,Consumer<String> failure){CompletableFuture.supplyAsync(()->{try{return work.get();}catch(Exception error){throw new RuntimeException(error);}},executor).whenComplete((value,error)->Platform.runLater(()->{if(!active)return;if(error==null)success.accept(value);else failure.accept(message(error));}));}
    @Override public void close(){active=false;listGate.close();detailGate.close();}
    private static String message(Throwable error){Throwable value=error;while(value.getCause()!=null)value=value.getCause();return value.getMessage()==null?"请求失败":value.getMessage();}
    @FunctionalInterface private interface IoSupplier<T>{T get()throws Exception;}@FunctionalInterface private interface IoAction{void run()throws Exception;}
}
