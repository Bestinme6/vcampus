package com.vcampus.server.model;

import java.time.Instant;

public record ShopProductImageRecord(long id, long productId, String storageKey,
                                     String thumbnailStorageKey, String mimeType, long byteSize,
                                     String sha256, int sortOrder, boolean cover,
                                     Instant createdAt, Instant updatedAt) {
}
