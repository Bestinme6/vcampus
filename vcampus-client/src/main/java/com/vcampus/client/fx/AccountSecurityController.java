package com.vcampus.client.fx;

import javafx.application.Platform;
import javafx.scene.Parent;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

final class AccountSecurityController implements AutoCloseable, AccountSecurityView.Listener {
    private final AccountSecurityGateway gateway;
    private final Executor executor;
    private final AccountSecurityView view;
    private final Runnable passwordChanged;
    private boolean active;
    private boolean passwordTransitioned;
    private long generation;

    AccountSecurityController(AccountSecurityGateway gateway, Executor executor,
                              Runnable back, Runnable passwordChanged) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.passwordChanged = Objects.requireNonNull(passwordChanged, "passwordChanged");
        view = new AccountSecurityView(back);
        view.setListener(this);
    }

    Parent view() { return view; }
    AccountSecurityView typedView() { return view; }

    void activate() {
        active = true;
        long request = ++generation;
        view.setBusy(true);
        async(gateway::overview, summary -> {
            if (request != generation) return;
            view.show(summary);
        }, error -> {
            if (request != generation) return;
            view.showError(error);
        });
    }

    void deactivate() {
        active = false;
        generation++;
    }

    @Override
    public void changePassword(char[] current, char[] next) {
        char[] currentCopy = Arrays.copyOf(current, current.length);
        char[] nextCopy = Arrays.copyOf(next, next.length);
        Arrays.fill(current, '\0');
        Arrays.fill(next, '\0');
        long request = ++generation;
        view.setBusy(true);
        CompletableFuture.supplyAsync(() -> {
            try {
                gateway.changePassword(currentCopy, nextCopy);
                return null;
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            } finally {
                Arrays.fill(currentCopy, '\0');
                Arrays.fill(nextCopy, '\0');
            }
        }, executor).whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error == null) {
                if (!passwordTransitioned) {
                    passwordTransitioned = true;
                    passwordChanged.run();
                }
            } else if (active && request == generation) {
                view.showError(message(error));
            }
        }));
    }

    private <T> void async(IoSupplier<T> work, Consumer<T> success, Consumer<String> failure) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return work.get();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, executor).whenComplete((value, error) -> Platform.runLater(() -> {
            if (!active) return;
            if (error == null) success.accept(value);
            else failure.accept(message(error));
        }));
    }

    private String message(Throwable error) {
        Throwable value = error;
        while (value.getCause() != null) value = value.getCause();
        return value.getMessage() == null ? "请求失败" : value.getMessage();
    }

    @Override public void close() { deactivate(); }

    @FunctionalInterface
    private interface IoSupplier<T> { T get() throws Exception; }
}
