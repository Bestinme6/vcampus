package com.vcampus.client.fx.system;

import com.vcampus.client.fx.identity.RequestGate;
import com.vcampus.common.model.ClassPeriodTime;
import com.vcampus.common.model.SystemTermConfig;
import javafx.application.Platform;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class SystemManagementController implements AutoCloseable {
    public interface View {
        void showConfig(SystemData.Config config);
        void showAudit(SystemData.AuditPage page);
        void setConfigBusy(boolean busy);
        void setAuditBusy(boolean busy);
        void showMessage(String message);
        void showError(String message);
    }

    private final SystemGateway gateway;
    private final Executor executor;
    private final View view;
    private final RequestGate configGate = new RequestGate();
    private final RequestGate auditGate = new RequestGate();
    private boolean active;
    private SystemData.AuditQuery auditQuery = new SystemData.AuditQuery("", "", "", null, null, 1);

    public SystemManagementController(SystemGateway gateway, Executor executor, View view) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.view = Objects.requireNonNull(view, "view");
    }

    public void activate() {
        active = true;
        loadConfig();
        searchAudit(auditQuery);
    }

    public void deactivate() {
        active = false;
        configGate.next();
        auditGate.next();
    }

    public void saveTerm(SystemTermConfig term) {
        long generation = configGate.next();
        view.setConfigBusy(true);
        async(() -> { gateway.updateTerm(term); return null; }, ignored -> {
            if (!configGate.accepts(generation)) return;
            view.setConfigBusy(false);
            view.showMessage("学期配置已保存");
            loadConfig();
        }, error -> finishConfigFailure(generation, error));
    }

    public void savePeriods(List<ClassPeriodTime> periods) {
        List<ClassPeriodTime> copy = List.copyOf(periods);
        long generation = configGate.next();
        view.setConfigBusy(true);
        async(() -> { gateway.updatePeriods(copy); return null; }, ignored -> {
            if (!configGate.accepts(generation)) return;
            view.setConfigBusy(false);
            view.showMessage("节次时间已保存");
            loadConfig();
        }, error -> finishConfigFailure(generation, error));
    }

    public void searchAudit(SystemData.AuditQuery query) {
        auditQuery = Objects.requireNonNull(query, "query");
        long generation = auditGate.next();
        view.setAuditBusy(true);
        async(() -> SystemData.auditPage(gateway.search(query.userKeyword(), query.actionPrefix(),
                        query.resultCode(), query.fromDate(), query.toDate(), query.page())), page -> {
            if (!auditGate.accepts(generation)) return;
            view.setAuditBusy(false);
            view.showAudit(page);
        }, error -> {
            if (!auditGate.accepts(generation)) return;
            view.setAuditBusy(false);
            view.showError(error);
        });
    }

    private void loadConfig() {
        long generation = configGate.next();
        view.setConfigBusy(true);
        async(() -> SystemData.config(gateway.config()), config -> {
            if (!configGate.accepts(generation)) return;
            view.setConfigBusy(false);
            view.showConfig(config);
        }, error -> finishConfigFailure(generation, error));
    }

    private void finishConfigFailure(long generation, String error) {
        if (!configGate.accepts(generation)) return;
        view.setConfigBusy(false);
        view.showError(error);
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

    @Override
    public void close() {
        active = false;
        configGate.close();
        auditGate.close();
    }

    @FunctionalInterface
    private interface IoSupplier<T> { T get() throws Exception; }
}
