package com.vcampus.client.fx.forum;

import com.vcampus.common.model.ForumBoostTier;
import com.vcampus.common.model.UserRole;
import java.math.BigDecimal;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class ForumControllerTest {
    @BeforeAll static void toolkit()throws Exception{ForumUiTest.toolkit();}
    @Test void navigationToPostWinsOverSlowFeedAndCloseStopsWrites()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicBoolean onUi=new AtomicBoolean();
        ForumGateway gateway=(ForumGateway)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ForumGateway.class},(p,m,a)->{
            if(javafx.application.Platform.isFxApplicationThread())onUi.set(true);
            return switch(m.getName()){
                case "feed"->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));yield ForumFixtures.feed();}
                case "sections"->ForumFixtures.sections();case "hot"->List.of();
                case "post"->ForumFixtures.post(false);case "comments"->ForumFixtures.comments();
                case "engagement"->new ForumData.Engagement(42,false,false);
                case "boostState"->boostState();
                default->throw new AssertionError("Unexpected call: "+m.getName());
            };
        });
        try(var workers=Executors.newFixedThreadPool(2)){
            var controller=ForumUiTest.fx(()->{var c=new ForumController(gateway,1L,Set.of(UserRole.STUDENT),workers,()->{});new Scene(c.view(),1220,900);c.openHome();return c;});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            ForumUiTest.fx(()->{controller.openPost(1);return null;});release.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);boolean loaded=false;
            while(System.nanoTime()<deadline){
                loaded=ForumUiTest.fx(()->{controller.view().applyCss();controller.view().layout();return controller.view().lookup("#forum-like")!=null;});
                if(loaded)break;Thread.sleep(20);
            }
            assertTrue(loaded);assertFalse(onUi.get());
            ForumUiTest.fx(()->{assertNull(controller.view().lookup("#forum-post-1"));controller.close();return null;});
        } finally {release.countDown();}
    }

    @Test void boostTierDoubleClickCreatesOnePaymentAndShowsReceipt()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();AtomicReference<UUID> operation=new AtomicReference<>();
        ForumGateway gateway=(ForumGateway)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ForumGateway.class},(p,m,a)->switch(m.getName()){
            case "post"->ForumFixtures.post(false);case "comments"->ForumFixtures.comments();
            case "engagement"->new ForumData.Engagement(42,false,false);
            case "boostState"->calls.get()==0?boostState():boostedState();
            case "createBoost"->{calls.incrementAndGet();operation.set((UUID)a[2]);entered.countDown();
                assertTrue(release.await(5,TimeUnit.SECONDS));
                yield new ForumData.BoostReceipt(31,41,"FB-"+operation.get(),new BigDecimal("10.00"),
                        100,new BigDecimal("40.00"),3,175,false);}
            default->throw new AssertionError("Unexpected call: "+m.getName());
        });
        try(var workers=Executors.newFixedThreadPool(2)){
            var controller=ForumUiTest.fx(()->{var c=new ForumController(gateway,1L,Set.of(UserRole.STUDENT),workers,()->{});new Scene(c.view(),1220,900);c.openPost(1);return c;});
            awaitFx(()->{controller.view().applyCss();controller.view().layout();
                return controller.view().lookup("#forum-boost-open")!=null;});
            ForumUiTest.fx(()->{((Button)controller.view().lookup("#forum-boost-open")).fire();return null;});
            awaitFx(()->boostDialog()!=null);
            ForumUiTest.fx(()->{Button ten=(Button)boostDialog().lookup("#forum-boost-tier-ten");ten.fire();ten.fire();return null;});
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(1,calls.get());assertNotNull(operation.get());
            ForumUiTest.fx(()->{assertTrue(((Button)boostDialog().lookup("#forum-boost-tier-five")).isDisabled());return null;});
            release.countDown();
            awaitFx(()->((Label)boostDialog().lookup("#forum-boost-status")).getText().contains("FB-"));
            awaitFx(()->((Label)controller.view().lookup("#forum-boost-summary")).getText().contains("175"));
            ForumUiTest.fx(()->{String text=((Label)boostDialog().lookup("#forum-boost-status")).getText();
                assertTrue(text.contains("10.00")&&text.contains("100")&&text.contains("40.00")&&text.contains("FB-"));
                boostDialog().getScene().getWindow().hide();controller.close();return null;});
        }finally{release.countDown();}
    }

    @Test void definiteFailureGetsNewUuidWhileTransportRetryKeepsUuid()throws Exception {
        List<UUID> operations=new CopyOnWriteArrayList<>();AtomicInteger attempts=new AtomicInteger();
        ForumGateway gateway=(ForumGateway)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ForumGateway.class},(p,m,a)->switch(m.getName()){
            case "post"->ForumFixtures.post(false);case "comments"->ForumFixtures.comments();
            case "engagement"->new ForumData.Engagement(42,false,false);case "boostState"->boostState();
            case "createBoost"->{operations.add((UUID)a[2]);int attempt=attempts.incrementAndGet();
                if(attempt==1)throw new ForumGateway.BusinessException("余额不足");
                if(attempt==2)throw new IOException("连接中断");
                yield new ForumData.BoostReceipt(32,42,"FB-"+a[2],new BigDecimal("5.00"),50,
                        new BigDecimal("45.00"),1,50,false);}
            default->throw new AssertionError("Unexpected call: "+m.getName());
        });
        try(var workers=Executors.newFixedThreadPool(2)){
            var controller=ForumUiTest.fx(()->{var c=new ForumController(gateway,1L,Set.of(UserRole.STUDENT),workers,()->{});new Scene(c.view(),1220,900);c.openPost(1);return c;});
            awaitFx(()->{controller.view().applyCss();controller.view().layout();return controller.view().lookup("#forum-boost-open")!=null;});
            ForumUiTest.fx(()->{((Button)controller.view().lookup("#forum-boost-open")).fire();return null;});
            awaitFx(()->boostDialog()!=null);
            ForumUiTest.fx(()->{((Button)boostDialog().lookup("#forum-boost-tier-five")).fire();return null;});
            awaitFx(()->status().contains("余额不足"));
            ForumUiTest.fx(()->{assertFalse(((Button)boostDialog().lookup("#forum-boost-tier-ten")).isDisabled());
                ((Button)boostDialog().lookup("#forum-boost-tier-five")).fire();return null;});
            awaitFx(()->status().contains("连接中断"));
            ForumUiTest.fx(()->{assertTrue(((Button)boostDialog().lookup("#forum-boost-tier-ten")).isDisabled());
                ((Button)boostDialog().lookup("#forum-boost-tier-five")).fire();return null;});
            awaitFx(()->status().contains("FB-"));
            assertEquals(3,operations.size());assertNotEquals(operations.get(0),operations.get(1));
            assertEquals(operations.get(1),operations.get(2));
            ForumUiTest.fx(()->{boostDialog().getScene().getWindow().hide();controller.close();return null;});
        }
    }

    private static ForumData.BoostState boostState(){return new ForumData.BoostState(true,"",0,0,0,
            BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("50.00"),List.of(ForumBoostTier.values()));}
    private static ForumData.BoostState boostedState(){return new ForumData.BoostState(true,"",3,175,1,
            new BigDecimal("10.00"),new BigDecimal("10.00"),new BigDecimal("40.00"),List.of(ForumBoostTier.values()));}
    private static DialogPane boostDialog(){
        for(Window window:Window.getWindows())if(window.isShowing()&&window.getScene()!=null&&
                window.getScene().getRoot() instanceof DialogPane pane&&"forum-boost-dialog".equals(pane.getId()))return pane;
        return null;
    }
    private static String status(){DialogPane pane=boostDialog();return pane==null?"":((Label)pane.lookup("#forum-boost-status")).getText();}
    private static void awaitFx(Callable<Boolean> condition)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline){if(ForumUiTest.fx(condition))return;Thread.sleep(20);}
        fail("Timed out waiting for JavaFX state");
    }
}
