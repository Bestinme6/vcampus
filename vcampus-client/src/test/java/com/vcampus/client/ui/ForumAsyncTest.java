package com.vcampus.client.ui;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForumAsyncTest {
    @Test
    void completionRunsOnEventDispatchThread() throws Exception {
        AtomicBoolean completedOnEdt = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        ForumAsync.run(() -> "ok",
                value -> completedOnEdt.set(SwingUtilities.isEventDispatchThread()),
                failure::set);

        await(completedOnEdt);
        assertTrue(completedOnEdt.get());
        assertNull(failure.get());
    }

    private void await(AtomicBoolean condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(3));
        while (!condition.get() && Instant.now().isBefore(deadline)) {
            Thread.sleep(10);
        }
    }
}
