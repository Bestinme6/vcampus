package com.vcampus.client.fx;

import com.vcampus.common.model.AuditLogEntry;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.ResponseMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountSecurityDataTest {
    @Test
    void parsesAccountSessionAndRecentSecurityActivity() {
        AuditLogEntry activity = new AuditLogEntry(
                21L, 31L, "T0000031", "李老师", Actions.AUTH_LOGIN,
                "SUCCESS", "10.0.0.2", Instant.parse("2026-09-13T02:00:00Z"));
        Map<String, String> data = new LinkedHashMap<>();
        data.put("userId", "31");
        data.put("username", "T0000031");
        data.put("displayName", "李老师");
        data.put("roles", "ACADEMIC_ADMIN,TEACHER");
        data.put("expiresAt", "2026-09-13T10:00:00Z");
        data.put("row.count", "1");
        data.put("row.0", activity.encode());

        AccountSecurityData.Summary summary = AccountSecurityData.from(
                ResponseMessage.success("r", "ok", data));

        assertEquals(31L, summary.userId());
        assertEquals("T0000031", summary.username());
        assertEquals("李老师", summary.displayName());
        assertEquals(Set.of(UserRole.TEACHER, UserRole.ACADEMIC_ADMIN), summary.roles());
        assertEquals(Instant.parse("2026-09-13T10:00:00Z"), summary.expiresAt());
        assertEquals(activity, summary.activities().getFirst());
    }

    @Test
    void rejectsMalformedOrFailedSecurityResponses() {
        assertThrows(IllegalArgumentException.class, () -> AccountSecurityData.from(
                ResponseMessage.failure("r", "登录已过期")));
        assertThrows(IllegalArgumentException.class, () -> AccountSecurityData.from(
                ResponseMessage.success("r", "ok", Map.of(
                        "userId", "2", "username", "student", "displayName", "学生", "roles", "STUDENT",
                        "expiresAt", "not-an-instant", "row.count", "0"))));
    }

    @Test
    void rejectsActivityThatBelongsToAnotherUser() {
        AuditLogEntry other = new AuditLogEntry(
                22L, 99L, "other", "其他用户", Actions.AUTH_LOGIN,
                "SUCCESS", "10.0.0.9", Instant.parse("2026-09-13T02:00:00Z"));
        assertThrows(IllegalArgumentException.class, () -> AccountSecurityData.from(
                ResponseMessage.success("r", "ok", Map.of(
                        "userId", "31", "username", "T0000031", "displayName", "李老师",
                        "roles", "TEACHER", "expiresAt", "2026-09-13T10:00:00Z",
                        "row.count", "1", "row.0", other.encode()))));
    }
}
