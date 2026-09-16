package com.vcampus.client.fx.shop;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

final class SecondhandReviewImageUploader {
    private static final int MAX_CHUNK=192*1024;private final SecondhandGateway gateway;private final Executor executor;
    SecondhandReviewImageUploader(SecondhandGateway gateway,Executor executor){this.gateway=Objects.requireNonNull(gateway);this.executor=Objects.requireNonNull(executor);}
    CompletableFuture<String> upload(Path file,Consumer<Double>progress){CompletableFuture<String>result=new CompletableFuture<>();try{executor.execute(()->run(file.toAbsolutePath().normalize(),progress,result));}catch(RejectedExecutionException error){result.completeExceptionally(error);}return result;}
    private void run(Path file,Consumer<Double>progress,CompletableFuture<String>result){try{var info=ShopReviewImageDraft.inspect(file);var ticket=gateway.reviewUploadStart(info.mimeType(),info.byteSize());if(ticket.expectedBytes()!=info.byteSize()||ticket.chunkBytes()<1)throw new IOException("评价图片上传凭据与文件不匹配");int chunkSize=Math.min(MAX_CHUNK,ticket.chunkBytes());long sent=0;int index=0;try(FileChannel channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){ByteBuffer buffer=ByteBuffer.allocate(chunkSize);while(sent<info.byteSize()){buffer.clear();int expected=(int)Math.min(chunkSize,info.byteSize()-sent);buffer.limit(expected);while(buffer.hasRemaining()){int read=channel.read(buffer);if(read<0)throw new IOException("评价图片读取不完整");}byte[]chunk=new byte[expected];buffer.flip();buffer.get(chunk);gateway.reviewUploadChunk(ticket.uploadId(),index++,chunk);sent+=expected;progress.accept((double)sent/info.byteSize());}}gateway.reviewUploadComplete(ticket.uploadId());result.complete(ticket.uploadId());}catch(Throwable error){result.completeExceptionally(error);}}
}
