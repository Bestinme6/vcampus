package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.ImageChunk;
import com.vcampus.client.fx.shop.ShopData.ImageRef;
import com.vcampus.client.fx.shop.ShopData.ImageVariant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ShopImageCacheTest {
    private static final int CHUNK_BYTES = 192 * 1024;

    @TempDir Path temporary;

    @Test
    void reassemblesThreeChunksAndDefensivelyIsolatesCallers() {
        byte[] expected = bytes(CHUNK_BYTES * 2 + 17);
        FakeChunks chunks = new FakeChunks(expected);
        try (ShopImageCache cache = cache(chunks.gateway())) {
            byte[] first = cache.load(ref(expected), ImageVariant.DETAIL).join();
            first[0] ^= 0x7f;
            byte[] second = cache.load(ref(expected), ImageVariant.DETAIL).join();

            assertArrayEquals(expected, second);
            assertEquals(3, chunks.calls.get());
        }
    }

    @Test
    void concurrentLoadsShareOneChunkSequenceAndNewInstanceHitsDisk() {
        byte[] expected = bytes(CHUNK_BYTES * 2 + 9);
        FakeChunks chunks = new FakeChunks(expected);
        ShopImageCacheConfig config = config();
        QueuedExecutor executor = new QueuedExecutor();
        try (ShopImageCache cache = new ShopImageCache(chunks.gateway(), executor, config)) {
            CompletableFuture<byte[]> first = cache.load(ref(expected), ImageVariant.THUMBNAIL);
            CompletableFuture<byte[]> second = cache.load(ref(expected), ImageVariant.THUMBNAIL);
            assertEquals(1, executor.size());
            executor.runNext();
            assertArrayEquals(expected, first.join());
            assertArrayEquals(expected, second.join());
            cache.close();
            executor.runAll();
        }
        int downloaded = chunks.calls.get();
        try (ShopImageCache cache = new ShopImageCache(chunks.gateway(), Runnable::run, config)) {
            assertArrayEquals(expected, cache.load(ref(expected), ImageVariant.THUMBNAIL).join());
        }
        assertEquals(3, downloaded);
        assertEquals(downloaded, chunks.calls.get());
    }

    @Test
    void detailAndThumbnailUseSeparateCacheKeys() {
        byte[] expected = bytes(31);
        FakeChunks chunks = new FakeChunks(expected);
        try (ShopImageCache cache = cache(chunks.gateway())) {
            assertArrayEquals(expected, cache.load(ref(expected), ImageVariant.DETAIL).join());
            assertArrayEquals(expected, cache.load(ref(expected), ImageVariant.THUMBNAIL).join());
            assertEquals(2, chunks.calls.get());
        }
    }

    @Test
    void wrongIndexFailsAndAHealthyRetryStartsFresh() {
        byte[] expected = bytes(CHUNK_BYTES + 3);
        FakeChunks chunks = new FakeChunks(expected);
        chunks.wrongIndex = true;
        try (ShopImageCache cache = cache(chunks.gateway())) {
            assertThrows(CompletionException.class,
                    () -> cache.load(ref(expected), ImageVariant.DETAIL).join());
            chunks.wrongIndex = false;
            assertArrayEquals(expected, cache.load(ref(expected), ImageVariant.DETAIL).join());
        }
    }

    @Test
    void badHashLeavesNoPartAndAllowsRetry() throws IOException {
        byte[] expected = bytes(CHUNK_BYTES + 7);
        FakeChunks chunks = new FakeChunks(expected);
        chunks.advertisedHash = "0".repeat(64);
        ImageRef ref = ref(expected);
        try (ShopImageCache cache = cache(chunks.gateway())) {
            assertThrows(CompletionException.class,
                    () -> cache.load(ref, ImageVariant.DETAIL).join());
            try (var files = Files.exists(config().root()) ? Files.list(config().root()) : null) {
                if (files != null) assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".part")));
            }
            chunks.advertisedHash = sha256(expected);
            assertArrayEquals(expected, cache.load(ref, ImageVariant.DETAIL).join());
        }
    }

    @Test
    void changedChunkCountAndMetadataAreRejected() {
        byte[] expected = bytes(CHUNK_BYTES + 11);
        FakeChunks chunks = new FakeChunks(expected);
        chunks.changedCountAfterFirst = true;
        try (ShopImageCache cache = cache(chunks.gateway())) {
            assertThrows(CompletionException.class,
                    () -> cache.load(ref(expected), ImageVariant.THUMBNAIL).join());
            chunks.changedCountAfterFirst = false;
            chunks.wrongMimeAfterFirst = true;
            assertThrows(CompletionException.class,
                    () -> cache.load(ref(expected), ImageVariant.THUMBNAIL).join());
        }
    }

    @Test
    void wrongChunkGeometryAndOversizedMetadataAreRejected() {
        byte[] expected = bytes(101);
        FakeChunks chunks = new FakeChunks(expected);
        chunks.shortChunk = true;
        try (ShopImageCache cache = cache(chunks.gateway())) {
            assertThrows(CompletionException.class,
                    () -> cache.load(ref(expected), ImageVariant.THUMBNAIL).join());
            chunks.shortChunk = false;
            chunks.reportedTotalBytes = 2L * 1024 * 1024 + 1;
            assertThrows(CompletionException.class,
                    () -> cache.load(ref(expected), ImageVariant.THUMBNAIL).join());
        }
    }

    @Test
    void gatewayDecodeFailureDoesNotPoisonRetry() {
        byte[] expected = bytes(29);
        FakeChunks chunks = new FakeChunks(expected);
        chunks.decodeFailure = true;
        try (ShopImageCache cache = cache(chunks.gateway())) {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> cache.load(ref(expected), ImageVariant.DETAIL).join());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            chunks.decodeFailure = false;
            assertArrayEquals(expected, cache.load(ref(expected), ImageVariant.DETAIL).join());
        }
    }

    @Test
    void unknownReferenceSizeStillUsesVerifiedChunkMetadata() {
        byte[] expected = bytes(47);
        FakeChunks chunks = new FakeChunks(expected);
        ImageRef referenceWithoutSize = new ImageRef(41, sha256(expected));
        try (ShopImageCache cache = cache(chunks.gateway())) {
            assertArrayEquals(expected,
                    cache.load(referenceWithoutSize, ImageVariant.DETAIL).join());
        }
    }

    @Test
    void truncatedDiskEntryIsDeletedAndDownloadedAgain() throws IOException {
        byte[] expected = bytes(37);
        FakeChunks chunks = new FakeChunks(expected);
        ShopImageCacheConfig config = config();
        try (ShopImageCache cache = new ShopImageCache(chunks.gateway(), Runnable::run, config)) {
            cache.load(ref(expected), ImageVariant.DETAIL).join();
        }
        Path image;
        try (var files = Files.list(config.root())) {
            image = files.filter(path -> path.getFileName().toString().endsWith(".img"))
                    .findFirst().orElseThrow();
        }
        Files.write(image, new byte[]{1, 2, 3});
        int before = chunks.calls.get();
        try (ShopImageCache cache = new ShopImageCache(chunks.gateway(), Runnable::run, config)) {
            assertArrayEquals(expected, cache.load(ref(expected), ImageVariant.DETAIL).join());
        }
        assertEquals(before + 1, chunks.calls.get());
    }

    @Test
    void closeCancelsQueuedLoadAndRejectsLaterLoads() {
        byte[] expected = bytes(43);
        FakeChunks chunks = new FakeChunks(expected);
        QueuedExecutor executor = new QueuedExecutor();
        ShopImageCache cache = new ShopImageCache(chunks.gateway(), executor, config());
        CompletableFuture<byte[]> pending = cache.load(ref(expected), ImageVariant.DETAIL);
        cache.close();
        executor.runAll();

        assertThrows(CompletionException.class, pending::join);
        assertThrows(CompletionException.class,
                () -> cache.load(ref(expected), ImageVariant.DETAIL).join());
        assertEquals(0, chunks.calls.get());
    }

    @Test
    void pruneDeletesExpiredImagesAndEnforcesDiskLimit() throws IOException {
        byte[] first = bytes(53);
        byte[] second = bytes(71);
        FakeChunks chunks = new FakeChunks(first);
        ShopImageCacheConfig config = new ShopImageCacheConfig(
                temporary.resolve("bounded").resolve("shop"), 2, 1024,
                100, Duration.ofDays(30));
        try (ShopImageCache cache = new ShopImageCache(chunks.gateway(), Runnable::run, config)) {
            cache.load(ref(41, first), ImageVariant.DETAIL).join();
            Path old;
            try (var files = Files.list(config.root())) {
                old = files.filter(path -> path.getFileName().toString().endsWith(".img"))
                        .findFirst().orElseThrow();
            }
            Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(31))));
            chunks.content = second;
            chunks.advertisedHash = sha256(second);
            cache.load(ref(42, second), ImageVariant.DETAIL).join();
            cache.pruneDisk();
        }
        try (var files = Files.list(config.root())) {
            assertEquals(0, files.filter(path -> path.getFileName().toString().endsWith(".img")).count());
        }
    }

    @Test
    void completedLoadAutomaticallyEnforcesDiskLimit() throws IOException {
        byte[] expected = bytes(89);
        FakeChunks chunks = new FakeChunks(expected);
        ShopImageCacheConfig config = new ShopImageCacheConfig(
                temporary.resolve("automatic-bound").resolve("shop"), 2, 1024,
                64, Duration.ofDays(30));
        try (ShopImageCache cache = new ShopImageCache(chunks.gateway(), Runnable::run, config)) {
            assertArrayEquals(expected, cache.load(ref(expected), ImageVariant.DETAIL).join());
        }
        try (var files = Files.list(config.root())) {
            assertEquals(0, files.filter(path -> path.getFileName().toString().endsWith(".img")).count());
        }
    }

    @Test
    void stalePartIsRemovedBeforeAtomicWriteAndCloseCleansParts() throws IOException {
        byte[] expected = bytes(61);
        FakeChunks chunks = new FakeChunks(expected);
        ShopImageCacheConfig config = config();
        Files.createDirectories(config.root());
        String stem = "41-" + sha256(expected) + "-DETAIL";
        Path part = config.root().resolve(stem + ".part");
        Files.write(part, new byte[]{9, 9, 9});
        ShopImageCache cache = new ShopImageCache(chunks.gateway(), Runnable::run, config);
        assertArrayEquals(expected, cache.load(ref(expected), ImageVariant.DETAIL).join());
        assertFalse(Files.exists(part));
        Files.write(part, new byte[]{8});
        cache.close();
        assertFalse(Files.exists(part));
    }

    @Test
    void memoryEntryLimitEvictsEldestWithoutAffectingDiskIntegrity() throws IOException {
        byte[] first = bytes(67);
        byte[] second = bytes(73);
        FakeChunks chunks = new FakeChunks(first);
        ShopImageCacheConfig config = new ShopImageCacheConfig(
                temporary.resolve("memory-bounded").resolve("shop"), 1, 1024,
                4096, Duration.ofDays(30));
        try (ShopImageCache cache = new ShopImageCache(chunks.gateway(), Runnable::run, config)) {
            cache.load(ref(41, first), ImageVariant.DETAIL).join();
            chunks.content = second;
            chunks.advertisedHash = sha256(second);
            cache.load(ref(42, second), ImageVariant.DETAIL).join();
            try (var files = Files.list(config.root())) {
                Path firstDisk = files.filter(path -> path.getFileName().toString().startsWith("41-"))
                        .findFirst().orElseThrow();
                Files.delete(firstDisk);
            }
            chunks.content = first;
            chunks.advertisedHash = sha256(first);
            int before = chunks.calls.get();
            assertArrayEquals(first, cache.load(ref(41, first), ImageVariant.DETAIL).join());
            assertEquals(before + 1, chunks.calls.get());
        }
    }

    @Test
    void configUsesOverrideAndRejectsFilesystemRoot() {
        Properties properties = new Properties();
        properties.setProperty("user.home", temporary.resolve("home").toString());
        Path override = temporary.resolve("override").resolve("..").resolve("shop");
        ShopImageCacheConfig configured = ShopImageCacheConfig.from(
                Map.of("VCAMPUS_SHOP_CACHE_DIR", override.toString()), properties);
        assertEquals(override.toAbsolutePath().normalize(), configured.root());
        assertThrows(IllegalArgumentException.class,
                () -> new ShopImageCacheConfig(temporary.getRoot(), 1, 1, 1, Duration.ofDays(1)));
    }

    @Test
    void cacheRootCannotEscapeThroughAnAncestorSymlink() throws IOException {
        Path outside = temporary.resolve("outside");
        Files.createDirectory(outside);
        Path link = temporary.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic links are unavailable on this platform");
        }
        byte[] expected = bytes(23);
        FakeChunks chunks = new FakeChunks(expected);
        ShopImageCacheConfig config = new ShopImageCacheConfig(link.resolve("cache").resolve("shop"),
                2, 1024, 4096, Duration.ofDays(30));
        try (ShopImageCache cache = new ShopImageCache(chunks.gateway(), Runnable::run, config)) {
            assertThrows(CompletionException.class,
                    () -> cache.load(ref(expected), ImageVariant.DETAIL).join());
        }
        assertFalse(Files.exists(outside.resolve("cache")));
        assertEquals(0, chunks.calls.get());
    }

    private ShopImageCache cache(ShopGateway gateway) {
        return new ShopImageCache(gateway, Runnable::run, config());
    }

    private ShopImageCacheConfig config() {
        return new ShopImageCacheConfig(temporary.resolve("cache").resolve("shop"),
                8, 4L * 1024 * 1024, 16L * 1024 * 1024, Duration.ofDays(30));
    }

    private static ImageRef ref(byte[] bytes) {
        return ref(41, bytes);
    }

    private static ImageRef ref(long id, byte[] bytes) {
        return new ImageRef(id, "image/png", bytes.length, sha256(bytes), 0, true);
    }

    private static byte[] bytes(int length) {
        byte[] value = new byte[length];
        for (int index = 0; index < value.length; index++) value[index] = (byte) (index * 31 + 7);
        return value;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class FakeChunks {
        private volatile byte[] content;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean wrongIndex;
        private volatile String advertisedHash;
        private volatile boolean changedCountAfterFirst;
        private volatile boolean wrongMimeAfterFirst;
        private volatile boolean decodeFailure;
        private volatile boolean shortChunk;
        private volatile long reportedTotalBytes;

        private FakeChunks(byte[] content) {
            this.content = content;
            this.advertisedHash = sha256(content);
        }

        private ShopGateway gateway() {
            return (ShopGateway) Proxy.newProxyInstance(ShopGateway.class.getClassLoader(),
                    new Class<?>[]{ShopGateway.class}, (proxy, method, arguments) -> {
                        if (!method.getName().equals("imageChunk")) throw new UnsupportedOperationException();
                        int requested = (int) arguments[2];
                        calls.incrementAndGet();
                        if (decodeFailure) throw new IllegalArgumentException("invalid Base64");
                        int total = (content.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
                        int from = requested * CHUNK_BYTES;
                        if (from >= content.length) throw new IOException("unexpected chunk");
                        byte[] piece = Arrays.copyOfRange(content, from,
                                Math.min(content.length, from + CHUNK_BYTES));
                        if (shortChunk) piece = Arrays.copyOf(piece, piece.length - 1);
                        long totalBytes = reportedTotalBytes == 0 ? content.length : reportedTotalBytes;
                        int reportedChunks = (int) ((totalBytes + CHUNK_BYTES - 1) / CHUNK_BYTES);
                        return new ImageChunk((long) arguments[0],
                                wrongMimeAfterFirst && requested > 0 ? "image/jpeg" : "image/png",
                                advertisedHash, totalBytes,
                                changedCountAfterFirst && requested > 0 ? total + 1 : reportedChunks,
                                wrongIndex ? requested + 1 : requested, piece);
                    });
        }
    }

    private static final class QueuedExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        private int size() { return tasks.size(); }
        private void runNext() { tasks.remove().run(); }
        private void runAll() { while (!tasks.isEmpty()) runNext(); }
    }
}
