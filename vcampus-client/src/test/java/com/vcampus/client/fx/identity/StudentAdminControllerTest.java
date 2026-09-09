package com.vcampus.client.fx.identity;

import com.vcampus.common.model.Gender;
import com.vcampus.common.model.StudentStatus;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudentAdminControllerTest {
    @BeforeAll static void toolkit()throws Exception{CountDownLatch ready=new CountDownLatch(1);try{Platform.startup(ready::countDown);}catch(IllegalStateException e){ready.countDown();}assertTrue(ready.await(15,TimeUnit.SECONDS));Platform.setImplicitExit(false);}
    @Test void activateLoadsPageAndSelectingStudentLoadsDetail()throws Exception{
        FakeGateway gateway=new FakeGateway();StudentAdminController controller=fx(()->new StudentAdminController(gateway,Runnable::run,()->{}));
        fx(()->{controller.activate();return null;});flush();
        assertEquals(List.of("林同学"),fx(()->controller.typedView().visibleNames()));
        fx(()->{controller.select(8);return null;});flush();
        assertEquals("2026000008",fx(()->controller.typedView().detailStudentNumber()));controller.close();
    }
    private static <T>T fx(java.util.concurrent.Callable<T> work)throws Exception{FutureTask<T> task=new FutureTask<>(work);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
    private static void flush()throws Exception{fx(()->null);fx(()->null);}
    private static final class FakeGateway implements StudentGateway{
        final StudentData.Profile profile=new StudentData.Profile(8,"2026000008","林同学",Gender.FEMALE,"2008-05-01",10,"计算机学院",20,"软件工程",30,"软工2601",2026,StudentStatus.ENROLLED,"139","s@v.edu","1号楼");
        @Override public StudentData.Profile self(){return profile;}@Override public StudentData.Page search(String k,StudentStatus s,int p){return new StudentData.Page(List.of(new StudentData.Summary(8,"2026000008","林同学",Gender.FEMALE,"计算机学院","软件工程","软工2601",2026,StudentStatus.ENROLLED,"139","s@v.edu")),1,8,1);}@Override public StudentData.Profile get(long id){return profile;}@Override public StudentData.References references(){return new StudentData.References(List.of(),List.of(),List.of());}@Override public List<StudentData.History> history(Long id){return List.of();}@Override public void updateProfile(Map<String,String> v){}@Override public void updateContact(long id,String p,String e,String a){}@Override public void updateOwnContact(String p,String e,String a){}@Override public void changeStatus(long id,StudentStatus s,String r){}
    }
}
