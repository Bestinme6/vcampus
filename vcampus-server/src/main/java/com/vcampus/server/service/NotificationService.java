package com.vcampus.server.service;

import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.NotificationStore;
import com.vcampus.server.database.NotificationStore.NotificationPage;
import com.vcampus.server.database.NotificationStore.NotificationQuery;
import com.vcampus.server.model.NotificationRecord;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;

import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class NotificationService {
    private static final int PAGE_SIZE = 10;
    private static final int SUMMARY_LENGTH = 80;

    private final NotificationStore notifications;
    private final SessionManager sessions;

    public NotificationService(NotificationStore notifications, SessionManager sessions) {
        this.notifications = notifications;
        this.sessions = sessions;
    }

    public ResponseMessage search(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        try {
            Map<String, String> values = request.parameters();
            NotificationSource source = optionalSource(values.get("source"));
            Boolean read = optionalBoolean(values.get("read"));
            int page = positiveInteger(values.get("page"), 1);
            NotificationPage result = notifications.search(
                    session.get().userId(),
                    new NotificationQuery(values.get("keyword"), source, read, page, PAGE_SIZE));
            Map<String, String> data = new LinkedHashMap<>();
            data.put("page", Integer.toString(result.page()));
            data.put("pageSize", Integer.toString(result.pageSize()));
            data.put("total", Integer.toString(result.total()));
            data.put("count", Integer.toString(result.rows().size()));
            for (int index = 0; index < result.rows().size(); index++) {
                data.put("row." + index, encode(result.rows().get(index)));
            }
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), "消息筛选条件无效");
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage get(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        try {
            long notificationId = positiveLong(request.parameters().get("notificationId"));
            Optional<NotificationRecord> found = notifications.findOwned(
                    session.get().userId(), notificationId);
            if (found.isEmpty()) {
                return ResponseMessage.failure(request.requestId(), "消息不存在");
            }
            return ResponseMessage.success(
                    request.requestId(), "查询成功", detail(found.get()));
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), "消息不存在");
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage unreadCount(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        try {
            int count = notifications.unreadCount(session.get().userId());
            return ResponseMessage.success(
                    request.requestId(), "查询成功",
                    Map.of("unreadCount", Integer.toString(count)));
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage markRead(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        try {
            long notificationId = positiveLong(request.parameters().get("notificationId"));
            if (!notifications.markRead(session.get().userId(), notificationId)) {
                return ResponseMessage.failure(request.requestId(), "消息不存在");
            }
            return ResponseMessage.success(request.requestId(), "标记成功", Map.of());
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), "消息不存在");
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage markAllRead(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        try {
            int updated = notifications.markAllRead(session.get().userId());
            return ResponseMessage.success(
                    request.requestId(), "标记成功",
                    Map.of("updated", Integer.toString(updated)));
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    private Optional<UserSession> session(RequestMessage request) {
        return sessions.find(request.parameters().get("sessionToken"));
    }

    private String encode(NotificationRecord record) {
        return RowCodec.encode(
                Long.toString(record.id()),
                record.type().name(),
                record.source().name(),
                record.title(),
                summary(record.content()),
                record.target().name(),
                nullableLong(record.relatedEntityId()),
                Boolean.toString(record.read()),
                record.createdAt().toString());
    }

    private Map<String, String> detail(NotificationRecord record) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("id", Long.toString(record.id()));
        data.put("type", record.type().name());
        data.put("source", record.source().name());
        data.put("title", record.title());
        data.put("content", record.content());
        data.put("target", record.target().name());
        data.put("relatedEntityId", nullableLong(record.relatedEntityId()));
        data.put("isRead", Boolean.toString(record.read()));
        data.put("readAt", nullableInstant(record.readAt()));
        data.put("createdAt", record.createdAt().toString());
        return data;
    }

    private String summary(String content) {
        return content.length() <= SUMMARY_LENGTH
                ? content
                : content.substring(0, SUMMARY_LENGTH) + "…";
    }

    private NotificationSource optionalSource(String value) {
        return value == null || value.isBlank()
                ? null
                : NotificationSource.valueOf(value.trim());
    }

    private Boolean optionalBoolean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if ("true".equalsIgnoreCase(value.trim())) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value.trim())) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Invalid boolean");
    }

    private int positiveInteger(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        int parsed = Integer.parseInt(value);
        if (parsed < 1) {
            throw new IllegalArgumentException("Invalid positive integer");
        }
        return parsed;
    }

    private long positiveLong(String value) {
        long parsed = Long.parseLong(value == null ? "" : value);
        if (parsed < 1) {
            throw new IllegalArgumentException("Invalid positive long");
        }
        return parsed;
    }

    private String nullableLong(Long value) {
        return value == null ? "" : Long.toString(value);
    }

    private String nullableInstant(Instant value) {
        return value == null ? "" : value.toString();
    }

    private ResponseMessage expired(RequestMessage request) {
        return ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录");
    }

    private ResponseMessage databaseFailure(RequestMessage request, SQLException exception) {
        System.err.println("Notification database operation failed: " + exception.getMessage());
        return ResponseMessage.failure(request.requestId(), "数据库操作失败，请稍后重试");
    }
}
