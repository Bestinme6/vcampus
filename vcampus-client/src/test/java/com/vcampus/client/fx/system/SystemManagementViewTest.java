package com.vcampus.client.fx.system;

import com.vcampus.common.model.AuditLogEntry;
import com.vcampus.common.model.AcademicTermStatus;
import com.vcampus.common.model.ClassPeriodTime;
import com.vcampus.common.model.SystemTermConfig;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void auditResultAcceptsEveryStoredCodeAndSelectionShowsAllFields() throws Exception {
        fx(() -> {
            SystemManagementView view = new SystemManagementView(() -> { });
            new Scene(view, 1000, 720);
            view.applyCss();
            view.layout();
            ComboBox<?> result = (ComboBox<?>) view.lookup("#audit-result");
            assertTrue(result.isEditable());
            assertTrue(result.getItems().containsAll(List.of("DENIED", "DISABLED", "NO_ROLE",
                    "INVALID_ROLES", "DUPLICATE")));

            AuditLogEntry row = new AuditLogEntry(17, 9L, "admin", "系统管理员",
                    "system.config.updatePeriods", "SUCCESS", "127.0.0.1",
                    Instant.parse("2026-09-12T08:00:00Z"));
            view.showAudit(new SystemData.AuditPage(List.of(row), 1, 20, 1));
            @SuppressWarnings("unchecked")
            TableView<AuditLogEntry> table = (TableView<AuditLogEntry>) view.lookup("#audit-table");
            table.getSelectionModel().select(row);
            Parent details = (Parent) view.lookup("#audit-details");
            assertInstanceOf(GridPane.class, details);
            assertNull(details.lookup(".scroll-pane"));
            assertEquals("17", label(view, "#audit-detail-id").getText());
            assertEquals("9", label(view, "#audit-detail-user-id").getText());
            assertEquals("admin", label(view, "#audit-detail-username").getText());
            assertEquals("系统管理员", label(view, "#audit-detail-display-name").getText());
            assertEquals("system.config.updatePeriods", label(view, "#audit-detail-action").getText());
            assertEquals("SUCCESS", label(view, "#audit-detail-result").getText());
            assertEquals("127.0.0.1", label(view, "#audit-detail-client-address").getText());
            assertEquals("2026-09-12T08:00:00Z", label(view, "#audit-detail-created-at").getText());
            table.getSelectionModel().clearSelection();
            assertEquals("—", label(view, "#audit-detail-action").getText());
            assertSame(TableView.UNCONSTRAINED_RESIZE_POLICY, table.getColumnResizePolicy());
            return null;
        });
    }

    @Test
    void validatesPeriodOverlapAndTextLengthsBeforeCallingListener() throws Exception {
        fx(() -> {
            SystemManagementView view = new SystemManagementView(() -> { });
            AtomicInteger saves = new AtomicInteger();
            view.setListener(new SystemManagementView.Listener() {
                @Override public void saveTerm(SystemTermConfig term) { }
                @Override public void savePeriods(List<ClassPeriodTime> periods) { saves.incrementAndGet(); }
                @Override public void searchAudit(SystemData.AuditQuery query) { }
            });
            new Scene(view, 1000, 720);
            view.applyCss();
            view.layout();
            ((TextField) view.lookup("#period-1-start")).setText("08:00");
            ((TextField) view.lookup("#period-1-end")).setText("09:00");
            ((TextField) view.lookup("#period-2-start")).setText("08:45");
            ((TextField) view.lookup("#period-2-end")).setText("09:30");
            ((javafx.scene.control.Button) view.lookup("#save-periods")).fire();
            assertEquals(0, saves.get());
            assertTrue(((javafx.scene.control.Label) view.lookup("#system-feedback"))
                    .getText().contains("不能重叠"));

            TextField user = (TextField) view.lookup("#audit-user");
            user.setText("x".repeat(101));
            assertEquals(0, user.getText().length());
            return null;
        });
    }

    @Test
    void displaysImmutableAcademicYearAndTermNumber() throws Exception {
        fx(() -> {
            SystemManagementView view = new SystemManagementView(() -> { });
            new Scene(view, 1000, 720);
            view.applyCss();
            view.layout();
            SystemTermConfig term = new SystemTermConfig(1, "2026-2027", 2, "春季学期",
                    LocalDate.parse("2027-02-20"), LocalDate.parse("2027-07-01"),
                    LocalDateTime.parse("2027-02-01T08:00:00"),
                    LocalDateTime.parse("2027-02-10T18:00:00"),
                    LocalDateTime.parse("2027-02-17T18:00:00"), AcademicTermStatus.PLANNED);
            List<ClassPeriodTime> periods = java.util.stream.IntStream.rangeClosed(1, 12)
                    .mapToObj(number -> new ClassPeriodTime(number, null, null)).toList();
            view.showConfig(new SystemData.Config(List.of(term), periods));

            assertEquals("2026-2027", ((javafx.scene.control.Label) view.lookup("#term-academic-year")).getText());
            assertEquals("第 2 学期", ((javafx.scene.control.Label) view.lookup("#term-number")).getText());
            return null;
        });
    }

    private static <T> T fx(java.util.concurrent.Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }

    private static Label label(Parent root, String selector) {
        return (Label) root.lookup(selector);
    }
}
