package com.vcampus.server.image;

import java.io.InputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

public interface ShopImageStore {
    UploadTicket startUpload(long ownerId, long productId, String mimeType, long expectedBytes);

    void appendChunk(long ownerId, String uploadId, int index, byte[] bytes);

    UploadedImage completeUpload(long ownerId, String uploadId);

    FinalizedImage finalizeUpload(long ownerId, String uploadId);

    InputStream open(String storageKey);

    boolean deleteIfExists(String storageKey);

    void cleanup(Set<String> referencedKeys, Instant now);

    record UploadTicket(String uploadId, long productId, long expectedBytes,
                        int chunkBytes, Instant expiresAt) {
        public UploadTicket {
            Objects.requireNonNull(uploadId, "uploadId");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    record UploadedImage(String uploadId, long productId, String mimeType,
                         byte[] normalizedBytes, String sha256, int width, int height) {
        public UploadedImage {
            Objects.requireNonNull(uploadId, "uploadId");
            Objects.requireNonNull(mimeType, "mimeType");
            normalizedBytes = Arrays.copyOf(Objects.requireNonNull(normalizedBytes, "normalizedBytes"),
                    normalizedBytes.length);
            Objects.requireNonNull(sha256, "sha256");
        }

        @Override
        public byte[] normalizedBytes() {
            return Arrays.copyOf(normalizedBytes, normalizedBytes.length);
        }
    }

    record FinalizedImage(String storageKey, String thumbnailStorageKey) {
        public FinalizedImage {
            Objects.requireNonNull(storageKey, "storageKey");
            Objects.requireNonNull(thumbnailStorageKey, "thumbnailStorageKey");
        }
    }
}
