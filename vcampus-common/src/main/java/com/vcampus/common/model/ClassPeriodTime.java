package com.vcampus.common.model;

import com.vcampus.common.protocol.RowCodec;

import java.time.LocalTime;
import java.util.List;

public record ClassPeriodTime(int periodNumber, LocalTime startTime, LocalTime endTime) {
    private static final int FIELD_COUNT = 3;

    public ClassPeriodTime {
        if (periodNumber < 1 || periodNumber > 12) {
            throw new IllegalArgumentException("节次必须为 1—12");
        }
        if ((startTime == null) != (endTime == null)) {
            throw new IllegalArgumentException("开始和结束时间必须同时填写");
        }
        if (startTime != null && !startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("开始时间必须早于结束时间");
        }
    }

    public boolean configured() {
        return startTime != null;
    }

    public String encode() {
        return RowCodec.encode(Integer.toString(periodNumber), text(startTime), text(endTime));
    }

    public static ClassPeriodTime decode(String encoded) {
        List<String> fields = RowCodec.decode(encoded);
        if (fields.size() != FIELD_COUNT) {
            throw new IllegalArgumentException("Invalid class period field count");
        }
        try {
            return new ClassPeriodTime(Integer.parseInt(fields.get(0)), time(fields.get(1)), time(fields.get(2)));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid class period row", exception);
        }
    }

    private static String text(LocalTime value) {
        return value == null ? "" : value.toString();
    }

    private static LocalTime time(String value) {
        return value.isEmpty() ? null : LocalTime.parse(value);
    }
}
