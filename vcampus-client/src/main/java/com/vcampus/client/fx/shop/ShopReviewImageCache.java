package com.vcampus.client.fx.shop;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Dedicated integrity-checked cache for committed review images. */
public final class ShopReviewImageCache implements AutoCloseable {
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private final ShopImageCache delegate;

    public ShopReviewImageCache(ShopGateway gateway, Executor executor) {
        this(gateway, executor, defaults());
    }

    ShopReviewImageCache(ShopGateway gateway, Executor executor, ShopImageCacheConfig config) {
        Objects.requireNonNull(gateway, "gateway");
        delegate = new ShopImageCache(gateway::reviewImageChunk,
                Objects.requireNonNull(executor, "executor"),
                Objects.requireNonNull(config, "config"), MAX_IMAGE_BYTES);
    }

    public CompletableFuture<byte[]> load(ShopData.ReviewImageRef ref, ShopData.ImageVariant variant) {
        Objects.requireNonNull(ref, "ref");
        ShopData.ImageRef compatible = new ShopData.ImageRef(ref.id(), ref.mimeType(), ref.byteSize(),
                ref.sha256(), ref.position(), false);
        return delegate.load(compatible, variant);
    }

    public void clearMemory() { delegate.clearMemory(); }

    public void pruneDisk() { delegate.pruneDisk(); }

    @Override public void close() { delegate.close(); }

    private static ShopImageCacheConfig defaults() {
        String override = System.getenv("VCAMPUS_SHOP_REVIEW_CACHE_DIR");
        Path root;
        if (override != null && !override.isBlank()) {
            root = Path.of(override.trim());
        } else {
            String home = System.getProperty("user.home");
            if (home == null || home.isBlank()) {
                throw new IllegalStateException("user.home is required for the review image cache");
            }
            root = Path.of(home).resolve(".vcampus").resolve("cache").resolve("shop-reviews");
        }
        return new ShopImageCacheConfig(root, 64, 32L * 1024 * 1024,
                256L * 1024 * 1024, Duration.ofDays(30));
    }
}
