package com.vcampus.server.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vcampus.server.config.ShopReviewImageConfig;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileShopReviewImageStoreTest {
    @TempDir Path temp;

    @Test
    void delegatesSafeNormalizationRangeReadsAndCleanup() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(12, 8, BufferedImage.TYPE_INT_ARGB), "png", output);
        byte[] bytes = output.toByteArray();
        var store = new FileShopReviewImageStore(new ShopReviewImageConfig(
                temp.resolve("reviews"), 5 * 1024 * 1024, 64 * 1024,
                1_000_000, Duration.ofMinutes(30)));
        var ticket = store.start(7, "image/png", bytes.length);
        assertThrows(ShopImageException.class,
                () -> store.append(7, ticket.uploadId(), 1, bytes));
        store.append(7, ticket.uploadId(), 0, bytes);
        var completed = store.complete(7, ticket.uploadId());

        assertEquals("image/png", completed.mimeType());
        assertEquals(12, completed.width());
        assertEquals(8, completed.height());
        assertTrue(store.describe(completed.storageKey()).totalBytes() > 0);
        assertTrue(store.readRange(completed.thumbnailStorageKey(), 0, 192 * 1024)
                .content().length > 0);
        store.cleanup(Set.of(completed.storageKey(), completed.thumbnailStorageKey()),
                java.time.Instant.now().plus(Duration.ofDays(2)));
        assertTrue(store.describe(completed.storageKey()).totalBytes() > 0);
    }
}
