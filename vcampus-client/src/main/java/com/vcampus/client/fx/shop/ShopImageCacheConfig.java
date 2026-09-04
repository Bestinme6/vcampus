package com.vcampus.client.fx.shop;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Validated limits and dedicated storage root for the client-side shop image cache. */
public record ShopImageCacheConfig(Path root, int maxMemoryEntries, long maxMemoryBytes,
                                   long maxDiskBytes, Duration maxDiskAge) {
    private static final int DEFAULT_MEMORY_ENTRIES = 128;
    private static final long DEFAULT_MEMORY_BYTES = 32L * 1024 * 1024;
    private static final long DEFAULT_DISK_BYTES = 256L * 1024 * 1024;
    private static final Duration DEFAULT_DISK_AGE = Duration.ofDays(30);

    public ShopImageCacheConfig {
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        maxDiskAge = Objects.requireNonNull(maxDiskAge, "maxDiskAge");
        if (root.getParent() == null || maxMemoryEntries < 1 || maxMemoryBytes < 1
                || maxDiskBytes < 1 || maxDiskAge.isZero() || maxDiskAge.isNegative()) {
            throw new IllegalArgumentException("Invalid shop image cache configuration");
        }
    }

    public static ShopImageCacheConfig defaults() {
        return from(System.getenv(), System.getProperties());
    }

    static ShopImageCacheConfig from(Map<String, String> environment, Properties properties) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(properties, "properties");
        String override = environment.get("VCAMPUS_SHOP_CACHE_DIR");
        Path root;
        if (override != null && !override.isBlank()) {
            root = Path.of(override.trim());
        } else {
            String home = properties.getProperty("user.home");
            if (home == null || home.isBlank()) {
                throw new IllegalStateException("user.home is required for the shop image cache");
            }
            root = Path.of(home).resolve(".vcampus").resolve("cache").resolve("shop");
        }
        return new ShopImageCacheConfig(root, DEFAULT_MEMORY_ENTRIES, DEFAULT_MEMORY_BYTES,
                DEFAULT_DISK_BYTES, DEFAULT_DISK_AGE);
    }
}
