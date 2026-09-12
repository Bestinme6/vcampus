package com.vcampus.client.fx.system;

import com.vcampus.common.model.AuditLogEntry;
import com.vcampus.common.model.ClassPeriodTime;
import com.vcampus.common.protocol.ResponseMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SystemDataTest {
    @Test
    void malformedPeriodCountIsRejectedWithStableMessage() {
        ResponseMessage response = success(Map.of("term.count", "0", "period.count", "11"));
        var error = assertThrows(IllegalArgumentException.class, () -> SystemData.config(response));
        assertEquals("服务器返回的系统配置格式不正确", error.getMessage());
    }

    @Test
    void parsesTwelveOrderedPeriodsAndAuditPage() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("term.count", "0");
        config.put("period.count", "12");
        for (int index = 0; index < 12; index++) {
            config.put("period." + index, new ClassPeriodTime(index + 1, null, null).encode());
        }
        assertEquals(12, SystemData.config(success(config)).periods().size());

        AuditLogEntry row = new AuditLogEntry(1, null, "", "", "auth.login", "FAILED", "",
                java.time.Instant.parse("2026-09-12T08:00:00Z"));
        var page = SystemData.auditPage(success(Map.of(
                "page", "1", "pageSize", "20", "total", "1", "row.count", "1", "row.0", row.encode())));
        assertEquals(row, page.rows().getFirst());
    }

    @Test
    void auditQueryRejectsReverseDateRange() {
        assertThrows(IllegalArgumentException.class, () -> new SystemData.AuditQuery(
                "", "", "", LocalDate.parse("2026-09-12"), LocalDate.parse("2026-09-11"), 1));
    }

    @Test
    void auditQueryRejectsOverlongFilterText() {
        var error = assertThrows(IllegalArgumentException.class, () -> new SystemData.AuditQuery(
                "x".repeat(101), "", "", null, null, 1));
        assertEquals("用户关键词不能超过 100 位", error.getMessage());
    }

    @Test
    void auditQueryRejectsDatesOutsideDatabaseRange() {
        var error = assertThrows(IllegalArgumentException.class, () -> new SystemData.AuditQuery(
                "", "", "", LocalDate.of(10000, 1, 1), null, 1));
        assertEquals("开始日期年份必须在 1970—9998 之间", error.getMessage());
    }

    private ResponseMessage success(Map<String, String> data) {
        return ResponseMessage.success("r", "ok", data);
    }
}
