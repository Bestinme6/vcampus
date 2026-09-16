package com.vcampus.server.database;

import com.vcampus.common.model.ShopReviewStatus;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public interface SecondhandReviewStore {
    Optional<MutationResult> findCreated(long buyerId, String operationId, long orderId)
            throws SQLException;

    MutationResult create(long buyerId, String operationId, long orderId, int rating,
                          String content, List<PendingImage> images) throws SQLException;

    MutationResult update(long actorId, long reviewId, int rating, String content)
            throws SQLException;

    boolean delete(long actorId, long reviewId) throws SQLException;

    LikeResult setLike(long actorId, long reviewId, boolean enabled) throws SQLException;

    ReviewPage listSeller(long sellerId, long viewerId, int page, int pageSize)
            throws SQLException;

    ReviewPage searchAdmin(String keyword, ShopReviewStatus status, int page, int pageSize)
            throws SQLException;

    Summary summary(long sellerId) throws SQLException;

    boolean moderate(long adminId, long reviewId, boolean hidden, String reason)
            throws SQLException;

    record PendingImage(String storageKey, String thumbnailStorageKey, String mimeType,
                        long byteSize, String sha256, int width, int height) {
        public PendingImage {
            storageKey = required(storageKey, "图片存储键无效");
            thumbnailStorageKey = required(thumbnailStorageKey, "缩略图存储键无效");
            mimeType = required(mimeType, "图片类型无效").toLowerCase(java.util.Locale.ROOT);
            sha256 = required(sha256, "图片摘要无效").toLowerCase(java.util.Locale.ROOT);
            if (!Set.of("image/jpeg", "image/png", "image/webp").contains(mimeType)
                    || byteSize < 1 || byteSize > 5L * 1024 * 1024
                    || sha256.length() != 64 || width < 1 || height < 1
                    || (long) width * height > 20_000_000L) {
                throw new IllegalArgumentException("评价图片数据无效");
            }
        }
    }

    record Review(long id, long orderId, long itemId, long sellerId, long authorId,
                  String authorName, int rating, String content, ShopReviewStatus status,
                  int likeCount, boolean likedByViewer, Instant createdAt, Instant updatedAt) {
        public Review {
            authorName = Objects.requireNonNull(authorName, "authorName");
            content = Objects.requireNonNull(content, "content");
            status = Objects.requireNonNull(status, "status");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    record ReviewImage(long id, long reviewId, int position, String mimeType,
                       long byteSize, String sha256, int width, int height) { }

    record MutationResult(Review review, boolean duplicate, Set<String> keptKeys) {
        public MutationResult {
            review = Objects.requireNonNull(review, "review");
            keptKeys = Set.copyOf(Objects.requireNonNull(keptKeys, "keptKeys"));
        }
    }

    record LikeResult(long reviewId, boolean enabled, boolean changed, int likeCount) { }

    record Summary(BigDecimal average, int count, List<Integer> starCounts) {
        public Summary {
            starCounts = List.copyOf(Objects.requireNonNull(starCounts, "starCounts"));
            if (average != null) average = average.setScale(1);
        }
    }

    record ReviewPage(List<Review> rows, java.util.Map<Long, List<ReviewImage>> images,
                      int page, int pageSize, int total) {
        public ReviewPage {
            rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
            var copy = new java.util.LinkedHashMap<Long, List<ReviewImage>>();
            Objects.requireNonNull(images, "images").forEach(
                    (key, value) -> copy.put(key, List.copyOf(value)));
            images = java.util.Map.copyOf(copy);
        }
    }

    static String reviewContent(String content) {
        String normalized = required(content, "评价内容无效");
        if (normalized.length() < 5 || normalized.length() > 1000
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("评价内容无效");
        }
        return normalized;
    }

    static void rating(int rating) {
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("评价评分无效");
    }

    private static String required(String value, String message) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(message);
        return normalized;
    }
}
