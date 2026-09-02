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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopImageReadTest {
    private static final int CHUNK_BYTES = 192 * 1024;
    private final SessionManager sessions = new SessionManager();
    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private final List<String> openedKeys = new ArrayList<>();
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
        ShopStore store = (ShopStore) Proxy.newProxyInstance(ShopStore.class.getClassLoader(),
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
        ShopImageStore imageStore = (ShopImageStore) Proxy.newProxyInstance(
                ShopImageStore.class.getClassLoader(), new Class<?>[]{ShopImageStore.class},
                (proxy, method, arguments) -> {
                    if (!"open".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    String key = (String) arguments[0];
                    openedKeys.add(key);
                    byte[] bytes = files.get(key);
                    if (bytes == null) {
                        throw new ShopImageException("IMAGE_NOT_FOUND", "\u56fe\u7247\u4e0d\u5b58\u5728",
                                new java.io.IOException("C:/secret/shop/" + key));
                    }
                    return new ByteArrayInputStream(bytes);
                });
        service = new ShopImageService(store, imageStore, sessions);
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
        assertEquals(List.of("secret/detail.bin", "secret/detail.bin", "secret/detail.bin"), openedKeys);
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
        assertEquals(List.of("secret/thumb.bin", "secret/thumb.bin", "secret/thumb.bin"), openedKeys);
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
        assertEquals(List.of("secret/detail.bin"), openedKeys);
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
}
