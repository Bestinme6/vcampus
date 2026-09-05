package com.vcampus.client.fx;

import com.vcampus.common.protocol.*;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class CampusFlowTest {
    @BeforeAll static void toolkit() throws Exception {FxUiTest.toolkit();}
    @Test void campusDashboardOrderTaskTargetsNativeShopOrdersRoute() throws Exception {
        FxUiTest.fx(() -> {
            var route = new java.util.concurrent.atomic.AtomicReference<String>();
            var view = new FxCampusView("演示同学", Set.of(com.vcampus.common.model.UserRole.STUDENT),
                    java.time.LocalDate.of(2026, 9, 1), day -> { }, () -> { }, route::set);
            new javafx.scene.Scene(view, 1000, 720); view.showData(FxUiTest.example());
            view.applyCss(); view.layout();
            ((Button) view.lookup("#task-1")).fire();
            assertEquals("shop-orders", route.get());
            return null;
        });
    }
    @Test void workspaceShopCardOpensNativeJavaFxStoreAndLogoutClosesSession() throws Exception {
        try(var server=new AuthPeer(false)) {
            CampusApplication app=new CampusApplication();
            Stage stage=FxUiTest.fx(()->{Stage s=new Stage(); app.start(s); return s;});
            try {
                submitLogin(stage,server.port()); await(()->"campus-shell".equals(stage.getScene().getRoot().getId()));
                FxUiTest.fx(()->{
                    button(stage,"nav-workspace").fire();
                    stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
                    var card=stage.getScene().getRoot().lookupAll(".module-card").stream()
                            .map(node->(Button)node).filter(node->"商店购物".equals(node.getAccessibleText()))
                            .findFirst().orElseThrow();
                    card.fire();
                    assertNotNull(stage.getScene().getRoot().lookup("#shop-view"));
                    button(stage,"logout").fire();
                    assertEquals("login-view",stage.getScene().getRoot().getId());
                    return null;
                });
                assertTrue(server.loggedOut.await(5,TimeUnit.SECONDS));
            } finally {FxUiTest.fx(()->{app.stop();stage.close();return null;});}
        }
    }
    @Test void firstLoginCannotEnterCampusUntilPasswordChangeThenLogoutRevokesSession() throws Exception {
        try(var server=new AuthPeer(true)) {
            CampusApplication app=new CampusApplication();
            Stage stage=FxUiTest.fx(()->{Stage s=new Stage(); app.start(s); return s;});
            try {
                submitLogin(stage,server.port());
                await(()->stage.getScene().getRoot().getId().equals("required-password-change"));
                assertEquals(List.of(Actions.AUTH_LOGIN),server.actions);
                FxUiTest.fx(()->{
                    FxUiTest.capture(stage.getScene().getRoot(),"required-password-change-1000",1000,720);
                    text(stage,"current-password","initial-demo"); text(stage,"new-password","changed-demo");
                    text(stage,"repeat-password","changed-demo"); button(stage,"change-password-submit").fire(); return null;
                });
                await(()->"campus-shell".equals(stage.getScene().getRoot().getId()));
                await(()->!button(stage,"campus-refresh").isDisabled());
                FxUiTest.fx(()->{
                    assertNull(stage.getScene().getRoot().lookup("#nav-accounts"));
                    ((FxCampusView)stage.getScene().getRoot().lookup("#campus-view")).showData(FxUiTest.example());
                    FxUiTest.capture(stage.getScene().getRoot(),"campus-shell-1440",1440,1024);
                    FxUiTest.capture(stage.getScene().getRoot(),"campus-shell-1000",1000,720);
                    button(stage,"nav-workspace").fire();
                    stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
                    assertNotNull(stage.getScene().getRoot().lookup(".module-card"));
                    button(stage,"logout").fire();
                    assertEquals("login-view",stage.getScene().getRoot().getId()); return null;
                });
                assertTrue(server.loggedOut.await(5,TimeUnit.SECONDS));
                int change=server.actions.indexOf(Actions.AUTH_CHANGE_PASSWORD);
                int business=server.actions.indexOf(Actions.AUTH_SESSION);
                assertTrue(change>0 && business>change,server.actions.toString());
            } finally {FxUiTest.fx(()->{app.stop(); stage.close(); return null;});}
        }
    }
    @Test void closingWhileLoginIsInFlightRevokesLateTokenInsteadOfOpeningCampus() throws Exception {
        try(var server=new AuthPeer(false)) {
            server.delayLogin=true;
            CampusApplication app=new CampusApplication();
            Stage stage=FxUiTest.fx(()->{Stage s=new Stage(); app.start(s); return s;});
            submitLogin(stage,server.port()); assertTrue(server.loginArrived.await(5,TimeUnit.SECONDS));
            FxUiTest.fx(()->{app.stop(); stage.close(); return null;});
            server.releaseLogin.countDown();
            assertTrue(server.loggedOut.await(7,TimeUnit.SECONDS),"late successful login must not leave a live server session");
            assertFalse(server.actions.contains(Actions.AUTH_SESSION));
        }
    }
    @Test void slowObsoleteDatesCannotBlockLoginToAnotherServer() throws Exception {
        try(var slow=new AuthPeer(false); var healthy=new AuthPeer(false)) {
            slow.delayDashboard=true;
            CampusApplication app=new CampusApplication();
            Stage stage=FxUiTest.fx(()->{Stage s=new Stage(); app.start(s); return s;});
            try {
                submitLogin(stage,slow.port());
                assertTrue(slow.dashboardArrived.await(5,TimeUnit.SECONDS));
                FxUiTest.fx(()->{
                    var root=stage.getScene().getRoot(); root.applyCss(); root.layout();
                    for(var day:root.lookupAll(".day-button")) ((Button)day).fire();
                    button(stage,"logout").fire(); return null;
                });
                submitLogin(stage,healthy.port());
                assertTrue(healthy.loginArrived.await(2,TimeUnit.SECONDS),
                        "authentication must not queue behind obsolete campus requests");
                assertEquals(1,slow.releaseDashboard.getCount(),"old server is still blocked");
                await(()->"campus-shell".equals(stage.getScene().getRoot().getId()));
            } finally {
                slow.releaseDashboard.countDown();
                FxUiTest.fx(()->{app.stop(); stage.close(); return null;});
            }
        }
    }
    private static void submitLogin(Stage stage,int port) throws Exception {
        FxUiTest.fx(()->{text(stage,"server-host","127.0.0.1");text(stage,"server-port",Integer.toString(port));
            text(stage,"login-username","DemoStudent");text(stage,"login-password","initial-demo");
            button(stage,"login-submit").fire();return null;});
    }
    private static void text(Stage stage,String id,String value) {((TextField)stage.getScene().getRoot().lookup("#"+id)).setText(value);}
    private static Button button(Stage stage,String id) {return (Button)stage.getScene().getRoot().lookup("#"+id);}
    private static void await(Callable<Boolean> condition) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(System.nanoTime()<end) {if(FxUiTest.fx(condition)) return; Thread.sleep(30);}
        fail("UI transition timed out");
    }
    private static final class AuthPeer implements AutoCloseable {
        private final ServerSocket socket=new ServerSocket(0,20,InetAddress.getLoopbackAddress());
        private final AtomicBoolean force;
        private final Thread thread;
        final List<String> actions=new CopyOnWriteArrayList<>();
        final CountDownLatch loginArrived=new CountDownLatch(1),releaseLogin=new CountDownLatch(1),loggedOut=new CountDownLatch(1);
        final CountDownLatch dashboardArrived=new CountDownLatch(1),releaseDashboard=new CountDownLatch(1);
        volatile boolean delayLogin,delayDashboard;
        AuthPeer(boolean force) throws IOException {
            this.force=new AtomicBoolean(force);
            thread=Thread.ofPlatform().daemon().start(()->{
                while(!socket.isClosed()) try(Socket peer=socket.accept()) {
                    peer.setSoTimeout(6000);
                    var request=MessageCodec.readRequest(new DataInputStream(peer.getInputStream())); actions.add(request.action());
                    if(request.action().equals(Actions.AUTH_LOGIN)) {loginArrived.countDown();if(delayLogin)releaseLogin.await(6,TimeUnit.SECONDS);}
                    if(request.action().equals(Actions.AUTH_SESSION)) {dashboardArrived.countDown();if(delayDashboard)releaseDashboard.await(6,TimeUnit.SECONDS);}
                    if(request.action().equals(Actions.AUTH_CHANGE_PASSWORD)) this.force.set(false);
                    Map<String,String> data=new HashMap<>();
                    data.put("sessionToken","test-only-session");data.put("displayName","演示同学");data.put("roles","STUDENT");
                    data.put("forcePasswordChange",Boolean.toString(this.force.get()));data.put("unreadCount","0");
                    boolean accepted=request.action().startsWith("auth.") || request.action().contains("unread");
                    var response=accepted?ResponseMessage.success(request.requestId(),"ok",data):ResponseMessage.failure(request.requestId(),"测试业务暂不可用");
                    MessageCodec.writeResponse(new DataOutputStream(peer.getOutputStream()),response);
                    if(request.action().equals(Actions.AUTH_LOGOUT)) loggedOut.countDown();
                } catch(Exception error) {if(!socket.isClosed()) throw new RuntimeException(error);}
            });
        }
        int port(){return socket.getLocalPort();}
        @Override public void close() throws Exception {releaseLogin.countDown();releaseDashboard.countDown();socket.close();thread.join(1000);}
    }
}
