package com.vcampus.client.fx.system;

import com.vcampus.common.model.AuditLogEntry;
import com.vcampus.common.model.ClassPeriodTime;
import com.vcampus.common.model.SystemTermConfig;
import com.vcampus.common.protocol.ResponseMessage;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemManagementControllerTest {
    @BeforeAll
    static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException exception) { ready.countDown(); }
        assertTrue(ready.await(15, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }

    @Test
    void staleAuditResponseCannotReplaceNewerFilter() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        FakeGateway gateway = new FakeGateway();
        FakeView view = new FakeView();
        SystemManagementController controller = new SystemManagementController(gateway, executor, view);
        controller.activate();
        executor.clear();

        controller.searchAudit(query("old"));
        controller.searchAudit(query("new"));
        executor.run(1);
        flush();
        executor.run(0);
        flush();

        assertEquals(List.of("new"), view.visibleActions);
        controller.close();
    }

    private SystemData.AuditQuery query(String action) {
        return new SystemData.AuditQuery("", action, "", null, null, 1);
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }

    private static void flush() throws Exception { fx(() -> null); fx(() -> null); }

    private static final class ManualExecutor implements java.util.concurrent.Executor {
        private final List<Runnable> tasks = new ArrayList<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        void run(int index) { tasks.remove(index).run(); }
        void clear() { tasks.clear(); }
    }

    private static final class FakeGateway implements SystemGateway {
        @Override public ResponseMessage config() { return ResponseMessage.success("r", "ok", java.util.Map.of()); }
        @Override public void updateTerm(SystemTermConfig term) { }
        @Override public void updatePeriods(List<ClassPeriodTime> periods) { }
        @Override public ResponseMessage search(String userKeyword, String actionPrefix, String resultCode,
                                                 LocalDate fromDate, LocalDate toDate, int page) {
            AuditLogEntry row = new AuditLogEntry(actionPrefix.equals("old") ? 1 : 2, null, "", "",
                    actionPrefix, "SUCCESS", "", Instant.parse("2026-09-12T08:00:00Z"));
            return ResponseMessage.success("r", "ok", java.util.Map.of(
                    "page", "1", "pageSize", "20", "total", "1", "row.count", "1", "row.0", row.encode()));
        }
    }

    private static final class FakeView implements SystemManagementController.View {
        private List<String> visibleActions = List.of();
        @Override public void showConfig(SystemData.Config config) { }
        @Override public void showAudit(SystemData.AuditPage page) {
            visibleActions = page.rows().stream().map(AuditLogEntry::actionCode).toList();
        }
        @Override public void setConfigBusy(boolean busy) { }
        @Override public void setAuditBusy(boolean busy) { }
        @Override public void showMessage(String message) { }
        @Override public void showError(String message) { }
    }
}
