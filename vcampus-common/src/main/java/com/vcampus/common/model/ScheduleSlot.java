package com.vcampus.common.model;

import java.util.Objects;

public record ScheduleSlot(
        int dayOfWeek,
        int startPeriod,
        int endPeriod,
        int startWeek,
        int endWeek,
        String classroom) {

    public ScheduleSlot {
        if (dayOfWeek < 1 || dayOfWeek > 7) {
            throw new IllegalArgumentException("星期必须在 1—7 之间");
        }
        if (startPeriod < 1 || startPeriod > 12 || endPeriod < startPeriod || endPeriod > 12) {
            throw new IllegalArgumentException("节次必须在 1—12 之间，且结束节次不能早于开始节次");
        }
        if (startWeek < 1 || startWeek > 30 || endWeek < startWeek || endWeek > 30) {
            throw new IllegalArgumentException("周次必须在 1—30 之间，且结束周不能早于开始周");
        }
        classroom = Objects.requireNonNull(classroom, "classroom").trim();
        if (classroom.isBlank()) {
            throw new IllegalArgumentException("请填写教室");
        }
        if (classroom.length() > 100) {
            throw new IllegalArgumentException("教室名称不能超过 100 位");
        }
    }

    public boolean overlaps(ScheduleSlot other) {
        Objects.requireNonNull(other, "other");
        return dayOfWeek == other.dayOfWeek
                && startPeriod <= other.endPeriod
                && endPeriod >= other.startPeriod
                && startWeek <= other.endWeek
                && endWeek >= other.startWeek;
    }
}
