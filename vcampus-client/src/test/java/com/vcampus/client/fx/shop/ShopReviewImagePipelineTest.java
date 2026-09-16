package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.ImageChunk;
import com.vcampus.client.fx.shop.ShopData.ImageVariant;
import com.vcampus.client.fx.shop.ShopData.ReviewImageRef;
import com.vcampus.client.fx.shop.ShopData.ReviewUploadTicket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class ShopReviewImagePipelineTest {
    @TempDir Path temporary;

    @Test
    void draftValidatesPreviewsLimitsOrderRemovalAndCompletedUploads() throws Exception {
        Path first = png("first.png", 0xff336699);
        Path second = png("second.png", 0xff993366);
        Path third = png("third.png", 0xff669933);
        ShopReviewImageDraft draft = new ShopReviewImageDraft();

        var one = draft.add(first);
        var two = draft.add(second);
        var three = draft.add(third);
        assertEquals(List.of("first.png", "second.png", "third.png"),
                draft.images().stream().map(ShopReviewImageDraft.DraftImage::displayName).toList());
        assertTrue(one.preview().getWidth() > 0);
        assertThrows(IllegalStateException.class, () -> draft.add(png("fourth.png", 0xff112233)));

        draft.move(2, 0);
        draft.remove(two.key());
        draft.markUploaded(one.key(), "upload-one");
        assertEquals(List.of(three.key(), one.key()),
                draft.images().stream().map(ShopReviewImageDraft.DraftImage::key).toList());
        assertEquals("upload-one", draft.images().get(1).uploadId());

        Path text = temporary.resolve("fake.png");
        Files.writeString(text, "not an image");
        assertThrows(IOException.class, () -> new ShopReviewImageDraft().add(text));
        Path tooLarge = temporary.resolve("large.png");
        try (var channel = java.nio.channels.FileChannel.open(tooLarge,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(5L * 1024 * 1024);
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
        }
        assertThrows(IOException.class, () -> new ShopReviewImageDraft().add(tooLarge));
    }

    @Test
    void uploaderUsesSequential192KChunksAndFinishesAtOne() throws Exception {
        byte[] encoded = Files.readAllBytes(png("upload-source.png", 0xff225588));
        byte[] bytes = java.util.Arrays.copyOf(encoded, 192 * 1024 + 17);
        Path file = temporary.resolve("upload.png");
        Files.write(file, bytes);
        RecordingGateway gateway = new RecordingGateway(bytes);
        List<Double> progress = new ArrayList<>();

        String uploadId = new ShopReviewImageUploader(gateway, Runnable::run)
                .upload(file, progress::add).join();

        assertEquals("review-upload", uploadId);
        assertEquals(List.of(192 * 1024, 17), gateway.uploaded.stream().map(a -> a.length).toList());
        assertEquals(List.of(0, 1), gateway.indexes);
        assertTrue(gateway.completed);
        assertEquals(1.0, progress.getLast());
    }

    @Test
    void cacheValidatesHashReplacesCorruptionAndCancelsAfterClose() throws Exception {
        byte[] bytes = Files.readAllBytes(png("cached.png", 0xff445566));
        RecordingGateway gateway = new RecordingGateway(bytes);
        ShopImageCacheConfig config = new ShopImageCacheConfig(temporary.resolve("review-cache"),
                4, 16 * 1024 * 1024, 32 * 1024 * 1024, Duration.ofDays(30));
        ReviewImageRef ref = new ReviewImageRef(71, 0, "image/png", bytes.length,
                sha256(bytes), 2, 2);

        ShopReviewImageCache cache = new ShopReviewImageCache(gateway, Runnable::run, config);
        assertArrayEquals(bytes, cache.load(ref, ImageVariant.DETAIL).join());
        cache.clearMemory();
        Path cached = Files.list(config.root()).filter(path -> path.toString().endsWith(".img"))
                .findFirst().orElseThrow();
        Files.write(cached, new byte[]{1, 2, 3});
        assertArrayEquals(bytes, cache.load(ref, ImageVariant.DETAIL).join());
        assertEquals(2, gateway.downloads);
        cache.close();
        assertThrows(CompletionException.class, () -> cache.load(ref, ImageVariant.DETAIL).join());
    }

    private Path png(String name, int argb) throws IOException {
        Path file = temporary.resolve(name);
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, argb);
        assertTrue(ImageIO.write(image, "png", file.toFile()));
        return file;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class RecordingGateway implements ShopGateway {
        private final byte[] download;
        private final List<byte[]> uploaded = new ArrayList<>();
        private final List<Integer> indexes = new ArrayList<>();
        private boolean completed;
        private int downloads;

        private RecordingGateway(byte[] download) { this.download = download; }

        @Override public ReviewUploadTicket startReviewImageUpload(String mimeType, long byteSize) {
            return new ReviewUploadTicket("review-upload", byteSize, 192 * 1024);
        }
        @Override public void uploadReviewImageChunk(String uploadId, int chunkIndex, byte[] content) {
            indexes.add(chunkIndex); uploaded.add(content.clone());
        }
        @Override public void completeReviewImageUpload(String uploadId) { completed = true; }
        @Override public ImageChunk reviewImageChunk(long imageId, ImageVariant variant, int chunkIndex) {
            downloads++;
            return new ImageChunk(imageId, "image/png", sha(download), download.length,
                    1, 0, download);
        }

        private static String sha(byte[] bytes) {
            try { return sha256(bytes); }
            catch (Exception impossible) { throw new IllegalStateException(impossible); }
        }

        @Override public ShopData.ProductPage search(String keyword, com.vcampus.common.model.ShopCategory category,
                                                      com.vcampus.common.model.ShopProductSort sort, int page) { throw new UnsupportedOperationException(); }
        @Override public ShopData.ProductDetail product(long productId) { throw new UnsupportedOperationException(); }
        @Override public ImageChunk imageChunk(long imageId, ImageVariant variant, int chunkIndex) { throw new UnsupportedOperationException(); }
        @Override public ShopData.Cart cart() { throw new UnsupportedOperationException(); }
        @Override public ShopData.Cart setCartQuantity(long productId, int quantity) { throw new UnsupportedOperationException(); }
        @Override public ShopData.Cart removeCartItem(long productId) { throw new UnsupportedOperationException(); }
        @Override public List<com.vcampus.common.model.ShopAddress> addresses() { return List.of(); }
        @Override public com.vcampus.common.model.ShopAddress saveAddress(Integer replaceSlot,
                com.vcampus.common.model.ShippingAddressValue value) { throw new UnsupportedOperationException(); }
        @Override public boolean deleteAddress(int slot) { throw new UnsupportedOperationException(); }
        @Override public ShopData.CheckoutReceipt checkout(java.util.Set<Long> selectedProductIds,
                String operationId, com.vcampus.common.model.ShippingAddressChoice address) { throw new UnsupportedOperationException(); }
        @Override public ShopData.CheckoutReceipt buyNow(long productId, int quantity,
                String operationId, com.vcampus.common.model.ShippingAddressChoice address) { throw new UnsupportedOperationException(); }
        @Override public ShopData.OrderPage orders(com.vcampus.common.model.ShopOrderStatus status, int page) { throw new UnsupportedOperationException(); }
        @Override public ShopData.OrderDetail order(long orderId) { throw new UnsupportedOperationException(); }
        @Override public ShopData.CheckoutReceipt cancelOrder(long orderId) { throw new UnsupportedOperationException(); }
        @Override public ShopData.CheckoutReceipt confirmOrder(long orderId) { throw new UnsupportedOperationException(); }
        @Override public ShopData.ProductPage adminSearchProducts(String keyword, com.vcampus.common.model.ShopCategory category, Boolean enabled, com.vcampus.common.model.ShopProductSort sort, int page) { throw new UnsupportedOperationException(); }
        @Override public long saveProduct(ShopData.ProductInput product) { throw new UnsupportedOperationException(); }
        @Override public boolean setProductEnabled(long productId, boolean enabled) { throw new UnsupportedOperationException(); }
        @Override public int adjustInventory(long productId, int delta, String reason) { throw new UnsupportedOperationException(); }
        @Override public ShopData.OrderPage adminOrders(String keyword, com.vcampus.common.model.ShopOrderStatus status, int page) { throw new UnsupportedOperationException(); }
        @Override public ShopData.CheckoutReceipt shipOrder(long orderId) { throw new UnsupportedOperationException(); }
        @Override public java.math.BigDecimal bankBalance() { throw new UnsupportedOperationException(); }
        @Override public ShopData.UploadTicket uploadStart(long productId, String mimeType, long expectedBytes) { throw new UnsupportedOperationException(); }
        @Override public void uploadChunk(String uploadId, int chunkIndex, byte[] content) { throw new UnsupportedOperationException(); }
        @Override public void uploadComplete(String uploadId) { throw new UnsupportedOperationException(); }
        @Override public List<ShopData.ImageRef> commitImages(long productId, List<ShopData.ImagePlanItem> items) { throw new UnsupportedOperationException(); }
    }
}
