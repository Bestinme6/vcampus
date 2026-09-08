package com.vcampus.client.fx.forum;

import com.vcampus.client.ui.ForumViewData.PostImageRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ForumImagePipelineTest {
    @TempDir Path temp;
    @Test void uploaderPreservesChunkOrderAndProgress()throws Exception{
        byte[] content=new byte[]{1,2,3,4,5,6,7};Path file=temp.resolve("photo.jpg");Files.write(file,content);List<Integer> indexes=new ArrayList<>();
        ForumGateway gateway=(ForumGateway)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ForumGateway.class},(p,m,a)->switch(m.getName()){case "imageUploadStart"->new ForumData.UploadTicket("upload-1",7,3);case "imageUploadChunk"->{indexes.add((Integer)a[1]);yield null;}case "imageUploadComplete"->null;default->throw new AssertionError(m.getName());});
        List<Double> progress=new ArrayList<>();var ids=new ForumImageUploader(gateway).upload(List.of(new ForumImageDraft(file,"photo.jpg",7,"image/jpeg",null)),progress::add);
        assertEquals(List.of("upload-1"),ids);assertEquals(List.of(0,1,2),indexes);assertEquals(1.0,progress.getLast());
    }
    @Test void cacheCoalescesSuccessfulBytesInMemory()throws Exception{
        byte[] bytes="forum-image".getBytes();String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));AtomicInteger calls=new AtomicInteger();
        ForumGateway gateway=(ForumGateway)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ForumGateway.class},(p,m,a)->{if(m.getName().equals("imageChunk")){calls.incrementAndGet();return new ForumData.ImageChunk(5,ForumData.ImageVariant.DETAIL,"image/png",sha,bytes.length,1,0,bytes);}throw new AssertionError(m.getName());});
        var cache=new ForumImageCache(gateway);var ref=new PostImageRef(5,"image/png",bytes.length,sha,10,10,0);
        assertArrayEquals(bytes,cache.load(ref,ForumData.ImageVariant.DETAIL));assertArrayEquals(bytes,cache.load(ref,ForumData.ImageVariant.DETAIL));assertEquals(1,calls.get());
    }
}
