package com.vcampus.client.ui;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

final class ForumAsync {
    private ForumAsync() {
    }

    static <T> void run(Component owner, CheckedSupplier<T> request,
                        Consumer<T> success,
                        Consumer<Throwable> failure) {
        if (!LegacyUiLifecycle.active(owner)) {
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                return request.get();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((value, error) -> SwingUtilities.invokeLater(() -> {
            if (!LegacyUiLifecycle.active(owner)) {
                return;
            }
            if (error == null) {
                success.accept(value);
            } else {
                Throwable cause = error instanceof CompletionException
                        && error.getCause() != null ? error.getCause() : error;
                failure.accept(cause);
            }
        }));
    }

    @FunctionalInterface
    interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
