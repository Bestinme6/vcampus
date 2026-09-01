package com.vcampus.client.fx.forum;

import com.vcampus.client.ui.ForumViewData.*;
import com.vcampus.common.model.*;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Session-owned coordinator. All UI state stays on the JavaFX application thread. */
public final class ForumController implements AutoCloseable {
    private final ForumGateway gateway;private final ForumAsync async;private final ForumView view;
    private final boolean manager;private boolean closed;
    private ForumData.FeedFilter filter=ForumData.FeedFilter.home();
    private List<SectionRow> sections=List.of();
    private ForumHomeView home;private ForumEditorView editor;private ForumDetailView detail;private ForumAdminView admin;
    private double homeScroll;private long postId;private int commentPage=1;private long adminVersion;
    private ForumData.Engagement engagement;
    private record HomeData(ForumData.FeedPage page,List<SectionRow> sections){}
    private record DetailData(PostDetail post,ForumData.CommentPage comments,ForumData.Engagement engagement){}
    public ForumController(ForumGateway gateway,Set<UserRole> roles,ExecutorService requests,Runnable workspace){
        requireFx();this.gateway=gateway;manager=ForumAccessPolicy.canManage(roles);
        async=new ForumAsync(requests,Platform::runLater);
        view=new ForumView(manager,this::openEditor,()->{if(canLeave())workspace.run();},this::navigate,
                keyword->{if(canLeave()){filter=new ForumData.FeedFilter(ForumFeedScope.HOME,ForumFeedOrder.LATEST,filter.sectionId(),keyword,1);loadHome(false);}});
    }
    public Parent view(){return view;}
    public void openHome(){requireFx();if(!closed)loadHome(true);}
    private void navigate(String scope){
        if(!canLeave()||closed)return;
        if(scope.equals("ADMIN")){openAdmin();return;}
        filter=new ForumData.FeedFilter(ForumFeedScope.valueOf(scope),ForumFeedOrder.LATEST,null,"",1);
        view.keyword("");loadHome(false);
    }
    private void loadHome(boolean restore){
        if(closed)return;editor=null;async.invalidate();view.selected(filter.scope().name());
        var requested=filter;
        view.loading("正在读取校园动态","精彩的讨论，马上就来");
        async.run(()->new HomeData(gateway.feed(requested),gateway.sections()),data->{
            sections=data.sections();home=new ForumHomeView(f->{filter=f;loadHome(false);},this::openPost);
            home.show(requested,data.page(),sections);view.page(home);if(restore)home.setVvalue(homeScroll);
            ForumHomeView active=home;
            async.run(gateway::hot,active::hot,active::hotError);
            async.run(()->gateway.feed(new ForumData.FeedFilter(ForumFeedScope.ANNOUNCEMENTS,ForumFeedOrder.LATEST,null,"",1)),
                    result->active.notices(result.rows().stream().limit(3).toList()),active::noticeError);
        },error->view.message("暂时无法读取论坛",error,()->loadHome(restore)));
    }
    public void openPost(long id){
        requireFx();if(closed||!canLeave())return;
        if(home!=null&&view.getCenter()==home)homeScroll=home.getVvalue();
        if(id<=0){view.message("无法打开帖子","消息缺少有效的帖子编号",this::openHome);return;}
        postId=id;commentPage=1;loadPost();
    }
    private void loadPost(){
        async.invalidate();editor=null;
        long id=postId;int page=commentPage;
        view.loading("正在打开帖子","正在读取正文和评论");
        async.run(()->{
            var post=gateway.post(id);
            if(post.status()!=ForumContentStatus.NORMAL)throw new IllegalArgumentException("该帖子已隐藏或删除，请到内容管理查看审核记录");
            return new DetailData(post,gateway.comments(id,page),gateway.engagement(id));
        },data->{
            engagement=data.engagement();
            detail=new ForumDetailView(()->loadHome(true),this::sendComment,this::deleteComment,
                    ()->interaction(true),()->interaction(false),this::deletePost,
                    next->{commentPage=next;loadPost();});
            detail.show(data.post(),data.comments(),engagement);view.page(detail);
        },error->view.message("该帖子当前不可访问",error,()->loadHome(true)));
    }
    private void sendComment(String text,Long reply){
        long id=postId;ForumDetailView active=detail;
        async.run(()->gateway.createComment(id,text,reply),created->loadPost(),active::error);
    }
    private void interaction(boolean like){
        long id=postId;ForumDetailView active=detail;active.busy(true);
        boolean state=like?!engagement.liked():!engagement.bookmarked();
        async.run(()->like?gateway.setLiked(id,state):gateway.setBookmarked(id,state),result->{
            engagement=result;active.engagement(result);active.busy(false);
        },error->{active.busy(false);active.error(error);});
    }
    private void deleteComment(long id){
        if(!confirm("删除评论","删除后不能恢复，确定删除这条评论吗？"))return;
        ForumDetailView active=detail;active.busy(true);
        async.run(()->{gateway.deleteComment(id);return true;},ok->loadPost(),error->{active.busy(false);active.error(error);});
    }
    private void deletePost(){
        if(!confirm("删除帖子","确定删除这篇帖子吗？"))return;
        long id=postId;ForumDetailView active=detail;active.busy(true);
        async.run(()->{gateway.deletePost(id);return true;},ok->loadHome(true),error->{active.busy(false);active.error(error);});
    }
    private void openEditor(){
        if(!canLeave()||closed)return;
        async.invalidate();
        view.loading("准备发布","正在读取可用板块");
        async.run(gateway::sections,rows->{
            sections=rows;editor=new ForumEditorView(rows,(section,title,text)->{
                ForumEditorView active=editor;
                async.run(()->gateway.createPost(section,title,text),id->{editor=null;openPost(id);},active::error);
            },()->{if(canLeave())loadHome(true);});
            view.page(editor);
        },error->view.message("暂时无法发帖",error,this::openEditor));
    }
    public boolean canLeave(){
        requireFx();
        if(editor!=null&&editor.dirty()) {
            if(!confirm("尚未发布","离开会丢弃正在编辑的内容，确定离开吗？"))return false;
            editor=null;
        }
        return true;
    }
    private boolean confirm(String title,String text){
        Alert alert=new Alert(Alert.AlertType.CONFIRMATION,text,ButtonType.OK,ButtonType.CANCEL);
        alert.setTitle(title);alert.setHeaderText(null);
        if(view.getScene()!=null&&view.getScene().getWindow()!=null)alert.initOwner(view.getScene().getWindow());
        styleDialog(alert.getDialogPane());return alert.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK;
    }
    private void styleDialog(DialogPane pane){pane.getStyleClass().add("forum-root");pane.getStylesheets().addAll(view.getStylesheets());}
    private void openAdmin(){
        if(!manager||closed)return;async.invalidate();editor=null;view.selected("ADMIN");
        admin=new ForumAdminView(this::loadAdminContent,this::moderate,
                values->adminMutation(()->{gateway.saveSection(values);return true;}),
                (id,enabled)->adminMutation(()->{gateway.setSectionEnabled(id,enabled);return true;}),
                this::loadLogs);
        view.page(admin);loadAdminContent(admin.query());loadSections();loadLogs(1);
    }
    private void loadAdminContent(ForumAdminView.Query q){
        long version=++adminVersion;ForumAdminView active=admin;
        async.run(()->gateway.adminContent(q.target(),q.status(),q.keyword(),q.page()),
                page->{if(version==adminVersion)active.showContent(page);},active::error);
    }
    private void loadSections(){ForumAdminView active=admin;async.run(gateway::adminSections,active::showSections,active::error);}
    private void loadLogs(int page){ForumAdminView active=admin;async.run(()->gateway.moderationLogs(page),active::showLogs,active::error);}
    private void moderate(AdminContentRow row,ForumModerationAction action){
        TextInputDialog dialog=new TextInputDialog();dialog.setTitle("论坛内容管理");dialog.setHeaderText("请填写本次操作的原因（2—255字）");
        dialog.setContentText("原因");styleDialog(dialog.getDialogPane());
        if(view.getScene()!=null&&view.getScene().getWindow()!=null)dialog.initOwner(view.getScene().getWindow());
        dialog.showAndWait().ifPresent(reason->{
            if(reason.strip().length()<2||reason.strip().length()>255){admin.error("管理原因需要2—255个字符");return;}
            adminMutation(()->{if(row.targetType()==ForumTargetType.POST)gateway.moderatePost(row.id(),action,reason.strip());
                else gateway.moderateComment(row.id(),action,reason.strip());return true;});
        });
    }
    private void adminMutation(Callable<Boolean> work){
        ForumAdminView active=admin;active.busy(true);
        async.run(work,ok->{active.busy(false);active.error("操作完成");loadAdminContent(active.query());loadSections();loadLogs(1);},
                error->{active.busy(false);active.error(error);});
    }
    public void deactivate(){requireFx();if(home!=null&&view.getCenter()==home)homeScroll=home.getVvalue();async.invalidate();}
    @Override public void close(){requireFx();closed=true;async.close();editor=null;home=null;detail=null;admin=null;view.page(new javafx.scene.layout.Pane());}
    private static void requireFx(){if(!Platform.isFxApplicationThread())throw new IllegalStateException("论坛界面必须在JavaFX线程操作");}
}
