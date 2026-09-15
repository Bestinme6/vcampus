package com.vcampus.server.database;

import com.vcampus.server.model.ShopReviewRecord;
import com.vcampus.server.model.ShopReviewImageRecord;
import com.vcampus.common.model.ShopReviewStatus;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public interface ShopReviewStore {
    ReviewMutationResult create(long authorUserId, CreateReview command) throws SQLException;

    ReviewMutationResult update(long authorUserId, UpdateReview command) throws SQLException;

    boolean delete(long authorUserId, long reviewId) throws SQLException;

    java.util.Map<Long, Long> reviewIdsByOrderItems(Set<Long> orderItemIds) throws SQLException;

    ReviewSummary summary(long productId) throws SQLException;

    Map<Long, ReviewSummary> summaries(Set<Long> productIds) throws SQLException;

    ReviewPage list(ReviewQuery query) throws SQLException;

    LikeResult setLike(long actorUserId, long reviewId, boolean enabled) throws SQLException;

    AdminReviewPage searchAdmin(AdminReviewQuery query) throws SQLException;

    boolean hide(long operatorUserId, long reviewId, String reason) throws SQLException;

    boolean restore(long operatorUserId, long reviewId, String reason) throws SQLException;

    record CreateReview(String operationId, long orderItemId, int rating, String content,
                        List<PendingImage> images) {
        public CreateReview {
            operationId = required(operationId, "操作编号无效");
            if (operationId.length() > 64 || orderItemId < 1) {
                throw new IllegalArgumentException("评价请求无效");
            }
            validateReview(rating, content);
            content = content.trim();
            images = List.copyOf(Objects.requireNonNull(images, "images"));
            if (images.size() > 3) throw new IllegalArgumentException("评价最多上传3张图片");
        }
    }

    record UpdateReview(long reviewId, int rating, String content, ImagePlan imagePlan) {
        public UpdateReview {
            if (reviewId < 1) throw new IllegalArgumentException("评价ID无效");
            validateReview(rating, content);
            content = content.trim();
            imagePlan = Objects.requireNonNull(imagePlan, "imagePlan");
        }
    }

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

    record ImagePlan(List<ImagePlanItem> items) {
        public ImagePlan {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            if (items.size() > 3) throw new IllegalArgumentException("评价最多保留3张图片");
        }
    }

    record ImagePlanItem(Long existingImageId, PendingImage upload) {
        public ImagePlanItem {
            if ((existingImageId == null) == (upload == null)
                    || (existingImageId != null && existingImageId < 1)) {
                throw new IllegalArgumentException("图片计划项无效");
            }
        }
    }

    record ReviewMutationResult(ShopReviewRecord review, boolean duplicate,
                                Set<String> keptKeys, Set<String> deletedKeys) {
        public ReviewMutationResult {
            review = Objects.requireNonNull(review, "review");
            keptKeys = Set.copyOf(Objects.requireNonNull(keptKeys, "keptKeys"));
            deletedKeys = Set.copyOf(Objects.requireNonNull(deletedKeys, "deletedKeys"));
        }
    }

    record ReviewSummary(BigDecimal average, int total, List<Integer> starCounts) {
        public ReviewSummary {
            if (total < 0) throw new IllegalArgumentException("评价数量无效");
            starCounts = List.copyOf(Objects.requireNonNull(starCounts, "starCounts"));
            if (starCounts.size() != 5 || starCounts.stream().anyMatch(value -> value == null || value < 0)) {
                throw new IllegalArgumentException("星级分布无效");
            }
            if ((total == 0) != (average == null)) throw new IllegalArgumentException("平均分无效");
            if (average != null) average = average.setScale(1);
        }
    }

    record ReviewQuery(long productId, long viewerUserId, int page, int pageSize) {
        public ReviewQuery {
            if (productId < 1 || viewerUserId < 1 || page < 1 || pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("评价分页参数无效");
            }
        }
    }

    record ReviewPage(List<ShopReviewRecord> rows,
                      Map<Long, List<ShopReviewImageRecord>> images,
                      int page, int pageSize, int total) {
        public ReviewPage {
            rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
            images = immutableImages(images);
        }
    }

    record LikeResult(long reviewId, boolean enabled, boolean changed, int likeCount) {
        public LikeResult {
            if (reviewId < 1 || likeCount < 0) throw new IllegalArgumentException("点赞结果无效");
        }
    }

    record AdminReviewQuery(String keyword, ShopReviewStatus status, int page, int pageSize) {
        public AdminReviewQuery {
            keyword = keyword == null ? "" : keyword.trim();
            if (page < 1 || pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("评价管理分页参数无效");
            }
        }
    }

    record AdminReviewPage(List<ShopReviewRecord> rows,
                           Map<Long, List<ShopReviewImageRecord>> images,
                           int page, int pageSize, int total) {
        public AdminReviewPage {
            rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
            images = immutableImages(images);
        }
    }

    private static void validateReview(int rating, String content) {
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("评分必须为1到5星");
        String value = required(content, "评价内容不能为空");
        int length = value.length();
        if (length < 5 || length > 1000) throw new IllegalArgumentException("评价内容需为5到1000字");
    }

    private static String required(String value, String message) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private static Map<Long, List<ShopReviewImageRecord>> immutableImages(
            Map<Long, List<ShopReviewImageRecord>> source) {
        Objects.requireNonNull(source, "images");
        var copy = new java.util.LinkedHashMap<Long, List<ShopReviewImageRecord>>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }
}
