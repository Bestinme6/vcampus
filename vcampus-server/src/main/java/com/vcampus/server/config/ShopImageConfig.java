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
    public static final int DEFAULT_MAX_ACTIVE_SESSIONS = 128;
    public static final long DEFAULT_MAX_ACTIVE_RESERVED_BYTES = 128L * 1024 * 1024;
    public static final int DEFAULT_MAX_SESSIONS_PER_OWNER = 8;
    public static final int DEFAULT_MAX_CONCURRENT_IMAGE_PROCESSING = 4;

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

    public static ResourceLimits defaultResourceLimits() {
        return new ResourceLimits(DEFAULT_MAX_ACTIVE_SESSIONS, DEFAULT_MAX_ACTIVE_RESERVED_BYTES,
                DEFAULT_MAX_SESSIONS_PER_OWNER, DEFAULT_MAX_CONCURRENT_IMAGE_PROCESSING);
    }

    public static ResourceLimits resourceLimitsFromEnvironment() {
        return resourceLimitsFromEnvironment(System.getenv());
    }

    public static ResourceLimits resourceLimitsFromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return new ResourceLimits(
                readInt(environment, "VCAMPUS_SHOP_IMAGE_MAX_ACTIVE_UPLOADS",
                        DEFAULT_MAX_ACTIVE_SESSIONS),
                readLong(environment, "VCAMPUS_SHOP_IMAGE_MAX_RESERVED_BYTES",
                        DEFAULT_MAX_ACTIVE_RESERVED_BYTES),
                readInt(environment, "VCAMPUS_SHOP_IMAGE_MAX_OWNER_UPLOADS",
                        DEFAULT_MAX_SESSIONS_PER_OWNER),
                readInt(environment, "VCAMPUS_SHOP_IMAGE_MAX_PROCESSING",
                        DEFAULT_MAX_CONCURRENT_IMAGE_PROCESSING));
    }

    private static int readInt(Map<String, String> environment, String name, int defaultValue) {
        String value = environment.get(name);
        try {
            return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static long readLong(Map<String, String> environment, String name, long defaultValue) {
        String value = environment.get(name);
        try {
            return value == null || value.isBlank() ? defaultValue : Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    public record ResourceLimits(int maxActiveSessions, long maxActiveReservedBytes,
                                 int maxSessionsPerOwner, int maxConcurrentImageProcessing) {
        public ResourceLimits {
            if (maxActiveSessions <= 0 || maxActiveSessions > 10_000) {
                throw new IllegalArgumentException("maxActiveSessions must be positive and bounded");
            }
            if (maxActiveReservedBytes <= 0 || maxActiveReservedBytes > 16L * 1024 * 1024 * 1024) {
                throw new IllegalArgumentException("maxActiveReservedBytes must be positive and bounded");
            }
            if (maxSessionsPerOwner <= 0 || maxSessionsPerOwner > maxActiveSessions) {
                throw new IllegalArgumentException("maxSessionsPerOwner must fit the global session limit");
            }
            if (maxConcurrentImageProcessing <= 0 || maxConcurrentImageProcessing > 64) {
                throw new IllegalArgumentException("maxConcurrentImageProcessing must be positive and bounded");
            }
        }
    }
}
