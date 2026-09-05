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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

public class FileShopImageStore implements ShopImageStore {
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final int THUMBNAIL_BOUND = 480;
    private static final int FINALIZE_ATTEMPTS = 16;
    private static final Duration ORPHAN_RETENTION = Duration.ofHours(24);

    private final ShopImageConfig config;
    private final Clock clock;
    private final Path root;
    private final Path tempDirectory;
    private final Path filesDirectory;
    private final DirectoryIdentity rootIdentity;
    private final DirectoryIdentity tempIdentity;
    private final DirectoryIdentity filesIdentity;
    private final ShopImageConfig.ResourceLimits resourceLimits;
    private final Semaphore processingPermits;
    private final Object quotaLock = new Object();
    private final Map<Long, Integer> ownerSessionCounts = new HashMap<>();
    private int activeSessionCount;
    private long activeReservedBytes;
    private final ConcurrentMap<String, UploadSession> sessions = new ConcurrentHashMap<>();

    public FileShopImageStore(ShopImageConfig config) {
        this(config, Clock.systemUTC(), ShopImageConfig.resourceLimitsFromEnvironment());
    }

    public FileShopImageStore(ShopImageConfig config, Clock clock) {
        this(config, clock, ShopImageConfig.resourceLimitsFromEnvironment());
    }

    public FileShopImageStore(ShopImageConfig config, ShopImageConfig.ResourceLimits resourceLimits) {
        this(config, Clock.systemUTC(), resourceLimits);
    }

    public FileShopImageStore(ShopImageConfig config, Clock clock,
                              ShopImageConfig.ResourceLimits resourceLimits) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.resourceLimits = Objects.requireNonNull(resourceLimits, "resourceLimits");
        this.processingPermits = new Semaphore(resourceLimits.maxConcurrentImageProcessing(), true);
        this.root = config.root();
        this.tempDirectory = child(root, "temp");
        this.filesDirectory = child(root, "files");
        initializeDirectories();
        this.rootIdentity = captureDirectory(root, null);
        this.tempIdentity = captureDirectory(tempDirectory, rootIdentity.realPath);
        this.filesIdentity = captureDirectory(filesDirectory, rootIdentity.realPath);
    }

    @Override
    public UploadTicket startUpload(long ownerId, long productId, String mimeType, long expectedBytes) {
        validateDirectoryLayout();
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
        reserveQuota(ownerId, expectedBytes);
        boolean reservationTransferred = false;
        Path pendingTempPath = null;
        Instant createdAt = clock.instant();
        try {
            for (int attempt = 0; attempt < 8; attempt++) {
                String uploadId = UUID.randomUUID().toString();
                Path tempPath = child(tempDirectory, uploadId + ".part");
                createEmptyFile(tempPath);
                pendingTempPath = tempPath;
                FileIdentity partIdentity = captureRegularFile(tempPath, tempIdentity.realPath);
                UploadSession session = new UploadSession(uploadId, ownerId, productId, normalizedMime,
                        expectedBytes, Math.toIntExact(Math.ceilDiv(expectedBytes, config.chunkBytes())),
                        createdAt, tempPath, partIdentity);
                if (sessions.putIfAbsent(uploadId, session) == null) {
                    reservationTransferred = true;
                    pendingTempPath = null;
                    return new UploadTicket(uploadId, productId, expectedBytes, config.chunkBytes(),
                            createdAt.plus(config.uploadTtl()));
                }
                Files.deleteIfExists(tempPath);
                pendingTempPath = null;
            }
            throw error("STORAGE_ERROR", "图片存储失败");
        } catch (ShopImageException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw storageError(exception);
        } finally {
            if (!reservationTransferred) {
                if (pendingTempPath != null) {
                    deleteOwnedQuietly(pendingTempPath);
                }
                releaseQuota(ownerId, expectedBytes);
            }
        }
    }

    @Override
    public void appendChunk(long ownerId, String uploadId, int index, byte[] bytes) {
        UploadSession session = requireSession(ownerId, uploadId);
        synchronized (session) {
            requireActive(session);
            try {
                validateDirectoryLayout();
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
                if (index >= session.expectedChunks) {
                    throw error("INVALID_CHUNK_ORDER", "图片分块顺序错误");
                }
                int requiredBytes = index == session.expectedChunks - 1
                        ? Math.toIntExact(session.expectedBytes
                        - (long) config.chunkBytes() * (session.expectedChunks - 1))
                        : config.chunkBytes();
                if (bytes.length != requiredBytes) {
                    throw error("INVALID_CHUNK_SIZE", "图片分块大小错误");
                }
                validatePartFile(session, session.receivedBytes);
                long newSize = session.receivedBytes + bytes.length;
                if (newSize > config.maxImageBytes()) {
                    throw error("IMAGE_TOO_LARGE", "图片大小超过限制");
                }
                if (newSize > session.expectedBytes) {
                    throw error("SIZE_MISMATCH", "图片大小与声明不一致");
                }
                byte[] acceptedChunk = Arrays.copyOf(bytes, bytes.length);
                appendNoFollow(session.tempPath, acceptedChunk);
                session.receivedBytes = newSize;
                session.nextIndex++;
                session.receivedDigest.update(acceptedChunk);
                refreshPartIdentityAfterOwnedWrite(session, newSize);
            } catch (ShopImageException exception) {
                if ("STORAGE_BOUNDARY".equals(exception.code())) {
                    failSession(session);
                }
                throw exception;
            } catch (IOException | SecurityException exception) {
                failSession(session);
                throw storageError(exception);
            }
        }
    }

    @Override
    public UploadedImage completeUpload(long ownerId, String uploadId) {
        UploadSession session = requireSession(ownerId, uploadId);
        synchronized (session) {
            requireActive(session);
            try {
                validateDirectoryLayout();
            } catch (ShopImageException exception) {
                failSession(session);
                throw exception;
            }
            if (session.state == State.COMPLETED) {
                return session.uploaded;
            }
            if (session.state != State.RECEIVING) {
                throw error("UPLOAD_STATE", "上传状态无效");
            }
            if (session.receivedBytes != session.expectedBytes) {
                ShopImageException mismatch = error("SIZE_MISMATCH", "图片大小与声明不一致");
                failSession(session);
                throw mismatch;
            }
            session.state = State.PROCESSING;
        }

        boolean permitAcquired = false;
        try {
            beforeProcessingPermitAcquire(session.uploadId);
            processingPermits.acquire();
            permitAcquired = true;
            synchronized (session) {
                if (session.state != State.PROCESSING) {
                    throw error("UPLOAD_STATE", "上传状态无效");
                }
                requireActive(session);
            }
            onProcessingPermitAcquired(session.uploadId);
            validateDirectoryLayout();
            validatePartFile(session, session.expectedBytes);
            byte[] raw = readExactlyBounded(session.tempPath, session.expectedBytes);
            validatePartFile(session, session.expectedBytes);
            if (!MessageDigest.isEqual(session.receivedDigest.digest(), sha256Bytes(raw))) {
                throw boundaryError(null);
            }
            ProcessedImage processed = processImage(raw, session.declaredMime);
            synchronized (session) {
                if (session.state != State.PROCESSING) {
                    throw error("UPLOAD_STATE", "上传状态无效");
                }
                session.uploaded = new UploadedImage(session.uploadId, session.productId,
                        processed.mimeType, processed.fullBytes, sha256(processed.fullBytes),
                        processed.width, processed.height);
                session.thumbnailBytes = processed.thumbnailBytes;
                session.state = State.COMPLETED;
                return session.uploaded;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failSession(session);
            throw error("PROCESSING_INTERRUPTED", "图片处理已中断", exception);
        } catch (ShopImageException exception) {
            failSession(session);
            throw exception;
        } catch (IOException | SecurityException exception) {
            failSession(session);
            throw storageError(exception);
        } catch (RuntimeException exception) {
            failSession(session);
            throw exception;
        } finally {
            if (permitAcquired) {
                try {
                    onProcessingPermitReleased(session.uploadId);
                } finally {
                    processingPermits.release();
                }
            }
        }
    }

    protected void beforeProcessingPermitAcquire(String uploadId) throws InterruptedException {
        // Test/observability seam; production acquires immediately.
    }

    protected void onProcessingPermitAcquired(String uploadId) throws InterruptedException {
        // Test/observability seam; called while holding one processing permit.
    }

    protected void onProcessingPermitReleased(String uploadId) {
        // Test/observability seam; called immediately before releasing the permit.
    }

    @Override
    public FinalizedImage finalizeUpload(long ownerId, String uploadId) {
        UploadSession session = requireSession(ownerId, uploadId);
        synchronized (session) {
            requireActive(session);
            if (session.state != State.COMPLETED) {
                throw error("UPLOAD_STATE", "上传尚未完成");
            }
            try {
                validateDirectoryLayout();
                validatePartFile(session, session.expectedBytes);
            } catch (ShopImageException exception) {
                failSession(session);
                throw exception;
            }
            session.state = State.FINALIZING;
            for (int attempt = 0; attempt < FINALIZE_ATTEMPTS; attempt++) {
                String fullKey;
                String thumbnailKey;
                Path fullTarget;
                Path thumbnailTarget;
                Path fullTemporary;
                Path thumbnailTemporary;
                try {
                    fullKey = newStorageKey();
                    thumbnailKey = newStorageKey();
                    fullTarget = storagePath(fullKey);
                    thumbnailTarget = storagePath(thumbnailKey);
                    fullTemporary = child(filesDirectory, ".tmp-" + UUID.randomUUID());
                    thumbnailTemporary = child(filesDirectory, ".tmp-" + UUID.randomUUID());
                } catch (RuntimeException exception) {
                    failSession(session);
                    throw exception;
                }
                if (fullKey.equals(thumbnailKey)) {
                    continue;
                }
                boolean fullCreated = false;
                boolean thumbnailCreated = false;
                try {
                    validateDirectoryLayout();
                    writeNewFile(fullTemporary, session.uploaded.normalizedBytes());
                    writeNewFile(thumbnailTemporary, session.thumbnailBytes);
                    ensureTargetAbsent(fullTarget);
                    moveAtomically(fullTemporary, fullTarget);
                    fullCreated = true;
                    validateDirectoryLayout();
                    ensureTargetAbsent(thumbnailTarget);
                    moveAtomically(thumbnailTemporary, thumbnailTarget);
                    thumbnailCreated = true;
                    deleteOwnedQuietly(session.tempPath);
                    session.state = State.FINALIZED;
                    sessions.remove(session.uploadId, session);
                    releaseReservation(session);
                    return new FinalizedImage(fullKey, thumbnailKey);
                } catch (FileAlreadyExistsException collision) {
                    deleteOwnedQuietly(fullTemporary);
                    deleteOwnedQuietly(thumbnailTemporary);
                    if (fullCreated) {
                        deleteOwnedQuietly(fullTarget);
                    }
                    if (thumbnailCreated) {
                        deleteOwnedQuietly(thumbnailTarget);
                    }
                } catch (IOException | SecurityException exception) {
                    deleteOwnedQuietly(fullTemporary);
                    deleteOwnedQuietly(thumbnailTemporary);
                    if (fullCreated) {
                        deleteOwnedQuietly(fullTarget);
                    }
                    if (thumbnailCreated) {
                        deleteOwnedQuietly(thumbnailTarget);
                    }
                    failSession(session);
                    throw storageError(exception);
                } catch (ShopImageException exception) {
                    deleteOwnedQuietly(fullTemporary);
                    deleteOwnedQuietly(thumbnailTemporary);
                    if (fullCreated) {
                        deleteOwnedQuietly(fullTarget);
                    }
                    if (thumbnailCreated) {
                        deleteOwnedQuietly(thumbnailTarget);
                    }
                    failSession(session);
                    throw exception;
                }
            }
            failSession(session);
            throw error("STORAGE_ERROR", "图片存储失败");
        }
    }

    @Override
    public InputStream open(String storageKey) {
        Path path = storagePath(storageKey);
        validateDirectoryLayout();
        try {
            captureRegularFile(path, filesIdentity.realPath);
            return Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.NoSuchFileException exception) {
            throw error("IMAGE_NOT_FOUND", "图片不存在");
        } catch (IOException | SecurityException exception) {
            throw storageError(exception);
        }
    }

    @Override
    public ImageDescriptor describe(String storageKey) {
        Path path = storagePath(storageKey);
        validateDirectoryLayout();
        try {
            FileIdentity before = captureStoredImage(path);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long totalBytes = 0;
            byte[] buffer = new byte[8192];
            try (InputStream input = Files.newInputStream(
                    path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    totalBytes += count;
                    if (totalBytes > before.size || totalBytes > config.maxImageBytes()) {
                        throw boundaryError(null);
                    }
                    digest.update(buffer, 0, count);
                }
            }
            FileIdentity after = captureStoredImage(path);
            if (totalBytes != before.size || !before.sameFile(after)) throw boundaryError(null);
            return new ImageDescriptor(totalBytes, HexFormat.of().formatHex(digest.digest()));
        } catch (java.nio.file.NoSuchFileException exception) {
            throw error("IMAGE_NOT_FOUND", "图片不存在");
        } catch (ShopImageException exception) {
            throw exception;
        } catch (IOException | SecurityException | NoSuchAlgorithmException exception) {
            throw storageError(exception);
        }
    }

    @Override
    public ImageRange readRange(String storageKey, long offset, int maxBytes) {
        if (offset < 0 || maxBytes < 1 || maxBytes > MAX_RANGE_BYTES) {
            throw error("INVALID_RANGE", "图片读取范围无效");
        }
        Path path = storagePath(storageKey);
        validateDirectoryLayout();
        try {
            FileIdentity before = captureStoredImage(path);
            int expected = offset >= before.size ? 0
                    : Math.toIntExact(Math.min((long) maxBytes, before.size - offset));
            ByteBuffer buffer = ByteBuffer.allocate(expected);
            try (SeekableByteChannel channel = Files.newByteChannel(path,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                if (channel.size() != before.size) throw boundaryError(null);
                channel.position(Math.min(offset, before.size));
                while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                    // Continue until the requested bounded slice is complete.
                }
                if (buffer.hasRemaining() || channel.size() != before.size) throw boundaryError(null);
            }
            FileIdentity after = captureStoredImage(path);
            if (!before.sameFile(after)) throw boundaryError(null);
            return new ImageRange(Arrays.copyOf(buffer.array(), buffer.position()), before.size);
        } catch (java.nio.file.NoSuchFileException exception) {
            throw error("IMAGE_NOT_FOUND", "图片不存在");
        } catch (ShopImageException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw storageError(exception);
        }
    }

    @Override
    public boolean deleteIfExists(String storageKey) {
        Path path = storagePath(storageKey);
        validateDirectoryLayout();
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            captureRegularFile(path, filesIdentity.realPath);
            Files.delete(path);
            return true;
        } catch (IOException | SecurityException exception) {
            throw storageError(exception);
        }
    }

    @Override
    public void cleanup(Set<String> referencedKeys, Instant now) {
        Objects.requireNonNull(referencedKeys, "referencedKeys");
        Objects.requireNonNull(now, "now");
        validateDirectoryLayout();
        cleanupExpiredSessions(now);
        cleanupDirectory(tempDirectory, Set.of(), now.minus(config.uploadTtl()));
        cleanupDirectory(filesDirectory, Set.copyOf(referencedKeys), now.minus(ORPHAN_RETENTION));
    }

    protected void moveAtomically(Path source, Path target) throws IOException {
        try {
            moveFile(source, target, true);
        } catch (AtomicMoveNotSupportedException exception) {
            moveFile(source, target, false);
        }
    }

    protected void moveFile(Path source, Path target, boolean atomic) throws IOException {
        if (atomic) {
            Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } else {
            Files.move(source, target);
        }
    }

    protected String newStorageKey() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void initializeDirectories() {
        try {
            rejectSymlinkedPath(root);
            Files.createDirectories(root);
            rejectSymlinkedPath(root);
            Files.createDirectories(tempDirectory);
            Files.createDirectories(filesDirectory);
            rejectSymlinkedPath(tempDirectory);
            rejectSymlinkedPath(filesDirectory);
        } catch (ShopImageException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw storageError(exception);
        }
    }

    private void validateDirectoryLayout() {
        DirectoryIdentity currentRoot = captureDirectory(root, null);
        DirectoryIdentity currentTemp = captureDirectory(tempDirectory, rootIdentity.realPath);
        DirectoryIdentity currentFiles = captureDirectory(filesDirectory, rootIdentity.realPath);
        if (!rootIdentity.sameFile(currentRoot) || !tempIdentity.sameFile(currentTemp)
                || !filesIdentity.sameFile(currentFiles)) {
            throw boundaryError(null);
        }
    }

    private static DirectoryIdentity captureDirectory(Path path, Path requiredRealParent) {
        rejectSymlinkedPath(path);
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || Files.isSymbolicLink(path)) {
                throw boundaryError(null);
            }
            Path realPath = path.toRealPath();
            if (!realPath.equals(path.toAbsolutePath().normalize())
                    || requiredRealParent != null && !requiredRealParent.equals(realPath.getParent())) {
                throw boundaryError(null);
            }
            return new DirectoryIdentity(realPath, attributes.fileKey(), attributes.creationTime());
        } catch (ShopImageException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw boundaryError(exception);
        }
    }

    private static FileIdentity captureRegularFile(Path path, Path requiredRealParent) throws IOException {
        rejectSymlinkedPath(path);
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || Files.isSymbolicLink(path)) {
            throw boundaryError(null);
        }
        Path realPath = path.toRealPath();
        if (!requiredRealParent.equals(realPath.getParent())) {
            throw boundaryError(null);
        }
        return new FileIdentity(realPath, attributes.fileKey(), attributes.creationTime(),
                attributes.lastModifiedTime(), attributes.size());
    }

    private FileIdentity captureStoredImage(Path path) throws IOException {
        FileIdentity identity = captureRegularFile(path, filesIdentity.realPath);
        if (identity.size < 1 || identity.size > config.maxImageBytes()) throw boundaryError(null);
        return identity;
    }

    private static void rejectSymlinkedPath(Path path) {
        Path current = path.toAbsolutePath().normalize();
        try {
            while (current != null) {
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                    throw boundaryError(null);
                }
                current = current.getParent();
            }
        } catch (SecurityException exception) {
            throw boundaryError(exception);
        }
    }

    private void validatePartFile(UploadSession session, long expectedSize) {
        try {
            FileIdentity current = captureRegularFile(session.tempPath, tempIdentity.realPath);
            if (!session.partIdentity.sameFile(current) || current.size != expectedSize
                    || current.size > session.expectedBytes || current.size > config.maxImageBytes()) {
                throw boundaryError(null);
            }
        } catch (ShopImageException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw boundaryError(exception);
        }
    }

    private void refreshPartIdentityAfterOwnedWrite(UploadSession session, long expectedSize) {
        try {
            FileIdentity current = captureRegularFile(session.tempPath, tempIdentity.realPath);
            if (!session.partIdentity.sameOrigin(current) || current.size != expectedSize
                    || current.size > session.expectedBytes || current.size > config.maxImageBytes()) {
                throw boundaryError(null);
            }
            session.partIdentity = current;
        } catch (ShopImageException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw boundaryError(exception);
        }
    }

    private static void createEmptyFile(Path path) throws IOException {
        try (SeekableByteChannel ignored = Files.newByteChannel(path,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
            // Creation through a no-follow channel establishes the session leaf.
        }
    }

    private static void appendNoFollow(Path path, byte[] bytes) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(path,
                Set.of(StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }
    }

    private static void writeNewFile(Path path, byte[] bytes) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(path,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }
    }

    private static byte[] readExactlyBounded(Path path, long expectedBytes) throws IOException {
        int expected = Math.toIntExact(expectedBytes);
        byte[] result = new byte[expected];
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            int offset = 0;
            while (offset < expected) {
                int read = input.read(result, offset, expected - offset);
                if (read < 0) {
                    throw boundaryError(null);
                }
                offset += read;
            }
            if (input.read() != -1) {
                throw boundaryError(null);
            }
        }
        return result;
    }

    private static void ensureTargetAbsent(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(path.getFileName().toString());
        }
    }

    private void reserveQuota(long ownerId, long expectedBytes) {
        synchronized (quotaLock) {
            int ownerSessions = ownerSessionCounts.getOrDefault(ownerId, 0);
            boolean bytesExceeded = expectedBytes > resourceLimits.maxActiveReservedBytes()
                    - activeReservedBytes;
            if (activeSessionCount >= resourceLimits.maxActiveSessions()
                    || ownerSessions >= resourceLimits.maxSessionsPerOwner() || bytesExceeded) {
                throw error("UPLOAD_QUOTA_EXCEEDED", "图片上传资源已达上限");
            }
            activeSessionCount++;
            activeReservedBytes += expectedBytes;
            ownerSessionCounts.put(ownerId, ownerSessions + 1);
        }
    }

    private void releaseReservation(UploadSession session) {
        if (session.reservationReleased.compareAndSet(false, true)) {
            releaseQuota(session.ownerId, session.expectedBytes);
        }
    }

    private void releaseQuota(long ownerId, long expectedBytes) {
        synchronized (quotaLock) {
            int ownerSessions = ownerSessionCounts.getOrDefault(ownerId, 0);
            if (activeSessionCount <= 0 || activeReservedBytes < expectedBytes || ownerSessions <= 0) {
                throw new IllegalStateException("image upload quota accounting underflow");
            }
            activeSessionCount--;
            activeReservedBytes -= expectedBytes;
            if (ownerSessions == 1) {
                ownerSessionCounts.remove(ownerId);
            } else {
                ownerSessionCounts.put(ownerId, ownerSessions - 1);
            }
        }
    }

    private void cleanupExpiredSessions(Instant now) {
        for (UploadSession session : sessions.values()) {
            synchronized (session) {
                if (!now.isBefore(session.createdAt.plus(config.uploadTtl()))
                        && session.state != State.PROCESSING
                        && sessions.remove(session.uploadId, session)) {
                    session.state = State.FAILED;
                    releaseReservation(session);
                    deleteOwnedQuietly(session.tempPath);
                }
            }
        }
    }

    private void cleanupDirectory(Path directory, Set<String> referencedKeys, Instant cutoff) {
        try {
            validateDirectoryLayout();
            rejectSymlinkedPath(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("storage directory unavailable");
            }
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory)) {
                for (Path path : paths) {
                    if (Files.isSymbolicLink(path)) {
                        throw boundaryError(null);
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
            session.state = State.FAILED;
            releaseReservation(session);
            deleteOwnedQuietly(session.tempPath);
            throw error("UPLOAD_EXPIRED", "上传已过期");
        }
    }

    private void failSession(UploadSession session) {
        synchronized (session) {
            if (session.state == State.FAILED || session.state == State.FINALIZED) {
                return;
            }
            session.state = State.FAILED;
        }
        sessions.remove(session.uploadId, session);
        releaseReservation(session);
        deleteOwnedQuietly(session.tempPath);
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
        return HexFormat.of().formatHex(sha256Bytes(bytes));
    }

    private static byte[] sha256Bytes(byte[] bytes) {
        return newSha256Digest().digest(bytes);
    }

    private static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
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

    private void deleteOwnedQuietly(Path path) {
        try {
            validateDirectoryLayout();
            if (!path.getParent().equals(tempDirectory) && !path.getParent().equals(filesDirectory)) {
                return;
            }
            Files.deleteIfExists(path);
        } catch (IOException | SecurityException | ShopImageException ignored) {
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

    private static ShopImageException boundaryError(Throwable cause) {
        return cause == null
                ? error("STORAGE_BOUNDARY", "图片存储边界无效")
                : error("STORAGE_BOUNDARY", "图片存储边界无效", cause);
    }

    private enum State {
        RECEIVING, PROCESSING, COMPLETED, FINALIZING, FINALIZED, FAILED
    }

    private static final class UploadSession {
        private final String uploadId;
        private final long ownerId;
        private final long productId;
        private final String declaredMime;
        private final long expectedBytes;
        private final int expectedChunks;
        private final Instant createdAt;
        private final Path tempPath;
        private FileIdentity partIdentity;
        private final AtomicBoolean reservationReleased = new AtomicBoolean();
        private final MessageDigest receivedDigest = newSha256Digest();
        private int nextIndex;
        private long receivedBytes;
        private State state = State.RECEIVING;
        private UploadedImage uploaded;
        private byte[] thumbnailBytes;

        private UploadSession(String uploadId, long ownerId, long productId, String declaredMime,
                              long expectedBytes, int expectedChunks, Instant createdAt, Path tempPath,
                              FileIdentity partIdentity) {
            this.uploadId = uploadId;
            this.ownerId = ownerId;
            this.productId = productId;
            this.declaredMime = declaredMime;
            this.expectedBytes = expectedBytes;
            this.expectedChunks = expectedChunks;
            this.createdAt = createdAt;
            this.tempPath = tempPath;
            this.partIdentity = partIdentity;
        }
    }

    private record DirectoryIdentity(Path realPath, Object fileKey, FileTime creationTime) {
        private boolean sameFile(DirectoryIdentity other) {
            return realPath.equals(other.realPath)
                    && sameIdentity(fileKey, creationTime, other.fileKey, other.creationTime);
        }
    }

    private record FileIdentity(Path realPath, Object fileKey, FileTime creationTime,
                                FileTime lastModifiedTime, long size) {
        private boolean sameFile(FileIdentity other) {
            return sameOrigin(other) && lastModifiedTime.equals(other.lastModifiedTime)
                    && size == other.size;
        }

        private boolean sameOrigin(FileIdentity other) {
            return realPath.equals(other.realPath)
                    && sameIdentity(fileKey, creationTime, other.fileKey, other.creationTime);
        }
    }

    private static boolean sameIdentity(Object firstKey, FileTime firstCreation,
                                        Object secondKey, FileTime secondCreation) {
        if (firstKey != null || secondKey != null) {
            return Objects.equals(firstKey, secondKey);
        }
        return firstCreation.equals(secondCreation);
    }

    private record ProcessedImage(String mimeType, byte[] fullBytes, byte[] thumbnailBytes,
                                  int width, int height) {
    }
}
