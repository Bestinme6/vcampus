package com.vcampus.client.fx.academic;

import com.vcampus.common.model.AcademicTermStatus;
import com.vcampus.common.model.ScheduleSlot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
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

    @Test
    void currentTeachingWeekComesFromTermDates() {
        AcademicData.Term term = new AcademicData.Term(7, "2026 秋", AcademicTermStatus.IN_PROGRESS,
                LocalDate.of(2026, 9, 1), LocalDate.of(2027, 1, 20));

        assertEquals(1, TeacherWorkspaceView.teachingWeek(term, LocalDate.of(2026, 9, 1)));
        assertEquals(2, TeacherWorkspaceView.teachingWeek(term, LocalDate.of(2026, 9, 7)));
        assertEquals(2, TeacherWorkspaceView.teachingWeek(term, LocalDate.of(2026, 9, 8)));
        assertEquals(0, TeacherWorkspaceView.teachingWeek(term, LocalDate.of(2026, 8, 31)));
        assertEquals(0, TeacherWorkspaceView.teachingWeek(term, LocalDate.of(2027, 1, 21)));
    }

    private static AcademicData.ScheduleEntry entry(long id, ScheduleSlot slot) {
        return new AcademicData.ScheduleEntry(id, 7, "2026 秋", "C00000" + id,
                "课程" + id, "CS-0" + id, "张老师", slot);
    }
}
