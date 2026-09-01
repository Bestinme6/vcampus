package com.vcampus.server.service;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.ShopRuleException;
import com.vcampus.server.database.ShopStore;
import com.vcampus.server.image.ShopImageException;
import com.vcampus.server.image.ShopImageStore;
import com.vcampus.server.model.ShopProductImageRecord;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopImageServiceTest {
    private SessionManager sessions;
    private FakeImageStore images;
    private AtomicInteger repositoryCalls;
    private ImageCommitter committer;
    private ShopImageService service;
    private String studentToken;
    private String shopAdminToken;
    private String otherAdminToken;
    private String superAdminToken;

    @BeforeEach
    void setUp() {
        sessions = new SessionManager();
        studentToken = token(1L, "student", Set.of(UserRole.STUDENT));
        shopAdminToken = token(9L, "shop", Set.of(UserRole.TEACHER, UserRole.SHOP_ADMIN));
        otherAdminToken = token(11L, "shop2", Set.of(UserRole.TEACHER, UserRole.SHOP_ADMIN));
        superAdminToken = token(10L, "root", Set.of(UserRole.SUPER_ADMIN));
        images = new FakeImageStore();
        repositoryCalls = new AtomicInteger();
        committer = (operatorId, productId, uploads, plan) -> successResult(uploads);
        service = new ShopImageService(shopStore(), images, sessions);
    }

    @Test
    void studentCannotUseAnyAdministrativeImageAction() {
        assertFalse(service.uploadStart(request(Actions.SHOP_ADMIN_IMAGE_UPLOAD_START,
                studentToken, Map.of("productId", "4", "mimeType", "image/png",
                        "expectedBytes", "120"))).success());
        assertFalse(service.uploadChunk(request(Actions.SHOP_ADMIN_IMAGE_UPLOAD_CHUNK,
                studentToken, Map.of("uploadId", "up-1", "chunkIndex", "0",
                        "contentBase64", "eA=="))).success());
        assertFalse(service.uploadComplete(request(Actions.SHOP_ADMIN_IMAGE_UPLOAD_COMPLETE,
                studentToken, Map.of("uploadId", "up-1"))).success());
        assertFalse(service.commit(commitRequest(studentToken, 4L,
                RowCodec.encode("", "up-1", "true"))).success());

        assertEquals(0, images.totalCalls());
        assertEquals(0, repositoryCalls.get());
    }

    @Test
    void shopAndSuperAdministratorsCanStartUploadsWithTheirSessionIdentity() {
        assertTrue(service.uploadStart(request(Actions.SHOP_ADMIN_IMAGE_UPLOAD_START,
                shopAdminToken, Map.of("productId", "4", "mimeType", "image/png",
                        "expectedBytes", "120"))).success());
        assertTrue(service.uploadStart(request(Actions.SHOP_ADMIN_IMAGE_UPLOAD_START,
                superAdminToken, Map.of("productId", "5", "mimeType", "image/jpeg",
                        "expectedBytes", "121"))).success());

        assertEquals(List.of(9L, 10L), images.startOwners);
        assertEquals(List.of(4L, 5L), images.startProducts);
    }

    @Test
    void malformedBase64IsRejectedBeforeStorageIsCalled() {
        var response = service.uploadChunk(request(Actions.SHOP_ADMIN_IMAGE_UPLOAD_CHUNK,
                shopAdminToken, Map.of("uploadId", "up-1", "chunkIndex", "0",
                        "contentBase64", "%%%not-base64%%%")));

        assertFalse(response.success());
        assertEquals("图片分块编码无效", response.message());
        assertEquals(0, images.appendCalls);
    }

    @Test
    void base64WhitespaceIsRejectedBeforeStorageIsCalled() {
        var response = service.uploadChunk(request(Actions.SHOP_ADMIN_IMAGE_UPLOAD_CHUNK,
                shopAdminToken, Map.of("uploadId", "up-1", "chunkIndex", "0",
                        "contentBase64", " AQ== ")));

        assertFalse(response.success());
        assertEquals("图片分块编码无效", response.message());
        assertEquals(0, images.appendCalls);
    }

    @Test
    void uploadOperationsUseCurrentSessionOwnerAndStoreEnforcesBinding() {
        images.requiredOwner = 9L;
        var response = service.uploadChunk(request(Actions.SHOP_ADMIN_IMAGE_UPLOAD_CHUNK,
                otherAdminToken, Map.of("uploadId", "up-1", "chunkIndex", "0",
                        "contentBase64", Base64.getEncoder().encodeToString(new byte[]{1, 2}))));

        assertFalse(response.success());
        assertEquals("上传不属于当前用户", response.message());
        assertEquals(11L, images.lastAppendOwner);
    }

    @Test
    void imageStoreFailuresRemainDistinctAndDoNotExposeCauses() {
        images.completeFailure = new ShopImageException("UPLOAD_EXPIRED", "图片上传已过期",
                new IllegalStateException("C:\\private\\temp\\upload.bin"));
        var expired = service.uploadComplete(request(Actions.SHOP_ADMIN_IMAGE_UPLOAD_COMPLETE,
                shopAdminToken, Map.of("uploadId", "up-1")));
        images.completeFailure = new ShopImageException("INVALID_IMAGE", "图片格式无效");
        var invalid = service.uploadComplete(request(Actions.SHOP_ADMIN_IMAGE_UPLOAD_COMPLETE,
                shopAdminToken, Map.of("uploadId", "up-2")));

        assertEquals("图片上传已过期", expired.message());
        assertEquals("图片格式无效", invalid.message());
        assertFalse(expired.message().contains("private"));
    }

    @Test
    void malformedNumbersAndCountsReturnStableValidationErrors() {
        var product = service.uploadStart(request(Actions.SHOP_ADMIN_IMAGE_UPLOAD_START,
                shopAdminToken, Map.of("productId", "abc", "mimeType", "image/png",
                        "expectedBytes", "120")));
        var count = service.commit(request(Actions.SHOP_ADMIN_IMAGE_COMMIT,
                shopAdminToken, Map.of("productId", "4", "itemCount", "2147483648")));

        assertEquals("商品ID无效", product.message());
        assertEquals("图片数量无效", count.message());
        assertEquals(0, images.totalCalls());
    }

    @Test
    void malformedRowLengthReturnsAStablePlanError() {
        var response = service.commit(commitRequest(
                shopAdminToken, 4L, "2147483647:x"));

        assertFalse(response.success());
        assertEquals("图片计划格式无效", response.message());
        assertEquals(0, images.finalizeCalls);
        assertEquals(0, repositoryCalls.get());
    }

    @Test
    void successfulCommitFinalizesNewFilesThenDeletesOnlyRemovedOldFiles() {
        completeUpload(shopAdminToken, "up-1", 4L);
        committer = (operatorId, productId, uploads, plan) -> {
            assertEquals(Set.of("up-1"), uploads.keySet());
            assertEquals("new-full.png", uploads.get("up-1").storageKey());
            return new ShopStore.ImageCommitResult(
                    Set.of("new-full.png", "new-thumb.png"),
                    Set.of("old-full.png", "old-thumb.png"), List.of());
        };

        var response = service.commit(commitRequest(shopAdminToken, 4L,
                RowCodec.encode("", "up-1", "true")));

        assertTrue(response.success(), response.message());
        assertEquals(Set.of("old-full.png", "old-thumb.png"), Set.copyOf(images.deletedKeys));
        assertEquals(1, images.finalizeCalls);
        assertEquals(1, repositoryCalls.get());
    }

    @Test
    void sqlRollbackDeletesOnlyNewlyFinalizedFilesAndLeavesOldFilesForDatabaseRows() {
        completeUpload(shopAdminToken, "up-1", 4L);
        committer = (operatorId, productId, uploads, plan) -> {
            throw new SQLException("forced metadata failure");
        };

        var response = service.commit(commitRequest(shopAdminToken, 4L,
                RowCodec.encode("", "up-1", "true")));

        assertFalse(response.success());
        assertEquals("数据库操作失败，请稍后重试", response.message());
        assertEquals(Set.of("new-full.png", "new-thumb.png"), Set.copyOf(images.deletedKeys));
    }

    @Test
    void productMissingAfterFinalizationCompensatesNewFilesAndKeepsStableMessage() {
        completeUpload(shopAdminToken, "up-1", 4L);
        committer = (operatorId, productId, uploads, plan) -> {
            throw new ShopRuleException("商品不存在");
        };

        var response = service.commit(commitRequest(shopAdminToken, 4L,
                RowCodec.encode("", "up-1", "true")));

        assertFalse(response.success());
        assertEquals("商品不存在", response.message());
        assertEquals(Set.of("new-full.png", "new-thumb.png"), Set.copyOf(images.deletedKeys));
    }

    @Test
    void commitRejectsUploadForAnotherProductBeforeFinalization() {
        completeUpload(shopAdminToken, "up-1", 8L);

        var response = service.commit(commitRequest(shopAdminToken, 4L,
                RowCodec.encode("", "up-1", "true")));

        assertFalse(response.success());
        assertEquals("上传不属于当前商品", response.message());
        assertEquals(0, images.finalizeCalls);
        assertEquals(0, repositoryCalls.get());
    }

    @Test
    void commitRejectsMissingCompletedUploadAsAnInvalidPlan() {
        var response = service.commit(commitRequest(shopAdminToken, 4L,
                RowCodec.encode("", "missing", "true")));

        assertFalse(response.success());
        assertEquals("上传尚未完成或已过期", response.message());
        assertEquals(0, images.finalizeCalls);
        assertEquals(0, repositoryCalls.get());
    }

    @Test
    void partialFinalizationFailureCompensatesFilesAlreadyFinalized() {
        completeUpload(shopAdminToken, "up-1", 4L);
        completeUpload(shopAdminToken, "up-2", 4L);
        images.failFinalizeUploadId = "up-2";

        var response = service.commit(commitRequest(shopAdminToken, 4L,
                RowCodec.encode("", "up-1", "true"),
                RowCodec.encode("", "up-2", "false")));

        assertFalse(response.success());
        assertEquals("图片转正失败", response.message());
        assertEquals(Set.of("new-full.png", "new-thumb.png"), Set.copyOf(images.deletedKeys));
        assertEquals(0, repositoryCalls.get());
    }

    private void completeUpload(String token, String uploadId, long productId) {
        images.uploaded = new ShopImageStore.UploadedImage(uploadId, productId, "image/png",
                new byte[]{1, 2, 3}, "a".repeat(64), 2, 2);
        var response = service.uploadComplete(request(Actions.SHOP_ADMIN_IMAGE_UPLOAD_COMPLETE,
                token, Map.of("uploadId", uploadId)));
        assertTrue(response.success(), response.message());
    }

    private ShopStore.ImageCommitResult successResult(
            Map<String, ShopStore.FinalizedUpload> uploads) {
        Set<String> keys = uploads.values().stream()
                .flatMap(upload -> java.util.stream.Stream.of(
                        upload.storageKey(), upload.thumbnailStorageKey()))
                .collect(java.util.stream.Collectors.toSet());
        return new ShopStore.ImageCommitResult(keys, Set.of(), List.of());
    }

    private ShopStore shopStore() {
        return (ShopStore) Proxy.newProxyInstance(ShopStore.class.getClassLoader(),
                new Class<?>[]{ShopStore.class}, (proxy, method, arguments) -> {
                    if ("replaceProductImages".equals(method.getName())) {
                        repositoryCalls.incrementAndGet();
                        @SuppressWarnings("unchecked")
                        Map<String, ShopStore.FinalizedUpload> uploads =
                                (Map<String, ShopStore.FinalizedUpload>) arguments[2];
                        return committer.commit((long) arguments[0], (long) arguments[1], uploads,
                                (ShopStore.ImagePlan) arguments[3]);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private RequestMessage commitRequest(String token, long productId, String... rows) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("sessionToken", token);
        parameters.put("productId", Long.toString(productId));
        parameters.put("itemCount", Integer.toString(rows.length));
        for (int index = 0; index < rows.length; index++) {
            parameters.put("item." + index, rows[index]);
        }
        return RequestMessage.create(Actions.SHOP_ADMIN_IMAGE_COMMIT, parameters);
    }

    private RequestMessage request(String action, String token, Map<String, String> parameters) {
        Map<String, String> all = new LinkedHashMap<>(parameters);
        all.put("sessionToken", token);
        return RequestMessage.create(action, all);
    }

    private String token(long id, String username, Set<UserRole> roles) {
        return sessions.create(new UserAccount(id, username, "h", "s", username,
                true, false, roles)).token();
    }

    @FunctionalInterface
    private interface ImageCommitter {
        ShopStore.ImageCommitResult commit(long operatorId, long productId,
                                           Map<String, ShopStore.FinalizedUpload> uploads,
                                           ShopStore.ImagePlan plan) throws SQLException;
    }

    private static final class FakeImageStore implements ShopImageStore {
        private final List<Long> startOwners = new ArrayList<>();
        private final List<Long> startProducts = new ArrayList<>();
        private final List<String> deletedKeys = new ArrayList<>();
        private int appendCalls;
        private int completeCalls;
        private int finalizeCalls;
        private long lastAppendOwner;
        private Long requiredOwner;
        private RuntimeException completeFailure;
        private String failFinalizeUploadId;
        private UploadedImage uploaded = new UploadedImage("up-1", 4L, "image/png",
                new byte[]{1}, "a".repeat(64), 1, 1);

        @Override
        public UploadTicket startUpload(long ownerId, long productId, String mimeType,
                                        long expectedBytes) {
            startOwners.add(ownerId);
            startProducts.add(productId);
            return new UploadTicket("up-" + startOwners.size(), productId, expectedBytes,
                    192 * 1024, Instant.parse("2026-09-01T12:30:00Z"));
        }

        @Override
        public void appendChunk(long ownerId, String uploadId, int index, byte[] bytes) {
            appendCalls++;
            lastAppendOwner = ownerId;
            if (requiredOwner != null && requiredOwner != ownerId) {
                throw new ShopImageException("UPLOAD_OWNER", "上传不属于当前用户");
            }
        }

        @Override
        public UploadedImage completeUpload(long ownerId, String uploadId) {
            completeCalls++;
            if (completeFailure != null) throw completeFailure;
            return uploaded;
        }

        @Override
        public FinalizedImage finalizeUpload(long ownerId, String uploadId) {
            finalizeCalls++;
            if (uploadId.equals(failFinalizeUploadId)) {
                throw new ShopImageException("FINALIZE_FAILED", "图片转正失败");
            }
            return new FinalizedImage("new-full.png", "new-thumb.png");
        }

        @Override public InputStream open(String storageKey) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override public boolean deleteIfExists(String storageKey) {
            deletedKeys.add(storageKey);
            return true;
        }

        @Override public void cleanup(Set<String> referencedKeys, Instant now) { }

        private int totalCalls() {
            return startOwners.size() + appendCalls + completeCalls + finalizeCalls;
        }
    }
}
