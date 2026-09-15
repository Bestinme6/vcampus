package com.vcampus.client.fx.shop;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Mutable form-lifetime draft for up to three locally selected review images. */
final class ShopReviewImageDraft {
    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final int MAX_IMAGES = 3;

    record DraftImage(String key, Path file, String displayName, long byteSize,
                      String mimeType, Image preview, String uploadId) {
        DraftImage {
            if (key == null || key.isBlank() || file == null || displayName == null
                    || displayName.isBlank() || byteSize < 1 || byteSize > MAX_BYTES
                    || mimeType == null || preview == null || preview.isError()) {
                throw new IllegalArgumentException("评价图片草稿无效");
            }
            file = file.toAbsolutePath().normalize();
            if (uploadId != null && uploadId.isBlank()) throw new IllegalArgumentException("上传编号无效");
        }

        DraftImage uploaded(String value) {
            return new DraftImage(key, file, displayName, byteSize, mimeType, preview, value);
        }
    }

    record FileInfo(Path file, String displayName, long byteSize, String mimeType, Image preview) { }

    private final List<DraftImage> images = new ArrayList<>();

    List<DraftImage> images() { return List.copyOf(images); }

    DraftImage add(Path file) throws IOException {
        return add(inspect(file));
    }

    DraftImage add(FileInfo info) {
        if (images.size() >= MAX_IMAGES) throw new IllegalStateException("每条评价最多三张图片");
        Objects.requireNonNull(info, "info");
        DraftImage image = new DraftImage(UUID.randomUUID().toString(), info.file(), info.displayName(),
                info.byteSize(), info.mimeType(), info.preview(), null);
        images.add(image);
        return image;
    }

    void markUploaded(String key, String uploadId) {
        if (uploadId == null || uploadId.isBlank()) throw new IllegalArgumentException("上传编号无效");
        int index = indexOf(key);
        images.set(index, images.get(index).uploaded(uploadId.trim()));
    }

    void remove(String key) { images.remove(indexOf(key)); }

    void move(int from, int to) {
        if (from < 0 || from >= images.size() || to < 0 || to >= images.size()) {
            throw new IndexOutOfBoundsException("评价图片顺序无效");
        }
        images.add(to, images.remove(from));
    }

    List<String> completedUploadIds() {
        return images.stream().map(DraftImage::uploadId).filter(Objects::nonNull).toList();
    }

    static FileInfo inspect(Path selected) throws IOException {
        Path file = Objects.requireNonNull(selected, "file").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("请选择普通图片文件");
        }
        long bytes = Files.size(file);
        if (bytes < 12 || bytes > MAX_BYTES) throw new IOException("评价图片大小必须在 5 MiB 以内");
        String name = file.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        byte[] header = new byte[12];
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ)) {
            if (input.readNBytes(header, 0, header.length) < header.length) {
                throw new IOException("评价图片文件不完整");
            }
        }
        String mimeType;
        if (lower.endsWith(".png") && header[0] == (byte) 0x89 && header[1] == 0x50
                && header[2] == 0x4e && header[3] == 0x47) {
            mimeType = "image/png";
        } else if ((lower.endsWith(".jpg") || lower.endsWith(".jpeg"))
                && header[0] == (byte) 0xff && header[1] == (byte) 0xd8
                && header[2] == (byte) 0xff) {
            mimeType = "image/jpeg";
        } else if (lower.endsWith(".webp") && header[0] == 'R' && header[1] == 'I'
                && header[2] == 'F' && header[3] == 'F' && header[8] == 'W'
                && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            mimeType = "image/webp";
        } else {
            throw new IOException("仅支持真实的 JPG、PNG 或 WebP 图片");
        }
        BufferedImage decoded;
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) throw new IOException("无法读取评价图片");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("无法读取评价图片");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 20_000_000L) {
                    throw new IOException("评价图片像素不能超过 2000 万");
                }
                decoded = reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        if (decoded == null) {
            throw new IOException("无法读取评价图片");
        }
        return new FileInfo(file, name, bytes, mimeType, SwingFXUtils.toFXImage(decoded, null));
    }

    private int indexOf(String key) {
        for (int index = 0; index < images.size(); index++) {
            if (images.get(index).key().equals(key)) return index;
        }
        throw new IllegalArgumentException("评价图片不存在");
    }
}
