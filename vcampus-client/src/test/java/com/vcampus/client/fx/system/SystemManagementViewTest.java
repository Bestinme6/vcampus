package com.vcampus.client.fx.system;

import javafx.application.Platform;
import javafx.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SystemManagementViewTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException exception) { ready.countDown(); }
        ready.await(15, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
    }

    @Test
    void workspaceHasConfigurationAndAuditTabsWithoutAuditMutation() throws Exception {
        fx(() -> {
            SystemManagementView view = new SystemManagementView(() -> { });
            new Scene(view, 1000, 720);
            view.applyCss();
            view.layout();
            assertNotNull(view.lookup("#system-config-tab"));
            assertNotNull(view.lookup("#system-audit-tab"));
            assertNotNull(view.lookup("#save-term"));
            assertNotNull(view.lookup("#save-periods"));
            assertNull(view.lookup("#audit-delete"));
            return null;
        });
    }

    private static <T> T fx(java.util.concurrent.Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }
}
