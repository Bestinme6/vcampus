package com.vcampus.server.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record ShopReviewImageConfig(Path root, long maxImageBytes, int chunkBytes,
                                    long maxPixels, Duration uploadTtl) {
    public static final long DEFAULT_MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    public static final int DEFAULT_CHUNK_BYTES = 192 * 1024;
    public static final long DEFAULT_MAX_PIXELS = 20_000_000L;
    public static final Duration DEFAULT_UPLOAD_TTL = Duration.ofMinutes(30);

    public ShopReviewImageConfig {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(uploadTtl, "uploadTtl");
        root = root.toAbsolutePath().normalize();
        if (maxImageBytes < 1 || chunkBytes < 1 || chunkBytes > maxImageBytes
                || maxPixels < 1 || uploadTtl.isNegative() || uploadTtl.isZero()) {
            throw new IllegalArgumentException("商品评价图片配置无效");
        }
    }

    public static ShopReviewImageConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    public static ShopReviewImageConfig fromEnvironment(Map<String, String> environment) {
        String value = Objects.requireNonNull(environment, "environment")
                .get("VCAMPUS_SHOP_REVIEW_IMAGE_DIR");
        Path root = value == null || value.isBlank()
                ? Path.of("data", "shop-review-images") : Path.of(value);
        return new ShopReviewImageConfig(root, DEFAULT_MAX_IMAGE_BYTES, DEFAULT_CHUNK_BYTES,
                DEFAULT_MAX_PIXELS, DEFAULT_UPLOAD_TTL);
    }

    public ShopImageConfig asStoreConfig() {
        return new ShopImageConfig(root, maxImageBytes, chunkBytes, maxPixels, uploadTtl);
    }
}
