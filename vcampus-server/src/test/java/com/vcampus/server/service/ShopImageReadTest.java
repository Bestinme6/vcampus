package com.vcampus.server.service;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
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
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopImageReadTest {
    private static final int CHUNK_BYTES = 192 * 1024;
    private final SessionManager sessions = new SessionManager();
    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private final List<String> openedKeys = new ArrayList<>();
    private final List<RangeRequest> rangeRequests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger descriptorCalls = new AtomicInteger();
    private ShopStore shopStore;
    private ShopImageStore imageStore;
    private ShopImageService service;
    private ShopProductImageRecord metadata;
    private boolean enabled;
    private String studentToken;
    private String managerToken;

    @BeforeEach
    void setUp() {
        studentToken = sessions.create(account(11L, Set.of(UserRole.STUDENT))).token();
        managerToken = sessions.create(account(19L,
                Set.of(UserRole.TEACHER, UserRole.SHOP_ADMIN))).token();
        byte[] detail = bytes(CHUNK_BYTES * 2 + 17, 3);
        byte[] thumbnail = bytes(CHUNK_BYTES * 2 + 9, 29);
        files.put("secret/detail.bin", detail);
        files.put("secret/thumb.bin", thumbnail);
        metadata = new ShopProductImageRecord(41L, 7L, "secret/detail.bin", "secret/thumb.bin",
                "image/png", detail.length, sha256(detail), 0, true,
                Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-01T11:00:00Z"));
        enabled = true;
        shopStore = (ShopStore) Proxy.newProxyInstance(ShopStore.class.getClassLoader(),
                new Class<?>[]{ShopStore.class}, (proxy, method, arguments) -> {
                    if (!"productImage".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    long requestedId = (Long) arguments[0];
                    boolean includeDisabled = (Boolean) arguments[1];
                    if (metadata == null || requestedId != metadata.id()
                            || (!enabled && !includeDisabled)) {
                        throw new ShopRuleException("\u56fe\u7247\u4e0d\u5b58\u5728");
                    }
                    return metadata;
                });
        imageStore = (ShopImageStore) Proxy.newProxyInstance(
                ShopImageStore.class.getClassLoader(), new Class<?>[]{ShopImageStore.class},
                (proxy, method, arguments) -> {
                    String key = (String) arguments[0];
                    byte[] bytes = files.get(key);
                    if (bytes == null) {
                        throw new ShopImageException("IMAGE_NOT_FOUND", "\u56fe\u7247\u4e0d\u5b58\u5728",
                                new java.io.IOException("C:/secret/shop/" + key));
                    }
                    return switch (method.getName()) {
                        case "open" -> {
                            openedKeys.add(key);
                            yield new ByteArrayInputStream(bytes);
                        }
                        case "describe" -> {
                            descriptorCalls.incrementAndGet();
                            yield new ShopImageStore.ImageDescriptor(bytes.length, sha256(bytes));
                        }
                        case "readRange" -> {
                            long offset = (Long) arguments[1];
                            int maxBytes = (Integer) arguments[2];
                            rangeRequests.add(new RangeRequest(key, offset, maxBytes));
                            int from = Math.toIntExact(Math.min(offset, bytes.length));
                            int to = Math.min(bytes.length, from + maxBytes);
                            yield new ShopImageStore.ImageRange(
                                    Arrays.copyOfRange(bytes, from, to), bytes.length);
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        service = new ShopImageService(shopStore, imageStore, sessions);
    }

    @Test
    void detailFirstMiddleAndLastChunksReassembleToAdvertisedHash() {
        byte[] expected = files.get("secret/detail.bin");
        List<byte[]> chunks = new ArrayList<>();
        String advertisedHash = null;
        for (int index = 0; index < 3; index++) {
            ResponseMessage response = read(studentToken, "DETAIL", Integer.toString(index));
            assertTrue(response.success(), response.message());
            assertEquals("41", response.data().get("imageId"));
            assertEquals("image/png", response.data().get("mimeType"));
            assertEquals(Integer.toString(expected.length), response.data().get("totalBytes"));
            assertEquals("3", response.data().get("totalChunks"));
            assertEquals(Integer.toString(index), response.data().get("chunkIndex"));
            advertisedHash = response.data().get("sha256");
            byte[] chunk = Base64.getDecoder().decode(response.data().get("contentBase64"));
            assertTrue(chunk.length <= CHUNK_BYTES);
            chunks.add(chunk);
        }
        assertArrayEquals(expected, concatenate(chunks));
        assertEquals(sha256(expected), advertisedHash);
        assertTrue(openedKeys.isEmpty(), "detail chunks must not full-read the file");
        assertEquals(0, descriptorCalls.get(), "detail must use persisted size/hash metadata");
        assertEquals(List.of(
                new RangeRequest("secret/detail.bin", 0, CHUNK_BYTES),
                new RangeRequest("secret/detail.bin", CHUNK_BYTES, CHUNK_BYTES),
                new RangeRequest("secret/detail.bin", 2L * CHUNK_BYTES, CHUNK_BYTES)),
                rangeRequests);
    }

    @Test
    void thumbnailFirstMiddleAndLastChunksUseTruthfulThumbnailHash() {
        byte[] expected = files.get("secret/thumb.bin");
        ResponseMessage first = read(studentToken, "THUMBNAIL", "0");
        ResponseMessage middle = read(studentToken, "THUMBNAIL", "1");
        ResponseMessage last = read(studentToken, "THUMBNAIL", "2");

        assertTrue(first.success(), first.message());
        assertTrue(middle.success(), middle.message());
        assertTrue(last.success(), last.message());
        assertEquals(sha256(expected), first.data().get("sha256"));
        assertEquals(first.data().get("sha256"), middle.data().get("sha256"));
        assertEquals(first.data().get("sha256"), last.data().get("sha256"));
        assertEquals(Integer.toString(expected.length), first.data().get("totalBytes"));
        assertArrayEquals(expected, concatenate(List.of(
                Base64.getDecoder().decode(first.data().get("contentBase64")),
                Base64.getDecoder().decode(middle.data().get("contentBase64")),
                Base64.getDecoder().decode(last.data().get("contentBase64")))));
        assertTrue(first.data().values().stream().noneMatch(value -> value.contains("secret/")));
        assertTrue(openedKeys.isEmpty(), "thumbnail descriptor must be cached after one bounded scan");
        assertEquals(1, descriptorCalls.get());
        assertEquals(List.of(
                new RangeRequest("secret/thumb.bin", 0, CHUNK_BYTES),
                new RangeRequest("secret/thumb.bin", CHUNK_BYTES, CHUNK_BYTES),
                new RangeRequest("secret/thumb.bin", 2L * CHUNK_BYTES, CHUNK_BYTES)),
                rangeRequests);
    }

    @Test
    void invalidVariantAndIndicesFailBeforeOpeningAFile() {
        for (String variant : List.of("thumbnail", "ORIGINAL", "")) {
            ResponseMessage response = read(studentToken, variant, "0");
            assertFalse(response.success());
            assertEquals("\u56fe\u7247\u7248\u672c\u65e0\u6548", response.message());
        }
        for (String index : List.of("-1", "2147483648", "not-a-number")) {
            ResponseMessage response = read(studentToken, "DETAIL", index);
            assertFalse(response.success());
            assertEquals("\u56fe\u7247\u5206\u5757\u5e8f\u53f7\u65e0\u6548", response.message());
        }
        assertTrue(openedKeys.isEmpty());

        ResponseMessage beyondLast = read(studentToken, "DETAIL", "3");
        assertFalse(beyondLast.success());
        assertEquals("\u56fe\u7247\u5206\u5757\u5e8f\u53f7\u65e0\u6548", beyondLast.message());
    }

    @Test
    void missingMetadataAndMissingFileAreStableAndNeverLeakKeysOrPaths() {
        metadata = null;
        ResponseMessage missingMetadata = read(studentToken, "DETAIL", "0");
        assertFalse(missingMetadata.success());
        assertEquals("\u56fe\u7247\u4e0d\u5b58\u5728", missingMetadata.message());
        assertTrue(openedKeys.isEmpty());

        metadata = new ShopProductImageRecord(41L, 7L, "secret/missing-detail.bin",
                "secret/missing-thumb.bin", "image/png", 17L, "a".repeat(64), 0, true,
                Instant.EPOCH, Instant.EPOCH);
        ResponseMessage missingFile = read(studentToken, "DETAIL", "0");
        assertFalse(missingFile.success());
        assertEquals("\u56fe\u7247\u4e0d\u5b58\u5728", missingFile.message());
        assertTrue(missingFile.data().isEmpty());
        assertFalse(missingFile.toString().contains("secret"));
        assertFalse(missingFile.toString().contains("C:/"));
    }

    @Test
    void disabledProductImageIsHiddenFromBuyerButVisibleToManager() {
        enabled = false;
        ResponseMessage buyer = read(studentToken, "DETAIL", "0");
        ResponseMessage manager = read(managerToken, "DETAIL", "0");

        assertFalse(buyer.success());
        assertEquals("\u56fe\u7247\u4e0d\u5b58\u5728", buyer.message());
        assertTrue(manager.success(), manager.message());
        assertTrue(openedKeys.isEmpty());
        assertEquals(List.of(new RangeRequest("secret/detail.bin", 0, CHUNK_BYTES)), rangeRequests);
    }

    @Test
    void concurrentThumbnailReadsShareOneDescriptorComputation() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<ResponseMessage>> reads = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(index -> (Callable<ResponseMessage>) () ->
                            read(studentToken, "THUMBNAIL", "0"))
                    .toList();
            for (var result : executor.invokeAll(reads)) {
                assertTrue(result.get().success());
            }
        }

        assertEquals(1, descriptorCalls.get());
        assertEquals(8, rangeRequests.size());
    }

    @Test
    void thumbnailDescriptorCacheIsBoundedAndRecomputedAfterRestart() {
        for (int index = 1; index <= 257; index++) {
            String thumbnailKey = "secret/thumb-" + index + ".bin";
            files.put(thumbnailKey, new byte[]{(byte) index});
            metadata = new ShopProductImageRecord(index, 7L, "secret/detail.bin", thumbnailKey,
                    "image/png", files.get("secret/detail.bin").length,
                    sha256(files.get("secret/detail.bin")), 0, true, Instant.EPOCH, Instant.EPOCH);
            assertTrue(service.getChunk(request(studentToken, Map.of(
                    "imageId", Integer.toString(index), "variant", "THUMBNAIL",
                    "chunkIndex", "0"))).success());
        }
        metadata = new ShopProductImageRecord(1L, 7L, "secret/detail.bin", "secret/thumb-1.bin",
                "image/png", files.get("secret/detail.bin").length,
                sha256(files.get("secret/detail.bin")), 0, true, Instant.EPOCH, Instant.EPOCH);
        RequestMessage firstImage = request(studentToken, Map.of(
                "imageId", "1", "variant", "THUMBNAIL", "chunkIndex", "0"));
        assertTrue(service.getChunk(firstImage).success());
        assertEquals(258, descriptorCalls.get(), "oldest descriptor must be evicted");

        service = new ShopImageService(shopStore, imageStore, sessions);
        assertTrue(service.getChunk(firstImage).success());
        assertEquals(259, descriptorCalls.get(), "restart must rebuild the in-memory descriptor");
    }

    private ResponseMessage read(String token, String variant, String chunkIndex) {
        return service.getChunk(request(token, Map.of(
                "imageId", "41", "variant", variant, "chunkIndex", chunkIndex)));
    }

    private RequestMessage request(String token, Map<String, String> values) {
        Map<String, String> parameters = new LinkedHashMap<>(values);
        parameters.put("sessionToken", token);
        return RequestMessage.create("shop.image.getChunk", parameters);
    }

    private UserAccount account(long id, Set<UserRole> roles) {
        return new UserAccount(id, "user" + id, "hash", "salt", "User " + id,
                true, false, roles);
    }

    private byte[] bytes(int length, int seed) {
        byte[] result = new byte[length];
        for (int index = 0; index < result.length; index++) result[index] = (byte) (index * 31 + seed);
        return result;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private byte[] concatenate(List<byte[]> chunks) {
        int size = chunks.stream().mapToInt(chunk -> chunk.length).sum();
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }

    private record RangeRequest(String storageKey, long offset, int maxBytes) {
    }
}
