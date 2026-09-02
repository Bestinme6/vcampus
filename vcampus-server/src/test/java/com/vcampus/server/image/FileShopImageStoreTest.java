package com.vcampus.server.image;

import com.vcampus.server.config.ShopImageConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import static com.vcampus.server.image.ShopImageStore.FinalizedImage;
import static com.vcampus.server.image.ShopImageStore.UploadTicket;
import static com.vcampus.server.image.ShopImageStore.UploadedImage;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileShopImageStoreTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void rejectsUnsafeStorageKeysAndSymlinkedStorageDirectories() throws Exception {
        FileShopImageStore store = store(defaultConfig(), Clock.fixed(NOW, ZoneOffset.UTC));

        for (String key : new String[]{"../secret", ".", "..", "folder/image", "folder\\image", "C:image", "a\0b"}) {
            ShopImageException error = assertThrows(ShopImageException.class, () -> store.open(key), key);
            assertEquals("INVALID_STORAGE_KEY", error.code());
            assertEquals("图片存储键无效", error.getMessage());
        }
        assertFalse(Files.exists(tempDir.resolve("secret")));
    }

    @Test
    void chunksAreOrderedBoundedAndBoundToOwner() throws Exception {
        byte[] png = png(48, 32, true);
        FileShopImageStore store = store(config(128, 32, 20_000_000, Duration.ofMinutes(30)), fixedClock());
        UploadTicket ticket = store.startUpload(9L, 4L, "image/png", png.length);

        store.appendChunk(9L, ticket.uploadId(), 0, Arrays.copyOfRange(png, 0, 32));
        assertCode("UPLOAD_FORBIDDEN", () -> store.appendChunk(8L, ticket.uploadId(), 1, new byte[]{1}));
        assertCode("INVALID_CHUNK_ORDER", () -> store.appendChunk(9L, ticket.uploadId(), 0, new byte[]{1}));
        assertCode("CHUNK_TOO_LARGE", () -> store.appendChunk(9L, ticket.uploadId(), 1, new byte[33]));
        assertCode("IMAGE_TOO_LARGE", () -> store.startUpload(9L, 4L, "image/png", 129));
    }

    @Test
    void expectedSizeMustMatchAndExpiredUploadsCannotContinue() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        FileShopImageStore store = store(config(1024, 1024, 20_000_000, Duration.ofSeconds(5)), clock);
        UploadTicket mismatch = store.startUpload(9L, 4L, "image/png", 10);
        assertCode("SIZE_MISMATCH", () -> store.completeUpload(9L, mismatch.uploadId()));

        UploadTicket expired = store.startUpload(9L, 4L, "image/png", 10);
        clock.advance(Duration.ofSeconds(6));
        assertCode("UPLOAD_EXPIRED", () -> store.appendChunk(9L, expired.uploadId(), 0, new byte[10]));
    }

    @Test
    void rejectsTinyChunkAbuseAndRequiresTheExactFinalRemainder() {
        FileShopImageStore store = store(config(1024, 32, 20_000_000, Duration.ofMinutes(30)), fixedClock());
        UploadTicket ticket = store.startUpload(9L, 4L, "image/png", 70);

        assertCode("INVALID_CHUNK_SIZE", () -> store.appendChunk(9L, ticket.uploadId(), 0, new byte[1]));
        store.appendChunk(9L, ticket.uploadId(), 0, new byte[32]);
        assertCode("INVALID_CHUNK_SIZE", () -> store.appendChunk(9L, ticket.uploadId(), 1, new byte[31]));
        store.appendChunk(9L, ticket.uploadId(), 1, new byte[32]);
        assertCode("INVALID_CHUNK_SIZE", () -> store.appendChunk(9L, ticket.uploadId(), 2, new byte[5]));
        store.appendChunk(9L, ticket.uploadId(), 2, new byte[6]);
        assertCode("INVALID_CHUNK_ORDER", () -> store.appendChunk(9L, ticket.uploadId(), 3, new byte[1]));
    }

    @Test
    void completionRejectsExtendedOrReplacedPartFiles() throws Exception {
        byte[] png = png(16, 16, true);
        Path root = tempDir.resolve("extended-store");
        FileShopImageStore extended = storeAt(root, fixedClock());
        UploadTicket extendedTicket = extended.startUpload(9L, 4L, "image/png", png.length);
        extended.appendChunk(9L, extendedTicket.uploadId(), 0, png);
        Files.write(root.resolve("temp").resolve(extendedTicket.uploadId() + ".part"),
                new byte[]{1}, StandardOpenOption.APPEND);
        assertCode("STORAGE_BOUNDARY", () -> extended.completeUpload(9L, extendedTicket.uploadId()));

        Path replacedRoot = tempDir.resolve("replaced-store");
        FileShopImageStore replaced = storeAt(replacedRoot, fixedClock());
        UploadTicket replacedTicket = replaced.startUpload(9L, 4L, "image/png", png.length);
        replaced.appendChunk(9L, replacedTicket.uploadId(), 0, png);
        Path part = replacedRoot.resolve("temp").resolve(replacedTicket.uploadId() + ".part");
        byte[] original = Files.readAllBytes(part);
        original[original.length - 1] ^= 0x01;
        Files.delete(part);
        Files.write(part, original, StandardOpenOption.CREATE_NEW);
        assertCode("STORAGE_BOUNDARY", () -> replaced.completeUpload(9L, replacedTicket.uploadId()));
    }

    @Test
    void completionBoundaryFailureReleasesItsReservation() throws Exception {
        Path root = tempDir.resolve("completion-boundary-release");
        FileShopImageStore store = new FileShopImageStore(defaultLimits(root), fixedClock(),
                new ShopImageConfig.ResourceLimits(1, 2 * 1024 * 1024, 1, 1));
        byte[] image = png(8, 8, true);
        UploadTicket ticket = store.startUpload(9L, 4L, "image/png", image.length);
        store.appendChunk(9L, ticket.uploadId(), 0, image);
        Path originalTemp = root.resolve("temp");
        Path displacedTemp = root.resolve("displaced-temp");
        Files.move(originalTemp, displacedTemp);
        Files.createDirectory(originalTemp);

        assertCode("STORAGE_BOUNDARY", () -> store.completeUpload(9L, ticket.uploadId()));

        Files.delete(originalTemp);
        Files.move(displacedTemp, originalTemp);
        store.startUpload(9L, 5L, "image/png", 10);
    }

    @Test
    void appendBoundaryFailureReleasesItsReservation() throws Exception {
        Path root = tempDir.resolve("append-boundary-release");
        FileShopImageStore store = new FileShopImageStore(defaultLimits(root), fixedClock(),
                new ShopImageConfig.ResourceLimits(1, 2 * 1024 * 1024, 1, 1));
        UploadTicket ticket = store.startUpload(9L, 4L, "image/png", 10);
        Files.write(root.resolve("temp").resolve(ticket.uploadId() + ".part"), new byte[]{1},
                StandardOpenOption.APPEND);

        assertCode("STORAGE_BOUNDARY", () -> store.appendChunk(9L, ticket.uploadId(), 0, new byte[10]));

        store.startUpload(9L, 5L, "image/png", 10);
    }

    @Test
    void recoverableAppendErrorRetainsItsReservation() {
        Path root = tempDir.resolve("recoverable-append-reservation");
        FileShopImageStore store = new FileShopImageStore(defaultLimits(root), fixedClock(),
                new ShopImageConfig.ResourceLimits(1, 2 * 1024 * 1024, 1, 1));
        UploadTicket ticket = store.startUpload(9L, 4L, "image/png", 10);

        assertCode("INVALID_CHUNK_ORDER", () -> store.appendChunk(9L, ticket.uploadId(), 1, new byte[10]));

        assertCode("UPLOAD_QUOTA_EXCEEDED", () -> store.startUpload(9L, 5L, "image/png", 10));
    }

    @Test
    void rejectsSymlinkedAncestorAndUploadLeafWhenLinksAreAvailable() throws Exception {
        Path target = tempDir.resolve("symlink-target");
        Path link = tempDir.resolve("symlink-parent");
        Files.createDirectories(target);
        createSymlinkOrSkip(link, target);
        ShopImageException rootError = assertThrows(ShopImageException.class,
                () -> new FileShopImageStore(new ShopImageConfig(link.resolve("images"),
                        1024, 128, 20_000_000, Duration.ofMinutes(30)), fixedClock()));
        assertEquals("STORAGE_BOUNDARY", rootError.code());

        Path safeRoot = tempDir.resolve("leaf-store");
        FileShopImageStore store = storeAt(safeRoot, fixedClock());
        byte[] png = png(8, 8, true);
        UploadTicket ticket = store.startUpload(9L, 4L, "image/png", png.length);
        Path part = safeRoot.resolve("temp").resolve(ticket.uploadId() + ".part");
        Path outside = tempDir.resolve("outside.part");
        Files.write(outside, new byte[0]);
        Files.delete(part);
        createSymlinkOrSkip(part, outside);
        assertCode("STORAGE_BOUNDARY", () -> store.appendChunk(9L, ticket.uploadId(), 0, png));
    }

    @Test
    void directorySwapIsDetectedBeforeAWrite() throws Exception {
        Path root = tempDir.resolve("swapped-store");
        FileShopImageStore store = storeAt(root, fixedClock());
        byte[] png = png(8, 8, true);
        UploadTicket ticket = store.startUpload(9L, 4L, "image/png", png.length);
        Files.move(root.resolve("temp"), root.resolve("old-temp"));
        Files.createDirectory(root.resolve("temp"));

        assertCode("STORAGE_BOUNDARY", () -> store.appendChunk(9L, ticket.uploadId(), 0, png));
    }

    @Test
    void validPngAndJpegAreNormalizedHashedAndThumbnailed() throws Exception {
        FileShopImageStore store = store(defaultConfig(), fixedClock());

        UploadedImage alpha = complete(store, 9L, 4L, "image/png", png(1200, 800, true));
        assertEquals("image/png", alpha.mimeType());
        assertEquals(1200, alpha.width());
        assertEquals(800, alpha.height());
        assertEquals(64, alpha.sha256().length());
        assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(alpha.normalizedBytes())));
        FinalizedImage alphaFiles = store.finalizeUpload(9L, alpha.uploadId());
        assertNotEquals(alphaFiles.storageKey(), alphaFiles.thumbnailStorageKey());
        try (InputStream input = store.open(alphaFiles.thumbnailStorageKey())) {
            BufferedImage thumbnail = ImageIO.read(input);
            assertTrue(thumbnail.getWidth() <= 480);
            assertTrue(thumbnail.getHeight() <= 480);
            assertEquals(480, thumbnail.getWidth());
            assertEquals(320, thumbnail.getHeight());
        }

        byte[] jpeg = jpeg(64, 96);
        UploadedImage first = complete(store, 9L, 5L, "image/jpeg", jpeg);
        UploadedImage second = complete(store, 9L, 5L, "image/jpg", jpeg);
        assertEquals("image/jpeg", first.mimeType());
        assertEquals(first.sha256(), second.sha256());
        assertArrayEquals(first.normalizedBytes(), second.normalizedBytes());
    }

    @Test
    void rejectsFakeTruncatedGifMimeMismatchAndOversizedDimensions() throws Exception {
        FileShopImageStore store = store(defaultConfig(), fixedClock());

        assertCompleteCode(store, "INVALID_IMAGE", "image/png", "not an image".getBytes());
        assertCompleteCode(store, "INVALID_IMAGE", "image/jpeg", new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
        assertCompleteCode(store, "UNSUPPORTED_IMAGE", "image/png", gif());
        assertCompleteCode(store, "MIME_MISMATCH", "image/jpeg", png(1, 1, true));
        assertCompleteCode(store, "PIXEL_LIMIT", "image/png", pngWithDimensions(20_000_001, 1));
    }

    @Test
    void finalizeOpenDeleteAndPartialFailureAreSafe() throws Exception {
        FileShopImageStore store = store(defaultConfig(), fixedClock());
        UploadedImage image = complete(store, 9L, 4L, "image/png", png(32, 32, true));
        FinalizedImage files = store.finalizeUpload(9L, image.uploadId());
        byte[] opened;
        try (InputStream input = store.open(files.storageKey())) {
            opened = input.readAllBytes();
        }
        assertArrayEquals(image.normalizedBytes(), opened);
        assertTrue(store.deleteIfExists(files.storageKey()));
        assertFalse(store.deleteIfExists(files.storageKey()));

        AtomicInteger moves = new AtomicInteger();
        FileShopImageStore failing = new FileShopImageStore(defaultConfig(), fixedClock(),
                new ShopImageConfig.ResourceLimits(1, 2 * 1024 * 1024, 1, 1)) {
            @Override
            protected void moveAtomically(Path source, Path target) throws IOException {
                if (moves.incrementAndGet() == 2) {
                    throw new IOException("simulated second move failure");
                }
                super.moveAtomically(source, target);
            }
        };
        UploadedImage doomed = complete(failing, 7L, 8L, "image/png", png(16, 16, true));
        assertCode("STORAGE_ERROR", () -> failing.finalizeUpload(7L, doomed.uploadId()));
        failing.startUpload(7L, 9L, "image/png", 10);
        try (var filesOnDisk = Files.list(tempDir.resolve("files"))) {
            assertEquals(1, filesOnDisk.count(), "only the first store's thumbnail remains");
        }
    }

    @Test
    void descriptorAndRangeReadsReturnTruthfulBoundedSlices() throws Exception {
        FileShopImageStore store = store(defaultConfig(), fixedClock());
        UploadedImage image = complete(store, 9L, 4L, "image/png", png(64, 64, true));
        FinalizedImage files = store.finalizeUpload(9L, image.uploadId());

        var descriptor = store.describe(files.storageKey());
        var middle = store.readRange(files.storageKey(), 7, 19);
        var tail = store.readRange(files.storageKey(), image.normalizedBytes().length - 5L, 19);

        assertEquals(image.normalizedBytes().length, descriptor.totalBytes());
        assertEquals(image.sha256(), descriptor.sha256());
        assertEquals(image.normalizedBytes().length, middle.totalBytes());
        assertArrayEquals(Arrays.copyOfRange(image.normalizedBytes(), 7, 26), middle.content());
        assertArrayEquals(Arrays.copyOfRange(image.normalizedBytes(),
                image.normalizedBytes().length - 5, image.normalizedBytes().length), tail.content());
        assertCode("INVALID_RANGE", () -> store.readRange(files.storageKey(), -1, 1));
        assertCode("INVALID_RANGE", () -> store.readRange(files.storageKey(), 0, 192 * 1024 + 1));
    }

    @Test
    void rangeReadsRejectUnsafeKeys() {
        Path root = tempDir.resolve("range-boundary-store");
        FileShopImageStore store = storeAt(root, fixedClock());
        assertCode("INVALID_STORAGE_KEY", () -> store.readRange("../secret", 0, 1));
    }

    @Test
    void rangeReadsRetainNoFollowProtectionWhenLinksAreAvailable() throws Exception {
        Path root = tempDir.resolve("range-symlink-store");
        FileShopImageStore store = storeAt(root, fixedClock());
        Path outside = tempDir.resolve("outside-range.bin");
        Files.write(outside, new byte[]{1, 2, 3});
        Path link = root.resolve("files").resolve("safe-range-link");
        createSymlinkOrSkip(link, outside);
        assertCode("STORAGE_BOUNDARY", () -> store.readRange("safe-range-link", 0, 1));
    }

    @Test
    void finalizationRetriesKeyCollisionsWithoutOverwritingAndSupportsFallbackMove() throws Exception {
        Path collisionRoot = tempDir.resolve("collision-store");
        String occupied = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String freshFull = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String freshThumbnail = "cccccccccccccccccccccccccccccccc";
        ArrayDeque<String> keys = new ArrayDeque<>(java.util.List.of(occupied, freshThumbnail,
                freshFull, freshThumbnail));
        FileShopImageStore collisionStore = new FileShopImageStore(
                new ShopImageConfig(collisionRoot, 2 * 1024 * 1024, 192 * 1024,
                        20_000_000, Duration.ofMinutes(30)), fixedClock()) {
            @Override
            protected String newStorageKey() {
                return keys.removeFirst();
            }
        };
        byte[] sentinel = "existing".getBytes();
        Files.write(collisionRoot.resolve("files").resolve(occupied), sentinel, StandardOpenOption.CREATE_NEW);
        UploadedImage collisionImage = complete(collisionStore, 9L, 4L, "image/png", png(8, 8, true));
        FinalizedImage collisionFiles = collisionStore.finalizeUpload(9L, collisionImage.uploadId());
        assertEquals(freshFull, collisionFiles.storageKey());
        assertArrayEquals(sentinel, Files.readAllBytes(collisionRoot.resolve("files").resolve(occupied)));

        Path fallbackRoot = tempDir.resolve("fallback-store");
        AtomicInteger atomicAttempts = new AtomicInteger();
        AtomicInteger fallbackAttempts = new AtomicInteger();
        FileShopImageStore fallbackStore = new FileShopImageStore(defaultLimits(fallbackRoot), fixedClock()) {
            @Override
            protected void moveFile(Path source, Path target, boolean atomic) throws IOException {
                if (atomic) {
                    atomicAttempts.incrementAndGet();
                    throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test");
                }
                fallbackAttempts.incrementAndGet();
                super.moveFile(source, target, false);
            }
        };
        FinalizedImage fallbackFiles = finalize(fallbackStore, 9L, 5L, png(8, 8, true));
        assertEquals(2, atomicAttempts.get());
        assertEquals(2, fallbackAttempts.get());
        assertReadable(fallbackStore, fallbackFiles.storageKey());
    }

    @Test
    void cleanupKeepsReferencesAndYoungFilesButRemovesOldOrphansAndExpiredTemps() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        FileShopImageStore store = store(config(2 * 1024 * 1024, 192 * 1024,
                20_000_000, Duration.ofMinutes(30)), clock);
        FinalizedImage referenced = finalize(store, 9L, 4L, png(8, 8, true));
        FinalizedImage oldOrphan = finalize(store, 9L, 5L, png(9, 9, true));
        FinalizedImage youngOrphan = finalize(store, 9L, 6L, png(10, 10, true));
        Instant old = NOW.minus(Duration.ofHours(25));
        setModified(referenced, old);
        setModified(oldOrphan, old);

        UploadTicket expired = store.startUpload(9L, 7L, "image/png", 20);
        store.appendChunk(9L, expired.uploadId(), 0, new byte[20]);
        clock.advance(Duration.ofHours(1));
        store.cleanup(Set.of(referenced.storageKey(), referenced.thumbnailStorageKey()), clock.instant());

        assertReadable(store, referenced.storageKey());
        assertCode("IMAGE_NOT_FOUND", () -> store.open(oldOrphan.storageKey()));
        assertReadable(store, youngOrphan.storageKey());
        assertCode("UPLOAD_NOT_FOUND", () -> store.completeUpload(9L, expired.uploadId()));
    }

    @Test
    void concurrentDuplicateAppendHasExactlyOneWinner() throws Exception {
        byte[] png = png(32, 32, true);
        FileShopImageStore store = store(defaultConfig(), fixedClock());
        UploadTicket ticket = store.startUpload(9L, 4L, "image/png", png.length);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        Set<String> failures = java.util.Collections.synchronizedSet(new HashSet<>());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> one = executor.submit(() -> appendAtOnce(store, ticket, png, start, successes, failures));
            Future<?> two = executor.submit(() -> appendAtOnce(store, ticket, png, start, successes, failures));
            start.countDown();
            one.get();
            two.get();
        }

        assertEquals(1, successes.get());
        assertEquals(Set.of("INVALID_CHUNK_ORDER"), failures);
        assertEquals(64, store.completeUpload(9L, ticket.uploadId()).sha256().length());
    }

    @Test
    void sessionCountOwnerAndReservedByteQuotasAreAtomicAndExpireCleanly() {
        MutableClock clock = new MutableClock(NOW);
        Path globalRoot = tempDir.resolve("global-quota");
        ShopImageConfig globalConfig = new ShopImageConfig(globalRoot, 1024, 128,
                20_000_000, Duration.ofSeconds(5));
        FileShopImageStore global = new FileShopImageStore(globalConfig, clock,
                new ShopImageConfig.ResourceLimits(2, 1024, 2, 1));
        global.startUpload(1L, 1L, "image/png", 10);
        global.startUpload(1L, 2L, "image/png", 10);
        assertCode("UPLOAD_QUOTA_EXCEEDED", () -> global.startUpload(2L, 3L, "image/png", 10));
        clock.advance(Duration.ofSeconds(6));
        global.cleanup(Set.of(), clock.instant());
        global.startUpload(2L, 3L, "image/png", 10);

        Path ownerRoot = tempDir.resolve("owner-quota");
        FileShopImageStore owner = new FileShopImageStore(defaultLimits(ownerRoot), fixedClock(),
                new ShopImageConfig.ResourceLimits(3, 1024, 1, 1));
        UploadTicket retained = owner.startUpload(7L, 1L, "image/png", 10);
        assertCode("INVALID_CHUNK_ORDER",
                () -> owner.appendChunk(7L, retained.uploadId(), 1, new byte[10]));
        assertCode("UPLOAD_QUOTA_EXCEEDED", () -> owner.startUpload(7L, 2L, "image/png", 10));
        owner.startUpload(8L, 2L, "image/png", 10);

        Path bytesRoot = tempDir.resolve("byte-quota");
        FileShopImageStore bytes = new FileShopImageStore(defaultLimits(bytesRoot), fixedClock(),
                new ShopImageConfig.ResourceLimits(3, 15, 3, 1));
        bytes.startUpload(1L, 1L, "image/png", 10);
        assertCode("UPLOAD_QUOTA_EXCEEDED", () -> bytes.startUpload(2L, 2L, "image/png", 6));
    }

    @Test
    void validationFailureAndSuccessfulFinalizationReleaseReservationsExactlyOnce() throws Exception {
        ShopImageConfig.ResourceLimits oneSession =
                new ShopImageConfig.ResourceLimits(1, 2 * 1024 * 1024, 1, 1);
        Path invalidRoot = tempDir.resolve("invalid-release");
        FileShopImageStore invalid = new FileShopImageStore(defaultLimits(invalidRoot), fixedClock(), oneSession);
        byte[] fake = "not an image".getBytes();
        UploadTicket bad = invalid.startUpload(1L, 1L, "image/png", fake.length);
        invalid.appendChunk(1L, bad.uploadId(), 0, fake);
        assertCode("INVALID_IMAGE", () -> invalid.completeUpload(1L, bad.uploadId()));
        invalid.startUpload(1L, 2L, "image/png", 10);

        Path finalizedRoot = tempDir.resolve("finalized-release");
        FileShopImageStore finalized = new FileShopImageStore(
                defaultLimits(finalizedRoot), fixedClock(), oneSession);
        finalize(finalized, 1L, 1L, png(8, 8, true));
        finalized.startUpload(1L, 2L, "image/png", 10);
    }

    @Test
    void concurrentCompletionIsBackpressuredWithoutHoldingSessionMonitors() throws Exception {
        Path root = tempDir.resolve("processing-limit");
        CountDownLatch acquireAttempts = new CountDownLatch(2);
        CountDownLatch firstPermit = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean blockFirst = new AtomicBoolean(true);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        FileShopImageStore store = new FileShopImageStore(defaultLimits(root), fixedClock(),
                new ShopImageConfig.ResourceLimits(4, 8L * 1024 * 1024, 4, 1)) {
            @Override
            protected void beforeProcessingPermitAcquire(String uploadId) {
                acquireAttempts.countDown();
            }

            @Override
            protected void onProcessingPermitAcquired(String uploadId) throws InterruptedException {
                int now = active.incrementAndGet();
                maximum.accumulateAndGet(now, Math::max);
                if (blockFirst.compareAndSet(true, false)) {
                    firstPermit.countDown();
                    releaseFirst.await();
                }
            }

            @Override
            protected void onProcessingPermitReleased(String uploadId) {
                active.decrementAndGet();
            }
        };
        byte[] png = png(32, 32, true);
        UploadTicket first = store.startUpload(1L, 1L, "image/png", png.length);
        UploadTicket second = store.startUpload(2L, 2L, "image/png", png.length);
        store.appendChunk(1L, first.uploadId(), 0, png);
        store.appendChunk(2L, second.uploadId(), 0, png);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<UploadedImage> firstResult = executor.submit(
                    () -> store.completeUpload(1L, first.uploadId()));
            assertTrue(firstPermit.await(5, TimeUnit.SECONDS));
            Future<UploadedImage> secondResult = executor.submit(
                    () -> store.completeUpload(2L, second.uploadId()));
            assertTrue(acquireAttempts.await(5, TimeUnit.SECONDS));
            assertFalse(secondResult.isDone());
            assertEquals(1, maximum.get());
            releaseFirst.countDown();
            assertEquals(64, firstResult.get(5, TimeUnit.SECONDS).sha256().length());
            assertEquals(64, secondResult.get(5, TimeUnit.SECONDS).sha256().length());
        }
        assertEquals(0, active.get());
        assertEquals(1, maximum.get());
    }

    @Test
    void queuedCompletionExpiresBeforeProcessingAndReleasesItsReservation() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        Path root = tempDir.resolve("queued-expiry");
        CountDownLatch firstPermit = new CountDownLatch(1);
        CountDownLatch secondWaiter = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        AtomicInteger processingStarts = new AtomicInteger();
        ShopImageConfig config = new ShopImageConfig(root, 2 * 1024 * 1024, 192 * 1024,
                20_000_000, Duration.ofSeconds(5));
        FileShopImageStore store = new FileShopImageStore(config, clock,
                new ShopImageConfig.ResourceLimits(2, 4L * 1024 * 1024, 2, 1)) {
            @Override
            protected void beforeProcessingPermitAcquire(String uploadId) {
                if (!first.get()) {
                    secondWaiter.countDown();
                }
            }

            @Override
            protected void onProcessingPermitAcquired(String uploadId) throws InterruptedException {
                processingStarts.incrementAndGet();
                if (first.compareAndSet(true, false)) {
                    firstPermit.countDown();
                    releaseFirst.await();
                }
            }
        };
        byte[] image = png(32, 32, true);
        UploadTicket firstTicket = store.startUpload(1L, 1L, "image/png", image.length);
        UploadTicket queuedTicket = store.startUpload(2L, 2L, "image/png", image.length);
        store.appendChunk(1L, firstTicket.uploadId(), 0, image);
        store.appendChunk(2L, queuedTicket.uploadId(), 0, image);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<UploadedImage> firstResult = executor.submit(
                    () -> store.completeUpload(1L, firstTicket.uploadId()));
            assertTrue(firstPermit.await(5, TimeUnit.SECONDS));
            Future<UploadedImage> queuedResult = executor.submit(
                    () -> store.completeUpload(2L, queuedTicket.uploadId()));
            assertTrue(secondWaiter.await(5, TimeUnit.SECONDS));
            clock.advance(Duration.ofSeconds(6));
            releaseFirst.countDown();

            assertEquals(64, firstResult.get(5, TimeUnit.SECONDS).sha256().length());
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> queuedResult.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof ShopImageException);
            assertEquals("UPLOAD_EXPIRED", ((ShopImageException) failure.getCause()).code());
        }
        assertEquals(1, processingStarts.get());
        store.startUpload(2L, 3L, "image/png", 10);
    }

    private FileShopImageStore store(ShopImageConfig config, Clock clock) {
        return new FileShopImageStore(config, clock);
    }

    private ShopImageConfig defaultConfig() {
        return config(2 * 1024 * 1024, 192 * 1024, 20_000_000, Duration.ofMinutes(30));
    }

    private ShopImageConfig defaultLimits(Path root) {
        return new ShopImageConfig(root, 2 * 1024 * 1024, 192 * 1024,
                20_000_000, Duration.ofMinutes(30));
    }

    private ShopImageConfig config(long maxBytes, int chunkBytes, long maxPixels, Duration ttl) {
        return new ShopImageConfig(tempDir, maxBytes, chunkBytes, maxPixels, ttl);
    }

    private FileShopImageStore storeAt(Path root, Clock clock) {
        return new FileShopImageStore(defaultLimits(root), clock);
    }

    private static void createSymlinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "symbolic links unavailable: " + exception.getClass().getSimpleName());
        } catch (IOException exception) {
            Assumptions.assumeTrue(false, "symbolic links unavailable: " + exception.getMessage());
        }
    }

    private Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static UploadedImage complete(FileShopImageStore store, long owner, long product,
                                          String mime, byte[] bytes) {
        UploadTicket ticket = store.startUpload(owner, product, mime, bytes.length);
        store.appendChunk(owner, ticket.uploadId(), 0, bytes);
        return store.completeUpload(owner, ticket.uploadId());
    }

    private static FinalizedImage finalize(FileShopImageStore store, long owner, long product, byte[] bytes) {
        UploadedImage image = complete(store, owner, product, "image/png", bytes);
        return store.finalizeUpload(owner, image.uploadId());
    }

    private void setModified(FinalizedImage image, Instant instant) throws IOException {
        FileTime time = FileTime.from(instant);
        Files.setLastModifiedTime(tempDir.resolve("files").resolve(image.storageKey()), time);
        Files.setLastModifiedTime(tempDir.resolve("files").resolve(image.thumbnailStorageKey()), time);
    }

    private static void assertReadable(FileShopImageStore store, String key) throws IOException {
        try (InputStream input = store.open(key)) {
            assertTrue(input.read() >= 0);
        }
    }

    private static void appendAtOnce(FileShopImageStore store, UploadTicket ticket, byte[] bytes,
                                     CountDownLatch start, AtomicInteger successes, Set<String> failures) {
        try {
            start.await();
            store.appendChunk(9L, ticket.uploadId(), 0, bytes);
            successes.incrementAndGet();
        } catch (ShopImageException error) {
            failures.add(error.code());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static void assertCompleteCode(FileShopImageStore store, String code, String mime, byte[] bytes) {
        UploadTicket ticket = store.startUpload(9L, 4L, mime, bytes.length);
        store.appendChunk(9L, ticket.uploadId(), 0, bytes);
        assertCode(code, () -> store.completeUpload(9L, ticket.uploadId()));
    }

    private static void assertCode(String expected, ThrowingAction action) {
        ShopImageException error = assertThrows(ShopImageException.class, action::run);
        assertEquals(expected, error.code());
        assertFalse(error.getMessage().contains("\\"));
    }

    private static byte[] png(int width, int height, boolean alpha) throws IOException {
        BufferedImage image = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(alpha ? new Color(30, 90, 160, 120) : new Color(30, 90, 160));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return write(image, "png");
    }

    private static byte[] jpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(220, 140, 30));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return write(image, "jpeg");
    }

    private static byte[] write(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, output));
        return output.toByteArray();
    }

    private static byte[] gif() throws IOException {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        return write(image, "gif");
    }

    private static byte[] pngWithDimensions(int width, int height) throws IOException {
        byte[] bytes = png(1, 1, true);
        ByteBuffer.wrap(bytes, 16, 8).putInt(width).putInt(height);
        CRC32 crc = new CRC32();
        crc.update(bytes, 12, 17);
        ByteBuffer.wrap(bytes, 29, 4).putInt((int) crc.getValue());
        return bytes;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
