package com.vcampus.client.fx.library;

import com.vcampus.common.model.LibraryPaper;
import com.vcampus.common.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SocketPaperGatewayTest {
    @TempDir Path dir;
    static String hash(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    static LibraryPaper paper(byte[] b,String hash){return new LibraryPaper(1,"标题","作者","","","2026","","","","授权",true,"p.pdf",b.length,hash);}
    @Test void downloadChecksHashBeforeReplacingExistingDestination()throws Exception{
        byte[] b="test bytes".getBytes();var paper=paper(b,hash(b));Path dest=dir.resolve("paper.pdf");Files.writeString(dest,"original");
        var gateway=new SocketPaperGateway(r->{assertEquals("secret",r.parameters().get("sessionToken"));return chunk(r,b,paper.sha256());},"secret");
        List<Double> progress=new ArrayList<>();gateway.download(paper,dest,progress::add);
        assertArrayEquals(b,Files.readAllBytes(dest));assertEquals(1.0,progress.getLast());
        var corrupt=new SocketPaperGateway(r->chunk(r,"bad bytes!".getBytes(),paper.sha256()),"secret");
        assertThrows(IOException.class,()->corrupt.download(paper,dest,p->{}));assertArrayEquals(b,Files.readAllBytes(dest));
        try(var paths=Files.list(dir)){assertEquals(1,paths.count());}
    }
    @Test void rejectedUploadCancelsTicket()throws Exception {
        Path source=dir.resolve("x.pdf");Files.writeString(source,"%PDF-1.4\n%%EOF\n");List<String> actions=new ArrayList<>();
        var gateway=new SocketPaperGateway(r->{actions.add(r.action());return switch(r.action()){
            case Actions.LIBRARY_PAPER_UPLOAD_START->ResponseMessage.success(r.requestId(),"",Map.of("uploadId","ticket","chunkBytes","1024"));
            case Actions.LIBRARY_PAPER_UPLOAD_CHUNK->ResponseMessage.failure(r.requestId(),"upload failed");
            default->ResponseMessage.success(r.requestId(),"",Map.of());};},"s");
        assertThrows(IOException.class,()->gateway.upload(1,source,p->{}));assertEquals(Actions.LIBRARY_PAPER_UPLOAD_CANCEL,actions.getLast());
    }
    @Test void externalLinksEscapeQueriesAndRejectUnsafeOriginals() {
        var urls=PaperLinks.searches("校园 & PDF");assertTrue(urls.values().stream().allMatch(s->s.contains("%26")));
        var p=new LibraryPaper(1,"../../CON:研究","a","","","","","","file:///secret","",true,"",1,"a");
        assertTrue(PaperLinks.original(p).isEmpty());assertFalse(PaperLinks.safeFilename(p).contains("/"));assertFalse(PaperLinks.safeFilename(p).contains(":"));
    }
    static ResponseMessage chunk(RequestMessage r,byte[] b,String hash){return ResponseMessage.success(r.requestId(),"",Map.of("contentBase64",Base64.getEncoder().encodeToString(b),"totalBytes",""+b.length,"totalChunks","1","chunkIndex","0","sha256",hash,"filename","p.pdf"));}
}
