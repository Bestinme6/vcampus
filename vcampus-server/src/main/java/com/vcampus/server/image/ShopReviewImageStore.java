package com.vcampus.server.image;

import java.time.Instant;
import java.util.Set;

public interface ShopReviewImageStore {
    ShopImageStore.UploadTicket start(long ownerId, String mimeType, long bytes);

    void append(long ownerId, String uploadId, int index, byte[] bytes);

    Completed complete(long ownerId, String uploadId);

    ShopImageStore.ImageDescriptor describe(String storageKey);

    ShopImageStore.ImageRange readRange(String storageKey, long offset, int maxBytes);

    void cleanup(Set<String> referencedKeys, Instant now);

    record Completed(String uploadId, String storageKey, String thumbnailStorageKey,
                     String mimeType, long byteSize, String sha256, int width, int height) {
    }
}
