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

class PersonalProfileViewTest {
    @BeforeAll static void toolkit()throws Exception{CountDownLatch ready=new CountDownLatch(1);try{Platform.startup(ready::countDown);}catch(IllegalStateException e){ready.countDown();}assertTrue(ready.await(15,TimeUnit.SECONDS));Platform.setImplicitExit(false);}
    @Test void studentAndTeacherProfilesUseNativeCards()throws Exception{
        FutureTask<Void> task=new FutureTask<>(()->{
            StudentProfileView student=new StudentProfileView(()->{});
            new Scene(student,1100,760); student.showProfile(new StudentData.Profile(8,"2026000008","林同学", Gender.FEMALE,"2008-05-01",10,"计算机学院",20,"软件工程",30,"软工2601",2026, StudentStatus.ENROLLED,"139","s@v.edu","1号楼"));
            student.showHistory(List.of(new StudentData.History("",StudentStatus.ENROLLED,"建档","系统","2026-09-09")));student.applyCss();student.layout();
            assertEquals("林同学",student.nameText()); assertEquals("139",student.phoneText());
            assertNotNull(student.lookup("#student-contact-edit")); assertNotNull(student.lookup(".identity-status-positive"));
            TeacherProfileView teacher=new TeacherProfileView(()->{});new Scene(teacher,1100,760);
            teacher.showProfile(new TeacherData("T20260001","李老师","计算机学院","讲师","138","t@v.edu"));teacher.applyCss();teacher.layout();
            assertEquals("李老师",teacher.nameText()); assertNotNull(teacher.lookup("#teacher-contact-edit"));
            return null;
        });Platform.runLater(task);task.get(15,TimeUnit.SECONDS);
    }
}
