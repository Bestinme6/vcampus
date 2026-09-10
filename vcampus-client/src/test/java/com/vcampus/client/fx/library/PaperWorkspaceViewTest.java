package com.vcampus.client.fx.library;

import com.vcampus.common.model.*;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
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
        LibraryAdminViewTest.fx(()->{assertNull(view.lookup("#paper-new"));assertNotNull(view.lookup("#paper-external"));assertNotNull(view.lookup("#paper-tag"));
            ((TextField)view.lookup("#paper-keyword")).setText("校园");((TextField)view.lookup("#paper-year")).setText("2026");((Button)view.lookup("#paper-search")).fire();return null;});
        LibraryAdminViewTest.fx(()->{assertEquals("校园",gateway.keyword);assertEquals("2026",gateway.year);assertEquals("",gateway.tag);assertFalse(gateway.hidden);view.close();return null;});
        PaperWorkspaceView admin=LibraryAdminViewTest.fx(()->{var v=new PaperWorkspaceView(gateway,Set.of(UserRole.TEACHER,UserRole.LIBRARY_ADMIN),Runnable::run);LibraryViewTest.scene(v,1220,900);v.activate();return v;});
        LibraryAdminViewTest.fx(()->{assertNotNull(admin.lookup("#paper-new"));assertNotNull(admin.lookup("#paper-upload"));
            LibraryViewTest.capture(admin,"library-papers-admin-1220",1220,900);LibraryViewTest.capture(admin,"library-papers-admin-780",780,720);admin.close();return null;});
    }
    @Test void refreshAfterCreateClearsFiltersAndSelectsNewPaper()throws Exception {
        Fake gateway=new Fake();PaperWorkspaceView view=LibraryAdminViewTest.fx(()->{var v=new PaperWorkspaceView(gateway,Set.of(UserRole.TEACHER,UserRole.LIBRARY_ADMIN),Runnable::run);LibraryViewTest.scene(v,1000,800);v.activate();return v;});
        LibraryAdminViewTest.fx(()->{((TextField)view.lookup("#paper-keyword")).setText("旧条件");((TextField)view.lookup("#paper-year")).setText("2025");view.refreshAfterCreate(2);return null;});
        LibraryAdminViewTest.await(()->gateway.calls>=2&&((TextField)view.lookup("#paper-keyword")).getText().isEmpty());
        LibraryAdminViewTest.fx(()->{assertEquals("",gateway.keyword);assertEquals("",gateway.year);assertEquals(2,((LibraryPaper)((TableView<?>)view.lookup("#paper-table")).getSelectionModel().getSelectedItem()).id());view.close();return null;});
    }
    @Test void createCanSaveMetadataAndUploadPdfInOneFlow()throws Exception {
        Fake gateway=new Fake();PaperWorkspaceView view=LibraryAdminViewTest.fx(()->{var v=new PaperWorkspaceView(gateway,Set.of(UserRole.LIBRARY_ADMIN),Runnable::run);LibraryViewTest.scene(v,1000,800);v.activate();return v;});
        Path pdf=Path.of("new-paper.pdf");LibraryAdminViewTest.fx(()->{view.savePaper(0,Map.of("title","新建论文","authors","另一作者","rights","作者授权"),pdf,true);return null;});
        LibraryAdminViewTest.await(()->gateway.uploadedId==2&&gateway.calls>=2);
        LibraryAdminViewTest.fx(()->{assertEquals(pdf,gateway.uploadedPath);assertEquals(2,((LibraryPaper)((TableView<?>)view.lookup("#paper-table")).getSelectionModel().getSelectedItem()).id());view.close();return null;});
    }
    @Test void metadataOnlyDraftCanBeCreatedWithoutSourceOrRights()throws Exception {
        Fake gateway=new Fake();PaperWorkspaceView view=LibraryAdminViewTest.fx(()->{var v=new PaperWorkspaceView(gateway,Set.of(UserRole.LIBRARY_ADMIN),Runnable::run);LibraryViewTest.scene(v,1000,800);v.activate();return v;});
        try{
            Platform.runLater(()->((Button)view.lookup("#paper-new")).fire());LibraryAdminViewTest.await(()->paperDialog()!=null);
            LibraryAdminViewTest.fx(()->{DialogPane pane=paperDialog();field(pane,"标题 *").setText("只有元数据的论文");field(pane,"作者 *").setText("测试作者");((Button)pane.lookupButton(ButtonType.OK)).fire();return null;});
            LibraryAdminViewTest.await(()->gateway.saveCalls==1);assertEquals("只有元数据的论文",gateway.saved.get("title"));
        }finally{LibraryAdminViewTest.fx(()->{DialogPane pane=paperDialog();if(pane!=null)((Button)pane.lookupButton(ButtonType.CANCEL)).fire();view.close();return null;});}
    }
    @Test void closingCancelsQueuedRequest()throws Exception {
        List<Runnable> queued=new ArrayList<>();Fake g=new Fake();
        LibraryAdminViewTest.fx(()->{var v=new PaperWorkspaceView(g,Set.of(UserRole.STUDENT),queued::add);v.activate();v.close();queued.forEach(Runnable::run);assertNull(g.keyword);return null;});
    }
    static class Fake implements PaperGateway {
        String keyword,year,tag;boolean hidden;int calls,saveCalls;long uploadedId;Path uploadedPath;Map<String,String> saved=Map.of();
        public Page search(String k,String y,String t,int p,boolean h){keyword=k;year=y;tag=t;hidden=h;calls++;var rows=k.isBlank()&&y.isBlank()?List.of(example(),second()):List.of(example());return new Page(rows,p,8,rows.size(),List.of("信息检索","虚拟校园","Java"));}
        static LibraryPaper example(){return new LibraryPaper(1,"面向虚拟校园的分布式信息系统研究","示例作者","摘要示例：讨论校园信息检索与服务设计。","虚拟校园；信息检索","2026","校园课程演示","","https://example.org/paper","课程演示资料，已获作者分享授权",true,"paper.pdf",123456,"a".repeat(64));}
        static LibraryPaper second(){return new LibraryPaper(2,"新建论文","另一作者","摘要","Java；信息检索","2026","课程演示","","https://example.org/new","授权",false,"",0,"");}
        public LibraryPaper get(long id){return example();}public long save(long id,Map<String,String> m){saveCalls++;saved=Map.copyOf(m);return 2;}public void setEnabled(long id,boolean e){}
        public void upload(long id,Path p,DoubleConsumer progress){uploadedId=id;uploadedPath=p;}public void download(LibraryPaper p,Path dest,DoubleConsumer progress){}
    }
    private static DialogPane paperDialog(){return Window.getWindows().stream().filter(Window::isShowing).map(Window::getScene).filter(Objects::nonNull).map(javafx.scene.Scene::getRoot).filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElse(null);}
    private static TextField field(DialogPane pane,String caption){GridPane form=(GridPane)((ScrollPane)pane.getContent()).getContent();Node label=form.getChildren().stream().filter(n->n instanceof Label l&&caption.equals(l.getText())).findFirst().orElseThrow();int row=Objects.requireNonNullElse(GridPane.getRowIndex(label),0);return form.getChildren().stream().filter(TextField.class::isInstance).filter(n->Objects.requireNonNullElse(GridPane.getColumnIndex(n),0)==1&&Objects.requireNonNullElse(GridPane.getRowIndex(n),0)==row).map(TextField.class::cast).findFirst().orElseThrow();}
}
