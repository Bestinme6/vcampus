package com.vcampus.client.ui;

import com.vcampus.common.model.NotificationTarget;

final class NotificationNavigationPolicy {
    private NotificationNavigationPolicy() {
    }

    static long forumPostId(NotificationDestination destination) {
        if (destination.target() != NotificationTarget.FORUM_POST
                || destination.relatedEntityId() == null
                || destination.relatedEntityId() <= 0) {
            throw new IllegalArgumentException("论坛通知缺少帖子编号");
        }
        return destination.relatedEntityId();
    }
}
