package com.vcampus.client.fx.forum;

import javafx.scene.Scene;
import javafx.embed.swing.SwingFXUtils;
import org.junit.jupiter.api.*;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.List;
import java.time.Instant;
import com.vcampus.client.ui.ForumViewData.*;
import com.vcampus.common.model.*;

class ForumVisualTest {
    @BeforeAll static void toolkit()throws Exception{ForumUiTest.toolkit();}
    @Test void captureActualForumPages()throws Exception {
        ForumUiTest.fx(()->{
            for(int width:new int[]{1220,1060,780}) {
                var root=new ForumView(true,()->{},()->{},s->{},s->{});
                var home=new ForumHomeView(f->{},id->{});root.page(home);
                home.show(ForumData.FeedFilter.home(),ForumFixtures.feed(),ForumFixtures.sections());
                home.hot(ForumFixtures.feed().rows());home.notices(ForumFixtures.feed().rows().subList(0,1));
                capture(root,"home-"+width,width,900);
            }
            var root=new ForumView(false,()->{},()->{},s->{},s->{});
            var detail=new ForumDetailView(()->{},(text,reply)->{},id->{},()->{},()->{},()->{},page->{});
            detail.show(ForumFixtures.post(false),ForumFixtures.comments(),new ForumData.Engagement(42,false,false));
            root.page(detail);capture(root,"detail",1220,1050);
            var editorRoot=new ForumView(false,()->{},()->{},s->{},s->{});
            editorRoot.page(new ForumEditorView(ForumFixtures.sections(),(s,t,c)->{},()->{}));capture(editorRoot,"editor",1060,900);
            var commentRoot=new ForumView(false,()->{},()->{},s->{},s->{});
            var commentDetail=new ForumDetailView(()->{},(text,reply)->{},id->{},()->{},()->{},()->{},page->{});
            commentDetail.show(ForumFixtures.post(false),ForumFixtures.comments(),new ForumData.Engagement(42,true,true));
            commentRoot.page(commentDetail);
            new Scene(commentRoot,780,720);commentRoot.applyCss();commentRoot.layout();commentDetail.setVvalue(1);
            save(commentRoot.getScene(),"detail-comments-780");
            for(String state:List.of("empty","error","loading")) {
                var stateRoot=new ForumView(false,()->{},()->{},s->{},s->{});
                if(state.equals("empty")) {
                    var home=new ForumHomeView(f->{},id->{});stateRoot.page(home);
                    home.show(ForumData.FeedFilter.home(),new ForumData.FeedPage(List.of(),1,20,0),ForumFixtures.sections());
                    home.hot(List.of());home.notices(List.of());
                } else if(state.equals("error"))stateRoot.message("暂时无法读取论坛","连接已断开，请确认服务端已启动后重试。",()->{});
                else stateRoot.loading("正在读取校园动态","正在读取正文和评论");
                capture(stateRoot,state,1060,800);
            }
            for(int width:new int[]{1220,780}) {
                var adminRoot=new ForumView(true,()->{},()->{},s->{},s->{});adminRoot.selected("ADMIN");
                var admin=new ForumAdminView(q->{},(row,action)->{},values->{},(id,enabled)->{},page->{});
                admin.showContent(new AdminContentPage(List.of(
                        new AdminContentRow(ForumTargetType.COMMENT,1,1L,"校园生活",10,"林清禾","","违反社区规范的评论；管理员应隐藏而非冒充作者删除。",ForumContentStatus.HIDDEN,false,false,false,Instant.EPOCH),
                        new AdminContentRow(ForumTargetType.COMMENT,2,1L,"学习问答",11,"周明远","","作者自行删除的评论：只留审核记录，不允许恢复。",ForumContentStatus.DELETED,false,false,false,Instant.EPOCH)),1,10,2));
                admin.showSections(ForumFixtures.sections());adminRoot.page(admin);
                capture(adminRoot,"admin-"+width,width,800);
                if(width==780){((javafx.scene.control.ScrollPane)admin.getCenter()).setVvalue(1);save(adminRoot.getScene(),"admin-bottom-780");}
            }
            return null;
        });
    }
    static void capture(ForumView root,String name,int width,int height)throws Exception{
        Scene scene=new Scene(root,width,height);root.applyCss();root.layout();
        save(scene,name);
    }
    private static void save(Scene scene,String name)throws Exception{
        scene.getRoot().applyCss();scene.getRoot().layout();
        double scale=Double.parseDouble(System.getProperty("forum.visual.scale","1"));
        var parameters=new javafx.scene.SnapshotParameters();
        parameters.setTransform(javafx.scene.transform.Transform.scale(scale,scale));
        var snapshot=scene.getRoot().snapshot(parameters,null);
        org.junit.jupiter.api.Assertions.assertEquals(Math.ceil(scene.getWidth()*scale),snapshot.getWidth(),1);
        Path file=Path.of("target",scale==1?"forum-screenshots":"forum-screenshots-125",name+".png");Files.createDirectories(file.getParent());
        ImageIO.write(SwingFXUtils.fromFXImage(snapshot,null),"png",file.toFile());
    }
}
