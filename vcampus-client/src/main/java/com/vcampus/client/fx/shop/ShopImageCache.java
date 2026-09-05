package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.ImageChunk;
import com.vcampus.client.fx.shop.ShopData.ImageRef;
import com.vcampus.client.fx.shop.ShopData.ImageVariant;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Session-scoped, asynchronous and integrity-checked cache for shop images. */
public final class ShopImageCache implements AutoCloseable {
    private static final int CHUNK_BYTES = 192 * 1024;
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final int FILE_MAGIC = 0x56434931; // VCI1
    private static final int HASH_BYTES = 32;
    private static final String MARKER_NAME = ".vcampus-shop-images-v1";
    private static final byte[] MARKER_CONTENT = "VCAMPUS_SHOP_IMAGE_CACHE_V1\n"
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final Pattern IMAGE_FILE = Pattern.compile(
            "[1-9][0-9]*-[0-9a-f]{64}-(?:THUMBNAIL|DETAIL)\\.img");
    private static final Pattern PART_FILE = Pattern.compile(
            "[1-9][0-9]*-[0-9a-f]{64}-(?:THUMBNAIL|DETAIL)\\.[0-9a-f]{32}\\.part");
    private static final Duration STALE_PART_AGE = Duration.ofDays(1);
    private static final Object[] PUBLISH_LOCKS = new Object[64];
    private static final MoveOperations NIO_MOVES = new MoveOperations() {
        @Override public void atomicMove(Path source, Path target) throws IOException {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
        @Override public void moveNew(Path source, Path target) throws IOException {
            Files.move(source, target);
        }
    };

    static {
        for (int index = 0; index < PUBLISH_LOCKS.length; index++) PUBLISH_LOCKS[index] = new Object();
    }

    private final ShopGateway gateway;
    private final Executor executor;
    private final ShopImageCacheConfig config;
    private final MoveOperations moves;
    private final Path root;
    private final ConcurrentHashMap<CacheKey, CompletableFuture<byte[]>> inFlight =
            new ConcurrentHashMap<>();
    private final LinkedHashMap<CacheKey, byte[]> memory = new LinkedHashMap<>(16, .75f, true);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.Set<Path> ownedParts = ConcurrentHashMap.newKeySet();
    private long memoryBytes;

    public ShopImageCache(ShopGateway gateway, Executor executor, ShopImageCacheConfig config) {
        this(gateway, executor, config, NIO_MOVES);
    }

    ShopImageCache(ShopGateway gateway, Executor executor, ShopImageCacheConfig config,
                   MoveOperations moves) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.config = Objects.requireNonNull(config, "config");
        this.moves = Objects.requireNonNull(moves, "moves");
        this.root = config.root();
    }

    public CompletableFuture<byte[]> load(ImageRef ref, ImageVariant variant) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(variant, "variant");
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Image cache is closed"));
        CacheKey key = new CacheKey(ref.id(), ref.sha256(), variant);
        byte[] hit = memoryGet(key);
        if (hit != null) return CompletableFuture.completedFuture(hit);

        CompletableFuture<byte[]> created = new CompletableFuture<>();
        CompletableFuture<byte[]> shared = inFlight.putIfAbsent(key, created);
        if (shared == null) {
            shared = created;
            CompletableFuture<byte[]> owned = created;
            try {
                executor.execute(() -> runLoad(key, ref, variant, owned));
            } catch (RejectedExecutionException exception) {
                inFlight.remove(key, owned);
                owned.completeExceptionally(exception);
            }
        }
        return shared.thenApply(byte[]::clone);
    }

    public synchronized void clearMemory() {
        for (byte[] value : memory.values()) java.util.Arrays.fill(value, (byte) 0);
        memory.clear();
        memoryBytes = 0;
    }

    public void pruneDisk() {
        if (closed.get()) return;
        try {
            executor.execute(this::pruneDiskOnExecutor);
        } catch (RejectedExecutionException ignored) {
            // Cache maintenance is best effort.
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (CompletableFuture<byte[]> future : inFlight.values()) future.cancel(true);
        inFlight.clear();
        clearMemory();
    }

    private void runLoad(CacheKey key, ImageRef ref, ImageVariant variant,
                         CompletableFuture<byte[]> result) {
        try {
            requireOpen();
            ensureRoot();
            byte[] bytes = readDisk(key, ref, variant);
            if (bytes == null) {
                bytes = download(ref, variant);
                requireOpen();
                bytes = writeDisk(key, bytes);
                pruneDiskOnExecutor();
            }
            requireOpen();
            memoryPut(key, bytes);
            result.complete(bytes.clone());
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        } finally {
            inFlight.remove(key, result);
        }
    }

    private byte[] download(ImageRef ref, ImageVariant variant) throws IOException {
        ImageChunk first = null;
        ByteArrayOutputStream output = null;
        for (int index = 0; ; index++) {
            requireOpen();
            ImageChunk chunk = gateway.imageChunk(ref.id(), variant, index);
            if (first == null) {
                validateFirst(ref, variant, chunk);
                first = chunk;
                output = new ByteArrayOutputStream(Math.toIntExact(chunk.totalBytes()));
            }
            validateChunk(first, ref, chunk, index);
            output.write(chunk.content());
            if (index + 1 == first.totalChunks()) break;
        }
        byte[] bytes = output.toByteArray();
        if (bytes.length != first.totalBytes() || !sha256(bytes).equals(first.sha256())) {
            throw new IOException("Shop image content failed integrity validation");
        }
        return bytes;
    }

    private static void validateFirst(ImageRef ref, ImageVariant variant, ImageChunk chunk)
            throws IOException {
        if (chunk.imageId() != ref.id() || chunk.chunkIndex() != 0
                || (ref.mimeType() != null && !ref.mimeType().equals(chunk.mimeType()))
                || chunk.totalBytes() < 1 || chunk.totalBytes() > MAX_IMAGE_BYTES
                || chunk.totalChunks() != chunksFor(chunk.totalBytes())) {
            throw new IOException("Invalid shop image chunk metadata");
        }
        if (variant == ImageVariant.DETAIL
                && ((ref.byteSize() > 0 && chunk.totalBytes() != ref.byteSize())
                    || !chunk.sha256().equals(ref.sha256()))) {
            throw new IOException("Shop image detail metadata does not match its reference");
        }
    }

    private static void validateChunk(ImageChunk first, ImageRef ref, ImageChunk chunk, int index)
            throws IOException {
        int expectedLength = (int) Math.min(CHUNK_BYTES,
                first.totalBytes() - (long) index * CHUNK_BYTES);
        if (chunk.imageId() != ref.id() || chunk.chunkIndex() != index
                || chunk.totalBytes() != first.totalBytes() || chunk.totalChunks() != first.totalChunks()
                || !chunk.mimeType().equals(first.mimeType()) || !chunk.sha256().equals(first.sha256())
                || chunk.content().length != expectedLength) {
            throw new IOException("Inconsistent shop image chunks");
        }
    }

    private byte[] readDisk(CacheKey key, ImageRef ref, ImageVariant variant) throws IOException {
        Path path = imagePath(key);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            byte[] bytes = readContainer(path);
            String actual = sha256(bytes);
            if (variant == ImageVariant.DETAIL
                    && ((ref.byteSize() > 0 && bytes.length != ref.byteSize())
                        || !actual.equals(ref.sha256()))) {
                throw new IOException("Stale detail cache entry");
            }
            return bytes;
        } catch (IOException | RuntimeException failure) {
            deleteOwned(path);
            return null;
        }
    }

    private byte[] readContainer(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > MAX_IMAGE_BYTES + 64L) {
            throw new IOException("Unsafe or oversized cache entry");
        }
        byte[] container = Files.readAllBytes(path);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(container))) {
            if (input.readInt() != FILE_MAGIC) throw new IOException("Invalid cache entry");
            int length = input.readInt();
            byte[] hash = input.readNBytes(HASH_BYTES);
            if (length < 1 || length > MAX_IMAGE_BYTES || hash.length != HASH_BYTES
                    || input.available() != length) throw new IOException("Truncated cache entry");
            byte[] bytes = input.readNBytes(length);
            String actual = sha256(bytes);
            if (!MessageDigest.isEqual(hash, HexFormat.of().parseHex(actual))) {
                throw new IOException("Corrupt cache entry");
            }
            return bytes;
        }
    }

    private byte[] writeDisk(CacheKey key, byte[] bytes) throws IOException {
        requireOpen();
        Path part = uniquePartPath(key);
        ownedParts.add(part);
        String expectedSha256 = sha256(bytes);
        byte[] hash = HexFormat.of().parseHex(expectedSha256);
        try {
            try (FileChannel channel = FileChannel.open(part, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE); DataOutputStream output = new DataOutputStream(
                    java.nio.channels.Channels.newOutputStream(channel))) {
                output.writeInt(FILE_MAGIC);
                output.writeInt(bytes.length);
                output.write(hash);
                output.write(bytes);
                output.flush();
                channel.force(true);
            }
            requireOpen();
            return publish(key, part, expectedSha256);
        } finally {
            try {
                deleteOwned(part);
            } finally {
                ownedParts.remove(part);
            }
        }
    }

    private byte[] publish(CacheKey key, Path part, String expectedSha256) throws IOException {
        Path target = imagePath(key);
        Object processLock = PUBLISH_LOCKS[Math.floorMod(target.hashCode(), PUBLISH_LOCKS.length)];
        synchronized (processLock) {
            Path lockPath = lockPath(key);
            if (Files.isSymbolicLink(lockPath)) throw new IOException("Unsafe cache publication lock");
            try (FileChannel lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock ignored = lockChannel.lock()) {
                byte[] winner = validPublished(target, expectedSha256);
                if (winner != null) return winner;
                requireOpen();
                try {
                    moves.atomicMove(part, target);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    try {
                        moves.moveNew(part, target);
                    } catch (FileAlreadyExistsException collision) {
                        return requireValidWinner(target, expectedSha256, collision);
                    }
                } catch (FileAlreadyExistsException collision) {
                    return requireValidWinner(target, expectedSha256, collision);
                }
                return requireValidWinner(target, expectedSha256, null);
            }
        }
    }

    private byte[] validPublished(Path target, String expectedSha256) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            byte[] bytes = readContainer(target);
            if (!sha256(bytes).equals(expectedSha256)) {
                throw new IOException("Published cache image does not match the download");
            }
            return bytes;
        } catch (IOException | RuntimeException corrupt) {
            deleteOwned(target);
            return null;
        }
    }

    private byte[] requireValidWinner(Path target, String expectedSha256, IOException collision)
            throws IOException {
        byte[] winner = validPublished(target, expectedSha256);
        if (winner != null) return winner;
        if (collision != null) throw new IOException("Cache publication collision was invalid", collision);
        throw new IOException("Published cache image could not be validated");
    }

    private synchronized byte[] memoryGet(CacheKey key) {
        byte[] value = memory.get(key);
        return value == null ? null : value.clone();
    }

    private synchronized void memoryPut(CacheKey key, byte[] bytes) {
        if (bytes.length > config.maxMemoryBytes()) return;
        byte[] prior = memory.put(key, bytes.clone());
        if (prior != null) memoryBytes -= prior.length;
        memoryBytes += bytes.length;
        var iterator = memory.entrySet().iterator();
        while ((memory.size() > config.maxMemoryEntries() || memoryBytes > config.maxMemoryBytes())
                && iterator.hasNext()) {
            Map.Entry<CacheKey, byte[]> eldest = iterator.next();
            memoryBytes -= eldest.getValue().length;
            java.util.Arrays.fill(eldest.getValue(), (byte) 0);
            iterator.remove();
        }
    }

    private void ensureRoot() throws IOException {
        rejectSymbolicAncestors();
        Files.createDirectories(root);
        rejectSymbolicAncestors();
        if (Files.isSymbolicLink(root)
                || !Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                        .isDirectory()) {
            throw new IOException("Unsafe shop image cache root");
        }
        ensureOwnershipMarker();
    }

    private void ensureOwnershipMarker() throws IOException {
        Path marker = ownedPath(MARKER_NAME);
        try {
            Files.write(marker, MARKER_CONTENT, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException exists) {
            // Another cache instance initialized the same dedicated root.
        }
        if (Files.isSymbolicLink(marker)
                || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || !MessageDigest.isEqual(Files.readAllBytes(marker), MARKER_CONTENT)) {
            throw new IOException("Shop image cache ownership marker is invalid");
        }
    }

    private void rejectSymbolicAncestors() throws IOException {
        Path current = root.getRoot();
        if (current == null) throw new IOException("Shop image cache root must be absolute");
        for (Path component : root) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic links are not allowed in the shop image cache path");
            }
        }
    }

    private Path imagePath(CacheKey key) { return ownedPath(key.filename() + ".img"); }
    private Path uniquePartPath(CacheKey key) {
        return ownedPath(key.filename() + "." + UUID.randomUUID().toString().replace("-", "") + ".part");
    }
    private Path lockPath(CacheKey key) { return ownedPath(key.filename() + ".lck"); }

    private Path ownedPath(String filename) {
        Path path = root.resolve(filename).normalize();
        if (!path.getParent().equals(root)) throw new IllegalStateException("Unsafe cache path");
        return path;
    }

    private void pruneDiskOnExecutor() {
        try {
            ensureRoot();
            Instant cutoff = Instant.now().minus(config.maxDiskAge());
            List<DiskEntry> entries = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                for (Path path : stream) {
                    BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isSymbolicLink() || !attributes.isRegularFile()) continue;
                    String name = path.getFileName().toString();
                    if (PART_FILE.matcher(name).matches()) {
                        if (!ownedParts.contains(path.toAbsolutePath().normalize())
                                && attributes.lastModifiedTime().toInstant()
                                        .isBefore(Instant.now().minus(STALE_PART_AGE))) {
                            deleteOwned(path);
                        }
                    } else if (IMAGE_FILE.matcher(name).matches()) {
                        entries.add(new DiskEntry(path, attributes.size(), attributes.lastModifiedTime().toInstant()));
                    }
                }
            }
            long total = 0;
            entries.sort(Comparator.comparing(DiskEntry::modified));
            for (DiskEntry entry : entries) {
                if (entry.modified().isBefore(cutoff)) deleteOwned(entry.path());
                else total += entry.bytes();
            }
            for (DiskEntry entry : entries) {
                if (total <= config.maxDiskBytes()) break;
                if (Files.exists(entry.path(), LinkOption.NOFOLLOW_LINKS)) {
                    deleteOwned(entry.path());
                    total -= entry.bytes();
                }
            }
        } catch (IOException ignored) {
            // Cache maintenance must not make the application unavailable.
        }
    }

    private void deleteOwned(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.getParent().equals(root)) throw new IOException("Refusing to delete outside cache root");
        Files.deleteIfExists(normalized);
    }

    private void requireOpen() {
        if (closed.get()) throw new CancellationException("Image cache is closed");
    }

    private static int chunksFor(long bytes) {
        return Math.toIntExact((bytes + CHUNK_BYTES - 1) / CHUNK_BYTES);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record CacheKey(long imageId, String sha256, ImageVariant variant) {
        private CacheKey {
            if (imageId < 1 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid cache key");
            }
            Objects.requireNonNull(variant, "variant");
        }
        private String filename() { return imageId + "-" + sha256 + "-" + variant.name(); }
    }

    private record DiskEntry(Path path, long bytes, Instant modified) { }

    interface MoveOperations {
        void atomicMove(Path source, Path target) throws IOException;
        void moveNew(Path source, Path target) throws IOException;
    }
}
