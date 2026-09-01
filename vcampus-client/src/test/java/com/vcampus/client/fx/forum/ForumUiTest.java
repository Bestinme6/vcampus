package com.vcampus.client.fx.forum;

import com.vcampus.client.ui.ForumViewData.*;
import com.vcampus.common.model.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ForumUiTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready=new CountDownLatch(1);
        try{Platform.startup(ready::countDown);}catch(IllegalStateException e){ready.countDown();}
        assertTrue(ready.await(15,TimeUnit.SECONDS)); Platform.setImplicitExit(false);
    }
    static <T>T fx(Callable<T> work)throws Exception {
        FutureTask<T> task=new FutureTask<>(work);Platform.runLater(task);return task.get(20,TimeUnit.SECONDS);
    }
    @Test void homeReflowsAndNavigatesWithBlackButtonText()throws Exception {
        fx(()->{
            AtomicLong opened=new AtomicLong();
            var home=new ForumHomeView(f->{},opened::set);
            var root=new ForumView(false,()->{},()->{},s->{},s->{});
            root.page(home); new Scene(root,1220,800);
            home.show(ForumData.FeedFilter.home(),ForumFixtures.feed(),ForumFixtures.sections());
            root.applyCss();root.layout();
            assertTrue(home.lookup("#forum-sidebar").isManaged());
            ((Button)home.lookup("#forum-post-1")).fire();assertEquals(1,opened.get());
            assertNull(root.lookup("#forum-admin"));
            assertEquals(Color.web("#111111"),((Button)root.lookup("#forum-publish")).getTextFill());
            root.resize(1000,760);root.layout();
            assertFalse(home.lookup("#forum-sidebar").isManaged());
            return null;
        });
    }
    @Test void compactWindowKeepsActionLabelsAndAdminTableUsable()throws Exception {
        fx(()->{
            var root=new ForumView(true,()->{},()->{},s->{},s->{});
            var admin=new ForumAdminView(q->{},(r,a)->{},v->{},(id,enabled)->{},page->{});
            root.page(admin);new Scene(root,780,720);root.applyCss();root.layout();
            Button publish=(Button)root.lookup("#forum-publish");
            assertTrue(publish.getWidth()>=publish.prefWidth(-1)-1,"发布按钮文字不能因窗口缩小而省略");
            TableView<?> table=(TableView<?>)root.lookup("#forum-admin-table");
            assertTrue(table.getHeight()>=180,"管理列表至少保留数行可视空间，可纵向滚动页面");
            ScrollPane adminScroll=(ScrollPane)admin.getCenter();
            assertTrue(adminScroll.getContent().getBoundsInLocal().getHeight()>adminScroll.getViewportBounds().getHeight(),
                    "小窗口管理页必须允许滚动到预览区和分页按钮，而不是裁切它们");
            return null;
        });
    }
    @Test void lockedDetailDisablesReplyAndShowsUnavailableOriginal()throws Exception {
        fx(()->{
            var detail=new ForumDetailView(()->{},(text,reply)->{},id->{},()->{},()->{},()->{},page->{});
            detail.show(ForumFixtures.post(true),ForumFixtures.comments(),new ForumData.Engagement(4,false,false));
            new Scene(detail,1000,760); detail.applyCss(); detail.layout();
            assertTrue(((Button)detail.lookup("#forum-comment-send")).isDisabled());
            assertTrue(((TextArea)detail.lookup("#forum-comment-input")).isDisabled());
            assertTrue(detail.lookupAll(".forum-reply-context").stream().anyMatch(n->((Label)n).getText().contains("不可见")));
            return null;
        });
    }
    @Test void loadingIsNotPresentedAsRetryableFailure()throws Exception {
        fx(()->{
            var root=new ForumView(false,()->{},()->{},s->{},s->{});
            root.loading("正在读取校园动态","正在读取正文和评论");
            new Scene(root,1060,800);root.applyCss();root.layout();
            assertNotNull(root.lookup(".progress-indicator"));
            assertFalse(root.getCenter().lookupAll(".button").stream().anyMatch(n->((Button)n).getText().equals("重试")));
            return null;
        });
    }
    @Test void editorRejectsEmptyPostAndKeepsDraftAfterFailedSubmit()throws Exception {
        fx(()->{
            AtomicInteger calls=new AtomicInteger();
            var editor=new ForumEditorView(ForumFixtures.sections(),(section,title,content)->calls.incrementAndGet(),()->{});
            new Scene(editor,1000,760); editor.applyCss(); editor.layout();
            ((Button)editor.lookup("#forum-editor-submit")).fire();assertEquals(0,calls.get());
            ((TextField)editor.lookup("#forum-editor-title")).setText("短题");
            ((TextArea)editor.lookup("#forum-editor-content")).setText("正文");
            ((Button)editor.lookup("#forum-editor-submit")).fire();assertEquals(0,calls.get(),"客户端须与服务端的4字标题下限一致");
            ((TextField)editor.lookup("#forum-editor-title")).setText("校园分享");
            ((TextArea)editor.lookup("#forum-editor-content")).setText("正文内容");
            ((Button)editor.lookup("#forum-editor-submit")).fire();assertEquals(1,calls.get());
            editor.error("网络不可用");assertEquals("正文内容",((TextArea)editor.lookup("#forum-editor-content")).getText());
            assertTrue(editor.dirty()); return null;
        });
    }
    @Test void adminCannotRestoreDeletedCommentsButCanRestoreHiddenComments()throws Exception {
        fx(()->{
            var admin=new ForumAdminView(q->{},(row,action)->{},values->{},(id,enabled)->{},page->{});
            new Scene(admin,1000,760);admin.applyCss();admin.layout();
            var deleted=new AdminContentRow(ForumTargetType.COMMENT,1,10L,"校园生活",2,"作者","","已删除的评论",ForumContentStatus.DELETED,false,false,false,Instant.EPOCH);
            var hidden=new AdminContentRow(ForumTargetType.COMMENT,2,10L,"校园生活",2,"作者","","已隐藏的评论",ForumContentStatus.HIDDEN,false,false,false,Instant.EPOCH);
            admin.showContent(new AdminContentPage(List.of(deleted,hidden),1,10,2));
            @SuppressWarnings("unchecked") var table=(TableView<AdminContentRow>)admin.lookup("#forum-admin-table");
            table.getSelectionModel().select(0);
            assertTrue(((Button)admin.lookup("#forum-admin-restore")).isDisabled());
            assertTrue(((Button)admin.lookup("#forum-admin-hide")).isDisabled());
            table.getSelectionModel().select(1);
            assertFalse(((Button)admin.lookup("#forum-admin-restore")).isDisabled());return null;
        });
    }
}
