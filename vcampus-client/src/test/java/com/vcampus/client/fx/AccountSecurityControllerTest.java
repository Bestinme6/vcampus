package com.vcampus.client.fx;

import com.vcampus.common.model.UserRole;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccountSecurityControllerTest {
    @BeforeAll static void toolkit() throws Exception { FxUiTest.toolkit(); }

    @Test
    void loadsOverviewAndLogsOutAfterACompletedPasswordChange() throws Exception {
        FakeGateway gateway = new FakeGateway();
        AtomicInteger logouts = new AtomicInteger();
        AccountSecurityController controller = FxUiTest.fx(() -> new AccountSecurityController(
                gateway, Runnable::run, () -> { }, logouts::incrementAndGet));

        FxUiTest.fx(() -> { controller.activate(); return null; });
        FxUiTest.fx(() -> null);
        assertEquals("student", FxUiTest.fx(() -> controller.typedView().usernameText()));

        FxUiTest.fx(() -> {
            controller.changePassword("current-password".toCharArray(), "new-password-123".toCharArray());
            return null;
        });
        FxUiTest.fx(() -> null);

        assertEquals("current-password", gateway.current);
        assertEquals("new-password-123", gateway.next);
        assertEquals(1, logouts.get());
        controller.close();
    }

    @Test
    void completedPasswordChangeStillLogsOutAfterLeavingThePage() throws Exception {
        DelayedGateway gateway = new DelayedGateway();
        AtomicInteger logouts = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AccountSecurityController controller = FxUiTest.fx(() -> new AccountSecurityController(
                gateway, executor, () -> { }, logouts::incrementAndGet));
        try {
            FxUiTest.fx(() -> {
                controller.activate();
                controller.changePassword("current-password".toCharArray(), "new-password-123".toCharArray());
                return null;
            });
            assertEquals(true, gateway.started.await(3, TimeUnit.SECONDS));

            FxUiTest.fx(() -> { controller.deactivate(); return null; });
            gateway.release.countDown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (logouts.get() == 0 && System.nanoTime() < deadline) {
                FxUiTest.fx(() -> null);
                Thread.sleep(10);
            }
            assertEquals(1, logouts.get());
        } finally {
            gateway.release.countDown();
            controller.close();
            executor.shutdownNow();
        }
    }

    private static final class FakeGateway implements AccountSecurityGateway {
        private String current;
        private String next;

        @Override public AccountSecurityData.Summary overview() {
            return new AccountSecurityData.Summary(2L, "student", "演示学生", Set.of(UserRole.STUDENT),
                    Instant.parse("2026-09-13T10:00:00Z"), List.of());
        }

        @Override public void changePassword(char[] currentPassword, char[] newPassword) {
            current = new String(currentPassword);
            next = new String(newPassword);
        }
    }

    private static final class DelayedGateway implements AccountSecurityGateway {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override public AccountSecurityData.Summary overview() {
            return new AccountSecurityData.Summary(2L, "student", "演示学生", Set.of(UserRole.STUDENT),
                    Instant.parse("2026-09-13T10:00:00Z"), List.of());
        }

        @Override public void changePassword(char[] currentPassword, char[] newPassword)
                throws java.io.IOException {
            started.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new java.io.IOException("test timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("test interrupted", exception);
            }
        }
    }
}
