package com.vcampus.server.service;

import com.vcampus.server.database.LibraryNoticeStore;

import java.sql.SQLException;
import java.time.*;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * 周期扫描临近到期或已逾期借阅并创建通知的后台服务。
 *
 * <p>通知写入具有去重约束，重复扫描不会重复提醒同一借阅阶段。</p>
 *
 * @since 1.0
 */
public final class LibraryOverdueNotifier implements AutoCloseable {
    private static final int BATCH_SIZE=100;
    private final LibraryNoticeStore notices;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;

    public LibraryOverdueNotifier(LibraryNoticeStore notices){this(notices,Clock.systemUTC(),Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"library-notifier");t.setDaemon(true);return t;}));}
    public LibraryOverdueNotifier(LibraryNoticeStore notices,Clock clock,ScheduledExecutorService scheduler){this.notices=Objects.requireNonNull(notices);this.clock=Objects.requireNonNull(clock);this.scheduler=Objects.requireNonNull(scheduler);}
    public void runOnce(){Instant now=clock.instant();try{notices.sendDueSoon(now,now.plus(Duration.ofDays(3)),BATCH_SIZE);notices.sendOverdue(now,BATCH_SIZE);}catch(SQLException e){System.err.println("Library notice scan failed: "+e.getMessage());}}
    public void start(){runOnce();scheduler.scheduleAtFixedRate(this::runOnce,1,1,TimeUnit.HOURS);}
    @Override public void close(){scheduler.shutdownNow();}
}
