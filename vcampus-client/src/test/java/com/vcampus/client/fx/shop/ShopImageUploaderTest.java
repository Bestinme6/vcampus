package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.UploadTicket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class ShopImageUploaderTest {
    @TempDir Path temporary;

    @Test void uploaderUsesBoundedSequentialChunksAndReportsProgress() throws Exception {
        Path png = temporary.resolve("product.png");
        byte[] bytes = new byte[400_000];
        byte[] magic = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(magic, 0, bytes, 0, magic.length); Files.write(png, bytes);
        FakeUpload fake = new FakeUpload();
        List<Double> progress = new ArrayList<>();
        ShopImageUploader uploader = new ShopImageUploader(fake.gateway(), Runnable::run);
        assertEquals("upload-1", uploader.upload(png, 7, progress::add).join());
        assertEquals(List.of(0, 1, 2), fake.indexes);
        assertTrue(fake.sizes.stream().allMatch(size -> size <= 192 * 1024));
        assertEquals(1.0, progress.getLast());
        assertTrue(fake.completed);
    }

    @Test void preflightRejectsWrongExtensionMagicAndOversize() throws Exception {
        ShopImageUploader uploader = new ShopImageUploader(new FakeUpload().gateway(), Runnable::run);
        Path text = temporary.resolve("bad.txt"); Files.write(text, new byte[]{1, 2, 3});
        assertThrows(CompletionException.class, () -> uploader.upload(text, 7, value -> { }).join());
        Path fakePng = temporary.resolve("bad.png"); Files.write(fakePng, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertThrows(CompletionException.class, () -> uploader.upload(fakePng, 7, value -> { }).join());
        Path huge = temporary.resolve("huge.jpg");
        byte[] data = new byte[2 * 1024 * 1024 + 1]; data[0] = (byte) 0xff; data[1] = (byte) 0xd8; data[2] = (byte) 0xff;
        Files.write(huge, data);
        assertThrows(CompletionException.class, () -> uploader.upload(huge, 7, value -> { }).join());
    }

    private static final class FakeUpload {
        final List<Integer> indexes = new ArrayList<>();
        final List<Integer> sizes = new ArrayList<>();
        boolean completed;
        ShopGateway gateway() {
            return (ShopGateway) Proxy.newProxyInstance(ShopGateway.class.getClassLoader(),
                    new Class<?>[]{ShopGateway.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "uploadStart" -> new UploadTicket("upload-1", (long) args[0], (long) args[2],
                                192 * 1024, Instant.now().plusSeconds(60));
                        case "uploadChunk" -> { indexes.add((int) args[1]); sizes.add(((byte[]) args[2]).length); yield null; }
                        case "uploadComplete" -> { completed = true; yield null; }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
