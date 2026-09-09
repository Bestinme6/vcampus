package com.vcampus.client.fx.identity;

import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import javafx.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountAdminViewTest {
    @BeforeAll static void toolkit()throws Exception{CountDownLatch ready=new CountDownLatch(1);try{Platform.startup(ready::countDown);}catch(IllegalStateException e){ready.countDown();}assertTrue(ready.await(15,TimeUnit.SECONDS));Platform.setImplicitExit(false);}
    @Test void rendersAccountTableAndContextActions()throws Exception{FutureTask<Void> task=new FutureTask<>(()->{AccountAdminView view=new AccountAdminView(()->{});new Scene(view,1180,760);AccountData.Account account=new AccountData.Account(31,"T0000031","李老师",UserRole.TEACHER,Set.of(UserRole.ACADEMIC_ADMIN),true,false,"");view.showPage(new AccountData.Page(List.of(account),1,8,1));view.showDetail(account);view.applyCss();view.layout();assertEquals(List.of("T0000031"),view.visibleUsernames());assertEquals("李老师",view.detailName());assertNotNull(view.lookup("#account-create"));assertNotNull(view.lookup("#account-roles"));return null;});Platform.runLater(task);task.get(15,TimeUnit.SECONDS);}
    @Test void resetFiltersMakesCreatedAccountRefreshVisible()throws Exception{FutureTask<Void> task=new FutureTask<>(()->{AccountAdminView view=new AccountAdminView(()->{});view.setFilters("旧条件",UserRole.TEACHER,false);view.resetFilters();assertEquals("",view.keywordText());assertEquals(null,view.identityFilter());assertEquals(null,view.enabledFilter());return null;});Platform.runLater(task);task.get(15,TimeUnit.SECONDS);}
}
