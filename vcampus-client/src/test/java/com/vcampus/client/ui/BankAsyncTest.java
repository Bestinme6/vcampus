package com.vcampus.client.ui;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankAsyncTest {
    @Test
    void completionRunsOnEventDispatchThread() throws Exception {
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean onEdt = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        BankAsync.run(() -> "ok", value -> {
            onEdt.set(SwingUtilities.isEventDispatchThread());
            completed.set(true);
        }, error -> {
            failure.set(error);
            completed.set(true);
        });
        Instant deadline = Instant.now().plus(Duration.ofSeconds(3));
        while (!completed.get() && Instant.now().isBefore(deadline)) Thread.sleep(10);
        assertTrue(completed.get());
        assertTrue(onEdt.get());
        assertNull(failure.get());
    }
}
