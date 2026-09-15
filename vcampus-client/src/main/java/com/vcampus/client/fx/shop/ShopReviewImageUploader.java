package com.vcampus.client.fx.shop;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Sequential, cancellable background uploader for one review image. */
final class ShopReviewImageUploader {
    private static final int MAX_CHUNK_BYTES = 192 * 1024;
    private final ShopGateway gateway;
    private final Executor executor;

    ShopReviewImageUploader(ShopGateway gateway, Executor executor) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    CompletableFuture<String> upload(Path file, Consumer<Double> progress) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(progress, "progress");
        CompletableFuture<String> result = new CompletableFuture<>();
        try {
            executor.execute(() -> run(file.toAbsolutePath().normalize(), progress, result));
        } catch (RejectedExecutionException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private void run(Path file, Consumer<Double> progress, CompletableFuture<String> result) {
        try {
            ShopReviewImageDraft.FileInfo info = ShopReviewImageDraft.inspect(file);
            requireActive(result);
            ShopData.ReviewUploadTicket ticket = gateway.startReviewImageUpload(
                    info.mimeType(), info.byteSize());
            int chunkBytes = Math.min(MAX_CHUNK_BYTES, ticket.chunkBytes());
            if (ticket.expectedBytes() != info.byteSize() || chunkBytes < 1) {
                throw new IOException("评价图片上传凭据与文件不匹配");
            }
            long sent = 0;
            int index = 0;
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.allocate(chunkBytes);
                while (sent < info.byteSize()) {
                    requireActive(result);
                    buffer.clear();
                    int expected = (int) Math.min(chunkBytes, info.byteSize() - sent);
                    buffer.limit(expected);
                    while (buffer.hasRemaining()) {
                        int read = channel.read(buffer);
                        if (read < 0) throw new IOException("评价图片读取不完整");
                    }
                    byte[] content = new byte[expected];
                    buffer.flip();
                    buffer.get(content);
                    gateway.uploadReviewImageChunk(ticket.uploadId(), index++, content);
                    sent += expected;
                    progress.accept((double) sent / info.byteSize());
                }
            }
            requireActive(result);
            gateway.completeReviewImageUpload(ticket.uploadId());
            result.complete(ticket.uploadId());
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
    }

    private static void requireActive(CompletableFuture<?> result) {
        if (result.isCancelled()) throw new CancellationException("评价图片上传已取消");
    }
}
