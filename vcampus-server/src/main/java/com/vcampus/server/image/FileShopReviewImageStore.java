package com.vcampus.server.image;

import com.vcampus.server.config.ShopReviewImageConfig;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public final class FileShopReviewImageStore implements ShopReviewImageStore {
    private static final long REVIEW_UPLOAD_MARKER = 1L;
    private final ShopImageStore delegate;

    public FileShopReviewImageStore(ShopReviewImageConfig config) {
        delegate = new FileShopImageStore(Objects.requireNonNull(config, "config").asStoreConfig());
    }

    @Override
    public ShopImageStore.UploadTicket start(long ownerId, String mimeType, long bytes) {
        return delegate.startUpload(ownerId, REVIEW_UPLOAD_MARKER, mimeType, bytes);
    }

    @Override
    public void append(long ownerId, String uploadId, int index, byte[] bytes) {
        delegate.appendChunk(ownerId, uploadId, index, bytes);
    }

    @Override
    public Completed complete(long ownerId, String uploadId) {
        var uploaded = delegate.completeUpload(ownerId, uploadId);
        var stored = delegate.finalizeUpload(ownerId, uploadId);
        return new Completed(uploadId, stored.storageKey(), stored.thumbnailStorageKey(),
                uploaded.mimeType(), uploaded.normalizedBytes().length, uploaded.sha256(),
                uploaded.width(), uploaded.height());
    }

    @Override
    public ShopImageStore.ImageDescriptor describe(String storageKey) {
        return delegate.describe(storageKey);
    }

    @Override
    public ShopImageStore.ImageRange readRange(String storageKey, long offset, int maxBytes) {
        return delegate.readRange(storageKey, offset, maxBytes);
    }

    @Override
    public boolean deleteIfExists(String storageKey) {
        return delegate.deleteIfExists(storageKey);
    }

    @Override
    public void cleanup(Set<String> referencedKeys, Instant now) {
        delegate.cleanup(referencedKeys, now);
    }
}
