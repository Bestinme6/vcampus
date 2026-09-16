package com.vcampus.server.service;

import com.vcampus.common.model.SecondhandItemStatus;
import com.vcampus.common.model.SecondhandModerationStatus;
import com.vcampus.common.model.ShopAccessPolicy;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.SecondhandImageRepository;
import com.vcampus.server.database.SecondhandImageRepository.FinalizedUpload;
import com.vcampus.server.database.SecondhandImageRepository.ImagePlan;
import com.vcampus.server.database.SecondhandImageRepository.ImagePlanItem;
import com.vcampus.server.database.SecondhandImageRepository.ImageRow;
import com.vcampus.server.database.SecondhandListingStore;
import com.vcampus.server.database.SecondhandRuleException;
import com.vcampus.server.image.ShopImageException;
import com.vcampus.server.image.ShopImageStore;
import com.vcampus.server.image.ShopImageStore.UploadedImage;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class SecondhandImageService {
    private static final int MAX_IMAGES = 5;
    private static final int READ_CHUNK_BYTES = 192 * 1024;
    private static final Set<String> TERMINAL_CODES = Set.of("INVALID_IMAGE", "MIME_MISMATCH",
            "UNSUPPORTED_IMAGE", "PIXEL_LIMIT", "UPLOAD_EXPIRED", "UPLOAD_NOT_FOUND",
            "SIZE_MISMATCH", "STORAGE_BOUNDARY", "PROCESSING_INTERRUPTED", "STORAGE_ERROR");

    private final SecondhandListingStore listings;
    private final SecondhandImageRepository metadata;
    private final ShopImageStore files;
    private final SessionManager sessions;
    private final ConcurrentMap<String, StartedUpload> started = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletedUpload> completed = new ConcurrentHashMap<>();

    public SecondhandImageService(SecondhandListingStore listings,
                                  SecondhandImageRepository metadata,
                                  ShopImageStore files, SessionManager sessions) {
        this.listings = Objects.requireNonNull(listings, "listings");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.files = Objects.requireNonNull(files, "files");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public ResponseMessage uploadStart(RequestMessage request) {
        return handle(request, session -> {
            long itemId = positiveLong(request.parameters().get("itemId"), "物品ID");
            requireWritable(session, itemId);
            long expectedBytes = positiveLong(request.parameters().get("expectedBytes"), "图片大小");
            String mimeType = required(request, "mimeType", "图片类型");
            var ticket = files.startUpload(session.userId(), itemId, mimeType, expectedBytes);
            started.put(ticket.uploadId(), new StartedUpload(session.userId(), itemId,
                    ticket.expiresAt()));
            return success(request, "二手物品图片上传已开始", Map.of(
                    "uploadId", ticket.uploadId(), "itemId", Long.toString(itemId),
                    "expectedBytes", Long.toString(ticket.expectedBytes()),
                    "chunkBytes", Integer.toString(ticket.chunkBytes()),
                    "expiresAt", ticket.expiresAt().toString()));
        });
    }

    public ResponseMessage uploadChunk(RequestMessage request) {
        return handle(request, session -> {
            String uploadId = required(request, "uploadId", "上传ID");
            int index = nonNegativeInt(request.parameters().get("chunkIndex"), "图片分块序号");
            byte[] bytes;
            try {
                String encoded = request.parameters().get("contentBase64");
                if (encoded == null || encoded.isEmpty() || !encoded.equals(encoded.trim())) {
                    throw new IllegalArgumentException();
                }
                bytes = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("图片分块编码无效");
            }
            files.appendChunk(session.userId(), uploadId, index, bytes);
            return success(request, "二手物品图片分块已接收",
                    Map.of("uploadId", uploadId, "chunkIndex", Integer.toString(index)));
        });
    }

    public ResponseMessage uploadComplete(RequestMessage request) {
        return handle(request, session -> {
            String uploadId = required(request, "uploadId", "上传ID");
            CompletedUpload existing = completed.get(uploadId);
            if (existing != null) {
                if (existing.ownerId() != session.userId()) {
                    throw new IllegalArgumentException("上传尚未开始或已过期");
                }
                return completeSuccess(request, existing.image());
            }
            StartedUpload tracked = started.get(uploadId);
            UploadedImage image;
            try {
                image = files.completeUpload(session.userId(), uploadId);
            } catch (ShopImageException error) {
                if (tracked != null && TERMINAL_CODES.contains(error.code())) started.remove(uploadId, tracked);
                throw error;
            }
            if (tracked == null || tracked.ownerId() != session.userId()
                    || tracked.itemId() != image.productId()) {
                throw new IllegalArgumentException("上传尚未开始或已过期");
            }
            started.remove(uploadId, tracked);
            completed.put(uploadId, new CompletedUpload(session.userId(), tracked.itemId(),
                    tracked.expiresAt(), image));
            return completeSuccess(request, image);
        });
    }

    public ResponseMessage commit(RequestMessage request) {
        return handle(request, session -> {
            long itemId = positiveLong(request.parameters().get("itemId"), "物品ID");
            requireWritable(session, itemId);
            ImagePlan plan = parsePlan(request);
            List<CompletedUpload> planned = new ArrayList<>();
            for (ImagePlanItem item : plan.items()) {
                if (item.uploadId() == null) continue;
                CompletedUpload upload = completed.get(item.uploadId());
                if (upload == null || upload.ownerId() != session.userId()
                        || upload.itemId() != itemId) {
                    throw new IllegalArgumentException("上传尚未完成或不属于当前物品");
                }
                planned.add(upload);
            }
            Map<String, FinalizedUpload> finalized = new LinkedHashMap<>();
            Set<String> newKeys = new LinkedHashSet<>();
            boolean repositoryCalled = false;
            try {
                for (CompletedUpload upload : planned) {
                    var stored = files.finalizeUpload(session.userId(), upload.image().uploadId());
                    finalized.put(upload.image().uploadId(), new FinalizedUpload(
                            stored.storageKey(), stored.thumbnailStorageKey(),
                            upload.image().mimeType(), upload.image().normalizedBytes().length,
                            upload.image().sha256()));
                    newKeys.add(stored.storageKey());
                    newKeys.add(stored.thumbnailStorageKey());
                }
                repositoryCalled = true;
                var result = metadata.replace(session.userId(), itemId, finalized, plan);
                deleteKeys(result.deletedKeys());
                return success(request, "二手物品图片已更新", imageData(result.rows()));
            } catch (SQLException error) {
                // Commit outcome can be uncertain; keep finalized files for reconciliation cleanup.
                throw error;
            } catch (RuntimeException error) {
                if (!repositoryCalled) deleteKeys(newKeys);
                throw error;
            } finally {
                for (CompletedUpload upload : planned) {
                    completed.remove(upload.image().uploadId(), upload);
                }
            }
        });
    }

    public ResponseMessage getChunk(RequestMessage request) {
        return handle(request, session -> {
            long imageId = positiveLong(request.parameters().get("imageId"), "图片ID");
            ImageRow image = metadata.image(imageId);
            listings.get(image.itemId(), session.userId(), ShopAccessPolicy.canManage(session.roles()));
            String variant = request.parameters().get("variant");
            if (!"DETAIL".equals(variant) && !"THUMBNAIL".equals(variant)) {
                throw new IllegalArgumentException("图片版本无效");
            }
            String key = "DETAIL".equals(variant) ? image.storageKey() : image.thumbnailStorageKey();
            var descriptor = "DETAIL".equals(variant)
                    ? new ShopImageStore.ImageDescriptor(image.byteSize(), image.sha256())
                    : files.describe(key);
            int chunkIndex = nonNegativeInt(request.parameters().get("chunkIndex"), "图片分块序号");
            long offset = (long) chunkIndex * READ_CHUNK_BYTES;
            if (offset >= descriptor.totalBytes()) throw new IllegalArgumentException("图片分块序号无效");
            var range = files.readRange(key, offset, READ_CHUNK_BYTES);
            int expected = Math.toIntExact(Math.min((long) READ_CHUNK_BYTES,
                    descriptor.totalBytes() - offset));
            if (range.totalBytes() != descriptor.totalBytes() || range.content().length != expected) {
                throw new ShopImageException("IMAGE_NOT_FOUND", "图片不存在");
            }
            long chunks = (descriptor.totalBytes() - 1) / READ_CHUNK_BYTES + 1;
            return success(request, "二手物品图片读取成功", Map.of(
                    "imageId", Long.toString(image.id()), "mimeType", image.mimeType(),
                    "sha256", descriptor.sha256(),
                    "totalBytes", Long.toString(descriptor.totalBytes()),
                    "totalChunks", Long.toString(chunks), "chunkIndex", Integer.toString(chunkIndex),
                    "contentBase64", Base64.getEncoder().encodeToString(range.content())));
        });
    }

    public void cleanup(Instant now) {
        started.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        completed.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        try {
            files.cleanup(metadata.storageKeys(), now);
        } catch (SQLException | RuntimeException error) {
            System.err.println("Secondhand image cleanup failed: " + error.getMessage());
        }
    }

    public ScheduledExecutorService startCleanupScheduler(Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("cleanup interval must be positive");
        }
        cleanup(Instant.now());
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "vcampus-secondhand-image-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> cleanup(Instant.now()), interval.toMillis(),
                interval.toMillis(), TimeUnit.MILLISECONDS);
        return executor;
    }

    private void requireWritable(UserSession session, long itemId) throws SQLException {
        var item = listings.get(itemId, session.userId(), false);
        if (item.sellerId() != session.userId() || item.status() != SecondhandItemStatus.AVAILABLE
                || item.moderation() != SecondhandModerationStatus.VISIBLE) {
            throw new SecondhandRuleException("无权修改该二手物品图片");
        }
    }

    private ImagePlan parsePlan(RequestMessage request) {
        int count = nonNegativeInt(request.parameters().get("itemCount"), "图片数量");
        if (count > MAX_IMAGES) throw new IllegalArgumentException("每件二手物品最多五张图片");
        List<ImagePlanItem> items = new ArrayList<>();
        Set<Long> ids = new LinkedHashSet<>();
        Set<String> uploads = new LinkedHashSet<>();
        for (int index = 0; index < count; index++) {
            List<String> fields;
            try {
                fields = RowCodec.decode(Objects.requireNonNull(
                        request.parameters().get("item." + index)));
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("图片计划格式无效");
            }
            if (fields.size() != 3) throw new IllegalArgumentException("图片计划格式无效");
            Long existing = fields.get(0).isBlank() ? null : positiveLong(fields.get(0), "已有图片ID");
            String upload = fields.get(1).trim();
            if ((existing == null) == upload.isEmpty()) throw new IllegalArgumentException("图片计划来源无效");
            boolean cover = strictBoolean(fields.get(2), "封面状态");
            if (existing != null && !ids.add(existing) || !upload.isEmpty() && !uploads.add(upload)) {
                throw new IllegalArgumentException("图片计划包含重复图片");
            }
            items.add(new ImagePlanItem(existing, upload.isEmpty() ? null : upload, cover));
        }
        if (!items.isEmpty() && items.stream().filter(ImagePlanItem::cover).count() != 1) {
            throw new IllegalArgumentException("二手物品图片必须且只能设置一张封面");
        }
        return new ImagePlan(items);
    }

    private ResponseMessage completeSuccess(RequestMessage request, UploadedImage image) {
        return success(request, "二手物品图片上传已完成", Map.of(
                "uploadId", image.uploadId(), "itemId", Long.toString(image.productId()),
                "mimeType", image.mimeType(), "byteSize", Integer.toString(image.normalizedBytes().length),
                "sha256", image.sha256(), "width", Integer.toString(image.width()),
                "height", Integer.toString(image.height())));
    }

    private static Map<String, String> imageData(List<ImageRow> rows) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("count", Integer.toString(rows.size()));
        for (int index = 0; index < rows.size(); index++) {
            ImageRow row = rows.get(index);
            data.put("image." + index, RowCodec.encode(Long.toString(row.id()), row.mimeType(),
                    Long.toString(row.byteSize()), row.sha256(), Integer.toString(row.sortOrder()),
                    Boolean.toString(row.cover())));
        }
        return data;
    }

    private ResponseMessage handle(RequestMessage request, Work work) {
        Optional<UserSession> session = sessions.find(request.parameters().get("sessionToken"));
        if (session.isEmpty()) return ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录");
        try {
            return work.run(session.get());
        } catch (ShopImageException | SecondhandRuleException | IllegalArgumentException error) {
            return ResponseMessage.failure(request.requestId(), error.getMessage());
        } catch (SQLException error) {
            System.err.println("Secondhand image database operation failed: " + error.getMessage());
            return ResponseMessage.failure(request.requestId(), "二手物品图片暂不可用，请稍后重试");
        }
    }

    private void deleteKeys(Iterable<String> keys) {
        for (String key : keys) {
            try { files.deleteIfExists(key); } catch (RuntimeException ignored) { }
        }
    }

    private static ResponseMessage success(RequestMessage request, String message,
                                           Map<String, String> data) {
        return ResponseMessage.success(request.requestId(), message, data);
    }

    private static String required(RequestMessage request, String key, String label) {
        String value = request.parameters().get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "无效");
        return value.trim();
    }

    private static long positiveLong(String value, String label) {
        try {
            long parsed = Long.parseLong(Objects.requireNonNullElse(value, "").trim());
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(label + "无效");
        }
    }

    private static int nonNegativeInt(String value, String label) {
        try {
            int parsed = Integer.parseInt(Objects.requireNonNullElse(value, "").trim());
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(label + "无效");
        }
    }

    private static boolean strictBoolean(String value, String label) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(label + "无效");
    }

    @FunctionalInterface
    private interface Work { ResponseMessage run(UserSession session) throws SQLException; }
    private record StartedUpload(long ownerId, long itemId, Instant expiresAt) { }
    private record CompletedUpload(long ownerId, long itemId, Instant expiresAt,
                                   UploadedImage image) { }
}
