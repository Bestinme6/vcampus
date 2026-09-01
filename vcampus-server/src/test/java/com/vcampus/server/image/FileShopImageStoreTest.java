package com.vcampus.server.image;

import com.vcampus.server.config.ShopImageConfig;
import org.junit.jupiter.api.Test;
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
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
        store.appendChunk(9L, mismatch.uploadId(), 0, new byte[9]);
        assertCode("SIZE_MISMATCH", () -> store.completeUpload(9L, mismatch.uploadId()));

        UploadTicket expired = store.startUpload(9L, 4L, "image/png", 10);
        clock.advance(Duration.ofSeconds(6));
        assertCode("UPLOAD_EXPIRED", () -> store.appendChunk(9L, expired.uploadId(), 0, new byte[10]));
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
        FileShopImageStore failing = new FileShopImageStore(defaultConfig(), fixedClock()) {
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
        try (var filesOnDisk = Files.list(tempDir.resolve("files"))) {
            assertEquals(1, filesOnDisk.count(), "only the first store's thumbnail remains");
        }
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
        store.appendChunk(9L, expired.uploadId(), 0, new byte[10]);
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

    private FileShopImageStore store(ShopImageConfig config, Clock clock) {
        return new FileShopImageStore(config, clock);
    }

    private ShopImageConfig defaultConfig() {
        return config(2 * 1024 * 1024, 192 * 1024, 20_000_000, Duration.ofMinutes(30));
    }

    private ShopImageConfig config(long maxBytes, int chunkBytes, long maxPixels, Duration ttl) {
        return new ShopImageConfig(tempDir, maxBytes, chunkBytes, maxPixels, ttl);
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
