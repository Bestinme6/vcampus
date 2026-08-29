package com.vcampus.client.ui;

import javax.swing.SwingUtilities;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

final class BankAsync {
    private BankAsync() {
    }

    static <T> void run(CheckedSupplier<T> request, Consumer<T> success,
                        Consumer<Throwable> failure) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return request.get();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((value, error) -> SwingUtilities.invokeLater(() -> {
            if (error == null) {
                success.accept(value);
            } else {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                failure.accept(cause);
            }
        }));
    }

    @FunctionalInterface
    interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
