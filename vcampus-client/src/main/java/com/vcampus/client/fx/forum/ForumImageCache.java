package com.vcampus.client.fx.forum;

import com.vcampus.client.ui.ForumViewData.PostImageRef;
import java.io.*;
import java.security.*;
import java.util.*;

public final class ForumImageCache {
    private final ForumGateway gateway;private final Map<String,byte[]> memory=new LinkedHashMap<>(16,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,byte[]> e){return size()>24;}};
    public ForumImageCache(ForumGateway gateway){this.gateway=gateway;}
    public synchronized byte[] load(PostImageRef ref,ForumData.ImageVariant variant)throws IOException{
        String key=ref.id()+":"+variant;byte[] cached=memory.get(key);if(cached!=null)return cached.clone();ByteArrayOutputStream out=new ByteArrayOutputStream();String sha=null;long total=-1;int chunks=-1;
        for(int index=0;;index++){var part=gateway.imageChunk(ref.id(),variant,index);if(part.imageId()!=ref.id()||part.variant()!=variant||part.chunkIndex()!=index)throw new IOException("图片分块顺序无效");if(index==0){sha=part.sha256();total=part.totalBytes();chunks=part.totalChunks();}if(part.totalBytes()!=total||part.totalChunks()!=chunks||!Objects.equals(part.sha256(),sha))throw new IOException("图片分块元数据不一致");out.write(part.content());if(index+1==chunks)break;}
        byte[] bytes=out.toByteArray();if(bytes.length!=total||!hex(bytes).equals(sha))throw new IOException("图片完整性校验失败");memory.put(key,bytes.clone());return bytes;
    }
    private static String hex(byte[] bytes)throws IOException{try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IOException(e);}}
}
