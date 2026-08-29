package com.vcampus.client.ui;

import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class NotificationViewData {
    private NotificationViewData() {
    }

    static int unreadCount(ResponseMessage response) {
        requireSuccess(response);
        try {
            return nonNegativeInteger(response.data(), "unreadCount");
        } catch (RuntimeException exception) {
            throw malformed(exception);
        }
    }

    record NotificationRow(
            long id,
            NotificationType type,
            NotificationSource source,
            String title,
            String summary,
            NotificationTarget target,
            Long relatedEntityId,
            boolean read,
            Instant createdAt) {
    }

    record NotificationPage(List<NotificationRow> rows, int page, int pageSize, int total) {
        NotificationPage {
            rows = List.copyOf(rows);
        }

        static NotificationPage parse(ResponseMessage response) {
            requireSuccess(response);
            try {
                Map<String, String> data = response.data();
                int page = positiveInteger(data, "page");
                int pageSize = positiveInteger(data, "pageSize");
                int total = nonNegativeInteger(data, "total");
                int count = nonNegativeInteger(data, "count");
                if (count > pageSize || count > total) {
                    throw new IllegalArgumentException("Invalid message count");
                }
                List<NotificationRow> rows = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    List<String> fields = RowCodec.decode(required(data, "row." + index));
                    if (fields.size() != 9) {
                        throw new IllegalArgumentException("Invalid message row size");
                    }
                    rows.add(new NotificationRow(
                            positiveLong(fields.get(0)),
                            NotificationType.valueOf(fields.get(1)),
                            NotificationSource.valueOf(fields.get(2)),
                            fields.get(3),
                            fields.get(4),
                            NotificationTarget.valueOf(fields.get(5)),
                            nullablePositiveLong(fields.get(6)),
                            strictBoolean(fields.get(7)),
                            Instant.parse(fields.get(8))));
                }
                return new NotificationPage(rows, page, pageSize, total);
            } catch (RuntimeException exception) {
                throw malformed(exception);
            }
        }
    }

    record NotificationDetail(
            long id,
            NotificationType type,
            NotificationSource source,
            String title,
            String content,
            NotificationTarget target,
            Long relatedEntityId,
            boolean read,
            Instant readAt,
            Instant createdAt) {

        static NotificationDetail parse(ResponseMessage response) {
            requireSuccess(response);
            try {
                Map<String, String> data = response.data();
                return new NotificationDetail(
                        positiveLong(required(data, "id")),
                        NotificationType.valueOf(required(data, "type")),
                        NotificationSource.valueOf(required(data, "source")),
                        required(data, "title"),
                        required(data, "content"),
                        NotificationTarget.valueOf(required(data, "target")),
                        nullablePositiveLong(required(data, "relatedEntityId")),
                        strictBoolean(required(data, "isRead")),
                        nullableInstant(required(data, "readAt")),
                        Instant.parse(required(data, "createdAt")));
            } catch (RuntimeException exception) {
                throw malformed(exception);
            }
        }
    }

    private static void requireSuccess(ResponseMessage response) {
        if (!response.success()) {
            throw new IllegalArgumentException(response.message());
        }
    }

    private static IllegalArgumentException malformed(RuntimeException cause) {
        return new IllegalArgumentException("服务器返回的消息数据格式不正确", cause);
    }

    private static int positiveInteger(Map<String, String> data, String key) {
        int value = Integer.parseInt(required(data, key));
        if (value < 1) {
            throw new IllegalArgumentException("Invalid positive integer");
        }
        return value;
    }

    private static int nonNegativeInteger(Map<String, String> data, String key) {
        int value = Integer.parseInt(required(data, key));
        if (value < 0) {
            throw new IllegalArgumentException("Invalid non-negative integer");
        }
        return value;
    }

    private static long positiveLong(String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 1) {
            throw new IllegalArgumentException("Invalid positive long");
        }
        return parsed;
    }

    private static Long nullablePositiveLong(String value) {
        return value.isBlank() ? null : positiveLong(value);
    }

    private static Instant nullableInstant(String value) {
        return value.isBlank() ? null : Instant.parse(value);
    }

    private static boolean strictBoolean(String value) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Invalid boolean");
        }
        return Boolean.parseBoolean(value);
    }

    private static String required(Map<String, String> data, String key) {
        String value = data.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        return value;
    }
}
