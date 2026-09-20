package com.vcampus.server.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public final class SecondhandImageConfig {
    private SecondhandImageConfig() {
    }

    public static ShopImageConfig fromEnvironment() {
        ServerSettings settings = ServerSettings.current();
        return new ShopImageConfig(settings.path("VCAMPUS_SECONDHAND_IMAGE_DIR",
                "storage.secondhand", "data/secondhand-images"),
                ShopImageConfig.DEFAULT_MAX_IMAGE_BYTES,
                ShopImageConfig.DEFAULT_CHUNK_BYTES, ShopImageConfig.DEFAULT_MAX_PIXELS,
                ShopImageConfig.DEFAULT_UPLOAD_TTL);
    }

    public static ShopImageConfig fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String configured = environment.get("VCAMPUS_SECONDHAND_IMAGE_DIR");
        Path root = configured == null || configured.isBlank()
                ? Path.of("data", "secondhand-images") : Path.of(configured);
        return new ShopImageConfig(root, ShopImageConfig.DEFAULT_MAX_IMAGE_BYTES,
                ShopImageConfig.DEFAULT_CHUNK_BYTES, ShopImageConfig.DEFAULT_MAX_PIXELS,
                ShopImageConfig.DEFAULT_UPLOAD_TTL);
    }
}
