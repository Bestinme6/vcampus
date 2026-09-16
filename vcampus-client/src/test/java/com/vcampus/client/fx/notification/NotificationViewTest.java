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
import java.util.concurrent.atomic.AtomicReference;

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

    @Test void secondhandOrderNotificationUsesNativeOrderNavigationLabel()throws Exception{
        fx(()->{NotificationView view=new NotificationView(()->{});new Scene(view,1000,720);
            view.showDetail(new NotificationDetail(12,NotificationType.SECONDHAND_ORDER_SHIPPED,
                    NotificationSource.SHOP,"二手订单已发货","请确认收货",NotificationTarget.SECONDHAND_ORDERS,
                    88L,false,null,Instant.parse("2026-09-09T00:00:00Z")));
            view.applyCss();view.layout();assertEquals("查看二手订单",view.navigationText());return null;});
    }

    @Test void boostPaymentLivesInBankFilterAndNavigatesToItsLedgerAccount()throws Exception{
        fx(()->{
            AtomicReference<NotificationSource> selectedSource=new AtomicReference<>();
            AtomicReference<com.vcampus.client.ui.NotificationDestination> opened=new AtomicReference<>();
            NotificationView view=new NotificationView(()->{});new Scene(view,1180,780);
            view.setListener(new NotificationView.Listener(){
                public void search(String keyword,NotificationSource source,Boolean read,int page){selectedSource.set(source);}
                public void select(long id){}public void markAllRead(){}public void markUnread(long id){}public void delete(long id){}
                public void navigate(com.vcampus.client.ui.NotificationDestination destination){opened.set(destination);}
            });
            NotificationRow boost=new NotificationRow(9,NotificationType.BANK_FORUM_BOOST_PAID,NotificationSource.BANK,
                    "论坛助力扣费成功","支付 5.00 元，增加 50 热度，流水号 FB-test",NotificationTarget.BANK_LEDGER,21L,false,
                    Instant.parse("2026-09-09T00:00:00Z"));
            view.showPage(new NotificationPage(List.of(boost),1,10,1));
            view.showDetail(new NotificationDetail(9,boost.type(),boost.source(),boost.title(),boost.summary(),
                    boost.target(),boost.relatedEntityId(),false,null,boost.createdAt()));
            view.applyCss();view.layout();
            assertEquals(List.of("论坛助力扣费成功"),view.visibleTitles());
            assertEquals("查看银行流水",view.navigationText());
            String content=((javafx.scene.control.Label)view.lookup(".notification-detail-content")).getText();
            assertTrue(content.contains("5.00")&&content.contains("50")&&content.contains("FB-test"));
            view.sourceLabels();
            view.lookupAll(".notification-source-filter").stream().map(javafx.scene.control.ToggleButton.class::cast)
                    .filter(button->button.getText().equals("银行")).findFirst().orElseThrow().fire();
            assertEquals(NotificationSource.BANK,selectedSource.get());
            ((javafx.scene.control.Button)view.lookup("#notification-open-target")).fire();
            assertEquals(NotificationTarget.BANK_LEDGER,opened.get().target());
            assertEquals(21L,opened.get().relatedEntityId());
            view.lookupAll(".notification-source-filter").stream().map(javafx.scene.control.ToggleButton.class::cast)
                    .filter(button->button.getText().equals("论坛")).findFirst().orElseThrow().fire();
            assertEquals(NotificationSource.FORUM,selectedSource.get());
            view.showPage(new NotificationPage(List.of(),1,10,0));
            assertTrue(view.visibleTitles().isEmpty());
            return null;
        });
    }

    private static NotificationRow row(NotificationTarget target,Long related,boolean read){return new NotificationRow(7,NotificationType.SHOP_ORDER_SHIPPED,NotificationSource.SHOP,"订单已发货","订单 SO73 已发货",target,related,read,Instant.parse("2026-09-09T00:00:00Z"));}
    private static <T>T fx(java.util.concurrent.Callable<T> work)throws Exception{FutureTask<T> task=new FutureTask<>(work);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
}
