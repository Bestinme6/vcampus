package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.ImageChunk;
import com.vcampus.client.fx.shop.ShopData.ImageRef;
import com.vcampus.client.fx.shop.ShopData.ImageVariant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    void legacySharedPartNameIsNeverClaimedOrDeleted() throws IOException {
        byte[] expected = bytes(61);
        FakeChunks chunks = new FakeChunks(expected);
        ShopImageCacheConfig config = config();
        Files.createDirectories(config.root());
        String stem = "41-" + sha256(expected) + "-DETAIL";
        Path part = config.root().resolve(stem + ".part");
        Files.write(part, new byte[]{9, 9, 9});
        ShopImageCache cache = new ShopImageCache(chunks.gateway(), Runnable::run, config);
        assertArrayEquals(expected, cache.load(ref(expected), ImageVariant.DETAIL).join());
        assertArrayEquals(new byte[]{9, 9, 9}, Files.readAllBytes(part));
        cache.close();
        assertArrayEquals(new byte[]{9, 9, 9}, Files.readAllBytes(part));
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

    @Test
    void prunePreservesUnrelatedFilesThatMerelyUseCacheSuffixes() throws IOException {
        ShopImageCacheConfig config = config();
        Files.createDirectories(config.root());
        Path unrelatedImage = config.root().resolve("notes.img");
        Path unrelatedPart = config.root().resolve("upload.part");
        Files.write(unrelatedImage, new byte[]{1});
        Files.write(unrelatedPart, new byte[]{2});
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofDays(31)));
        Files.setLastModifiedTime(unrelatedImage, old);
        Files.setLastModifiedTime(unrelatedPart, old);

        try (ShopImageCache cache = new ShopImageCache(
                new FakeChunks(bytes(19)).gateway(), Runnable::run, config)) {
            cache.pruneDisk();
        }

        assertArrayEquals(new byte[]{1}, Files.readAllBytes(unrelatedImage));
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(unrelatedPart));
    }

    @Test
    void pruneDeletesOnlyStaleWellFormedTemporaryFiles() throws IOException {
        ShopImageCacheConfig config = config();
        Files.createDirectories(config.root());
        String stem = "41-" + "a".repeat(64) + "-DETAIL.";
        Path stale = config.root().resolve(stem + "0".repeat(32) + ".part");
        Path fresh = config.root().resolve(stem + "1".repeat(32) + ".part");
        Files.write(stale, new byte[]{1});
        Files.write(fresh, new byte[]{2});
        Files.setLastModifiedTime(stale,
                FileTime.from(Instant.now().minus(Duration.ofDays(2))));

        try (ShopImageCache cache = new ShopImageCache(
                new FakeChunks(bytes(19)).gateway(), Runnable::run, config)) {
            cache.pruneDisk();
        }

        assertFalse(Files.exists(stale));
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(fresh));
    }

    @Test
    void initializedOverrideRootCarriesAnOwnershipMarker() {
        byte[] expected = bytes(21);
        ShopImageCacheConfig config = config();
        try (ShopImageCache cache = new ShopImageCache(
                new FakeChunks(expected).gateway(), Runnable::run, config)) {
            cache.load(ref(expected), ImageVariant.DETAIL).join();
        }
        assertTrue(Files.isRegularFile(config.root().resolve(".vcampus-shop-images-v1")));
    }

    @Test
    void separateCacheInstancesCanPublishTheSameImageConcurrently() throws Exception {
        byte[] expected = bytes(83);
        String hash = sha256(expected);
        CyclicBarrier bothDownloading = new CyclicBarrier(2);
        AtomicInteger calls = new AtomicInteger();
        ShopGateway gateway = chunkGateway((imageId, variant, chunkIndex) -> {
            calls.incrementAndGet();
            bothDownloading.await(5, TimeUnit.SECONDS);
            return new ImageChunk(imageId, "image/png", hash, expected.length,
                    1, 0, expected);
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (ShopImageCache first = new ShopImageCache(gateway, workers, config());
             ShopImageCache second = new ShopImageCache(gateway, workers, config())) {
            CompletableFuture<byte[]> firstLoad = first.load(ref(expected), ImageVariant.DETAIL);
            CompletableFuture<byte[]> secondLoad = second.load(ref(expected), ImageVariant.DETAIL);
            assertArrayEquals(expected, firstLoad.orTimeout(5, TimeUnit.SECONDS).join());
            assertArrayEquals(expected, secondLoad.orTimeout(5, TimeUnit.SECONDS).join());
            assertEquals(2, calls.get());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
        try (var files = Files.list(config().root())) {
            assertEquals(1, files.filter(path -> path.getFileName().toString().endsWith(".img")).count());
        }
    }

    @Test
    void closeDuringNetworkLeavesNoImageOrTemporaryFile() throws Exception {
        byte[] expected = bytes(97);
        String hash = sha256(expected);
        CountDownLatch enteredNetwork = new CountDownLatch(1);
        CountDownLatch releaseNetwork = new CountDownLatch(1);
        ShopGateway gateway = chunkGateway((imageId, variant, chunkIndex) -> {
            enteredNetwork.countDown();
            if (!releaseNetwork.await(5, TimeUnit.SECONDS)) throw new IOException("timeout");
            return new ImageChunk(imageId, "image/png", hash, expected.length,
                    1, 0, expected);
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ShopImageCache cache = new ShopImageCache(gateway, worker, config());
        CompletableFuture<byte[]> load = cache.load(ref(expected), ImageVariant.DETAIL);
        assertTrue(enteredNetwork.await(5, TimeUnit.SECONDS));
        cache.close();
        releaseNetwork.countDown();
        assertThrows(CompletionException.class, () -> load.orTimeout(5, TimeUnit.SECONDS).join());
        worker.shutdown();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        if (Files.exists(config().root())) {
            try (var files = Files.list(config().root())) {
                assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".img")
                        || path.getFileName().toString().endsWith(".part")));
            }
        }
    }

    @Test
    void unsupportedAtomicMoveFallsBackToNonReplacingMove() {
        byte[] expected = bytes(107);
        AtomicInteger atomicCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        ShopImageCache.MoveOperations moves = new ShopImageCache.MoveOperations() {
            @Override public void atomicMove(Path source, Path target) throws IOException {
                atomicCalls.incrementAndGet();
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "forced");
            }
            @Override public void moveNew(Path source, Path target) throws IOException {
                fallbackCalls.incrementAndGet();
                Files.move(source, target);
            }
        };
        try (ShopImageCache cache = new ShopImageCache(new FakeChunks(expected).gateway(),
                Runnable::run, config(), moves)) {
            assertArrayEquals(expected, cache.load(ref(expected), ImageVariant.DETAIL).join());
        }
        assertEquals(1, atomicCalls.get());
        assertEquals(1, fallbackCalls.get());
    }

    @Test
    void targetCreatedDuringMoveIsValidatedAndReturned() {
        byte[] expected = bytes(109);
        ShopImageCache.MoveOperations racingMoves = new ShopImageCache.MoveOperations() {
            @Override public void atomicMove(Path source, Path target) throws IOException {
                Files.copy(source, target);
                throw new FileAlreadyExistsException(target.toString());
            }
            @Override public void moveNew(Path source, Path target) throws IOException {
                fail("fallback must not run for a target-created race");
            }
        };
        FakeChunks chunks = new FakeChunks(expected);
        try (ShopImageCache cache = new ShopImageCache(chunks.gateway(), Runnable::run,
                config(), racingMoves)) {
            assertArrayEquals(expected, cache.load(ref(expected), ImageVariant.DETAIL).join());
        }
        int before = chunks.calls.get();
        try (ShopImageCache cache = cache(chunks.gateway())) {
            assertArrayEquals(expected, cache.load(ref(expected), ImageVariant.DETAIL).join());
        }
        assertEquals(before, chunks.calls.get());
    }

    @Test
    void targetCreatedDuringMoveMustMatchTheDownloadedImage() {
        byte[] expected = bytes(111);
        byte[] different = bytes(112);
        ShopImageCache.MoveOperations invalidWinner = new ShopImageCache.MoveOperations() {
            @Override public void atomicMove(Path source, Path target) throws IOException {
                writeCacheContainer(target, different);
                throw new FileAlreadyExistsException(target.toString());
            }
            @Override public void moveNew(Path source, Path target) throws IOException {
                fail("fallback must not run for a target-created race");
            }
        };
        FakeChunks chunks = new FakeChunks(expected);
        try (ShopImageCache cache = new ShopImageCache(chunks.gateway(), Runnable::run,
                config(), invalidWinner)) {
            assertThrows(CompletionException.class,
                    () -> cache.load(ref(expected), ImageVariant.DETAIL).join());
        }
        assertEquals(0, imageCountUnchecked(config().root()));
        try (ShopImageCache cache = cache(chunks.gateway())) {
            assertArrayEquals(expected, cache.load(ref(expected), ImageVariant.DETAIL).join());
        }
    }

    @Test
    void closeAndPruneDoNotDeleteAnActivelyOwnedPart() throws Exception {
        byte[] expected = bytes(113);
        CountDownLatch moveStarted = new CountDownLatch(1);
        CountDownLatch allowMove = new CountDownLatch(1);
        ShopImageCache.MoveOperations blockingMoves = new ShopImageCache.MoveOperations() {
            @Override public void atomicMove(Path source, Path target) throws IOException {
                moveStarted.countDown();
                try {
                    if (!allowMove.await(5, TimeUnit.SECONDS)) throw new IOException("timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", interrupted);
                }
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
            @Override public void moveNew(Path source, Path target) throws IOException {
                Files.move(source, target);
            }
        };
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ShopImageCache owner = new ShopImageCache(new FakeChunks(expected).gateway(), worker,
                config(), blockingMoves);
        CompletableFuture<byte[]> load = owner.load(ref(expected), ImageVariant.DETAIL);
        assertTrue(moveStarted.await(5, TimeUnit.SECONDS));
        assertEquals(1, partCount(config().root()));
        try (ShopImageCache maintainer = cache(new FakeChunks(expected).gateway())) {
            maintainer.pruneDisk();
        }
        assertEquals(1, partCount(config().root()));
        owner.close();
        assertEquals(1, partCount(config().root()));
        allowMove.countDown();
        assertThrows(CompletionException.class, () -> load.orTimeout(5, TimeUnit.SECONDS).join());
        worker.shutdown();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, partCount(config().root()));
        assertEquals(1, imageCount(config().root()));
        FakeChunks readerChunks = new FakeChunks(expected);
        try (ShopImageCache reader = cache(readerChunks.gateway())) {
            assertArrayEquals(expected, reader.load(ref(expected), ImageVariant.DETAIL).join());
        }
        assertEquals(0, readerChunks.calls.get());
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

    private static long partCount(Path root) throws IOException {
        try (var files = Files.list(root)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".part")).count();
        }
    }

    private static long imageCount(Path root) throws IOException {
        try (var files = Files.list(root)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".img")).count();
        }
    }

    private static long imageCountUnchecked(Path root) {
        try {
            return imageCount(root);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void writeCacheContainer(Path target, byte[] bytes) throws IOException {
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(target))) {
            output.writeInt(0x56434931);
            output.writeInt(bytes.length);
            output.write(HexFormat.of().parseHex(sha256(bytes)));
            output.write(bytes);
        }
    }

    private static ShopGateway chunkGateway(ChunkCall call) {
        return (ShopGateway) Proxy.newProxyInstance(ShopGateway.class.getClassLoader(),
                new Class<?>[]{ShopGateway.class}, (proxy, method, arguments) -> {
                    if (!method.getName().equals("imageChunk")) throw new UnsupportedOperationException();
                    return call.get((long) arguments[0], (ImageVariant) arguments[1], (int) arguments[2]);
                });
    }

    @FunctionalInterface
    private interface ChunkCall {
        ImageChunk get(long imageId, ImageVariant variant, int chunkIndex) throws Exception;
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
