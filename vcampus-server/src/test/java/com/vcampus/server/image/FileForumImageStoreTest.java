package com.vcampus.server.image;

import com.vcampus.server.config.ForumImageConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class FileForumImageStoreTest {
    @TempDir Path temp;
    @Test void validatesNormalizesAndReadsUploadedPng()throws Exception{
        ByteArrayOutputStream output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(12,8,BufferedImage.TYPE_INT_ARGB),"png",output);byte[] bytes=output.toByteArray();
        var store=new FileForumImageStore(new ForumImageConfig(temp.resolve("forum"),5*1024*1024,64*1024,1_000_000,Duration.ofMinutes(10)));
        var ticket=store.start(7,"image/png",bytes.length);store.append(7,ticket.uploadId(),0,bytes);var completed=store.complete(7,ticket.uploadId());
        assertEquals("image/png",completed.mimeType());assertEquals(12,completed.width());assertEquals(8,completed.height());assertTrue(store.describe(completed.storageKey()).totalBytes()>0);assertTrue(store.readRange(completed.thumbnailStorageKey(),0,192*1024).content().length>0);
    }
}
