package com.vcampus.server.image;

import com.vcampus.server.config.ShopImageConfig;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class FileShopImageStore implements ShopImageStore {
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final int THUMBNAIL_BOUND = 480;
    private static final Duration ORPHAN_RETENTION = Duration.ofHours(24);

    private final ShopImageConfig config;
    private final Clock clock;
    private final Path root;
    private final Path tempDirectory;
    private final Path filesDirectory;
    private final ConcurrentMap<String, UploadSession> sessions = new ConcurrentHashMap<>();

    public FileShopImageStore(ShopImageConfig config) {
        this(config, Clock.systemUTC());
    }

    public FileShopImageStore(ShopImageConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.root = config.root();
        this.tempDirectory = child(root, "temp");
        this.filesDirectory = child(root, "files");
        initializeDirectories();
    }

    @Override
    public UploadTicket startUpload(long ownerId, long productId, String mimeType, long expectedBytes) {
        if (ownerId <= 0 || productId <= 0) {
            throw error("INVALID_UPLOAD", "上传参数无效");
        }
        String normalizedMime = normalizeDeclaredMime(mimeType);
        if (expectedBytes <= 0) {
            throw error("INVALID_UPLOAD", "上传大小无效");
        }
        if (expectedBytes > config.maxImageBytes()) {
            throw error("IMAGE_TOO_LARGE", "图片大小超过限制");
        }
        Instant createdAt = clock.instant();
        for (int attempt = 0; attempt < 8; attempt++) {
            String uploadId = UUID.randomUUID().toString();
            Path tempPath = child(tempDirectory, uploadId + ".part");
            try {
                Files.createFile(tempPath);
                UploadSession session = new UploadSession(uploadId, ownerId, productId, normalizedMime,
                        expectedBytes, createdAt, tempPath);
                if (sessions.putIfAbsent(uploadId, session) == null) {
                    return new UploadTicket(uploadId, productId, expectedBytes, config.chunkBytes(),
                            createdAt.plus(config.uploadTtl()));
                }
                Files.deleteIfExists(tempPath);
            } catch (IOException | SecurityException exception) {
                throw storageError(exception);
            }
        }
        throw error("STORAGE_ERROR", "图片存储失败");
    }

    @Override
    public void appendChunk(long ownerId, String uploadId, int index, byte[] bytes) {
        UploadSession session = requireSession(ownerId, uploadId);
        synchronized (session) {
            requireActive(session);
            if (session.state != State.RECEIVING) {
                throw error("UPLOAD_STATE", "上传状态无效");
            }
            if (bytes == null || bytes.length == 0) {
                throw error("INVALID_CHUNK", "图片分块无效");
            }
            if (bytes.length > config.chunkBytes()) {
                throw error("CHUNK_TOO_LARGE", "图片分块超过限制");
            }
            if (index != session.nextIndex) {
                throw error("INVALID_CHUNK_ORDER", "图片分块顺序错误");
            }
            long newSize = session.receivedBytes + bytes.length;
            if (newSize > config.maxImageBytes()) {
                throw error("IMAGE_TOO_LARGE", "图片大小超过限制");
            }
            if (newSize > session.expectedBytes) {
                throw error("SIZE_MISMATCH", "图片大小与声明不一致");
            }
            try {
                Files.write(session.tempPath, bytes, StandardOpenOption.APPEND);
            } catch (IOException | SecurityException exception) {
                throw storageError(exception);
            }
            session.receivedBytes = newSize;
            session.nextIndex++;
        }
    }

    @Override
    public UploadedImage completeUpload(long ownerId, String uploadId) {
        UploadSession session = requireSession(ownerId, uploadId);
        synchronized (session) {
            requireActive(session);
            if (session.state == State.COMPLETED) {
                return session.uploaded;
            }
            if (session.state != State.RECEIVING) {
                throw error("UPLOAD_STATE", "上传状态无效");
            }
            if (session.receivedBytes != session.expectedBytes) {
                throw error("SIZE_MISMATCH", "图片大小与声明不一致");
            }
            try {
                byte[] raw = Files.readAllBytes(session.tempPath);
                ProcessedImage processed = processImage(raw, session.declaredMime);
                session.uploaded = new UploadedImage(session.uploadId, session.productId,
                        processed.mimeType, processed.fullBytes, sha256(processed.fullBytes),
                        processed.width, processed.height);
                session.thumbnailBytes = processed.thumbnailBytes;
                session.state = State.COMPLETED;
                return session.uploaded;
            } catch (ShopImageException exception) {
                removeFailedSession(session);
                throw exception;
            } catch (IOException | SecurityException exception) {
                removeFailedSession(session);
                throw storageError(exception);
            }
        }
    }

    @Override
    public FinalizedImage finalizeUpload(long ownerId, String uploadId) {
        UploadSession session = requireSession(ownerId, uploadId);
        synchronized (session) {
            requireActive(session);
            if (session.state != State.COMPLETED) {
                throw error("UPLOAD_STATE", "上传尚未完成");
            }
            session.state = State.FINALIZING;
            String fullKey = randomStorageKey();
            String thumbnailKey = randomStorageKey();
            Path fullTarget = storagePath(fullKey);
            Path thumbnailTarget = storagePath(thumbnailKey);
            Path fullTemporary = child(filesDirectory, ".tmp-" + UUID.randomUUID());
            Path thumbnailTemporary = child(filesDirectory, ".tmp-" + UUID.randomUUID());
            try {
                Files.write(fullTemporary, session.uploaded.normalizedBytes(), StandardOpenOption.CREATE_NEW);
                Files.write(thumbnailTemporary, session.thumbnailBytes, StandardOpenOption.CREATE_NEW);
                moveAtomically(fullTemporary, fullTarget);
                moveAtomically(thumbnailTemporary, thumbnailTarget);
                Files.deleteIfExists(session.tempPath);
                session.state = State.FINALIZED;
                sessions.remove(session.uploadId, session);
                return new FinalizedImage(fullKey, thumbnailKey);
            } catch (IOException | SecurityException exception) {
                deleteQuietly(fullTemporary);
                deleteQuietly(thumbnailTemporary);
                deleteQuietly(fullTarget);
                deleteQuietly(thumbnailTarget);
                session.state = State.COMPLETED;
                throw storageError(exception);
            }
        }
    }

    @Override
    public InputStream open(String storageKey) {
        Path path = storagePath(storageKey);
        ensureFilesDirectorySafe();
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || Files.isSymbolicLink(path)) {
                throw error("IMAGE_NOT_FOUND", "图片不存在");
            }
            return Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.NoSuchFileException exception) {
            throw error("IMAGE_NOT_FOUND", "图片不存在");
        } catch (IOException | SecurityException exception) {
            throw storageError(exception);
        }
    }

    @Override
    public boolean deleteIfExists(String storageKey) {
        Path path = storagePath(storageKey);
        ensureFilesDirectorySafe();
        try {
            if (Files.isSymbolicLink(path)) {
                throw error("INVALID_STORAGE_KEY", "图片存储键无效");
            }
            return Files.deleteIfExists(path);
        } catch (IOException | SecurityException exception) {
            throw storageError(exception);
        }
    }

    @Override
    public void cleanup(Set<String> referencedKeys, Instant now) {
        Objects.requireNonNull(referencedKeys, "referencedKeys");
        Objects.requireNonNull(now, "now");
        cleanupExpiredSessions(now);
        cleanupDirectory(tempDirectory, Set.of(), now.minus(config.uploadTtl()));
        cleanupDirectory(filesDirectory, Set.copyOf(referencedKeys), now.minus(ORPHAN_RETENTION));
    }

    protected void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void initializeDirectories() {
        try {
            rejectSymbolicLink(root);
            Files.createDirectories(root);
            rejectSymbolicLink(root);
            Files.createDirectories(tempDirectory);
            Files.createDirectories(filesDirectory);
            rejectSymbolicLink(tempDirectory);
            rejectSymbolicLink(filesDirectory);
        } catch (IOException | SecurityException exception) {
            throw storageError(exception);
        }
    }

    private void ensureFilesDirectorySafe() {
        try {
            rejectSymbolicLink(filesDirectory);
            if (!Files.isDirectory(filesDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw error("STORAGE_ERROR", "图片存储失败");
            }
        } catch (IOException | SecurityException exception) {
            throw storageError(exception);
        }
    }

    private static void rejectSymbolicLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("symbolic storage directory");
        }
    }

    private void cleanupExpiredSessions(Instant now) {
        for (UploadSession session : sessions.values()) {
            synchronized (session) {
                if (!now.isBefore(session.createdAt.plus(config.uploadTtl()))
                        && sessions.remove(session.uploadId, session)) {
                    deleteQuietly(session.tempPath);
                }
            }
        }
    }

    private void cleanupDirectory(Path directory, Set<String> referencedKeys, Instant cutoff) {
        try {
            rejectSymbolicLink(root);
            rejectSymbolicLink(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("storage directory unavailable");
            }
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory)) {
                for (Path path : paths) {
                    if (Files.isSymbolicLink(path)) {
                        continue;
                    }
                    BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isRegularFile()) {
                        continue;
                    }
                    String key = path.getFileName().toString();
                    if (!referencedKeys.contains(key)
                            && attributes.lastModifiedTime().toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        } catch (IOException | SecurityException exception) {
            throw storageError(exception);
        }
    }

    private UploadSession requireSession(long ownerId, String uploadId) {
        if (uploadId == null || uploadId.isBlank()) {
            throw error("UPLOAD_NOT_FOUND", "上传不存在或已过期");
        }
        UploadSession session = sessions.get(uploadId);
        if (session == null) {
            throw error("UPLOAD_NOT_FOUND", "上传不存在或已过期");
        }
        if (session.ownerId != ownerId) {
            throw error("UPLOAD_FORBIDDEN", "无权访问此上传");
        }
        return session;
    }

    private void requireActive(UploadSession session) {
        if (!clock.instant().isBefore(session.createdAt.plus(config.uploadTtl()))) {
            sessions.remove(session.uploadId, session);
            deleteQuietly(session.tempPath);
            throw error("UPLOAD_EXPIRED", "上传已过期");
        }
    }

    private void removeFailedSession(UploadSession session) {
        sessions.remove(session.uploadId, session);
        deleteQuietly(session.tempPath);
        session.state = State.FAILED;
    }

    private ProcessedImage processImage(byte[] raw, String declaredMime) throws IOException {
        String actualMime = magicMime(raw);
        if (!actualMime.equals(declaredMime)) {
            throw error("MIME_MISMATCH", "图片类型与声明不一致");
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            if (input == null) {
                throw error("INVALID_IMAGE", "图片内容无效或已损坏");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw error("INVALID_IMAGE", "图片内容无效或已损坏");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                enforcePixelLimit(width, height);
                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw error("INVALID_IMAGE", "图片内容无效或已损坏");
                }
                boolean alpha = decoded.getColorModel().hasAlpha();
                String normalizedMime = alpha ? "image/png" : "image/jpeg";
                String format = alpha ? "png" : "jpeg";
                BufferedImage normalized = convert(decoded, alpha, width, height);
                byte[] fullBytes = encode(normalized, format);
                BufferedImage thumbnail = thumbnail(normalized, alpha);
                byte[] thumbnailBytes = encode(thumbnail, format);
                return new ProcessedImage(normalizedMime, fullBytes, thumbnailBytes, width, height);
            } catch (ShopImageException exception) {
                throw exception;
            } catch (IOException | RuntimeException exception) {
                throw error("INVALID_IMAGE", "图片内容无效或已损坏", exception);
            } finally {
                reader.dispose();
            }
        }
    }

    private String magicMime(byte[] raw) {
        if (raw.length >= PNG_MAGIC.length) {
            boolean png = true;
            for (int i = 0; i < PNG_MAGIC.length; i++) {
                png &= raw[i] == PNG_MAGIC[i];
            }
            if (png) {
                return "image/png";
            }
        }
        if (raw.length >= 3 && (raw[0] & 0xff) == 0xff && (raw[1] & 0xff) == 0xd8
                && (raw[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (raw.length >= 6 && raw[0] == 'G' && raw[1] == 'I' && raw[2] == 'F') {
            throw error("UNSUPPORTED_IMAGE", "仅支持 JPG/JPEG 和 PNG 图片");
        }
        throw error("INVALID_IMAGE", "图片内容无效或已损坏");
    }

    private void enforcePixelLimit(int width, int height) {
        if (width <= 0 || height <= 0 || (long) width > config.maxPixels() / height) {
            throw error("PIXEL_LIMIT", "图片像素超过限制");
        }
    }

    private static BufferedImage convert(BufferedImage source, boolean alpha, int width, int height) {
        BufferedImage converted = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = converted.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return converted;
    }

    private static BufferedImage thumbnail(BufferedImage source, boolean alpha) {
        double scale = Math.min(1.0, Math.min((double) THUMBNAIL_BOUND / source.getWidth(),
                (double) THUMBNAIL_BOUND / source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage thumbnail = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = thumbnail.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return thumbnail;
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, output)) {
            throw new IOException("image writer unavailable");
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String normalizeDeclaredMime(String mimeType) {
        if (mimeType == null) {
            throw error("UNSUPPORTED_IMAGE", "仅支持 JPG/JPEG 和 PNG 图片");
        }
        return switch (mimeType.trim().toLowerCase(Locale.ROOT)) {
            case "image/png" -> "image/png";
            case "image/jpeg", "image/jpg" -> "image/jpeg";
            default -> throw error("UNSUPPORTED_IMAGE", "仅支持 JPG/JPEG 和 PNG 图片");
        };
    }

    private Path storagePath(String storageKey) {
        if (storageKey == null || !storageKey.matches("[A-Za-z0-9_-]{16,160}")) {
            throw error("INVALID_STORAGE_KEY", "图片存储键无效");
        }
        return child(filesDirectory, storageKey);
    }

    private static Path child(Path parent, String name) {
        Path child = parent.resolve(name).normalize();
        if (!child.startsWith(parent) || child.equals(parent)) {
            throw error("INVALID_STORAGE_KEY", "图片存储键无效");
        }
        return child;
    }

    private static String randomStorageKey() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | SecurityException ignored) {
            // Best-effort compensation; cleanup will retry files that remain under the owned root.
        }
    }

    private static ShopImageException error(String code, String message) {
        return new ShopImageException(code, message);
    }

    private static ShopImageException error(String code, String message, Throwable cause) {
        return new ShopImageException(code, message, cause);
    }

    private static ShopImageException storageError(Throwable cause) {
        return error("STORAGE_ERROR", "图片存储失败", cause);
    }

    private enum State {
        RECEIVING, COMPLETED, FINALIZING, FINALIZED, FAILED
    }

    private static final class UploadSession {
        private final String uploadId;
        private final long ownerId;
        private final long productId;
        private final String declaredMime;
        private final long expectedBytes;
        private final Instant createdAt;
        private final Path tempPath;
        private int nextIndex;
        private long receivedBytes;
        private State state = State.RECEIVING;
        private UploadedImage uploaded;
        private byte[] thumbnailBytes;

        private UploadSession(String uploadId, long ownerId, long productId, String declaredMime,
                              long expectedBytes, Instant createdAt, Path tempPath) {
            this.uploadId = uploadId;
            this.ownerId = ownerId;
            this.productId = productId;
            this.declaredMime = declaredMime;
            this.expectedBytes = expectedBytes;
            this.createdAt = createdAt;
            this.tempPath = tempPath;
        }
    }

    private record ProcessedImage(String mimeType, byte[] fullBytes, byte[] thumbnailBytes,
                                  int width, int height) {
    }
}
