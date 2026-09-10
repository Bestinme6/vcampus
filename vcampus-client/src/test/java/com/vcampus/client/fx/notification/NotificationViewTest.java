package com.vcampus.client.fx.notification;

import com.vcampus.client.ui.NotificationViewData.NotificationDetail;
import com.vcampus.client.ui.NotificationViewData.NotificationPage;
import com.vcampus.client.ui.NotificationViewData.NotificationRow;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationViewTest {
    @BeforeAll static void toolkit()throws Exception{CountDownLatch ready=new CountDownLatch(1);try{Platform.startup(ready::countDown);}catch(IllegalStateException e){ready.countDown();}assertTrue(ready.await(15,TimeUnit.SECONDS));Platform.setImplicitExit(false);}

    @Test void rendersUnreadCardsAndPersistentNavigableDetail()throws Exception{
        fx(()->{
            NotificationView view=new NotificationView(()->{});new Scene(view,1180,780);
            NotificationRow row=row(NotificationTarget.SHOP_ORDERS,73L,false);
            view.showUnreadCount(4);view.showPage(new NotificationPage(List.of(row),1,10,1));
            view.showDetail(new NotificationDetail(7,NotificationType.SHOP_ORDER_SHIPPED,NotificationSource.SHOP,
                    "订单已发货","订单 SO73 已发货，请注意查收。",NotificationTarget.SHOP_ORDERS,73L,false,null,Instant.parse("2026-09-09T00:00:00Z")));
            view.applyCss();view.layout();
            assertEquals(List.of("订单已发货"),view.visibleTitles());
            assertEquals("4 条未读",view.unreadText());
            assertEquals("订单已发货",view.detailTitle());
            assertEquals("查看订单详情",view.navigationText());
            assertNotNull(view.lookup("#notification-delete"));
            assertNotNull(view.lookup("#notification-mark-unread"));
            assertNotNull(view.lookup("#notification-list"));
            assertTrue(view.sourceLabels().containsAll(List.of("全部消息","教务","学籍","账号安全","图书馆","论坛","银行","商店")));
            return null;
        });
    }

    @Test void changesToStackedLayoutForNarrowContentAndHidesInvalidNavigation()throws Exception{
        fx(()->{
            NotificationView view=new NotificationView(()->{});new Scene(view,880,760);view.resize(880,760);
            view.showDetail(new NotificationDetail(8,NotificationType.PASSWORD_RESET,NotificationSource.ACCOUNT_SECURITY,
                    "密码已重置","请使用临时密码登录。",NotificationTarget.NONE,null,true,Instant.parse("2026-09-09T01:00:00Z"),Instant.parse("2026-09-09T00:00:00Z")));
            view.applyCss();view.layout();
            assertEquals(Orientation.VERTICAL,view.layoutOrientation());
            assertEquals("",view.navigationText());
            return null;
        });
    }

    private static NotificationRow row(NotificationTarget target,Long related,boolean read){return new NotificationRow(7,NotificationType.SHOP_ORDER_SHIPPED,NotificationSource.SHOP,"订单已发货","订单 SO73 已发货",target,related,read,Instant.parse("2026-09-09T00:00:00Z"));}
    private static <T>T fx(java.util.concurrent.Callable<T> work)throws Exception{FutureTask<T> task=new FutureTask<>(work);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
}
