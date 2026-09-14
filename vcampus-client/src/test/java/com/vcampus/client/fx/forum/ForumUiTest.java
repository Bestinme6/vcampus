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
    @Test void workspaceLinkStaysAtRightEdgeOfForumNavigation()throws Exception {
        fx(()->{
            var root=new ForumView(true,()->{},()->{},s->{},s->{});
            new Scene(root,1220,800);root.applyCss();root.layout();
            Button back=root.lookupAll(".button").stream().map(Button.class::cast)
                    .filter(button->button.getText().equals("返回工作台")).findFirst().orElseThrow();
            double rightGap=root.getWidth()-back.localToScene(back.getBoundsInLocal()).getMaxX();
            assertEquals(28,rightGap,2,"返回工作台应与论坛导航右边缘对齐");
            return null;
        });
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
    @Test void adminCanDeleteSelectedNormalComment()throws Exception {
        fx(()->{
            AtomicReference<ForumModerationAction> action=new AtomicReference<>();
            var admin=new ForumAdminView(q->{},(row,selected)->action.set(selected),values->{},(id,enabled)->{},page->{});
            new Scene(admin,1000,760);admin.applyCss();admin.layout();
            var comment=new AdminContentRow(ForumTargetType.COMMENT,9,10L,"校园生活",2,"作者","","待删除评论",ForumContentStatus.NORMAL,false,false,false,Instant.EPOCH);
            admin.showContent(new AdminContentPage(List.of(comment),1,10,1));
            @SuppressWarnings("unchecked") var table=(TableView<AdminContentRow>)admin.lookup("#forum-admin-table");
            table.getSelectionModel().selectFirst();
            Button delete=(Button)admin.lookup("#forum-admin-delete");
            assertNotNull(delete);assertFalse(delete.isDisabled());delete.fire();
            assertEquals(ForumModerationAction.DELETE,action.get());return null;
        });
    }
    @Test void adminContentStartsWithTabsAndCanDeleteSelectedNormalPost()throws Exception {
        fx(()->{
            AtomicReference<ForumModerationAction> action=new AtomicReference<>();
            var admin=new ForumAdminView(q->{},(row,selected)->action.set(selected),values->{},(id,enabled)->{},page->{});
            new Scene(admin,1000,760);admin.applyCss();admin.layout();
            assertTrue(admin.lookupAll(".label").stream().map(node->((Label)node).getText())
                    .noneMatch(text->text.equals("内容管理")||text.equals("保留审核记录，让每一次管理都有据可查")));
            var post=new AdminContentRow(ForumTargetType.POST,9,null,"校园生活",2,"作者","帖子标题","帖子正文",ForumContentStatus.NORMAL,false,false,false,Instant.EPOCH);
            admin.showContent(new AdminContentPage(List.of(post),1,10,1));
            @SuppressWarnings("unchecked") var table=(TableView<AdminContentRow>)admin.lookup("#forum-admin-table");
            table.getSelectionModel().selectFirst();
            Button delete=(Button)admin.lookup("#forum-admin-delete");
            assertEquals("删除",delete.getText());assertFalse(delete.isDisabled());delete.fire();
            assertEquals(ForumModerationAction.DELETE,action.get());return null;
        });
    }
    @Test void detailOffersReportsForOtherUsersPostAndComment()throws Exception {
        fx(()->{
            AtomicReference<ForumTargetType> target=new AtomicReference<>();AtomicLong id=new AtomicLong();
            var detail=new ForumDetailView(()->{},(text,reply)->{},ignored->{},()->{},()->{},()->{},page->{},
                    (selected,value)->{target.set(selected);id.set(value);});
            detail.show(ForumFixtures.post(false),ForumFixtures.comments(),new ForumData.Engagement(4,false,false));
            new Scene(detail,1000,760);detail.applyCss();detail.layout();
            Button post=(Button)detail.lookup("#forum-report-post");assertNotNull(post);post.fire();
            assertEquals(ForumTargetType.POST,target.get());assertEquals(1,id.get());
            Button comment=(Button)detail.lookup("#forum-report-comment-1");assertNotNull(comment);comment.fire();
            assertEquals(ForumTargetType.COMMENT,target.get());assertEquals(1,id.get());
            detail.reported(ForumTargetType.COMMENT,1);assertTrue(comment.isDisabled());assertEquals("已举报",comment.getText());
            return null;
        });
    }
    @Test void adminReportQueueEnablesResolutionOnlyForPendingRows()throws Exception {
        fx(()->{
            AtomicReference<ForumReportResolution> resolution=new AtomicReference<>();
            var reports=new ForumReportsView(q->{},(row,action)->resolution.set(action));
            new Scene(reports,900,600);reports.applyCss();reports.layout();
            var pending=new ForumData.ReportRow(1,ForumTargetType.COMMENT,4,2,"违规评论",11,"举报人",
                    ForumReportReason.HARASSMENT,"辱骂",ForumReportStatus.PENDING,null,"",Instant.EPOCH,null);
            reports.show(new ForumData.ReportPage(List.of(pending),1,10,1));
            @SuppressWarnings("unchecked") var table=(TableView<ForumData.ReportRow>)reports.lookup("#forum-report-table");
            table.getSelectionModel().selectFirst();Button delete=(Button)reports.lookup("#forum-report-delete");
            assertFalse(delete.isDisabled());delete.fire();assertEquals(ForumReportResolution.DELETE,resolution.get());return null;
        });
    }
    @Test void editorTracksAndReordersImageDrafts()throws Exception{
        var path=java.nio.file.Files.createTempFile("forum-image-",".png");java.nio.file.Files.write(path,java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="));
        try{fx(()->{var editor=new ForumEditorView(ForumFixtures.sections(),(section,title,content,images)->{},()->{});new Scene(editor,1000,760);editor.addFile(path);editor.addFile(path);editor.applyCss();editor.layout();assertTrue(editor.dirty());assertEquals(2,editor.images().size());Button right=(Button)editor.lookup("#forum-image-right-0");assertNotNull(right);right.fire();assertEquals(2,editor.images().size());((Button)editor.lookup("#forum-image-remove-0")).fire();assertEquals(1,editor.images().size());return null;});}finally{java.nio.file.Files.deleteIfExists(path);}
    }
    @Test void detailRendersOrderedImageGallery()throws Exception{
        fx(()->{var base=ForumFixtures.post(false);var image=new PostImageRef(9,"image/png",20,"a".repeat(64),100,80,0);var post=new PostDetail(base.id(),base.sectionId(),base.sectionName(),base.authorUserId(),base.authorDisplayName(),base.title(),base.content(),base.status(),base.locked(),base.pinned(),base.featured(),base.viewCount(),base.commentCount(),base.createdAt(),base.updatedAt(),base.lastCommentedAt(),base.canDelete(),List.of(image));var detail=new ForumDetailView(()->{},(text,reply)->{},id->{},()->{},()->{},()->{},page->{},(type,id)->{},ignored->{});detail.show(post,ForumFixtures.comments(),new ForumData.Engagement(1,false,false));new Scene(detail,1000,760);detail.applyCss();detail.layout();assertNotNull(detail.lookup("#forum-image-gallery"));assertNotNull(detail.lookup("#forum-post-image-9"));return null;});
    }
    @Test void thumbnailLoadedBeforeFirstLayoutIsNotDiscarded()throws Exception{
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        fx(()->{var base=ForumFixtures.post(false);var image=new PostImageRef(9,"image/png",png.length,"a".repeat(64),1,1,0);var post=new PostDetail(base.id(),base.sectionId(),base.sectionName(),base.authorUserId(),base.authorDisplayName(),base.title(),base.content(),base.status(),base.locked(),base.pinned(),base.featured(),base.viewCount(),base.commentCount(),base.createdAt(),base.updatedAt(),base.lastCommentedAt(),base.canDelete(),List.of(image));var detail=new ForumDetailView(()->{},(text,reply)->{},id->{},()->{},()->{},()->{},page->{},(type,id)->{},ignored->{});detail.show(post,ForumFixtures.comments(),new ForumData.Engagement(1,false,false));detail.imageLoaded(9,png);new Scene(detail,1000,760);detail.applyCss();detail.layout();assertNotNull(((javafx.scene.image.ImageView)detail.lookup("#forum-post-image-9")).getImage());assertEquals("查看大图",((Button)detail.lookup("#forum-image-frame-9")).getText());return null;});
    }
}
