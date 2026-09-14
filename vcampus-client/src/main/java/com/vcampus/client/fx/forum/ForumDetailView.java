package com.vcampus.client.fx.forum;

import com.vcampus.client.ui.ForumViewData.PostDetail;
import com.vcampus.client.ui.ForumViewData.CommentRow;
import com.vcampus.common.model.ForumContentStatus;
import com.vcampus.common.model.ForumTargetType;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import org.kordamp.ikonli.feather.Feather;
import java.util.HashMap;
import java.util.Map;
import java.util.function.*;

public final class ForumDetailView extends ScrollPane {
    private final VBox body=new VBox(20),comments=new VBox(14);
    private final TextArea input=new TextArea();
    private final Label context=ForumStyles.label("写下你的想法，让讨论继续","forum-muted"),error=ForumStyles.label("","forum-error");
    private final Button send;
    private final BiConsumer<String,Long> submit;private final Consumer<CommentRow> delete;
    private final BiConsumer<ForumTargetType,Long> report;
    private final Consumer<com.vcampus.client.ui.ForumViewData.PostImageRef> openImage;
    private final Map<Long,ImageView> imageViews=new HashMap<>();
    private final Map<Long,Button> imageFrames=new HashMap<>();
    private final Runnable back,like,bookmark,deletePost;private final IntConsumer page;
    private Long replyTo;private boolean commentable;
    public ForumDetailView(Runnable back,BiConsumer<String,Long> submit,Consumer<CommentRow> delete,Runnable like,Runnable bookmark,Runnable deletePost,IntConsumer page){
        this(back,submit,delete,like,bookmark,deletePost,page,(target,id)->{},image->{});
    }
    public ForumDetailView(Runnable back,BiConsumer<String,Long> submit,Consumer<CommentRow> delete,Runnable like,Runnable bookmark,Runnable deletePost,IntConsumer page,BiConsumer<ForumTargetType,Long> report){
        this(back,submit,delete,like,bookmark,deletePost,page,report,image->{});
    }
    public ForumDetailView(Runnable back,BiConsumer<String,Long> submit,Consumer<CommentRow> delete,Runnable like,Runnable bookmark,Runnable deletePost,IntConsumer page,BiConsumer<ForumTargetType,Long> report,Consumer<com.vcampus.client.ui.ForumViewData.PostImageRef> openImage){
        this.back=back;this.submit=submit;this.delete=delete;this.like=like;this.bookmark=bookmark;this.deletePost=deletePost;this.page=page;
        this.report=report;
        this.openImage=openImage;
        body.getStyleClass().add("forum-reading");ForumStyles.scroll(this,body);
        input.setId("forum-comment-input");input.setWrapText(true);input.setPrefRowCount(3);input.setPromptText("友善交流，认真回应…");
        send=ForumStyles.button("发表评论",Feather.SEND,this::sendComment,"forum-primary");send.setId("forum-comment-send");
    }
    private void sendComment(){
        String text=input.getText().strip();if(text.isEmpty()||text.length()>2000){error.setText("评论需要1—2000个字符");return;}
        send.setDisable(true);submit.accept(text,replyTo);
    }
    public void show(PostDetail post,ForumData.CommentPage replies,ForumData.Engagement engagement){
        body.getChildren().clear();comments.getChildren().clear();imageViews.clear();imageFrames.clear();
        body.getChildren().add(ForumStyles.button("返回帖子列表",Feather.ARROW_LEFT,back,"forum-text-button"));
        var tags=new FlowPane(8,8);tags.getChildren().add(ForumStyles.label(post.sectionName(),"forum-tag"));
        if(post.pinned())tags.getChildren().add(ForumStyles.label("置顶","forum-tag-gold"));
        if(post.featured())tags.getChildren().add(ForumStyles.label("精华","forum-tag-green"));
        Label title=ForumStyles.label(post.title(),"forum-detail-title");
        var author=ForumStyles.label(post.authorDisplayName()+"  ·  "+ForumStyles.time(post.createdAt())+"  ·  "+post.viewCount()+" 次浏览","forum-muted");
        var content=ForumStyles.label(post.content(),"forum-body");
        var likeButton=ForumStyles.button((engagement.liked()?"已赞同 ":"赞同 ")+engagement.likeCount(),Feather.THUMBS_UP,like,"forum-quiet");likeButton.setId("forum-like");
        var saveButton=ForumStyles.button(engagement.bookmarked()?"已收藏":"收藏帖子",Feather.BOOKMARK,bookmark,"forum-quiet");saveButton.setId("forum-bookmark");
        var actions=ForumStyles.row(likeButton,saveButton,ForumStyles.spacer());
        if(post.canDelete())actions.getChildren().add(ForumStyles.button("删除帖子",Feather.TRASH_2,deletePost,"forum-text-button"));
        else {var reportButton=ForumStyles.button("举报帖子",Feather.FLAG,()->report.accept(ForumTargetType.POST,post.id()),"forum-text-button");reportButton.setId("forum-report-post");actions.getChildren().add(reportButton);}
        VBox postCard=ForumStyles.card(tags,title,author,new Separator(),content);
        if(!post.images().isEmpty()){FlowPane gallery=new FlowPane(10,10);gallery.setId("forum-image-gallery");for(var image:post.images()){ImageView preview=new ImageView();preview.setId("forum-post-image-"+image.id());preview.setFitWidth(210);preview.setFitHeight(150);preview.setPreserveRatio(true);Button frame=new Button("正在加载…",preview);frame.setContentDisplay(ContentDisplay.TOP);frame.setOnAction(e->openImage.accept(image));frame.setId("forum-image-frame-"+image.id());imageViews.put(image.id(),preview);imageFrames.put(image.id(),frame);gallery.getChildren().add(frame);}postCard.getChildren().add(gallery);}
        postCard.getChildren().add(actions);body.getChildren().add(postCard);
        commentable=post.status()==ForumContentStatus.NORMAL&&!post.locked();
        context.setText(commentable?"写下你的想法，让讨论继续":"帖子已锁定，暂时不能发表评论");
        input.setDisable(!commentable);send.setDisable(!commentable);
        var cancel=ForumStyles.button("取消回复",null,()->{replyTo=null;context.setText("写下你的想法，让讨论继续");},"forum-text-button");
        body.getChildren().add(ForumStyles.card(ForumStyles.label("参与讨论","forum-section-title"),context,input,ForumStyles.row(error,ForumStyles.spacer(),cancel,send)));
        body.getChildren().add(ForumStyles.label("全部评论 · "+replies.total(),"forum-section-title"));
        for(var c:replies.rows()){
            var row=c.row();var line=ForumStyles.row(ForumStyles.label(row.authorDisplayName(),"forum-author"),ForumStyles.spacer(),ForumStyles.label(ForumStyles.time(row.createdAt()),"forum-caption"));
            var card=ForumStyles.card(line);
            if(c.replyToId()!=null)card.getChildren().add(ForumStyles.label(c.replyVisible()?"回复 "+c.replyToName():"原评论已不可见","forum-reply-context"));
            card.getChildren().add(ForumStyles.label(row.content(),"forum-comment-body"));
            var reply=ForumStyles.button("回复",Feather.CORNER_UP_LEFT,()->{replyTo=row.id();context.setText("回复 "+row.authorDisplayName());input.requestFocus();setVvalue(.45);},"forum-text-button");
            reply.setDisable(!commentable);
            var ops=ForumStyles.row(ForumStyles.spacer(),reply);
            if(row.canDelete())ops.getChildren().add(ForumStyles.button("删除",Feather.TRASH_2,()->delete.accept(row),"forum-text-button"));
            else {var reportButton=ForumStyles.button("举报",Feather.FLAG,()->report.accept(ForumTargetType.COMMENT,row.id()),"forum-text-button");reportButton.setId("forum-report-comment-"+row.id());ops.getChildren().add(reportButton);}
            card.getChildren().add(ops);comments.getChildren().add(card);
        }
        if(replies.rows().isEmpty())comments.getChildren().add(ForumStyles.label("还没有评论，来分享你的想法吧。","forum-muted"));
        body.getChildren().add(comments);
        var previous=ForumStyles.button("上一页",Feather.CHEVRON_LEFT,()->page.accept(replies.page()-1),"forum-quiet");
        var next=ForumStyles.button("下一页",Feather.CHEVRON_RIGHT,()->page.accept(replies.page()+1),"forum-quiet");
        previous.setDisable(replies.page()<=1);next.setDisable((long)replies.page()*replies.pageSize()>=replies.total());
        body.getChildren().add(ForumStyles.row(previous,ForumStyles.label("第 "+replies.page()+" 页","forum-caption"),next));
    }
    public void error(String message){error.setText(message);send.setDisable(!commentable);}
    public void engagement(ForumData.Engagement state){
        ((Button)body.lookup("#forum-like")).setText((state.liked()?"已赞同 ":"赞同 ")+state.likeCount());
        ((Button)body.lookup("#forum-bookmark")).setText(state.bookmarked()?"已收藏":"收藏帖子");
    }
    public void busy(boolean busy){body.setDisable(busy);}
    public void reported(ForumTargetType target,long id){
        String selector=target==ForumTargetType.POST?"#forum-report-post":"#forum-report-comment-"+id;
        if(body.lookup(selector) instanceof Button button){button.setText("已举报");button.setDisable(true);}
    }
    public void imageLoaded(long id,byte[] bytes){ImageView view=imageViews.get(id);if(view==null)return;view.setImage(new Image(new java.io.ByteArrayInputStream(bytes)));Button button=imageFrames.get(id);if(button!=null){button.setText("查看大图");button.setId("forum-image-frame-"+id);}}
    public void imageFailed(com.vcampus.client.ui.ForumViewData.PostImageRef image,String message){Button button=imageFrames.get(image.id());if(button!=null){button.setText("加载失败，点击重试");button.setId("forum-image-retry-"+image.id());button.setOnAction(e->openImage.accept(image));}error.setText(message);}
    public void showFullImage(byte[] bytes){Dialog<Void> dialog=new Dialog<>();dialog.setTitle("查看图片");dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);ImageView image=new ImageView(new Image(new java.io.ByteArrayInputStream(bytes)));image.setPreserveRatio(true);image.setFitWidth(900);image.setFitHeight(650);dialog.getDialogPane().setContent(image);if(getScene()!=null&&getScene().getWindow()!=null)dialog.initOwner(getScene().getWindow());dialog.show();}
}
