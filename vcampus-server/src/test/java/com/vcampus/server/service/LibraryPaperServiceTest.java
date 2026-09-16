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
    String reader,other,admin;
    @BeforeEach void setup()throws Exception{
        files=new PaperFileStore(dir,1024,Clock.systemUTC());service=new LibraryPaperService(store,files,sessions);
        reader=login(1,Set.of(UserRole.STUDENT));other=login(3,Set.of(UserRole.TEACHER));admin=login(2,Set.of(UserRole.TEACHER,UserRole.LIBRARY_ADMIN));
    }
    @AfterEach void close(){files.close();}
    String login(long id,Set<UserRole> roles){return sessions.create(new UserAccount(id,"u"+id,"h","s","User",true,false,roles)).token();}
    ResponseMessage call(String action,String token,Map<String,String> values){var p=new HashMap<>(values);p.put("sessionToken",token);return service.handle(RequestMessage.create(action,p));}
    @Test void contributorsCanCreateAndMaintainOnlyTheirOwnPapers(){
        assertTrue(call(LIBRARY_PAPER_SAVE,reader,Map.of("title","共享资料","authors","学生作者")).success());
        store.paper=paper(false,"",0,"",1);
        assertTrue(call(LIBRARY_PAPER_SAVE,reader,Map.of("paperId","1","title","更新标题","authors","学生作者")).success());
        assertFalse(call(LIBRARY_PAPER_SAVE,other,Map.of("paperId","1","title","越权修改","authors","其他作者")).success());
        assertTrue(call(LIBRARY_PAPER_UPLOAD_START,reader,Map.of("paperId","1","filename","p.pdf","byteSize","20")).success());
        assertFalse(call(LIBRARY_PAPER_UPLOAD_START,other,Map.of("paperId","1","filename","p.pdf","byteSize","20")).success());
        assertFalse(call(LIBRARY_PAPER_SET_ENABLED,reader,Map.of("paperId","1","enabled","true")).success());
        assertFalse(call(LIBRARY_PAPER_SET_ENABLED,admin,Map.of("paperId","1","enabled","true")).success());
        assertFalse(store.paper.enabled());
    }
    @Test void contributorsSeeTheirOwnDraftsButNotOtherUsersDrafts(){
        store.paper=paper(false,"",0,"",1);
        assertTrue(call(LIBRARY_PAPER_GET,reader,Map.of("paperId","1")).success());
        assertFalse(call(LIBRARY_PAPER_GET,other,Map.of("paperId","1")).success());
        assertTrue(call(LIBRARY_PAPER_SEARCH,reader,Map.of("includeDisabled","true")).success());
        assertFalse(store.includeDisabled);assertEquals(1,store.viewerId);
    }
    @Test void ownerCannotReopenAnAdminDownedPaper(){
        store.paper=paper(false,"old.pdf",20,"a".repeat(64),1);
        assertFalse(call(LIBRARY_PAPER_GET,reader,Map.of("paperId","1")).success());
        assertFalse(call(LIBRARY_PAPER_SAVE,reader,Map.of("paperId","1","title","绕过下架","authors","学生作者","rights","作者授权")).success());
        assertFalse(call(LIBRARY_PAPER_UPLOAD_START,reader,Map.of("paperId","1","filename","new.pdf","byteSize","20")).success());
        assertTrue(call(LIBRARY_PAPER_UPLOAD_START,admin,Map.of("paperId","1","filename","new.pdf","byteSize","20")).success());
    }
    @Test void validationAndPaginationAreBounded(){
        assertFalse(call(LIBRARY_PAPER_SAVE,admin,Map.of("title","x","authors","a","sourceUrl","file:///secret")).success());
        assertFalse(call(LIBRARY_PAPER_SAVE,admin,Map.of("title","x","authors","a","year","abcd")).success());
        assertFalse(call(LIBRARY_PAPER_SEARCH,reader,Map.of("page","-1")).success());
        assertFalse(call(LIBRARY_PAPER_GET,"expired",Map.of("paperId","1")).success());
    }
    @Test void metadataOnlyPaperCanBeSavedAsUnpublishedDraft(){
        var response=call(LIBRARY_PAPER_SAVE,admin,Map.of("title","待补充来源的论文","authors","测试作者"));assertTrue(response.success(),response.message());assertEquals("待补充来源的论文",store.saved.title());assertTrue(store.saved.sourceUrl().isBlank());assertTrue(store.saved.rights().isBlank());
    }
    @Test void searchUsesExactKeywordCategoryAndReturnsFacets(){
        var response=call(LIBRARY_PAPER_SEARCH,admin,Map.of("tag","信息检索"));assertTrue(response.success());assertEquals("信息检索",store.tag);assertEquals("2",response.data().get("keywordCount"));assertEquals("Java",response.data().get("keyword.0"));
    }
    @Test void firstPdfUploadPublishesAndCanBeDownloadedByEveryone(){
        store.paper=paper(false,"",0,"",1);
        byte[] pdf="%PDF-1.4\n1 0 obj <<>> endobj\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var start=call(LIBRARY_PAPER_UPLOAD_START,reader,Map.of("paperId","1","filename","p.pdf","byteSize",""+pdf.length));assertTrue(start.success(),start.message());
        String id=start.data().get("uploadId");
        assertTrue(call(LIBRARY_PAPER_UPLOAD_CHUNK,reader,Map.of("uploadId",id,"chunkIndex","0","contentBase64",Base64.getEncoder().encodeToString(pdf))).success());
        assertTrue(call(LIBRARY_PAPER_UPLOAD_COMPLETE,reader,Map.of("uploadId",id)).success());
        assertTrue(store.paper.enabled());
        var read=call(LIBRARY_PAPER_READ,other,Map.of("paperId","1","chunkIndex","0","sha256",store.paper.sha256()));
        assertTrue(read.success(),read.message());assertArrayEquals(pdf,Base64.getDecoder().decode(read.data().get("contentBase64")));
        assertFalse(call(LIBRARY_PAPER_READ,other,Map.of("paperId","1","chunkIndex","0","sha256","wrong")).success());
    }
    @Test void deniedCompletionDoesNotKeepAnOrphanedPdf()throws Exception{
        store.paper=paper(false,"",0,"",1);
        byte[] pdf="%PDF-1.4\n1 0 obj <<>> endobj\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var start=call(LIBRARY_PAPER_UPLOAD_START,reader,Map.of("paperId","1","filename","p.pdf","byteSize",""+pdf.length));assertTrue(start.success(),start.message());
        String uploadId=start.data().get("uploadId");
        assertTrue(call(LIBRARY_PAPER_UPLOAD_CHUNK,reader,Map.of("uploadId",uploadId,"chunkIndex","0","contentBase64",Base64.getEncoder().encodeToString(pdf))).success());
        store.paper=paper(false,"moderated.pdf",20,"e".repeat(64),1);
        assertFalse(call(LIBRARY_PAPER_UPLOAD_COMPLETE,reader,Map.of("uploadId",uploadId)).success());
        try(var stored=Files.list(dir)){assertEquals(0,stored.filter(path->path.getFileName().toString().endsWith(".pdf")).count());}
    }
    @Test void anySqlFailureKeepsPdfForReconciliation()throws Exception{
        store.paper=paper(false,"",0,"",1);store.attachFailure=true;
        byte[] pdf="%PDF-1.4\n1 0 obj <<>> endobj\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var start=call(LIBRARY_PAPER_UPLOAD_START,reader,Map.of("paperId","1","filename","p.pdf","byteSize",""+pdf.length));assertTrue(start.success(),start.message());
        String uploadId=start.data().get("uploadId");
        assertTrue(call(LIBRARY_PAPER_UPLOAD_CHUNK,reader,Map.of("uploadId",uploadId,"chunkIndex","0","contentBase64",Base64.getEncoder().encodeToString(pdf))).success());
        assertFalse(call(LIBRARY_PAPER_UPLOAD_COMPLETE,reader,Map.of("uploadId",uploadId)).success());
        try(var stored=Files.list(dir)){assertEquals(1,stored.filter(path->path.getFileName().toString().endsWith(".pdf")).count());}
    }
    static class Memory implements LibraryPaperStore {
        boolean includeDisabled,attachFailure;long viewerId;String tag;Metadata saved;LibraryPaper paper=paper(false,"",0,"",2);
        public Page search(String k,String y,String t,int p,int n,boolean hidden,long viewer){includeDisabled=hidden;viewerId=viewer;tag=t;boolean visible=hidden||paper.enabled()||paper.createdBy()==viewer;return new Page(visible?List.of(paper):List.of(),p,visible?1:0,List.of("Java","信息检索"));}
        public Optional<LibraryPaper> find(long id){return id==1?Optional.of(paper):Optional.empty();}
        public long save(long id,Metadata m,long actor,boolean manager){saved=m;return 1;}
        public void setEnabled(long id,boolean e,long a){paper=paper(e,paper.filename(),paper.byteSize(),paper.sha256(),paper.createdBy());}
        public void attach(long id,String name,long size,String hash,String expected,long a,boolean manager,boolean publishFirst)throws java.sql.SQLException{if(attachFailure)throw new java.sql.SQLException("unknown commit outcome");paper=paper(publishFirst||paper.enabled(),name,size,hash,paper.createdBy());}
    }
    static LibraryPaper paper(boolean enabled,String filename,long size,String hash,long owner){return new LibraryPaper(1,owner,"title","author","abstract","key","2026","venue","","https://example.org","作者授权",enabled,filename,size,hash);}
}
