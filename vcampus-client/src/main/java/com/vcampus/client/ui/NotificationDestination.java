package com.vcampus.client.ui;

import com.vcampus.client.ui.NotificationViewData.NotificationDetail;
import com.vcampus.common.model.NotificationTarget;

import java.util.Objects;

record NotificationDestination(NotificationTarget target, Long relatedEntityId) {
    NotificationDestination {
        Objects.requireNonNull(target, "target");
    }

    static NotificationDestination from(NotificationDetail detail) {
        Objects.requireNonNull(detail, "detail");
        return new NotificationDestination(detail.target(), detail.relatedEntityId());
    }

    boolean navigable() {
        if (target == NotificationTarget.NONE) {
            return false;
        }
        return switch (target) {
            case FORUM_POST, SHOP_ORDERS -> relatedEntityId != null && relatedEntityId > 0;
            default -> true;
        };
    }
}
