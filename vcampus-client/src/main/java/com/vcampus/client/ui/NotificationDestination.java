package com.vcampus.client.ui;

import com.vcampus.client.ui.NotificationViewData.NotificationDetail;
import com.vcampus.common.model.NotificationTarget;

import java.util.Objects;

public record NotificationDestination(NotificationTarget target, Long relatedEntityId) {
    public NotificationDestination {
        Objects.requireNonNull(target, "target");
    }

    public static NotificationDestination from(NotificationDetail detail) {
        Objects.requireNonNull(detail, "detail");
        return new NotificationDestination(detail.target(), detail.relatedEntityId());
    }

    public boolean navigable() {
        if (target == NotificationTarget.NONE) {
            return false;
        }
        return switch (target) {
            case FORUM_POST, SHOP_ORDERS, SECONDHAND_ORDERS, LIBRARY_CATALOG -> relatedEntityId != null && relatedEntityId > 0;
            default -> true;
        };
    }

    public long shopOrderId() {
        if (target != NotificationTarget.SHOP_ORDERS || relatedEntityId == null || relatedEntityId <= 0) {
            throw new IllegalArgumentException("消息缺少有效的订单编号");
        }
        return relatedEntityId;
    }
}
