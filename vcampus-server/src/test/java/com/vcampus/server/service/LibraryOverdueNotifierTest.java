package com.vcampus.server.service;

import com.vcampus.server.database.LibraryNoticeStore;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LibraryOverdueNotifierTest {
    @Test void runOnceInvokesBothScansAndCloseStopsScheduler(){
        class Fake implements LibraryNoticeStore{int due,overdue;public int sendDueSoon(Instant n,Instant d,int b){due++;assertEquals(100,b);return 4;}public int sendOverdue(Instant n,int b){overdue++;assertEquals(100,b);return 2;}}
        Fake store=new Fake();ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor();
        var notifier=new LibraryOverdueNotifier(store,Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"),ZoneOffset.UTC),scheduler);
        notifier.runOnce();assertEquals(1,store.due);assertEquals(1,store.overdue);notifier.close();assertTrue(scheduler.isShutdown());
    }
}
