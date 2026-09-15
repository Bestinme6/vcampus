package com.vcampus.server.model;

import com.vcampus.common.model.ShopReviewStatus;
import java.time.Instant;
import java.util.Objects;

public record ShopReviewRecord(long id, long orderItemId, long productId,
                               long authorUserId, String authorDisplayName,
                               int rating, String content, boolean repeatPurchase,
                               ShopReviewStatus status, int likeCount,
                               boolean likedByViewer, Instant createdAt, Instant updatedAt) {
    public ShopReviewRecord {
        if (id < 1 || orderItemId < 1 || productId < 1 || authorUserId < 1) {
            throw new IllegalArgumentException("评价标识无效");
        }
        if (rating < 1 || rating > 5 || likeCount < 0) {
            throw new IllegalArgumentException("评价数据无效");
        }
        authorDisplayName = Objects.requireNonNull(authorDisplayName, "authorDisplayName");
        content = Objects.requireNonNull(content, "content");
        status = Objects.requireNonNull(status, "status");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
