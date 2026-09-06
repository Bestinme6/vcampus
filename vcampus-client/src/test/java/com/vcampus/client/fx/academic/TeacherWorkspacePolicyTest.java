package com.vcampus.client.fx.academic;

import com.vcampus.common.model.ScheduleSlot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TeacherWorkspacePolicyTest {
    @Test
    void todayListOrdersPublishedEntriesByStartingPeriod() {
        AcademicData.ScheduleEntry later = entry(2, new ScheduleSlot(2, 5, 6, 1, 16, "教二-201"));
        AcademicData.ScheduleEntry earlier = entry(1, new ScheduleSlot(2, 1, 2, 1, 16, "教一-101"));

        assertEquals(List.of(earlier, later),
                TeacherWorkspaceView.forDay(List.of(later, earlier), 2, 6));
    }

    private static AcademicData.ScheduleEntry entry(long id, ScheduleSlot slot) {
        return new AcademicData.ScheduleEntry(id, 7, "2026 秋", "C00000" + id,
                "课程" + id, "CS-0" + id, "张老师", slot);
    }
}
