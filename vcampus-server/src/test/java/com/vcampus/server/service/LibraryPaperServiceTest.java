package com.vcampus.server.service;

import com.vcampus.common.model.*;
import com.vcampus.common.protocol.*;
import com.vcampus.server.database.LibraryPaperStore;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.paper.PaperFileStore;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import static com.vcampus.common.protocol.Actions.*;
import static org.junit.jupiter.api.Assertions.*;

class LibraryPaperServiceTest {
    @TempDir Path dir;
    SessionManager sessions=new SessionManager();Memory store=new Memory();PaperFileStore files;LibraryPaperService service;
    String reader,admin;
    @BeforeEach void setup()throws Exception{
        files=new PaperFileStore(dir,1024,Clock.systemUTC());service=new LibraryPaperService(store,files,sessions);
        reader=login(1,Set.of(UserRole.STUDENT));admin=login(2,Set.of(UserRole.TEACHER,UserRole.LIBRARY_ADMIN));
    }
    @AfterEach void close(){files.close();}
    String login(long id,Set<UserRole> roles){return sessions.create(new UserAccount(id,"u"+id,"h","s","User",true,false,roles)).token();}
    ResponseMessage call(String action,String token,Map<String,String> values){var p=new HashMap<>(values);p.put("sessionToken",token);return service.handle(RequestMessage.create(action,p));}
    @Test void readersCannotModifyOrAccessHiddenPapers(){
        for(String a:List.of(LIBRARY_PAPER_SAVE,LIBRARY_PAPER_SET_ENABLED,LIBRARY_PAPER_UPLOAD_START,LIBRARY_PAPER_UPLOAD_CHUNK,LIBRARY_PAPER_UPLOAD_COMPLETE,LIBRARY_PAPER_UPLOAD_CANCEL))assertFalse(call(a,reader,Map.of()).success());
        assertFalse(call(LIBRARY_PAPER_GET,reader,Map.of("paperId","1")).success());
        assertFalse(call(LIBRARY_PAPER_READ,reader,Map.of("paperId","1","chunkIndex","0")).success());
        assertTrue(call(LIBRARY_PAPER_GET,admin,Map.of("paperId","1")).success());
        assertTrue(call(LIBRARY_PAPER_SEARCH,reader,Map.of("includeDisabled","true")).success());assertFalse(store.includeDisabled);
    }
    @Test void validationAndPaginationAreBounded(){
        assertFalse(call(LIBRARY_PAPER_SAVE,admin,Map.of("title","x","authors","a","sourceUrl","file:///secret")).success());
        assertFalse(call(LIBRARY_PAPER_SAVE,admin,Map.of("title","x","authors","a")).success());
        assertFalse(call(LIBRARY_PAPER_SAVE,admin,Map.of("title","x","authors","a","year","abcd")).success());
        assertFalse(call(LIBRARY_PAPER_SEARCH,reader,Map.of("page","-1")).success());
        assertFalse(call(LIBRARY_PAPER_GET,"expired",Map.of("paperId","1")).success());
    }
    @Test void uploadPublishReadRoundTripAndChangedHashRejection(){
        byte[] pdf="%PDF-1.4\n1 0 obj <<>> endobj\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var start=call(LIBRARY_PAPER_UPLOAD_START,admin,Map.of("paperId","1","filename","p.pdf","byteSize",""+pdf.length));assertTrue(start.success(),start.message());
        String id=start.data().get("uploadId");
        assertTrue(call(LIBRARY_PAPER_UPLOAD_CHUNK,admin,Map.of("uploadId",id,"chunkIndex","0","contentBase64",Base64.getEncoder().encodeToString(pdf))).success());
        assertTrue(call(LIBRARY_PAPER_UPLOAD_COMPLETE,admin,Map.of("uploadId",id)).success());
        assertTrue(call(LIBRARY_PAPER_SET_ENABLED,admin,Map.of("paperId","1","enabled","true")).success());
        var read=call(LIBRARY_PAPER_READ,reader,Map.of("paperId","1","chunkIndex","0","sha256",store.paper.sha256()));
        assertTrue(read.success(),read.message());assertArrayEquals(pdf,Base64.getDecoder().decode(read.data().get("contentBase64")));
        assertFalse(call(LIBRARY_PAPER_READ,reader,Map.of("paperId","1","chunkIndex","0","sha256","wrong")).success());
    }
    static class Memory implements LibraryPaperStore {
        boolean includeDisabled;LibraryPaper paper=new LibraryPaper(1,"title","author","abstract","key","2026","venue","","https://example.org","作者授权",false,"",0,"");
        public Page search(String k,String y,int p,int n,boolean hidden){includeDisabled=hidden;return new Page(hidden||paper.enabled()?List.of(paper):List.of(),p,hidden||paper.enabled()?1:0);}
        public Optional<LibraryPaper> find(long id){return id==1?Optional.of(paper):Optional.empty();}
        public long save(long id,Metadata m,long actor){return 1;}
        public void setEnabled(long id,boolean e,long a){paper=new LibraryPaper(1,paper.title(),paper.authors(),paper.abstractText(),paper.keywords(),paper.year(),paper.venue(),paper.doi(),paper.sourceUrl(),paper.rights(),e,paper.filename(),paper.byteSize(),paper.sha256());}
        public void attach(long id,String name,long size,String hash,String expected,long a){paper=new LibraryPaper(1,paper.title(),paper.authors(),paper.abstractText(),paper.keywords(),paper.year(),paper.venue(),paper.doi(),paper.sourceUrl(),paper.rights(),paper.enabled(),name,size,hash);}
    }
}
