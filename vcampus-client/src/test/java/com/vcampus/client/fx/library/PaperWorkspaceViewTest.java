package com.vcampus.client.fx.library;

import com.vcampus.common.model.*;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.DoubleConsumer;
import static org.junit.jupiter.api.Assertions.*;

class PaperWorkspaceViewTest {
    @BeforeAll static void toolkit()throws Exception{LibraryAdminViewTest.toolkit();}
    @Test void readerSearchAndAdminControlsFollowRoles()throws Exception {
        Fake gateway=new Fake();PaperWorkspaceView view=LibraryAdminViewTest.fx(()->{
            var v=new PaperWorkspaceView(gateway,Set.of(UserRole.STUDENT),Runnable::run);LibraryViewTest.scene(v,780,720);v.activate();return v;});
        LibraryAdminViewTest.fx(()->{assertNull(view.lookup("#paper-new"));assertNotNull(view.lookup("#paper-external"));
            ((TextField)view.lookup("#paper-keyword")).setText("校园");((TextField)view.lookup("#paper-year")).setText("2026");((Button)view.lookup("#paper-search")).fire();return null;});
        LibraryAdminViewTest.fx(()->{assertEquals("校园",gateway.keyword);assertEquals("2026",gateway.year);assertFalse(gateway.hidden);view.close();return null;});
        PaperWorkspaceView admin=LibraryAdminViewTest.fx(()->{var v=new PaperWorkspaceView(gateway,Set.of(UserRole.TEACHER,UserRole.LIBRARY_ADMIN),Runnable::run);LibraryViewTest.scene(v,1220,900);v.activate();return v;});
        LibraryAdminViewTest.fx(()->{assertNotNull(admin.lookup("#paper-new"));assertNotNull(admin.lookup("#paper-upload"));
            LibraryViewTest.capture(admin,"library-papers-admin-1220",1220,900);LibraryViewTest.capture(admin,"library-papers-admin-780",780,720);admin.close();return null;});
    }
    @Test void closingCancelsQueuedRequest()throws Exception {
        List<Runnable> queued=new ArrayList<>();Fake g=new Fake();
        LibraryAdminViewTest.fx(()->{var v=new PaperWorkspaceView(g,Set.of(UserRole.STUDENT),queued::add);v.activate();v.close();queued.forEach(Runnable::run);assertNull(g.keyword);return null;});
    }
    static class Fake implements PaperGateway {
        String keyword,year;boolean hidden;
        public Page search(String k,String y,int p,boolean h){keyword=k;year=y;hidden=h;return new Page(List.of(example()),p,8,1);}
        static LibraryPaper example(){return new LibraryPaper(1,"面向虚拟校园的分布式信息系统研究","示例作者","摘要示例：讨论校园信息检索与服务设计。","虚拟校园；信息检索","2026","校园课程演示","","https://example.org/paper","课程演示资料，已获作者分享授权",true,"paper.pdf",123456,"a".repeat(64));}
        public LibraryPaper get(long id){return example();}public long save(long id,Map<String,String> m){return 1;}public void setEnabled(long id,boolean e){}
        public void upload(long id,Path p,DoubleConsumer progress){}public void download(LibraryPaper p,Path dest,DoubleConsumer progress){}
    }
}
