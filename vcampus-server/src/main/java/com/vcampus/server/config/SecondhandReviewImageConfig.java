package com.vcampus.server.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public final class SecondhandReviewImageConfig {
    private SecondhandReviewImageConfig() { }

    public static ShopReviewImageConfig fromEnvironment() {
        ServerSettings settings = ServerSettings.current();
        return new ShopReviewImageConfig(settings.path("VCAMPUS_SECONDHAND_REVIEW_IMAGE_DIR",
                "storage.secondhand-review", "data/secondhand-review-images"),
                ShopReviewImageConfig.DEFAULT_MAX_IMAGE_BYTES,
                ShopReviewImageConfig.DEFAULT_CHUNK_BYTES,
                ShopReviewImageConfig.DEFAULT_MAX_PIXELS,
                ShopReviewImageConfig.DEFAULT_UPLOAD_TTL);
    }

    public static ShopReviewImageConfig fromEnvironment(Map<String, String> environment) {
        String value = Objects.requireNonNull(environment, "environment")
                .get("VCAMPUS_SECONDHAND_REVIEW_IMAGE_DIR");
        Path root = value == null || value.isBlank()
                ? Path.of("data", "secondhand-review-images") : Path.of(value);
        return new ShopReviewImageConfig(root,
                ShopReviewImageConfig.DEFAULT_MAX_IMAGE_BYTES,
                ShopReviewImageConfig.DEFAULT_CHUNK_BYTES,
                ShopReviewImageConfig.DEFAULT_MAX_PIXELS,
                ShopReviewImageConfig.DEFAULT_UPLOAD_TTL);
    }
}
