package com.vcampus.common.model;

import com.vcampus.common.protocol.RowCodec;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

public record SystemTermConfig(long id, String academicYear, int termNumber, String termName,
                               LocalDate startDate, LocalDate endDate,
                               LocalDateTime selectionStart, LocalDateTime selectionEnd,
                               LocalDateTime dropDeadline, AcademicTermStatus status) {
    private static final int FIELD_COUNT = 10;

    public SystemTermConfig {
        if (id <= 0) {
            throw new IllegalArgumentException("学期编号必须为正数");
        }
        academicYear = requireText(academicYear, "学年不能为空");
        termName = requireText(termName, "学期名称不能为空");
        if (termNumber < 1 || termNumber > 3) {
            throw new IllegalArgumentException("学期序号必须为 1—3");
        }
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        Objects.requireNonNull(selectionStart, "selectionStart");
        Objects.requireNonNull(selectionEnd, "selectionEnd");
        Objects.requireNonNull(dropDeadline, "dropDeadline");
        Objects.requireNonNull(status, "status");
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("开学日期不能晚于结束日期");
        }
        if (selectionStart.isAfter(selectionEnd)) {
            throw new IllegalArgumentException("选课开始时间不能晚于选课结束时间");
        }
        if (selectionEnd.isAfter(dropDeadline)) {
            throw new IllegalArgumentException("退课截止时间不能早于选课结束时间");
        }
    }

    public String encode() {
        return RowCodec.encode(Long.toString(id), academicYear, Integer.toString(termNumber), termName,
                startDate.toString(), endDate.toString(), selectionStart.toString(), selectionEnd.toString(),
                dropDeadline.toString(), status.name());
    }

    public static SystemTermConfig decode(String encoded) {
        List<String> fields = RowCodec.decode(encoded);
        if (fields.size() != FIELD_COUNT) {
            throw new IllegalArgumentException("Invalid system term field count");
        }
        try {
            return new SystemTermConfig(Long.parseLong(fields.get(0)), fields.get(1),
                    Integer.parseInt(fields.get(2)), fields.get(3), LocalDate.parse(fields.get(4)),
                    LocalDate.parse(fields.get(5)), LocalDateTime.parse(fields.get(6)),
                    LocalDateTime.parse(fields.get(7)), LocalDateTime.parse(fields.get(8)),
                    AcademicTermStatus.valueOf(fields.get(9)));
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException
                    && "Invalid system term field count".equals(exception.getMessage())) {
                throw exception;
            }
            throw new IllegalArgumentException("Invalid system term row", exception);
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
