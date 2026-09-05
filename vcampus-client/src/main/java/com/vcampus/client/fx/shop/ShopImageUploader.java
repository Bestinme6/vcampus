package com.vcampus.client.fx.shop;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

final class ShopImageUploader {
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final int MAX_CHUNK = 192 * 1024;
    @FunctionalInterface interface ProgressListener { void progress(double value); }

    private final ShopGateway gateway;
    private final Executor executor;

    ShopImageUploader(ShopGateway gateway, Executor executor) {
        this.gateway = Objects.requireNonNull(gateway); this.executor = Objects.requireNonNull(executor);
    }

    CompletableFuture<String> upload(Path file, long productId, ProgressListener progress) {
        Objects.requireNonNull(file); Objects.requireNonNull(progress);
        if (productId < 1) return CompletableFuture.failedFuture(new IllegalArgumentException("商品编号无效"));
        CompletableFuture<String> result = new CompletableFuture<>();
        try {
            executor.execute(() -> run(file.toAbsolutePath().normalize(), productId, progress, result));
        } catch (RejectedExecutionException error) { result.completeExceptionally(error); }
        return result;
    }

    private void run(Path file, long productId, ProgressListener progress, CompletableFuture<String> result) {
        try {
            FileInfo info = preflight(file);
            if (result.isCancelled()) throw new CancellationException();
            var ticket = gateway.uploadStart(productId, info.mimeType(), info.bytes());
            int chunkSize = Math.min(MAX_CHUNK, ticket.chunkBytes());
            if (chunkSize < 1 || ticket.expectedBytes() != info.bytes() || ticket.productId() != productId) {
                throw new IOException("上传凭据与文件不匹配");
            }
            long sent = 0; int index = 0;
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.allocate(chunkSize);
                while (sent < info.bytes()) {
                    if (result.isCancelled()) throw new CancellationException();
                    buffer.clear();
                    int expected = (int) Math.min(chunkSize, info.bytes() - sent);
                    buffer.limit(expected);
                    while (buffer.hasRemaining()) {
                        int read = channel.read(buffer);
                        if (read < 0) throw new IOException("图片文件读取不完整");
                    }
                    byte[] chunk = new byte[expected]; buffer.flip(); buffer.get(chunk);
                    gateway.uploadChunk(ticket.uploadId(), index++, chunk);
                    sent += expected; progress.progress((double) sent / info.bytes());
                }
            }
            if (result.isCancelled()) throw new CancellationException();
            gateway.uploadComplete(ticket.uploadId());
            result.complete(ticket.uploadId());
        } catch (Throwable error) {
            result.completeExceptionally(error);
        }
    }

    private static FileInfo preflight(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("请选择普通图片文件");
        }
        long bytes = Files.size(file);
        if (bytes < 3 || bytes > MAX_BYTES) throw new IOException("图片大小必须在 2 MiB 以内");
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        byte[] header = new byte[8];
        try (var input = Files.newInputStream(file, StandardOpenOption.READ)) {
            int read = input.read(header);
            if (read < 3) throw new IOException("图片文件不完整");
        }
        boolean png = name.endsWith(".png") && header[0] == (byte) 0x89 && header[1] == 0x50
                && header[2] == 0x4e && header[3] == 0x47 && header[4] == 0x0d && header[5] == 0x0a
                && header[6] == 0x1a && header[7] == 0x0a;
        boolean jpeg = (name.endsWith(".jpg") || name.endsWith(".jpeg"))
                && header[0] == (byte) 0xff && header[1] == (byte) 0xd8 && header[2] == (byte) 0xff;
        if (!png && !jpeg) throw new IOException("仅支持真实的 JPG 或 PNG 图片");
        return new FileInfo(png ? "image/png" : "image/jpeg", bytes);
    }

    private record FileInfo(String mimeType, long bytes) { }
}
