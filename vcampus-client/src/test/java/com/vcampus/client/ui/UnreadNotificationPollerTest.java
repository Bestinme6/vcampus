package com.vcampus.client.ui;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnreadNotificationPollerTest {
    @Test
    void startsImmediatelyAndSkipsTicksWhileARequestIsInFlight() {
        CompletableFuture<Integer> blocked = new CompletableFuture<>();
        QueuedSource source = new QueuedSource(blocked, CompletableFuture.completedFuture(8));
        ManualScheduler scheduler = new ManualScheduler();
        List<Integer> displayed = new ArrayList<>();
        UnreadNotificationPoller poller = new UnreadNotificationPoller(
                source, displayed::add, error -> { }, Duration.ofSeconds(10), scheduler);

        poller.start();
        scheduler.tick();
        scheduler.tick();

        assertEquals(1, source.calls);
        assertEquals(List.of(), displayed);

        blocked.complete(7);
        scheduler.tick();

        assertEquals(2, source.calls);
        assertEquals(List.of(7, 8), displayed);
    }

    @Test
    void refreshNowCoalescesWithAnExistingRequest() {
        CompletableFuture<Integer> blocked = new CompletableFuture<>();
        QueuedSource source = new QueuedSource(blocked);
        UnreadNotificationPoller poller = new UnreadNotificationPoller(
                source, count -> { }, error -> { }, Duration.ofSeconds(10),
                new ManualScheduler());

        poller.refreshNow();
        poller.refreshNow();

        assertEquals(1, source.calls);
    }

    @Test
    void failureKeepsLastDisplayedValueAndLaterTickRetries() {
        CompletableFuture<Integer> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("server unavailable"));
        QueuedSource source = new QueuedSource(
                CompletableFuture.completedFuture(4),
                failed,
                CompletableFuture.completedFuture(6));
        ManualScheduler scheduler = new ManualScheduler();
        List<Integer> displayed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        UnreadNotificationPoller poller = new UnreadNotificationPoller(
                source, displayed::add, error -> errors.add(error.getMessage()),
                Duration.ofSeconds(10), scheduler);

        poller.start();
        scheduler.tick();

        assertEquals(List.of(4), displayed);
        assertEquals(List.of("server unavailable"), errors);

        scheduler.tick();

        assertEquals(List.of(4, 6), displayed);
        assertEquals(3, source.calls);
    }

    @Test
    void closeSuppressesLateCallbacksAndFutureTicks() {
        CompletableFuture<Integer> blocked = new CompletableFuture<>();
        QueuedSource source = new QueuedSource(blocked);
        ManualScheduler scheduler = new ManualScheduler();
        List<Integer> displayed = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        UnreadNotificationPoller poller = new UnreadNotificationPoller(
                source, displayed::add, errors::add, Duration.ofSeconds(10), scheduler);

        poller.start();
        poller.close();
        blocked.complete(3);
        scheduler.tick();
        poller.refreshNow();

        assertEquals(1, source.calls);
        assertEquals(List.of(), displayed);
        assertEquals(List.of(), errors);
        assertEquals(1, scheduler.closeCalls);
    }

    private static final class QueuedSource implements Supplier<CompletableFuture<Integer>> {
        private final Deque<CompletableFuture<Integer>> responses = new ArrayDeque<>();
        private int calls;

        @SafeVarargs
        private QueuedSource(CompletableFuture<Integer>... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public CompletableFuture<Integer> get() {
            calls++;
            return responses.removeFirst();
        }
    }

    private static final class ManualScheduler
            implements UnreadNotificationPoller.PollScheduler {
        private Runnable task;
        private int closeCalls;

        @Override
        public void scheduleWithFixedDelay(Runnable task, Duration interval) {
            this.task = task;
        }

        @Override
        public void close() {
            closeCalls++;
        }

        private void tick() {
            if (task != null && closeCalls == 0) {
                task.run();
            }
        }
    }
}
