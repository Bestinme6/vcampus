package com.vcampus.client.fx.identity;

import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountAdminControllerTest {
    @BeforeAll static void toolkit()throws Exception{CountDownLatch ready=new CountDownLatch(1);try{Platform.startup(ready::countDown);}catch(IllegalStateException e){ready.countDown();}assertTrue(ready.await(15,TimeUnit.SECONDS));Platform.setImplicitExit(false);}
    @Test void activateLoadsAccountsAndSelectionShowsDetail()throws Exception{FakeGateway gateway=new FakeGateway();AccountAdminController controller=fx(()->new AccountAdminController(gateway,Runnable::run,()->{}));fx(()->{controller.activate();return null;});flush();assertEquals(List.of("T0000031"),fx(()->controller.typedView().visibleUsernames()));fx(()->{controller.select(31);return null;});assertEquals("李老师",fx(()->controller.typedView().detailName()));controller.close();}
    private static <T>T fx(java.util.concurrent.Callable<T> work)throws Exception{FutureTask<T> task=new FutureTask<>(work);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}private static void flush()throws Exception{fx(()->null);fx(()->null);}
    private static final class FakeGateway implements AccountGateway{final AccountData.Account account=new AccountData.Account(31,"T0000031","李老师",UserRole.TEACHER,Set.of(UserRole.ACADEMIC_ADMIN),true,false,"");@Override public AccountData.Page search(String k,UserRole i,Boolean e,int p){return new AccountData.Page(List.of(account),1,8,1);}@Override public AccountData.References references(){return new AccountData.References(List.of(),List.of(),List.of());}@Override public void create(Map<String,String> v){}@Override public void updateRoles(long id,Set<UserRole> r){}@Override public void setEnabled(long id,boolean e){}@Override public void resetPassword(long id,char[] p){} }
}
