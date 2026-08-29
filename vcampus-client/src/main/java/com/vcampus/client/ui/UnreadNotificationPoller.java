package com.vcampus.client.ui;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

final class UnreadNotificationPoller implements AutoCloseable {
    private final Supplier<CompletableFuture<Integer>> source;
    private final IntConsumer countConsumer;
    private final Consumer<Throwable> errorConsumer;
    private final Duration interval;
    private final PollScheduler scheduler;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    UnreadNotificationPoller(
            Supplier<CompletableFuture<Integer>> source,
            IntConsumer countConsumer,
            Consumer<Throwable> errorConsumer,
            Duration interval) {
        this(source, countConsumer, errorConsumer, interval, new ExecutorPollScheduler());
    }

    UnreadNotificationPoller(
            Supplier<CompletableFuture<Integer>> source,
            IntConsumer countConsumer,
            Consumer<Throwable> errorConsumer,
            Duration interval,
            PollScheduler scheduler) {
        this.source = Objects.requireNonNull(source, "source");
        this.countConsumer = Objects.requireNonNull(countConsumer, "countConsumer");
        this.errorConsumer = Objects.requireNonNull(errorConsumer, "errorConsumer");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("轮询间隔必须大于零");
        }
    }

    void start() {
        if (closed.get() || !started.compareAndSet(false, true)) {
            return;
        }
        refreshNow();
        scheduler.scheduleWithFixedDelay(this::refreshNow, interval);
    }

    void refreshNow() {
        if (closed.get() || !inFlight.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture<Integer> request;
        try {
            request = Objects.requireNonNull(source.get(), "source future");
        } catch (Throwable error) {
            inFlight.set(false);
            if (!closed.get()) {
                errorConsumer.accept(error);
            }
            return;
        }
        request.whenComplete((count, error) -> {
            inFlight.set(false);
            if (closed.get()) {
                return;
            }
            if (error != null) {
                errorConsumer.accept(unwrap(error));
            } else {
                countConsumer.accept(count);
            }
        });
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.close();
        }
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    interface PollScheduler extends AutoCloseable {
        void scheduleWithFixedDelay(Runnable task, Duration interval);

        @Override
        void close();
    }

    private static final class ExecutorPollScheduler implements PollScheduler {
        private final ScheduledExecutorService executor =
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "vcampus-unread-poller");
                    thread.setDaemon(true);
                    return thread;
                });

        @Override
        public void scheduleWithFixedDelay(Runnable task, Duration interval) {
            long delayMillis = Math.max(1L, interval.toMillis());
            executor.scheduleWithFixedDelay(
                    task, delayMillis, delayMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
