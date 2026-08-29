package com.vcampus.server.database;

import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

public interface NotificationWriter {
    void insert(Connection connection, NotificationDraft draft) throws SQLException;

    void insertBatch(Connection connection, List<NotificationDraft> drafts) throws SQLException;

    record NotificationDraft(
            long recipientUserId,
            Long senderUserId,
            NotificationType type,
            NotificationSource source,
            String title,
            String content,
            NotificationTarget target,
            Long relatedEntityId) {
        public NotificationDraft {
            if (recipientUserId <= 0) {
                throw new IllegalArgumentException("接收用户无效");
            }
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(target, "target");
        }
    }
}
