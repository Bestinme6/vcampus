package com.vcampus.client.fx.academic;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcademicAsyncTest {
    @Test
    void staleRequestCannotReplaceNewerPage() {
        Queue<Runnable> ui = new ArrayDeque<>();
        List<String> rendered = new ArrayList<>();
        AcademicAsync async = new AcademicAsync(Runnable::run, ui::add);

        async.submit(() -> "old", rendered::add, error -> rendered.add("error:" + error));
        async.submit(() -> "new", rendered::add, error -> rendered.add("error:" + error));

        ui.remove().run();
        ui.remove().run();
        assertFalse(rendered.contains("old"));
        assertEquals(List.of("new"), rendered);
    }

    @Test
    void invalidateAndCloseDiscardQueuedCallbacksAndFutureSubmissions() {
        Queue<Runnable> ui = new ArrayDeque<>();
        List<String> rendered = new ArrayList<>();
        AcademicAsync async = new AcademicAsync(Runnable::run, ui::add);

        async.submit(() -> "invalid", rendered::add, error -> rendered.add(error));
        async.invalidate();
        ui.remove().run();
        assertTrue(rendered.isEmpty());

        async.submit(() -> "closed", rendered::add, error -> rendered.add(error));
        async.close();
        ui.remove().run();
        assertTrue(rendered.isEmpty());
        assertTrue(async.submit(() -> "ignored", rendered::add, rendered::add).isCancelled());
    }

    @Test
    void reportsTheRootFailureMessage() {
        List<String> errors = new ArrayList<>();
        AcademicAsync async = new AcademicAsync(Runnable::run, Runnable::run);

        async.submit(() -> {
            throw new IllegalStateException("版本已过期");
        }, value -> { }, errors::add);

        assertEquals(List.of("版本已过期"), errors);
    }
}
