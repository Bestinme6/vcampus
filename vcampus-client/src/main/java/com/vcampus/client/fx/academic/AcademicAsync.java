package com.vcampus.client.fx.academic;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Cancels superseded work and prevents late worker or UI callbacks from updating a page. */
final class AcademicAsync implements AutoCloseable {
    private final Executor executor;
    private final Consumer<Runnable> dispatch;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<FutureTask<?>> pending = new AtomicReference<>();

    AcademicAsync(Executor executor, Consumer<Runnable> dispatch) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
    }

    <T> FutureTask<T> submit(Callable<T> work, Consumer<T> success, Consumer<String> failure) {
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(success, "success");
        Objects.requireNonNull(failure, "failure");
        if (closed.get()) return cancelled(work);

        long requestGeneration = generation.incrementAndGet();
        FutureTask<T> task = new FutureTask<>(work) {
            @Override
            protected void done() {
                if (isCancelled() || closed.get()) return;
                T value = null;
                String error = null;
                try {
                    value = get();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    error = "操作已取消";
                } catch (ExecutionException exception) {
                    error = message(rootCause(exception));
                }
                T completedValue = value;
                String completedError = error;
                dispatch.accept(() -> {
                    if (closed.get() || requestGeneration != generation.get() || isCancelled()) return;
                    pending.compareAndSet(this, null);
                    if (completedError == null) success.accept(completedValue);
                    else failure.accept(completedError);
                });
            }
        };

        FutureTask<?> previous = pending.getAndSet(task);
        if (previous != null) previous.cancel(true);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException exception) {
            task.cancel(false);
            pending.compareAndSet(task, null);
            if (!closed.get() && requestGeneration == generation.get()) {
                dispatch.accept(() -> {
                    if (!closed.get() && requestGeneration == generation.get()) {
                        failure.accept("教务连接已关闭，请重新进入");
                    }
                });
            }
        }
        return task;
    }

    void invalidate() {
        generation.incrementAndGet();
        FutureTask<?> task = pending.getAndSet(null);
        if (task != null) task.cancel(true);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) invalidate();
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? "操作失败，请稍后重试" : error.getMessage();
    }

    private static <T> FutureTask<T> cancelled(Callable<T> work) {
        FutureTask<T> task = new FutureTask<>(work);
        task.cancel(false);
        return task;
    }
}
