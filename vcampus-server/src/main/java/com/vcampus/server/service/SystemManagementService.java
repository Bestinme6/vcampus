package com.vcampus.server.service;

import com.vcampus.common.model.ClassPeriodTime;
import com.vcampus.common.model.SystemAccessPolicy;
import com.vcampus.common.model.SystemTermConfig;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.server.database.AuditQueryStore;
import com.vcampus.server.database.AuditQueryStore.AuditPage;
import com.vcampus.server.database.AuditQueryStore.AuditQuery;
import com.vcampus.server.database.AuditStore;
import com.vcampus.server.database.SystemManagementStore;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SystemManagementService {
    private static final int AUDIT_PAGE_SIZE = 20;

    private final SystemManagementStore configurationStore;
    private final AuditQueryStore auditQueries;
    private final AuditStore audit;
    private final SessionManager sessions;

    public SystemManagementService(SystemManagementStore configurationStore,
                                   AuditQueryStore auditQueries,
                                   AuditStore audit,
                                   SessionManager sessions) {
        this.configurationStore = configurationStore;
        this.auditQueries = auditQueries;
        this.audit = audit;
        this.sessions = sessions;
    }

    public ResponseMessage getConfig(RequestMessage request) {
        Optional<UserSession> administrator = administrator(request);
        if (administrator.isEmpty()) {
            return authorizationFailure(request);
        }
        try {
            SystemManagementStore.Snapshot snapshot = configurationStore.configuration();
            Map<String, String> data = new LinkedHashMap<>();
            putRows(data, "term", snapshot.terms().stream().map(SystemTermConfig::encode).toList());
            putRows(data, "period", snapshot.periods().stream().map(ClassPeriodTime::encode).toList());
            return ResponseMessage.success(request.requestId(), "系统配置加载成功", data);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage updateTerm(RequestMessage request, String clientAddress) {
        Optional<UserSession> administrator = administrator(request);
        if (administrator.isEmpty()) {
            return authorizationFailure(request);
        }
        UserSession session = administrator.get();
        try {
            SystemTermConfig term = SystemTermConfig.decode(required(request.parameters(), "term", "学期配置"));
            if (term.academicYear().length() > 9 || term.termName().length() > 100) {
                throw new IllegalArgumentException("学年或学期名称过长");
            }
            if (!configurationStore.updateTerm(term, session.userId())) {
                audit.record(session.userId(), Actions.SYSTEM_CONFIG_UPDATE_TERM, "NOT_FOUND", clientAddress);
                return ResponseMessage.failure(request.requestId(), "学期不存在或标识已变化");
            }
            audit.record(session.userId(), Actions.SYSTEM_CONFIG_UPDATE_TERM, "SUCCESS", clientAddress);
            return ResponseMessage.success(request.requestId(), "学期配置已保存", Map.of());
        } catch (IllegalArgumentException exception) {
            audit.record(session.userId(), Actions.SYSTEM_CONFIG_UPDATE_TERM, "INVALID", clientAddress);
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            audit.record(session.userId(), Actions.SYSTEM_CONFIG_UPDATE_TERM, "DATABASE_ERROR", clientAddress);
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage updatePeriods(RequestMessage request, String clientAddress) {
        Optional<UserSession> administrator = administrator(request);
        if (administrator.isEmpty()) {
            return authorizationFailure(request);
        }
        UserSession session = administrator.get();
        try {
            List<ClassPeriodTime> periods = parsePeriods(request.parameters());
            configurationStore.updatePeriods(periods, session.userId());
            audit.record(session.userId(), Actions.SYSTEM_CONFIG_UPDATE_PERIODS, "SUCCESS", clientAddress);
            return ResponseMessage.success(request.requestId(), "节次时间已保存", Map.of());
        } catch (IllegalArgumentException exception) {
            audit.record(session.userId(), Actions.SYSTEM_CONFIG_UPDATE_PERIODS, "INVALID", clientAddress);
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            audit.record(session.userId(), Actions.SYSTEM_CONFIG_UPDATE_PERIODS, "DATABASE_ERROR", clientAddress);
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage searchAudit(RequestMessage request) {
        Optional<UserSession> administrator = administrator(request);
        if (administrator.isEmpty()) {
            return authorizationFailure(request);
        }
        try {
            Map<String, String> values = request.parameters();
            AuditQuery query = new AuditQuery(
                    bounded(values.get("userKeyword"), "用户关键词", 100),
                    bounded(values.get("actionPrefix"), "动作前缀", 128),
                    bounded(values.get("resultCode"), "结果代码", 32),
                    optionalDate(values.get("fromDate"), "开始日期"),
                    optionalDate(values.get("toDate"), "结束日期"),
                    positiveInteger(values.getOrDefault("page", "1"), "页码"), AUDIT_PAGE_SIZE);
            AuditPage page = auditQueries.search(query);
            Map<String, String> data = new LinkedHashMap<>();
            data.put("page", Integer.toString(page.page()));
            data.put("pageSize", Integer.toString(page.pageSize()));
            data.put("total", Integer.toString(page.total()));
            data.put("row.count", Integer.toString(page.rows().size()));
            for (int index = 0; index < page.rows().size(); index++) {
                data.put("row." + index, page.rows().get(index).encode());
            }
            return ResponseMessage.success(request.requestId(), "审计日志查询成功", data);
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    private List<ClassPeriodTime> parsePeriods(Map<String, String> data) {
        if (!"12".equals(data.get("period.count"))) {
            throw new IllegalArgumentException("必须提交第 1—12 节完整配置");
        }
        List<ClassPeriodTime> periods = new ArrayList<>();
        LocalTime previousEnd = null;
        for (int index = 0; index < 12; index++) {
            ClassPeriodTime period = ClassPeriodTime.decode(required(data, "period." + index, "节次配置"));
            if (period.periodNumber() != index + 1) {
                throw new IllegalArgumentException("必须提交第 1—12 节完整配置");
            }
            if (previousEnd != null && period.startTime() != null && period.startTime().isBefore(previousEnd)) {
                throw new IllegalArgumentException("相邻节次时间不能重叠");
            }
            if (period.endTime() != null) {
                previousEnd = period.endTime();
            }
            periods.add(period);
        }
        return List.copyOf(periods);
    }

    private Optional<UserSession> administrator(RequestMessage request) {
        return sessions.find(request.parameters().get("sessionToken"))
                .filter(session -> SystemAccessPolicy.canManage(session.roles()));
    }

    private ResponseMessage authorizationFailure(RequestMessage request) {
        if (sessions.find(request.parameters().get("sessionToken")).isEmpty()) {
            return ResponseMessage.failure(request.requestId(), "会话已失效，请重新登录");
        }
        return ResponseMessage.failure(request.requestId(), "没有系统管理权限");
    }

    private String required(Map<String, String> values, String key, String label) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("请提交" + label);
        }
        return value;
    }

    private String bounded(String value, String label, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(label + "不能超过 " + maximum + " 位");
        }
        return normalized;
    }

    private LocalDate optionalDate(String value, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(label + "格式必须为 yyyy-MM-dd");
        }
    }

    private int positiveInteger(String value, String label) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1) {
                throw new IllegalArgumentException(label + "必须大于 0");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + "必须是数字");
        }
    }

    private void putRows(Map<String, String> data, String prefix, List<String> rows) {
        data.put(prefix + ".count", Integer.toString(rows.size()));
        for (int index = 0; index < rows.size(); index++) {
            data.put(prefix + "." + index, rows.get(index));
        }
    }

    private ResponseMessage databaseFailure(RequestMessage request, SQLException exception) {
        System.err.println("System management database error: " + exception.getMessage());
        return ResponseMessage.failure(request.requestId(), "系统管理数据暂时不可用，请稍后重试");
    }
}
