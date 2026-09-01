package com.vcampus.server.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopImageConfigTest {
    @Test
    void defaultsAreBoundedAndRootIsNormalized() {
        ShopImageConfig config = ShopImageConfig.fromEnvironment(Map.of());

        assertEquals(Path.of("data/shop-images").toAbsolutePath().normalize(), config.root());
        assertEquals(2L * 1024 * 1024, config.maxImageBytes());
        assertEquals(192 * 1024, config.chunkBytes());
        assertEquals(20_000_000L, config.maxPixels());
        assertEquals(Duration.ofMinutes(30), config.uploadTtl());
    }

    @Test
    void environmentOverridesOnlyTheStorageRoot() {
        Path configured = Path.of("build", "shop-images", "..").resolve("safe");

        ShopImageConfig config = ShopImageConfig.fromEnvironment(
                Map.of("VCAMPUS_SHOP_IMAGE_DIR", configured.toString()));

        assertEquals(configured.toAbsolutePath().normalize(), config.root());
    }

    @Test
    void rejectsBroadOrNonsensicalConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new ShopImageConfig(Path.of(""), 1, 1, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ShopImageConfig(Path.of("data/images"), 0, 1, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ShopImageConfig(Path.of("data/images"), 10, 11, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ShopImageConfig(Path.of("data/images"), 10, 1, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ShopImageConfig(Path.of("data/images"), 10, 1, 1, Duration.ZERO));
        assertTrue(Path.of("data/images").toAbsolutePath().normalize().isAbsolute());
    }
}
