package com.vcampus.client.ui;

import com.vcampus.common.model.ClassPeriodTime;

import java.time.format.DateTimeFormatter;
import java.util.List;

public final class ClassPeriodFormatter {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private ClassPeriodFormatter() {
    }

    public static String format(int start, int end, List<ClassPeriodTime> periods) {
        if (start < 1 || end < start || end > 12) throw new IllegalArgumentException("节次范围无效");
        String base = "第 " + start + (start == end ? "" : "—" + end) + " 节";
        ClassPeriodTime first = find(periods, start);
        ClassPeriodTime last = find(periods, end);
        if (first == null || last == null || !first.configured() || !last.configured()) return base;
        return base + " " + TIME.format(first.startTime()) + "—" + TIME.format(last.endTime());
    }

    private static ClassPeriodTime find(List<ClassPeriodTime> periods, int number) {
        if (periods == null) return null;
        return periods.stream().filter(period -> period.periodNumber() == number).findFirst().orElse(null);
    }
}
