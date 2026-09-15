package com.vcampus.server.database;

import com.vcampus.server.model.ShopReviewRecord;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public interface ShopReviewStore {
    ReviewMutationResult create(long authorUserId, CreateReview command) throws SQLException;

    ReviewMutationResult update(long authorUserId, UpdateReview command) throws SQLException;

    boolean delete(long authorUserId, long reviewId) throws SQLException;

    java.util.Map<Long, Long> reviewIdsByOrderItems(Set<Long> orderItemIds) throws SQLException;

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
}
