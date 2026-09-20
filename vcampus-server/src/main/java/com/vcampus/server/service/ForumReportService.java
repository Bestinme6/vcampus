package com.vcampus.server.service;

import com.vcampus.common.model.ForumAccessPolicy;
import com.vcampus.common.model.ForumReportReason;
import com.vcampus.common.model.ForumReportResolution;
import com.vcampus.common.model.ForumReportStatus;
import com.vcampus.common.model.ForumTargetType;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.ForumReportStore;
import com.vcampus.server.database.AuditStore;
import com.vcampus.server.database.ForumStore.MutationResult;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;

import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

/**
 * 论坛举报提交、状态查询和管理员处置服务。
 *
 * <p>处置过程保留举报状态、审核动作和审计记录，并限制普通用户只能访问自己的举报状态。</p>
 *
 * @since 1.0
 */
public final class ForumReportService {
    private static final int PAGE_SIZE = 10;
    private static final AuditStore NO_AUDIT = (userId, action, result, address) -> { };

    private final ForumReportStore reports;
    private final SessionManager sessions;
    private final AuditStore audit;

    public ForumReportService(ForumReportStore reports, SessionManager sessions) {
        this(reports, sessions, NO_AUDIT);
    }

    public ForumReportService(ForumReportStore reports, SessionManager sessions, AuditStore audit) {
        this.reports = Objects.requireNonNull(reports);
        this.sessions = Objects.requireNonNull(sessions);
        this.audit = Objects.requireNonNull(audit);
    }

    public ResponseMessage create(RequestMessage request) {
        Optional<UserSession> session = user(request);
        if (session.isEmpty()) return expired(request);
        try {
            Map<String, String> values = request.parameters();
            long reportId = reports.create(session.get().userId(),
                    enumValue(values.get("targetType"), ForumTargetType.class),
                    positiveLong(values.get("targetId")),
                    enumValue(values.get("reason"), ForumReportReason.class),
                    boundedOptional(values.get("detail"), 300, "补充说明"));
            return ResponseMessage.success(request.requestId(), "举报已提交",
                    Map.of("reportId", Long.toString(reportId)));
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage state(RequestMessage request) {
        Optional<UserSession> session = user(request);
        if (session.isEmpty()) return expired(request);
        try {
            Map<String, String> values = request.parameters();
            boolean reported = reports.reportedBy(session.get().userId(),
                    enumValue(values.get("targetType"), ForumTargetType.class),
                    positiveLong(values.get("targetId")));
            return ResponseMessage.success(request.requestId(), "查询成功",
                    Map.of("reported", Boolean.toString(reported)));
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), "举报查询条件无效");
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage search(RequestMessage request) {
        Optional<UserSession> session = manager(request);
        if (session.isEmpty()) return managerFailure(request);
        try {
            Map<String, String> values = request.parameters();
            var page = reports.search(new ForumReportStore.ReportQuery(
                    optionalEnum(values.get("status"), ForumReportStatus.class),
                    optionalEnum(values.get("targetType"), ForumTargetType.class),
                    optionalEnum(values.get("reason"), ForumReportReason.class),
                    positiveInt(values.get("page"), 1), PAGE_SIZE));
            Map<String, String> data = pageData(page.page(), page.pageSize(),
                    page.total(), page.rows().size());
            for (int index = 0; index < page.rows().size(); index++) {
                var row = page.rows().get(index);
                data.put("row." + index, RowCodec.encode(
                        Long.toString(row.id()), row.targetType().name(),
                        Long.toString(row.targetId()), Long.toString(row.postId()), row.content(),
                        Long.toString(row.reporterUserId()), row.reporterDisplayName(),
                        row.reason().name(), row.detail(), row.status().name(),
                        row.resolution() == null ? "" : row.resolution().name(),
                        row.handlerDisplayName(), row.createdAt().toString(),
                        instant(row.handledAt())));
            }
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), "举报查询条件无效");
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage resolve(RequestMessage request) {
        Optional<UserSession> session = manager(request);
        if (session.isEmpty()) return auditedAccessFailure(request, managerFailure(request));
        UserSession actor = session.get();
        try {
            Map<String, String> values = request.parameters();
            MutationResult result = reports.resolve(
                    positiveLong(values.get("reportId")),
                    enumValue(values.get("resolution"), ForumReportResolution.class),
                    bounded(values.get("note"), 2, 255, "处理说明"),
                    actor.userId());
            return switch (result) {
                case CHANGED -> audited(actor, request, "SUCCESS",
                        ResponseMessage.success(request.requestId(), "举报已处理", Map.of()));
                case UNCHANGED -> audited(actor, request, "CONFLICT",
                        ResponseMessage.failure(request.requestId(), "举报已被其他管理员处理"));
                case CONFLICT -> audited(actor, request, "CONFLICT",
                        ResponseMessage.failure(request.requestId(), "内容状态已变化，请刷新后重试"));
                case NOT_FOUND -> audited(actor, request, "NOT_FOUND",
                        ResponseMessage.failure(request.requestId(), "举报不存在"));
                case FORBIDDEN -> audited(actor, request, "DENIED",
                        ResponseMessage.failure(request.requestId(), "无权处理举报"));
            };
        } catch (IllegalArgumentException exception) {
            return audited(actor, request, "INVALID",
                    ResponseMessage.failure(request.requestId(), exception.getMessage()));
        } catch (SQLException exception) {
            return audited(actor, request, "DATABASE_ERROR", databaseFailure(request, exception));
        }
    }

    private ResponseMessage auditedAccessFailure(RequestMessage request, ResponseMessage response) {
        return sessions.find(request.parameters().get("sessionToken"))
                .map(actor -> audited(actor, request, "DENIED", response))
                .orElse(response);
    }

    private ResponseMessage audited(UserSession actor, RequestMessage request,
                                    String result, ResponseMessage response) {
        audit.record(actor.userId(), request.action(), result, null);
        return response;
    }

    private Optional<UserSession> user(RequestMessage request) {
        return sessions.find(request.parameters().get("sessionToken"))
                .filter(value -> ForumAccessPolicy.canUse(value.roles()));
    }

    private Optional<UserSession> manager(RequestMessage request) {
        return sessions.find(request.parameters().get("sessionToken"))
                .filter(value -> ForumAccessPolicy.canManage(value.roles()));
    }

    private ResponseMessage managerFailure(RequestMessage request) {
        return sessions.find(request.parameters().get("sessionToken")).isEmpty()
                ? expired(request)
                : ResponseMessage.failure(request.requestId(), "无权管理论坛内容");
    }

    private Map<String, String> pageData(int page, int pageSize, int total, int count) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("page", Integer.toString(page));
        data.put("pageSize", Integer.toString(pageSize));
        data.put("total", Integer.toString(total));
        data.put("count", Integer.toString(count));
        return data;
    }

    private String bounded(String value, int min, int max, String label) {
        String normalized = text(value);
        if (normalized.length() < min || normalized.length() > max) {
            throw new IllegalArgumentException(label + "长度应为 " + min + " 至 " + max + " 个字符");
        }
        return normalized;
    }

    private String boundedOptional(String value, int max, String label) {
        String normalized = text(value);
        if (normalized.length() > max) {
            throw new IllegalArgumentException(label + "不能超过 " + max + " 个字符");
        }
        return normalized;
    }

    private String text(String value) {
        return value == null ? "" : value.strip();
    }

    private long positiveLong(String value) {
        long parsed = Long.parseLong(value == null ? "" : value);
        if (parsed < 1) throw new IllegalArgumentException("编号无效");
        return parsed;
    }

    private int positiveInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        int parsed = Integer.parseInt(value);
        if (parsed < 1) throw new IllegalArgumentException("页码无效");
        return parsed;
    }

    private <E extends Enum<E>> E enumValue(String value, Class<E> type) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("枚举值不能为空");
        return Enum.valueOf(type, value.strip());
    }

    private <E extends Enum<E>> E optionalEnum(String value, Class<E> type) {
        return value == null || value.isBlank() ? null : Enum.valueOf(type, value.strip());
    }

    private String instant(Instant value) {
        return value == null ? "" : value.toString();
    }

    private ResponseMessage expired(RequestMessage request) {
        return ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录");
    }

    private ResponseMessage databaseFailure(RequestMessage request, SQLException exception) {
        System.err.println("Forum report database operation failed: " + exception.getMessage());
        return ResponseMessage.failure(request.requestId(), "数据库操作失败，请稍后重试");
    }
}
