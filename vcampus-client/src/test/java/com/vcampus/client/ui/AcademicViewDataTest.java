package com.vcampus.client.ui;

import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AcademicViewDataTest {
    @Test
    void legacyViewsDecodeTheFourteenFieldScheduleContract() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("count", "1");
        data.put("row.0", RowCodec.encode("301", "7", "2026 秋", "CS2101",
                "面向对象程序设计", "3.5", "01 班", "张老师",
                "1", "1", "2", "1", "16", "教一-401"));

        AcademicViewData.ScheduleEntryView entry = AcademicViewData.schedules(
                ResponseMessage.success("request", "ok", data)).getFirst();

        assertEquals(new BigDecimal("3.5"), entry.credits());
        assertEquals("张老师", entry.teacherName());
        assertEquals("教一-401", entry.classroom());
    }

    @Test
    void legacyViewsRejectTheObsoleteThirteenFieldScheduleContract() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("count", "1");
        data.put("row.0", RowCodec.encode("301", "7", "2026 秋", "CS2101",
                "面向对象程序设计", "01 班", "张老师",
                "1", "1", "2", "1", "16", "教一-401"));

        assertThrows(IllegalArgumentException.class, () -> AcademicViewData.schedules(
                ResponseMessage.success("request", "ok", data)));
    }
}
