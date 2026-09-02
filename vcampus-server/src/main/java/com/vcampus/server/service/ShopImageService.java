package com.vcampus.server.service;

import com.vcampus.common.model.ShopAccessPolicy;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.ShopRuleException;
import com.vcampus.server.database.ShopStore;
import com.vcampus.server.database.ShopStore.FinalizedUpload;
import com.vcampus.server.database.ShopStore.ImageCommitResult;
import com.vcampus.server.database.ShopStore.ImagePlan;
import com.vcampus.server.database.ShopStore.ImagePlanItem;
import com.vcampus.server.image.ShopImageException;
import com.vcampus.server.image.ShopImageStore;
import com.vcampus.server.image.ShopImageStore.FinalizedImage;
import com.vcampus.server.image.ShopImageStore.UploadedImage;
import com.vcampus.server.model.ShopProductImageRecord;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ShopImageService {
    private static final int MAX_IMAGES = 5;
    // FileShopImageStore.completeUpload releases its session for every code in this set.
    private static final Set<String> TERMINAL_COMPLETION_FAILURE_CODES = Set.of(
            "INVALID_IMAGE", "MIME_MISMATCH", "UNSUPPORTED_IMAGE", "PIXEL_LIMIT",
            "UPLOAD_EXPIRED", "UPLOAD_NOT_FOUND", "SIZE_MISMATCH", "STORAGE_BOUNDARY",
            "PROCESSING_INTERRUPTED", "STORAGE_ERROR");

    private final ShopStore shop;
    private final ShopImageStore images;
    private final SessionManager sessions;
    private final ConcurrentMap<String, StartedUpload> startedUploads =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletedUpload> completedUploads =
            new ConcurrentHashMap<>();

    public ShopImageService(ShopStore shop, ShopImageStore images, SessionManager sessions) {
        this.shop = java.util.Objects.requireNonNull(shop, "shop");
        this.images = java.util.Objects.requireNonNull(images, "images");
        this.sessions = java.util.Objects.requireNonNull(sessions, "sessions");
    }

    public ResponseMessage uploadStart(RequestMessage request) {
        return handle(request, session -> {
            long productId = positiveLong(request.parameters().get("productId"), "商品ID");
            long expectedBytes = positiveLong(
                    request.parameters().get("expectedBytes"), "图片大小");
            String mimeType = required(request, "mimeType", "图片类型");
            var ticket = images.startUpload(
                    session.userId(), productId, mimeType, expectedBytes);
            startedUploads.put(ticket.uploadId(), new StartedUpload(
                    session.userId(), ticket.productId(), ticket.expiresAt()));
            return success(request, "图片上传已开始", Map.of(
                    "uploadId", ticket.uploadId(),
                    "productId", Long.toString(ticket.productId()),
                    "expectedBytes", Long.toString(ticket.expectedBytes()),
                    "chunkBytes", Integer.toString(ticket.chunkBytes()),
                    "expiresAt", ticket.expiresAt().toString()));
        });
    }

    public ResponseMessage uploadChunk(RequestMessage request) {
        return handle(request, session -> {
            String uploadId = required(request, "uploadId", "上传ID");
            int chunkIndex = nonNegativeInteger(
                    request.parameters().get("chunkIndex"), "图片分块序号");
            byte[] content;
            try {
                String encoded = request.parameters().get("contentBase64");
                if (encoded == null || encoded.isEmpty()) {
                    throw new IllegalArgumentException();
                }
                content = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("图片分块编码无效");
            }
            images.appendChunk(session.userId(), uploadId, chunkIndex, content);
            return success(request, "图片分块已接收", Map.of(
                    "uploadId", uploadId, "chunkIndex", Integer.toString(chunkIndex)));
        });
    }

    public ResponseMessage uploadComplete(RequestMessage request) {
        return handle(request, session -> {
            String uploadId = required(request, "uploadId", "上传ID");
            CompletedUpload completed = completedUploads.get(uploadId);
            if (completed != null) {
                if (completed.ownerId() != session.userId()) {
                    throw new IllegalArgumentException("上传尚未开始或已过期");
                }
                return completeSuccess(request, completed.uploaded());
            }
            StartedUpload started = startedUploads.get(uploadId);
            UploadedImage uploaded;
            try {
                uploaded = images.completeUpload(session.userId(), uploadId);
            } catch (ShopImageException exception) {
                if (started != null && TERMINAL_COMPLETION_FAILURE_CODES.contains(exception.code())) {
                    startedUploads.remove(uploadId, started);
                }
                throw exception;
            }
            if (started == null || started.ownerId() != session.userId()
                    || started.productId() != uploaded.productId()) {
                throw new IllegalArgumentException("上传尚未开始或已过期");
            }
            startedUploads.remove(uploadId, started);
            completedUploads.put(uploadId, new CompletedUpload(session.userId(), uploaded.productId(),
                    started.expiresAt(), uploaded));
            return completeSuccess(request, uploaded);
        });
    }

    public ResponseMessage commit(RequestMessage request) {
        return handle(request, session -> {
            long productId = positiveLong(request.parameters().get("productId"), "商品ID");
            ImagePlan plan = parsePlan(request);
            List<PlannedUpload> plannedUploads = resolveUploads(session, productId, plan);
            Map<String, FinalizedUpload> finalized = new LinkedHashMap<>();
            Set<String> newKeys = new LinkedHashSet<>();
            boolean metadataCommitted = false;
            try {
                for (PlannedUpload planned : plannedUploads) {
                    FinalizedImage files = images.finalizeUpload(
                            session.userId(), planned.uploadId());
                    UploadedImage uploaded = planned.completed().uploaded();
                    FinalizedUpload value = new FinalizedUpload(
                            planned.uploadId(), uploaded.productId(), files.storageKey(),
                            files.thumbnailStorageKey(), uploaded.mimeType(),
                            uploaded.normalizedBytes().length, uploaded.sha256());
                    finalized.put(planned.uploadId(), value);
                    newKeys.add(files.storageKey());
                    newKeys.add(files.thumbnailStorageKey());
                }
                ImageCommitResult result = shop.replaceProductImages(
                        session.userId(), productId, finalized, plan);
                metadataCommitted = true;
                deleteKeys(result.deletedKeys());
                Map<String, String> data = imageData(result.images());
                return success(request, "商品图片已更新", data);
            } catch (SQLException | RuntimeException exception) {
                if (!metadataCommitted) deleteKeys(newKeys);
                throw exception;
            } finally {
                for (PlannedUpload planned : plannedUploads) {
                    completedUploads.remove(planned.uploadId(), planned.completed());
                }
            }
        });
    }

    public void cleanup(Instant now) {
        java.util.Objects.requireNonNull(now, "now");
        startedUploads.entrySet().removeIf(entry -> expired(entry.getValue().expiresAt(), now));
        completedUploads.entrySet().removeIf(entry -> expired(entry.getValue().expiresAt(), now));
        Set<String> referencedKeys;
        try {
            referencedKeys = shop.productImageStorageKeys();
        } catch (SQLException | RuntimeException exception) {
            System.err.println("Shop image referenced-key cleanup query failed: "
                    + exception.getMessage());
            return;
        }
        try {
            images.cleanup(referencedKeys, now);
        } catch (RuntimeException exception) {
            System.err.println("Shop image file cleanup failed: " + exception.getMessage());
        }
    }

    public ScheduledExecutorService startCleanupScheduler(Duration interval) {
        java.util.Objects.requireNonNull(interval, "interval");
        long intervalMillis = interval.toMillis();
        if (interval.isNegative() || interval.isZero() || intervalMillis < 1) {
            throw new IllegalArgumentException("cleanup interval must be positive");
        }
        cleanup(Instant.now());
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "vcampus-shop-image-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                () -> cleanup(Instant.now()), intervalMillis, intervalMillis,
                TimeUnit.MILLISECONDS);
        return executor;
    }

    private boolean expired(Instant expiresAt, Instant now) {
        return !expiresAt.isAfter(now);
    }

    private ResponseMessage completeSuccess(RequestMessage request, UploadedImage uploaded) {
        return success(request, "图片上传已完成", Map.of(
                "uploadId", uploaded.uploadId(),
                "productId", Long.toString(uploaded.productId()),
                "mimeType", uploaded.mimeType(),
                "byteSize", Integer.toString(uploaded.normalizedBytes().length),
                "sha256", uploaded.sha256(),
                "width", Integer.toString(uploaded.width()),
                "height", Integer.toString(uploaded.height())));
    }

    private ImagePlan parsePlan(RequestMessage request) {
        int count = nonNegativeInteger(request.parameters().get("itemCount"), "图片数量");
        if (count > MAX_IMAGES) throw new IllegalArgumentException("每件商品最多五张图片");
        List<ImagePlanItem> items = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String encoded = request.parameters().get("item." + index);
            if (encoded == null) throw new IllegalArgumentException("图片计划不完整");
            List<String> fields;
            try {
                fields = RowCodec.decode(encoded);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("图片计划格式无效");
            }
            if (fields.size() != 3) throw new IllegalArgumentException("图片计划格式无效");
            Long existingId = optionalPositiveLong(fields.get(0), "已有图片ID");
            String uploadId = fields.get(1).trim();
            if ((existingId == null) == uploadId.isEmpty()) {
                throw new IllegalArgumentException("图片计划来源无效");
            }
            boolean cover = strictBoolean(fields.get(2), "封面状态");
            items.add(new ImagePlanItem(existingId, uploadId.isEmpty() ? null : uploadId, cover));
        }
        long coverCount = items.stream().filter(ImagePlanItem::cover).count();
        if (!items.isEmpty() && coverCount != 1) {
            throw new IllegalArgumentException("商品图片必须且只能设置一张封面");
        }
        Set<Long> existingIds = new LinkedHashSet<>();
        Set<String> uploadIds = new LinkedHashSet<>();
        for (ImagePlanItem item : items) {
            if (item.existingImageId() != null && !existingIds.add(item.existingImageId())) {
                throw new IllegalArgumentException("图片计划包含重复图片");
            }
            if (item.uploadId() != null && !uploadIds.add(item.uploadId())) {
                throw new IllegalArgumentException("图片计划包含重复上传");
            }
        }
        return new ImagePlan(items);
    }

    private List<PlannedUpload> resolveUploads(UserSession session, long productId, ImagePlan plan) {
        List<PlannedUpload> uploads = new ArrayList<>();
        for (ImagePlanItem item : plan.items()) {
            if (item.uploadId() == null) continue;
            CompletedUpload completed = completedUploads.get(item.uploadId());
            if (completed == null || completed.ownerId() != session.userId()) {
                throw new IllegalArgumentException("上传尚未完成或已过期");
            }
            if (completed.productId() != productId) {
                throw new IllegalArgumentException("上传不属于当前商品");
            }
            uploads.add(new PlannedUpload(item.uploadId(), completed));
        }
        return uploads;
    }

    private Map<String, String> imageData(List<ShopProductImageRecord> imageRows) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("count", Integer.toString(imageRows.size()));
        for (int index = 0; index < imageRows.size(); index++) {
            ShopProductImageRecord image = imageRows.get(index);
            data.put("image." + index, RowCodec.encode(
                    Long.toString(image.id()), image.mimeType(), Long.toString(image.byteSize()),
                    image.sha256(), Integer.toString(image.sortOrder()),
                    Boolean.toString(image.cover())));
        }
        return data;
    }

    private void deleteKeys(Iterable<String> keys) {
        for (String key : keys) {
            try {
                images.deleteIfExists(key);
            } catch (RuntimeException ignored) {
                // Retention cleanup handles a deletion that could not complete immediately.
            }
        }
    }

    private ResponseMessage handle(RequestMessage request, Work work) {
        Optional<UserSession> session = sessions.find(request.parameters().get("sessionToken"));
        if (session.isEmpty()) {
            return ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录");
        }
        if (!ShopAccessPolicy.canManage(session.get().roles())) {
            return ResponseMessage.failure(request.requestId(), "无权执行商店图片管理操作");
        }
        try {
            return work.run(session.get());
        } catch (ShopImageException | ShopRuleException | IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            System.err.println("Shop image database operation failed: " + exception.getMessage());
            return ResponseMessage.failure(request.requestId(), "数据库操作失败，请稍后重试");
        }
    }

    private ResponseMessage success(RequestMessage request, String message,
                                    Map<String, String> data) {
        return ResponseMessage.success(request.requestId(), message, data);
    }

    private String required(RequestMessage request, String key, String label) {
        String value = request.parameters().get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "无效");
        return value.trim();
    }

    private long positiveLong(String value, String label) {
        try {
            long parsed = Long.parseLong(value == null ? "" : value.trim());
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + "无效");
        }
    }

    private Long optionalPositiveLong(String value, String label) {
        if (value == null || value.isBlank()) return null;
        return positiveLong(value, label);
    }

    private int nonNegativeInteger(String value, String label) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + "无效");
        }
    }

    private boolean strictBoolean(String value, String label) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(label + "无效");
    }

    @FunctionalInterface
    private interface Work {
        ResponseMessage run(UserSession session) throws SQLException;
    }

    private record StartedUpload(long ownerId, long productId, Instant expiresAt) {
    }

    private record CompletedUpload(long ownerId, long productId, Instant expiresAt,
                                   UploadedImage uploaded) {
    }

    private record PlannedUpload(String uploadId, CompletedUpload completed) {
    }
}
