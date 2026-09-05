package com.vcampus.client.fx.forum;

import com.vcampus.common.model.UserRole;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javafx.scene.Scene;
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
                default->throw new AssertionError("Unexpected call: "+m.getName());
            };
        });
        try(var workers=Executors.newFixedThreadPool(2)){
            var controller=ForumUiTest.fx(()->{var c=new ForumController(gateway,Set.of(UserRole.STUDENT),workers,()->{});new Scene(c.view(),1220,900);c.openHome();return c;});
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
}
