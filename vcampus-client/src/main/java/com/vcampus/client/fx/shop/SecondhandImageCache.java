package com.vcampus.client.fx.shop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

final class SecondhandImageCache implements AutoCloseable {
    private final SecondhandGateway gateway;private final Executor executor;
    private final AtomicBoolean closed=new AtomicBoolean();
    SecondhandImageCache(SecondhandGateway gateway,Executor executor){this.gateway=Objects.requireNonNull(gateway,"gateway");this.executor=Objects.requireNonNull(executor,"executor");}
    CompletableFuture<byte[]> load(ShopData.ImageRef ref,ShopData.ImageVariant variant){
        Objects.requireNonNull(ref,"ref");Objects.requireNonNull(variant,"variant");
        if(closed.get())return CompletableFuture.failedFuture(new IllegalStateException("Image cache is closed"));
        return CompletableFuture.supplyAsync(()->{try{return download(ref,variant);}catch(IOException error){throw new java.util.concurrent.CompletionException(error);}},executor);
    }
    private byte[] download(ShopData.ImageRef ref,ShopData.ImageVariant variant)throws IOException{
        ByteArrayOutputStream output=new ByteArrayOutputStream();String hash=null;long total=-1;int chunks=-1;
        for(int index=0;;index++){
            ShopData.ImageChunk chunk=gateway.imageChunk(ref.id(),variant,index);
            if(chunk.imageId()!=ref.id()||chunk.chunkIndex()!=index)throw new IOException("二手物品图片分块不匹配");
            if(index==0){hash=chunk.sha256();total=chunk.totalBytes();chunks=chunk.totalChunks();
                if(total>8L*1024*1024||chunks>total)throw new IOException("二手物品图片大小无效");}
            else if(!hash.equals(chunk.sha256())||total!=chunk.totalBytes()||chunks!=chunk.totalChunks())throw new IOException("二手物品图片元数据不一致");
            if(closed.get()||(long)output.size()+chunk.content().length>total)throw new IOException("二手物品图片读取已停止或大小无效");
            output.writeBytes(chunk.content());if(index+1==chunks)break;
        }
        byte[] bytes=output.toByteArray();if(bytes.length!=total)throw new IOException("二手物品图片不完整");
        String actual=sha256(bytes);if(!actual.equals(hash))throw new IOException("二手物品图片校验失败");
        if(variant==ShopData.ImageVariant.DETAIL&&!actual.equals(ref.sha256()))throw new IOException("二手物品图片版本不匹配");
        return bytes;
    }
    private static String sha256(byte[] bytes)throws IOException{try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IOException(impossible);}}
    @Override public void close(){closed.set(true);}
}
