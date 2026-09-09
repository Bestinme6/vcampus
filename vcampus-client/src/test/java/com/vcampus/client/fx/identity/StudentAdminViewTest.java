package com.vcampus.client.fx.identity;

import com.vcampus.common.model.Gender;
import com.vcampus.common.model.StudentStatus;
import javafx.application.Platform;
import javafx.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudentAdminViewTest {
    @BeforeAll static void toolkit()throws Exception{CountDownLatch ready=new CountDownLatch(1);try{Platform.startup(ready::countDown);}catch(IllegalStateException e){ready.countDown();}assertTrue(ready.await(15,TimeUnit.SECONDS));Platform.setImplicitExit(false);}
    @Test void rendersCompactTableAndSelectedDetail()throws Exception{
        FutureTask<Void> task=new FutureTask<>(()->{StudentAdminView view=new StudentAdminView(()->{});new Scene(view,1100,760);
            StudentData.Summary row=new StudentData.Summary(8,"2026000008","林同学", Gender.FEMALE,"计算机学院","软件工程","软工2601",2026,StudentStatus.ENROLLED,"139","s@v.edu");
            view.showPage(new StudentData.Page(List.of(row),1,8,1));
            view.showDetail(new StudentData.Profile(8,"2026000008","林同学",Gender.FEMALE,"2008-05-01",10,"计算机学院",20,"软件工程",30,"软工2601",2026,StudentStatus.ENROLLED,"139","s@v.edu","1号楼"));
            view.applyCss();view.layout();
            assertEquals(List.of("林同学"),view.visibleNames());assertEquals("2026000008",view.detailStudentNumber());
            assertNotNull(view.lookup("#student-admin-table"));assertNotNull(view.lookup("#student-admin-edit"));assertNotNull(view.lookup(".identity-status-positive"));return null;});
        Platform.runLater(task);task.get(15,TimeUnit.SECONDS);
    }
}
