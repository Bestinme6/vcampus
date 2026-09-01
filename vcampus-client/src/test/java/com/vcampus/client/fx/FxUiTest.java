package com.vcampus.client.fx;

import com.vcampus.client.ui.CampusDashboardData;
import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class FxUiTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(15, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }
    static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work); Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }
    @Test void loginDoesNotSubmitInvalidInputsAndClearsCapturedPassword() throws Exception {
        fx(() -> {
            AtomicInteger calls = new AtomicInteger();
            var view = new FxLoginView(new ServerConnection("localhost", 9090), (server, credentials) -> {
                calls.incrementAndGet(); assertEquals("StudentA", credentials.username());
                assertArrayEquals("a-test-password".toCharArray(), credentials.password());
                java.util.Arrays.fill(credentials.password(), '\0');
            }, server -> {});
            scene(view, 1440, 1024);
            Button submit = (Button)view.lookup("#login-submit"); submit.fire(); assertEquals(0, calls.get());
            ((TextField)view.lookup("#login-username")).setText("StudentA");
            ((TextField)view.lookup("#login-password")).setText("a-test-password");
            ((TextField)view.lookup("#server-port")).setText("65536");
            submit.fire(); assertEquals(0, calls.get());
            ((TextField)view.lookup("#server-port")).setText("9090"); submit.fire();
            assertEquals(1, calls.get()); assertEquals("", ((TextField)view.lookup("#login-password")).getText());
            return null;
        });
    }
    @Test void weekCrossesMonthAndActionsNavigateToRealModules() throws Exception {
        fx(() -> {
            AtomicReference<LocalDate> picked = new AtomicReference<>();
            AtomicReference<String> route = new AtomicReference<>();
            var view = new FxCampusView("演示同学", Set.of(UserRole.STUDENT), LocalDate.of(2026,8,31),
                    picked::set, () -> {}, route::set);
            scene(view, 1160, 960); view.showData(example());
            ((Button)view.lookup("#campus-day-2026-09-01")).fire();
            assertEquals(LocalDate.of(2026,9,1), picked.get());
            ((Button)view.lookup("#full-schedule")).fire(); assertEquals("student-schedule", route.get());
            ((Button)view.lookup("#task-0")).fire(); assertEquals("library-loans", route.get());
            view.showError("网络暂不可用");
            assertNull(view.lookup("#task-0"), "failure must not leave clickable stale personal records");
            return null;
        });
    }
    @Test void renderApprovedLayoutsAtDesktopAndMinimumSize() throws Exception {
        fx(() -> {
            var login = new FxLoginView(new ServerConnection("127.0.0.1",9090), (a,b)->{}, a->{});
            capture(login, "login-1440", 1440, 1024);
            capture(login, "login-1000", 1000, 720);
            ((javafx.scene.control.TitledPane)login.lookup(".connection-settings")).setExpanded(true);
            capture(login, "login-settings-1000", 1000, 720);
            var view = new FxCampusView("演示同学", Set.of(UserRole.STUDENT), LocalDate.of(2026,8,31), d->{},()->{},r->{});
            view.showData(example()); capture(view, "campus-content-1220", 1220, 1024);
            capture(view, "campus-content-780", 780, 720);
            return null;
        });
    }
    @Test void teacherAndAdministratorUseTheirOwnRoutes() throws Exception {
        fx(() -> {
            for(UserRole role:List.of(UserRole.TEACHER,UserRole.SUPER_ADMIN)) {
                AtomicReference<String> route=new AtomicReference<>();
                var view=new FxCampusView("演示账号",Set.of(role),LocalDate.of(2026,8,31),d->{},()->{},route::set);
                scene(view,1000,720);
                ((Button)view.lookup("#full-schedule")).fire();
                assertEquals(role==UserRole.TEACHER?"teacher-schedule":"workspace",route.get());
            }
            return null;
        });
    }
    private static void scene(Parent root, int width, int height) {
        if (root.getScene()!=null) root.getScene().setRoot(new javafx.scene.Group());
        FxStyles.install(root); new Scene(root, width, height); root.resize(width, height); root.applyCss(); root.layout();
    }
    static void capture(Parent root,String name,int width,int height) throws Exception {
        if(root.getScene()!=null && root.getScene().getWindow()!=null) {
            root.resize(width,height); root.applyCss(); root.layout();
        } else scene(root,width,height);
        Path folder=Path.of("target","javafx-qa"); Files.createDirectories(folder);
        ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null,null),null),"png",folder.resolve(name+".png").toFile());
    }
    static CampusDashboardData example() {
        return new CampusDashboardData("演示同学","学生","计算机学院 · 软件工程2601班",LocalDate.of(2026,8,31),
                List.of(new CampusDashboardData.Course("Java 程序设计","教学楼 A203","第3—4节","2026—2027 第一学期 · 第1周"),
                        new CampusDashboardData.Course("高等数学","教学楼 B105","第5—6节","2026—2027 第一学期 · 第1周")),
                List.of(new CampusDashboardData.Task("借阅即将到期","《Java 核心技术》 · 9月2日到期","library-loans",false),
                        new CampusDashboardData.Task("订单待收货","校园文具 · 1 笔","shop-orders",false)),
                "3 本","1 笔","¥286.50",List.of());
    }
}
