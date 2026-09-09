package com.vcampus.client.fx.identity;

import com.vcampus.common.model.Gender;
import com.vcampus.common.model.StudentStatus;
import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Parent;
import javafx.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityScreenshotTest {
    private static final Path OUTPUT=Path.of("..","docs","design","javafx-identity","screenshots");
    @BeforeAll static void toolkit()throws Exception{CountDownLatch ready=new CountDownLatch(1);try{Platform.startup(ready::countDown);}catch(IllegalStateException e){ready.countDown();}assertTrue(ready.await(15,TimeUnit.SECONDS));Platform.setImplicitExit(false);}
    @Test void rendersFourApprovedResponsiveLayouts()throws Exception{FutureTask<Void> task=new FutureTask<>(()->{Files.createDirectories(OUTPUT);StudentData.Profile p=profile();StudentAdminView admin=new StudentAdminView(()->{});admin.showPage(new StudentData.Page(List.of(summary(8,"林同学",StudentStatus.ENROLLED),summary(9,"周同学",StudentStatus.SUSPENDED),summary(10,"陈同学",StudentStatus.GRADUATED)),1,8,3));admin.showDetail(p);capture(admin,"student-admin-1000",1000,720);assertTrue(admin.lookup("#student-admin-edit").getBoundsInParent().getWidth()>0);
        StudentProfileView student=new StudentProfileView(()->{});student.showProfile(p);student.showHistory(List.of(new StudentData.History("",StudentStatus.ENROLLED,"新生建档","系统管理员","2026-09-01 09:00")));capture(student,"student-profile-1220",1220,820);
        AccountAdminView accounts=new AccountAdminView(()->{});AccountData.Account a=new AccountData.Account(31,"T0000031","李老师",UserRole.TEACHER,Set.of(UserRole.ACADEMIC_ADMIN,UserRole.STUDENT_ADMIN),true,false,"2026-09-09 08:30");accounts.showPage(new AccountData.Page(List.of(a,new AccountData.Account(32,"2026000032","王同学",UserRole.STUDENT,Set.of(UserRole.LIBRARY_ADMIN),false,true,"")),1,8,2));accounts.showDetail(a);capture(accounts,"account-admin-1440",1440,860);
        TeacherProfileView teacher=new TeacherProfileView(()->{});teacher.showProfile(new TeacherData("T20260001","李老师","计算机科学与工程学院","副教授","13800000000","teacher@vcampus.edu"));capture(teacher,"teacher-profile-1220",1220,760);return null;});Platform.runLater(task);task.get(30,TimeUnit.SECONDS);}
    private static void capture(Parent root,String name,int width,int height)throws Exception{Scene scene=new Scene(root,width,height);root.resize(width,height);root.applyCss();root.layout();ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null,null),null),"png",OUTPUT.resolve(name+".png").toFile());scene.setRoot(new javafx.scene.Group());}
    private static StudentData.Profile profile(){return new StudentData.Profile(8,"2026000008","林同学",Gender.FEMALE,"2008-05-01",10,"计算机科学与工程学院",20,"软件工程",30,"软件工程2601班",2026,StudentStatus.ENROLLED,"13900000000","student@vcampus.edu","学生公寓 1 号楼 302");}
    private static StudentData.Summary summary(long id,String name,StudentStatus status){return new StudentData.Summary(id,"202600000"+id,name,Gender.UNSPECIFIED,"计算机学院","软件工程","软工2601",2026,status,"13900000000",name+"@vcampus.edu");}
}
