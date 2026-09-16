package com.vcampus.server.service;

import com.vcampus.common.model.ShopAccessPolicy;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.server.database.SecondhandReviewImageRepository;
import com.vcampus.server.database.SecondhandReviewStore.PendingImage;
import com.vcampus.server.image.ShopImageException;
import com.vcampus.server.image.ShopReviewImageStore;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class SecondhandReviewImageService {
    private static final int READ_BYTES = 192 * 1024;
    private static final Duration COMPLETED_TTL = Duration.ofMinutes(30);
    private final ShopReviewImageStore images;
    private final SecondhandReviewImageRepository repository;
    private final SessionManager sessions;
    private final Map<String, OwnedImage> completed = new ConcurrentHashMap<>();

    public SecondhandReviewImageService(ShopReviewImageStore images,
                                        SecondhandReviewImageRepository repository,
                                        SessionManager sessions) {
        this.images = java.util.Objects.requireNonNull(images, "images");
        this.repository = repository;
        this.sessions = java.util.Objects.requireNonNull(sessions, "sessions");
    }

    public ResponseMessage start(RequestMessage request) {
        return handle(request, session -> {
            var ticket = images.start(session.userId(), required(request, "mimeType"),
                    positiveLong(request.parameters().get("byteSize")));
            return ResponseMessage.success(request.requestId(), "二手评价图片上传已开始", Map.of(
                    "uploadId", ticket.uploadId(), "chunkBytes", Integer.toString(ticket.chunkBytes()),
                    "expectedBytes", Long.toString(ticket.expectedBytes())));
        });
    }

    public ResponseMessage chunk(RequestMessage request) {
        return handle(request, session -> {
            byte[] content;
            try { content = Base64.getDecoder().decode(required(request, "contentBase64")); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException("图片分块编码无效"); }
            images.append(session.userId(), required(request, "uploadId"),
                    nonNegativeInt(request.parameters().get("chunkIndex")), content);
            return ResponseMessage.success(request.requestId(), "二手评价图片分块已接收", Map.of());
        });
    }

    public ResponseMessage complete(RequestMessage request) {
        return handle(request, session -> {
            var image = images.complete(session.userId(), required(request, "uploadId"));
            completed.put(image.uploadId(), new OwnedImage(session.userId(), image, Instant.now()));
            return ResponseMessage.success(request.requestId(), "二手评价图片上传完成", Map.of(
                    "uploadId", image.uploadId(), "mimeType", image.mimeType(),
                    "byteSize", Long.toString(image.byteSize()), "sha256", image.sha256(),
                    "width", Integer.toString(image.width()), "height", Integer.toString(image.height())));
        });
    }

    public ResponseMessage read(RequestMessage request) {
        Optional<UserSession> user = session(request);
        if (user.isEmpty()) return expired(request);
        if (repository == null) return ResponseMessage.failure(request.requestId(), "图片服务暂不可用");
        try {
            long imageId = positiveLong(request.parameters().get("imageId"));
            String variant = required(request, "variant");
            int chunkIndex = nonNegativeInt(request.parameters().get("chunkIndex"));
            if (!variant.equals("THUMBNAIL") && !variant.equals("DETAIL")) {
                throw new IllegalArgumentException("图片版本无效");
            }
            var row = repository.find(imageId, ShopAccessPolicy.canManage(user.get().roles()))
                    .orElseThrow(() -> new IllegalArgumentException("图片不存在"));
            String key = variant.equals("THUMBNAIL") ? row.thumbnailStorageKey() : row.storageKey();
            var descriptor = images.describe(key);
            long offset = (long) chunkIndex * READ_BYTES;
            if (offset >= descriptor.totalBytes()) throw new IllegalArgumentException("图片分块序号无效");
            var range = images.readRange(key, offset, READ_BYTES);
            int chunks = Math.toIntExact(Math.ceilDiv(descriptor.totalBytes(), READ_BYTES));
            return ResponseMessage.success(request.requestId(), "二手评价图片读取成功", Map.of(
                    "imageId", Long.toString(imageId), "variant", variant,
                    "mimeType", row.mimeType(), "sha256", descriptor.sha256(),
                    "totalBytes", Long.toString(descriptor.totalBytes()),
                    "totalChunks", Integer.toString(chunks), "chunkIndex", Integer.toString(chunkIndex),
                    "contentBase64", Base64.getEncoder().encodeToString(range.content())));
        } catch (IllegalArgumentException error) {
            return ResponseMessage.failure(request.requestId(), error.getMessage());
        } catch (SQLException error) {
            return ResponseMessage.failure(request.requestId(), "数据库操作失败，请稍后重试");
        }
    }

    public List<PendingImage> claim(long ownerId, List<String> uploadIds) {
        java.util.Objects.requireNonNull(uploadIds, "uploadIds");
        prune(Instant.now());
        if (uploadIds.size() > 3 || new LinkedHashSet<>(uploadIds).size() != uploadIds.size()) {
            throw new IllegalArgumentException("每条评价最多三张图片且不能重复");
        }
        List<PendingImage> result = new ArrayList<>();
        for (String uploadId : uploadIds) {
            OwnedImage value = completed.get(uploadId);
            if (value == null || value.ownerId() != ownerId) {
                throw new IllegalArgumentException("图片上传尚未完成或已过期");
            }
            var image = value.image();
            result.add(new PendingImage(image.storageKey(), image.thumbnailStorageKey(),
                    image.mimeType(), image.byteSize(), image.sha256(), image.width(), image.height()));
        }
        return List.copyOf(result);
    }

    public void consume(long ownerId, List<String> uploadIds) {
        for (String uploadId : uploadIds) completed.computeIfPresent(uploadId,
                (key, value) -> value.ownerId() == ownerId ? null : value);
    }

    public void deleteKeys(Set<String> keys) {
        for (String key : keys) try { images.deleteIfExists(key); }
        catch (RuntimeException error) {
            System.err.println("Secondhand review image deletion failed for " + key + ": " + error.getMessage());
        }
    }

    public void cleanup(Instant now) {
        prune(now);
        if (repository == null) return;
        try { images.cleanup(repository.storageKeys(), now); }
        catch (SQLException | RuntimeException error) {
            System.err.println("Secondhand review image cleanup failed: " + error.getMessage());
        }
    }

    public ScheduledExecutorService startCleanupScheduler(Duration interval) {
        cleanup(Instant.now());
        var executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "vcampus-secondhand-review-image-cleanup");
            thread.setDaemon(true); return thread;
        });
        executor.scheduleWithFixedDelay(() -> cleanup(Instant.now()), interval.toMillis(),
                interval.toMillis(), TimeUnit.MILLISECONDS);
        return executor;
    }

    private ResponseMessage handle(RequestMessage request, Work work) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) return expired(request);
        try { return work.run(session.get()); }
        catch (IllegalArgumentException | ShopImageException error) {
            return ResponseMessage.failure(request.requestId(), error.getMessage());
        }
    }

    private Optional<UserSession> session(RequestMessage request) {
        return sessions.find(request.parameters().get("sessionToken"));
    }

    private static ResponseMessage expired(RequestMessage request) {
        return ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录");
    }

    private static String required(RequestMessage request, String key) {
        String value = request.parameters().get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("图片参数无效");
        return value;
    }

    private static long positiveLong(String value) {
        try { long parsed=Long.parseLong(value==null?"":value);if(parsed<1)throw new NumberFormatException();return parsed; }
        catch(NumberFormatException error){throw new IllegalArgumentException("编号无效");}
    }

    private static int nonNegativeInt(String value) {
        try { int parsed=Integer.parseInt(value==null?"":value);if(parsed<0)throw new NumberFormatException();return parsed; }
        catch(NumberFormatException error){throw new IllegalArgumentException("图片分块序号无效");}
    }

    private void prune(Instant now) {
        completed.entrySet().removeIf(entry -> {
            OwnedImage value=entry.getValue();
            if(value.createdAt().plus(COMPLETED_TTL).isAfter(now))return false;
            deleteKeys(Set.of(value.image().storageKey(),value.image().thumbnailStorageKey()));return true;
        });
    }

    private record OwnedImage(long ownerId, ShopReviewImageStore.Completed image, Instant createdAt) { }
    @FunctionalInterface private interface Work { ResponseMessage run(UserSession session); }
}
