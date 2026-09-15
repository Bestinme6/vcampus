package com.vcampus.client.fx;

import com.vcampus.common.model.AuditLogEntry;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AccountSecurityViewTest {
    @BeforeAll static void toolkit() throws Exception { FxUiTest.toolkit(); }

    @Test
    void displaysAccountPasswordFormAndRecentActivityTogether() throws Exception {
        FxUiTest.fx(() -> {
            AccountSecurityView view = new AccountSecurityView(() -> { });
            new Scene(view, 1000, 720);
            view.setBusy(true);
            AuditLogEntry activity = new AuditLogEntry(
                    21L, 31L, "T0000031", "李老师", Actions.AUTH_LOGIN,
                    "SUCCESS", "10.0.0.2", Instant.parse("2026-09-13T02:00:00Z"));
            view.show(new AccountSecurityData.Summary(
                    31L, "T0000031", "李老师", Set.of(UserRole.TEACHER, UserRole.ACADEMIC_ADMIN),
                    Instant.parse("2026-09-13T10:00:00Z"), List.of(activity)));
            view.applyCss();
            view.layout();

            assertEquals("account-security-view", view.getId());
            assertEquals("T0000031", ((Label) view.lookup("#security-username")).getText());
            assertEquals("李老师", ((Label) view.lookup("#security-display-name")).getText());
            assertNotNull(view.lookup("#security-current-password"));
            assertNotNull(view.lookup("#security-new-password"));
            assertNotNull(view.lookup("#security-repeat-password"));
            assertNotNull(view.lookup("#security-change-password"));
            assertEquals("新密码长度为 8—128 位", ((Label) view.lookup("#security-feedback")).getText());
            @SuppressWarnings("unchecked")
            TableView<AuditLogEntry> table = (TableView<AuditLogEntry>) view.lookup("#security-activity-table");
            assertEquals(List.of(activity), table.getItems());
            FxUiTest.capture(view, "account-security-1000", 1000, 720);
            return null;
        });
    }
}
