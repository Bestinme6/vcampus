package com.vcampus.server.database;

import com.vcampus.common.model.NotificationSource;
import com.vcampus.server.model.NotificationRecord;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public interface NotificationStore {
    NotificationPage search(long recipientUserId, NotificationQuery query) throws SQLException;

    Optional<NotificationRecord> findOwned(long recipientUserId, long notificationId)
            throws SQLException;

    int unreadCount(long recipientUserId) throws SQLException;

    boolean markRead(long recipientUserId, long notificationId) throws SQLException;

    int markAllRead(long recipientUserId) throws SQLException;

    record NotificationQuery(
            String keyword,
            NotificationSource source,
            Boolean read,
            int page,
            int pageSize) {
        public NotificationQuery {
            keyword = keyword == null ? "" : keyword.trim();
            if (page < 1) {
                throw new IllegalArgumentException("页码必须大于 0");
            }
            if (pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("每页数量无效");
            }
        }
    }

    record NotificationPage(
            List<NotificationRecord> rows,
            int page,
            int pageSize,
            int total) {
        public NotificationPage {
            rows = List.copyOf(rows);
        }
    }
}
