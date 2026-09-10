package com.vcampus.client.fx.notification;

import com.vcampus.client.ui.NotificationDestination;
import com.vcampus.client.ui.NotificationViewData.NotificationDetail;
import com.vcampus.client.ui.NotificationViewData.NotificationPage;
import com.vcampus.client.ui.NotificationViewData.NotificationRow;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationControllerTest {
    @BeforeAll static void toolkit()throws Exception{CountDownLatch ready=new CountDownLatch(1);try{Platform.startup(ready::countDown);}catch(IllegalStateException e){ready.countDown();}assertTrue(ready.await(15,TimeUnit.SECONDS));Platform.setImplicitExit(false);}

    @Test void openingThePageKeepsUnreadStateUntilTheUserSelectsAMessage()throws Exception{
        FakeGateway gateway=new FakeGateway();AtomicInteger unreadRefresh=new AtomicInteger();AtomicReference<NotificationDestination> navigation=new AtomicReference<>();
        NotificationController controller=fx(()->new NotificationController(gateway,Runnable::run,()->{},navigation::set,unreadRefresh::incrementAndGet));
        fx(()->{controller.activate();return null;});flush(3);
        assertEquals(List.of("选课结果通知"),fx(()->controller.typedView().visibleTitles()));
        assertEquals("",fx(()->controller.typedView().detailTitle()));assertEquals("1 条未读",fx(()->controller.typedView().unreadText()));assertTrue(gateway.marked.isEmpty());
        fx(()->{controller.select(11);return null;});flush(5);
        assertEquals("选课结果通知",fx(()->controller.typedView().detailTitle()));
        assertEquals("0 条未读",fx(()->controller.typedView().unreadText()));
        assertEquals(List.of(11L),gateway.marked);assertEquals(1,unreadRefresh.get());
        fx(()->{controller.navigate(new NotificationDestination(NotificationTarget.ACADEMIC_SCHEDULE,81L));return null;});
        assertEquals(NotificationTarget.ACADEMIC_SCHEDULE,navigation.get().target());controller.close();
    }

    @Test void forwardsFiltersAndMarksEveryMessageRead()throws Exception{
        FakeGateway gateway=new FakeGateway();AtomicInteger unreadRefresh=new AtomicInteger();
        NotificationController controller=fx(()->new NotificationController(gateway,Runnable::run,()->{},ignored->{},unreadRefresh::incrementAndGet));
        fx(()->{controller.activate();controller.search("  选课  ",NotificationSource.ACADEMIC,false,3);return null;});flush(5);
        assertEquals("选课",gateway.keyword);assertEquals(NotificationSource.ACADEMIC,gateway.source);assertEquals(false,gateway.read);assertEquals(3,gateway.page);
        fx(()->{controller.markAllRead();return null;});flush(5);
        assertEquals(1,gateway.markAllCalls);assertTrue(unreadRefresh.get()>=1);assertEquals("0 条未读",fx(()->controller.typedView().unreadText()));controller.close();
    }

    @Test void markingTheCurrentMessageUnreadRefreshesListAndBadge()throws Exception{
        FakeGateway gateway=new FakeGateway();gateway.readState=true;AtomicInteger unreadRefresh=new AtomicInteger();
        NotificationController controller=fx(()->new NotificationController(gateway,Runnable::run,()->{},ignored->{},unreadRefresh::incrementAndGet));
        fx(()->{controller.activate();controller.select(11);return null;});flush(4);

        fx(()->{controller.markUnread(11);return null;});flush(5);

        assertEquals(List.of(11L),gateway.markedUnread);
        assertEquals("1 条未读",fx(()->controller.typedView().unreadText()));
        assertTrue(unreadRefresh.get()>=1);controller.close();
    }

    @Test void ignoresAnOlderSearchThatFinishesAfterTheLatestOne()throws Exception{
        SearchGateway gateway=new SearchGateway();QueueExecutor executor=new QueueExecutor();
        NotificationController controller=fx(()->new NotificationController(gateway,executor,()->{},ignored->{},()->{}));
        fx(()->{controller.activate();controller.search("旧",null,null,1);controller.search("新",null,null,1);return null;});
        executor.run(3);flush(2);assertEquals(List.of("新"),fx(()->controller.typedView().visibleTitles()));
        executor.run(2);flush(2);assertEquals(List.of("新"),fx(()->controller.typedView().visibleTitles()));controller.close();
    }

    @Test void deletingTheCurrentMessageClearsDetailAndRefreshesListAndBadge()throws Exception{
        FakeGateway gateway=new FakeGateway();AtomicInteger unreadRefresh=new AtomicInteger();
        NotificationController controller=fx(()->new NotificationController(gateway,Runnable::run,()->{},ignored->{},unreadRefresh::incrementAndGet));
        fx(()->{controller.activate();controller.select(11);return null;});flush(5);
        fx(()->{controller.delete(11);return null;});flush(5);
        assertEquals(List.of(),fx(()->controller.typedView().visibleTitles()));
        assertEquals("",fx(()->controller.typedView().detailTitle()));
        assertTrue(gateway.deleted);assertTrue(unreadRefresh.get()>=2);controller.close();
    }

    private static NotificationRow row(String title,boolean read){return new NotificationRow(11,NotificationType.SCHEDULE_CHANGED,NotificationSource.ACADEMIC,title,"你的选课申请已通过",NotificationTarget.ACADEMIC_SCHEDULE,81L,read,Instant.parse("2026-09-09T00:00:00Z"));}
    private static NotificationDetail detail(boolean read){return new NotificationDetail(11,NotificationType.SCHEDULE_CHANGED,NotificationSource.ACADEMIC,"选课结果通知","《软件工程》选课申请已通过。",NotificationTarget.ACADEMIC_SCHEDULE,81L,read,read?Instant.parse("2026-09-09T01:00:00Z"):null,Instant.parse("2026-09-09T00:00:00Z"));}
    private static <T>T fx(java.util.concurrent.Callable<T> work)throws Exception{FutureTask<T> task=new FutureTask<>(work);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
    private static void flush(int count)throws Exception{for(int i=0;i<count;i++)fx(()->null);}

    private static final class FakeGateway implements NotificationGateway{
        boolean readState,deleted;String keyword="";NotificationSource source;Boolean read;int page;int markAllCalls;final List<Long> marked=new ArrayList<>();final List<Long> markedUnread=new ArrayList<>();
        @Override public NotificationPage search(String keyword,NotificationSource source,Boolean read,int page){this.keyword=keyword;this.source=source;this.read=read;this.page=page;return new NotificationPage(deleted?List.of():List.of(row("选课结果通知",readState)),page,10,deleted?0:21);}
        @Override public NotificationDetail detail(long id){return NotificationControllerTest.detail(readState);}@Override public int unreadCount(){return readState?0:1;}
        @Override public void markRead(long id){marked.add(id);readState=true;}@Override public void markAllRead(){markAllCalls++;readState=true;}@Override public void delete(long id){deleted=true;}
        @Override public void markUnread(long id){markedUnread.add(id);readState=false;}
    }
    private static final class SearchGateway implements NotificationGateway{
        @Override public NotificationPage search(String keyword,NotificationSource source,Boolean read,int page){return new NotificationPage(List.of(row(keyword.isBlank()?"初始":keyword,true)),1,10,1);}
        @Override public NotificationDetail detail(long id){return NotificationControllerTest.detail(true);}@Override public int unreadCount(){return 0;}@Override public void markRead(long id){}@Override public void markUnread(long id){}@Override public void markAllRead(){}@Override public void delete(long id){}
    }
    private static final class QueueExecutor implements Executor{private final List<Runnable> tasks=new ArrayList<>();@Override public void execute(Runnable task){tasks.add(task);}void run(int index){tasks.get(index).run();}}
}
