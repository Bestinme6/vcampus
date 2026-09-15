package com.vcampus.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShopReviewImageConfigTest {
    @TempDir Path temp;

    @Test
    void defaultsAndEnvironmentUseDedicatedReviewStorage() {
        var config = ShopReviewImageConfig.fromEnvironment(Map.of());
        assertTrue(config.root().endsWith(Path.of("data", "shop-review-images")));
        assertEquals(5L * 1024 * 1024, config.maxImageBytes());
        assertEquals(192 * 1024, config.chunkBytes());
        assertEquals(20_000_000L, config.maxPixels());
        assertEquals(Duration.ofMinutes(30), config.uploadTtl());

        var custom = ShopReviewImageConfig.fromEnvironment(Map.of(
                "VCAMPUS_SHOP_REVIEW_IMAGE_DIR", temp.resolve("reviews").toString()));
        assertEquals(temp.resolve("reviews").toAbsolutePath().normalize(), custom.root());
        assertEquals(custom.root(), custom.asStoreConfig().root());
    }
}
