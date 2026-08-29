package com.vcampus.server.model;

import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;

import java.time.Instant;

public record NotificationRecord(
        long id,
        long recipientUserId,
        Long senderUserId,
        NotificationType type,
        NotificationSource source,
        String title,
        String content,
        NotificationTarget target,
        Long relatedEntityId,
        boolean read,
        Instant readAt,
        Instant createdAt) {
}
