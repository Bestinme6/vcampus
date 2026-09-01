package com.vcampus.server.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record ShopImageConfig(Path root, long maxImageBytes, int chunkBytes,
                              long maxPixels, Duration uploadTtl) {
    public static final long DEFAULT_MAX_IMAGE_BYTES = 2L * 1024 * 1024;
    public static final int DEFAULT_CHUNK_BYTES = 192 * 1024;
    public static final long DEFAULT_MAX_PIXELS = 20_000_000L;
    public static final Duration DEFAULT_UPLOAD_TTL = Duration.ofMinutes(30);

    public ShopImageConfig {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(uploadTtl, "uploadTtl");
        if (root.toString().isBlank()) {
            throw new IllegalArgumentException("root must name a dedicated directory");
        }
        root = root.toAbsolutePath().normalize();
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        if (root.getRoot() != null && root.equals(root.getRoot()) || root.equals(workingDirectory)) {
            throw new IllegalArgumentException("root must name a dedicated directory");
        }
        if (maxImageBytes <= 0 || maxImageBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxImageBytes must be positive and bounded");
        }
        if (chunkBytes <= 0 || chunkBytes > maxImageBytes || chunkBytes > 1024 * 1024) {
            throw new IllegalArgumentException("chunkBytes must be positive and no larger than the image limit");
        }
        if (maxPixels <= 0 || maxPixels > 100_000_000L) {
            throw new IllegalArgumentException("maxPixels must be positive and bounded");
        }
        if (uploadTtl.isZero() || uploadTtl.isNegative() || uploadTtl.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("uploadTtl must be positive and bounded");
        }
    }

    public static ShopImageConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    public static ShopImageConfig fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String configuredRoot = environment.get("VCAMPUS_SHOP_IMAGE_DIR");
        Path root = configuredRoot == null || configuredRoot.isBlank()
                ? Path.of("data", "shop-images")
                : Path.of(configuredRoot);
        return new ShopImageConfig(root, DEFAULT_MAX_IMAGE_BYTES, DEFAULT_CHUNK_BYTES,
                DEFAULT_MAX_PIXELS, DEFAULT_UPLOAD_TTL);
    }
}
