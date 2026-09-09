package com.vcampus.server.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class PaperFileStoreTest {
    @TempDir Path dir;
    static byte[] pdf(){return "%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);}
    @Test void orderedOwnedUploadAndImmutableRead() throws Exception {
        try(var store=new PaperFileStore(dir,1024,Clock.systemUTC())) {
            var id=store.start(1,3,"paper.pdf",pdf().length,"");
            assertThrows(IllegalArgumentException.class,()->store.append(2,id,0,pdf()));
            assertThrows(IllegalArgumentException.class,()->store.append(1,id,1,pdf()));
            store.append(1,id,0,pdf());
            var file=store.complete(1,id);
            assertEquals(3,file.paperId());assertEquals(64,file.sha256().length());
            assertArrayEquals(pdf(),store.read(file.sha256(),0,pdf().length));
            store.consume(1,id);
            assertThrows(IllegalArgumentException.class,()->store.complete(1,id));
            assertThrows(IllegalArgumentException.class,()->store.read("../escape",0,10));
        }
    }
    @Test void rejectsDisguisedIncompleteAndOversizedUploads() throws Exception {
        try(var store=new PaperFileStore(dir,1024,Clock.systemUTC())) {
            assertThrows(IllegalArgumentException.class,()->store.start(1,1,"x.pdf",1025,""));
            assertThrows(IllegalArgumentException.class,()->store.start(1,1,"x.exe",100,""));
            var id=store.start(1,1,"x.pdf",10,"");store.append(1,id,0,new byte[10]);
            assertThrows(IllegalArgumentException.class,()->store.complete(1,id));store.cancel(1,id);
            var shortId=store.start(1,1,"x.pdf",100,"");store.append(1,shortId,0,pdf());
            assertThrows(IllegalArgumentException.class,()->store.complete(1,shortId));
            store.cancel(1,shortId);assertEquals(0,Files.list(dir.resolve("pending")).count());
        }
    }
}
