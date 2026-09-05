package com.vcampus.client.fx.forum;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForumAsyncTest {
    @Test void leavingPageDiscardsQueuedOldResultsAndCloseStopsCallbacks()throws Exception {
        try(var workers=Executors.newSingleThreadExecutor()){
            BlockingQueue<Runnable> ui=new LinkedBlockingQueue<>();AtomicInteger displayed=new AtomicInteger();
            ForumAsync async=new ForumAsync(workers,ui::add);
            async.run(()->17,displayed::set,e->fail(e));
            Runnable old=ui.poll(5,TimeUnit.SECONDS);assertNotNull(old);
            async.invalidate();old.run();assertEquals(0,displayed.get());
            async.run(()->23,displayed::set,e->fail(e));
            Runnable current=ui.poll(5,TimeUnit.SECONDS);assertNotNull(current);current.run();assertEquals(23,displayed.get());
            async.run(()->31,displayed::set,e->fail(e));
            Runnable closed=ui.poll(5,TimeUnit.SECONDS);assertNotNull(closed);async.close();closed.run();assertEquals(23,displayed.get());
        }
    }
}
