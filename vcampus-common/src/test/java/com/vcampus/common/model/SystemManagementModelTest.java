package com.vcampus.common.model;

import com.vcampus.common.protocol.Actions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SystemManagementModelTest {
    @Test
    void termPeriodAndAuditRowsRoundTrip() {
        var term = new SystemTermConfig(7, "2026-2027", 1, "第一学期",
                LocalDate.parse("2026-09-01"), LocalDate.parse("2027-01-15"),
                LocalDateTime.parse("2026-08-20T00:00:00"),
                LocalDateTime.parse("2026-09-10T23:59:59"),
                LocalDateTime.parse("2026-09-20T23:59:59"), AcademicTermStatus.SELECTION);
        assertEquals(term, SystemTermConfig.decode(term.encode()));

        var emptyPeriod = new ClassPeriodTime(12, null, null);
        var period = new ClassPeriodTime(1, LocalTime.parse("08:00"), LocalTime.parse("08:45"));
        assertEquals(period, ClassPeriodTime.decode(period.encode()));
        assertEquals(emptyPeriod, ClassPeriodTime.decode(emptyPeriod.encode()));

        var audit = new AuditLogEntry(9, 1L, "admin", "系统管理员",
                Actions.SYSTEM_CONFIG_UPDATE_TERM, "SUCCESS", "127.0.0.1",
                Instant.parse("2026-09-12T08:00:00Z"));
        assertEquals(audit, AuditLogEntry.decode(audit.encode()));
        var anonymous = new AuditLogEntry(10, null, "", "", "auth.login", "FAILED", "", audit.createdAt());
        assertEquals(anonymous, AuditLogEntry.decode(anonymous.encode()));
    }

    @Test
    void periodRequiresBothTimesAndIncreasingOrder() {
        assertThrows(IllegalArgumentException.class,
                () -> new ClassPeriodTime(1, LocalTime.NOON, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ClassPeriodTime(1, LocalTime.NOON, LocalTime.NOON));
        assertThrows(IllegalArgumentException.class,
                () -> new ClassPeriodTime(13, null, null));
    }

    @Test
    void decodedRowsRequireExactFieldCounts() {
        assertThrows(IllegalArgumentException.class, () -> SystemTermConfig.decode("1:1"));
        assertThrows(IllegalArgumentException.class, () -> ClassPeriodTime.decode("1:1"));
        assertThrows(IllegalArgumentException.class, () -> AuditLogEntry.decode("1:1"));
    }

    @Test
    void termRequiresCoherentDates() {
        assertThrows(IllegalArgumentException.class, () -> new SystemTermConfig(1, "2026-2027", 1, "第一学期",
                LocalDate.parse("2027-01-15"), LocalDate.parse("2026-09-01"),
                LocalDateTime.parse("2026-08-20T00:00:00"),
                LocalDateTime.parse("2026-09-10T23:59:59"),
                LocalDateTime.parse("2026-09-20T23:59:59"), AcademicTermStatus.SELECTION));
    }
}
