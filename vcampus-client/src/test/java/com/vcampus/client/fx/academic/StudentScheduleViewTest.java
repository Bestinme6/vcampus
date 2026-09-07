package com.vcampus.client.fx.academic;

import com.vcampus.common.model.ScheduleSlot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StudentScheduleViewTest {
    @Test
    void weekFilterUsesPublishedSlotWeekRange() {
        AcademicData.ScheduleEntry firstHalf = entry(1, new ScheduleSlot(1, 1, 2, 1, 8, "教一-101"));
        AcademicData.ScheduleEntry secondHalf = entry(2, new ScheduleSlot(3, 3, 4, 9, 16, "实验楼-204"));

        assertEquals(List.of(firstHalf), StudentScheduleView.forWeek(List.of(firstHalf, secondHalf), 6));
        assertEquals(List.of(secondHalf), StudentScheduleView.forWeek(List.of(firstHalf, secondHalf), 12));
    }

    private static AcademicData.ScheduleEntry entry(long id, ScheduleSlot slot) {
        return new AcademicData.ScheduleEntry(id, 7, "2026 秋", "C00000" + id,
                "课程" + id, new BigDecimal("3.0"), "CS-0" + id, "张老师", slot);
    }
}
