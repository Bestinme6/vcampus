package com.vcampus.server.model;

import java.time.Instant;
import java.util.Objects;

public record ShopReviewImageRecord(long id, long reviewId, int position,
                                    String storageKey, String thumbnailStorageKey,
                                    String mimeType, long byteSize, String sha256,
                                    int width, int height, Instant createdAt) {
    public ShopReviewImageRecord {
        if (id < 1 || reviewId < 1 || position < 0 || position > 2
                || byteSize < 1 || width < 1 || height < 1) {
            throw new IllegalArgumentException("评价图片数据无效");
        }
        storageKey = Objects.requireNonNull(storageKey, "storageKey");
        thumbnailStorageKey = Objects.requireNonNull(thumbnailStorageKey, "thumbnailStorageKey");
        mimeType = Objects.requireNonNull(mimeType, "mimeType");
        sha256 = Objects.requireNonNull(sha256, "sha256");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}
