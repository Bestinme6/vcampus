package com.vcampus.client.fx.academic;

import com.vcampus.common.model.ScheduleSlot;
import java.util.List;

/** Same teacher/normalized classroom and inclusive week overlap rules as publication. */
final class ScheduleAvailability {
    private ScheduleAvailability() { }

    static List<ScheduleSlot> blocked(List<AcademicData.ScheduleOccupancy> rows, long sectionId,
                                      long teacherId, String classroom, int from, int to) {
        return rows.stream().filter(row -> row.sectionId() != sectionId)
                .filter(row -> row.teacherUserId() == teacherId || (!classroom.isBlank()
                        && row.slot().classroom().trim().equalsIgnoreCase(classroom.trim())))
                .map(AcademicData.ScheduleOccupancy::slot)
                .filter(slot -> slot.startWeek() <= to && slot.endWeek() >= from).distinct().toList();
    }

    static List<ScheduleSlot> conflicts(List<AcademicData.ScheduleOccupancy> rows, long sectionId,
                                        long teacherId, List<ScheduleSlot> local) {
        return local.stream().flatMap(slot -> blocked(rows, sectionId, teacherId, slot.classroom(),
                slot.startWeek(), slot.endWeek()).stream().filter(slot::overlaps).map(other ->
                new ScheduleSlot(slot.dayOfWeek(), Math.max(slot.startPeriod(), other.startPeriod()),
                        Math.min(slot.endPeriod(), other.endPeriod()), Math.max(slot.startWeek(), other.startWeek()),
                        Math.min(slot.endWeek(), other.endWeek()), slot.classroom()))).distinct().toList();
    }
}
