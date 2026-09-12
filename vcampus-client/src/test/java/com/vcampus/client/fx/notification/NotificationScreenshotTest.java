package com.vcampus.client.fx.notification;

import com.vcampus.client.ui.NotificationViewData.NotificationDetail;
import com.vcampus.client.ui.NotificationViewData.NotificationPage;
import com.vcampus.client.ui.NotificationViewData.NotificationRow;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Group;
import javafx.scene.Parent;
import javafx.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationScreenshotTest {
    private static final Path OUTPUT=Path.of("target","visual-snapshots","notification");

    @BeforeAll static void toolkit()throws Exception{CountDownLatch ready=new CountDownLatch(1);try{Platform.startup(ready::countDown);}catch(IllegalStateException e){ready.countDown();}assertTrue(ready.await(15,TimeUnit.SECONDS));Platform.setImplicitExit(false);}

    @Test void rendersWideAndNarrowMessageCenter()throws Exception{
        FutureTask<Void> task=new FutureTask<>(()->{
            Files.createDirectories(OUTPUT);
            capture(view(),"message-center-1220",1220,820);
            capture(view(),"message-center-860",860,780);
            return null;
        });Platform.runLater(task);task.get(30,TimeUnit.SECONDS);
    }

    private static NotificationView view(){
        NotificationView view=new NotificationView(()->{});view.showUnreadCount(3);
        view.showDetail(new NotificationDetail(11,NotificationType.SCHEDULE_CHANGED,NotificationSource.ACADEMIC,
                "选课结果通知","你提交的《软件工程》教学班选课申请已通过。点击下方按钮可查看本学期完整课表。",
                NotificationTarget.ACADEMIC_SCHEDULE,81L,false,null,Instant.parse("2026-09-09T09:18:00Z")));
        view.showPage(new NotificationPage(List.of(
                row(11,NotificationType.SCHEDULE_CHANGED,NotificationSource.ACADEMIC,"选课结果通知","《软件工程》选课申请已通过",NotificationTarget.ACADEMIC_SCHEDULE,81L,false,"2026-09-09T09:18:00Z"),
                row(12,NotificationType.LIBRARY_RESERVATION_AVAILABLE,NotificationSource.LIBRARY,"预约图书可以借阅了","《Effective Java》已有可借馆藏",NotificationTarget.LIBRARY_CATALOG,27L,false,"2026-09-09T08:22:00Z"),
                row(13,NotificationType.SHOP_ORDER_SHIPPED,NotificationSource.SHOP,"订单已发货","订单 SO20260909001 已发货，请注意查收",NotificationTarget.SHOP_ORDERS,42L,false,"2026-09-09T07:45:00Z"),
                row(14,NotificationType.PASSWORD_RESET,NotificationSource.ACCOUNT_SECURITY,"密码重置成功","你的账号密码已由管理员重置",NotificationTarget.NONE,null,true,"2026-09-08T12:30:00Z")
        ),1,10,4));
        return view;
    }

    private static NotificationRow row(long id,NotificationType type,NotificationSource source,String title,String summary,NotificationTarget target,Long related,boolean read,String created){return new NotificationRow(id,type,source,title,summary,target,related,read,Instant.parse(created));}
    private static void capture(Parent root,String name,int width,int height)throws Exception{Scene scene=new Scene(root,width,height);root.resize(width,height);root.applyCss();root.layout();ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null,null),null),"png",OUTPUT.resolve(name+".png").toFile());scene.setRoot(new Group());}
}
