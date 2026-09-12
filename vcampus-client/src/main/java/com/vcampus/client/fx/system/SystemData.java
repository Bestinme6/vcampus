package com.vcampus.client.fx.system;

import com.vcampus.common.model.AuditLogEntry;
import com.vcampus.common.model.ClassPeriodTime;
import com.vcampus.common.model.SystemTermConfig;
import com.vcampus.common.protocol.ResponseMessage;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SystemData {
    private SystemData() {
    }

    public static Config config(ResponseMessage response) {
        try {
            Map<String, String> data = successful(response);
            int termCount = nonNegativeInteger(data, "term.count");
            int periodCount = nonNegativeInteger(data, "period.count");
            if (periodCount != 12) throw new IllegalArgumentException();
            List<SystemTermConfig> terms = new ArrayList<>();
            for (int index = 0; index < termCount; index++) {
                terms.add(SystemTermConfig.decode(required(data, "term." + index)));
            }
            List<ClassPeriodTime> periods = new ArrayList<>();
            for (int index = 0; index < periodCount; index++) {
                ClassPeriodTime period = ClassPeriodTime.decode(required(data, "period." + index));
                if (period.periodNumber() != index + 1) throw new IllegalArgumentException();
                periods.add(period);
            }
            return new Config(terms, periods);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("服务器返回的系统配置格式不正确", exception);
        }
    }

    public static AuditPage auditPage(ResponseMessage response) {
        try {
            Map<String, String> data = successful(response);
            int page = positiveInteger(data, "page");
            int pageSize = positiveInteger(data, "pageSize");
            int total = nonNegativeInteger(data, "total");
            int count = nonNegativeInteger(data, "row.count");
            if (count > pageSize) throw new IllegalArgumentException();
            List<AuditLogEntry> rows = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                rows.add(AuditLogEntry.decode(required(data, "row." + index)));
            }
            return new AuditPage(rows, page, pageSize, total);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("服务器返回的审计日志格式不正确", exception);
        }
    }

    private static Map<String, String> successful(ResponseMessage response) {
        if (response == null || !response.success()) throw new IllegalArgumentException();
        return response.data();
    }

    private static int positiveInteger(Map<String, String> data, String key) {
        int value = Integer.parseInt(required(data, key));
        if (value < 1) throw new IllegalArgumentException();
        return value;
    }

    private static int nonNegativeInteger(Map<String, String> data, String key) {
        int value = Integer.parseInt(required(data, key));
        if (value < 0) throw new IllegalArgumentException();
        return value;
    }

    private static String required(Map<String, String> data, String key) {
        String value = data.get(key);
        if (value == null) throw new IllegalArgumentException();
        return value;
    }

    public record Config(List<SystemTermConfig> terms, List<ClassPeriodTime> periods) {
        public Config {
            terms = List.copyOf(terms);
            periods = List.copyOf(periods);
            if (periods.size() != 12) throw new IllegalArgumentException("必须包含第 1—12 节配置");
            for (int index = 0; index < periods.size(); index++) {
                if (periods.get(index).periodNumber() != index + 1) {
                    throw new IllegalArgumentException("节次配置必须按第 1—12 节排列");
                }
            }
        }
    }

    public record AuditQuery(String userKeyword, String actionPrefix, String resultCode,
                             LocalDate fromDate, LocalDate toDate, int page) {
        public AuditQuery {
            userKeyword = normalize(userKeyword);
            actionPrefix = normalize(actionPrefix);
            resultCode = normalize(resultCode);
            if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
                throw new IllegalArgumentException("开始日期不能晚于结束日期");
            }
            if (page < 1) throw new IllegalArgumentException("页码必须大于 0");
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }

    public record AuditPage(List<AuditLogEntry> rows, int page, int pageSize, int total) {
        public AuditPage {
            rows = List.copyOf(rows);
            if (page < 1 || pageSize < 1 || total < 0) throw new IllegalArgumentException("审计分页参数无效");
        }
    }
}
