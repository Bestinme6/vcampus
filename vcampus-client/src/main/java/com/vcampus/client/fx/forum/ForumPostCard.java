package com.vcampus.client.fx.forum;

import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.kordamp.ikonli.feather.Feather;
import java.util.function.LongConsumer;
import com.vcampus.common.model.ForumContentStatus;

final class ForumPostCard extends VBox {
    ForumPostCard(ForumData.FeedRow item,LongConsumer open) {
        super(14);getStyleClass().addAll("forum-card","forum-post-card");setMinWidth(0);
        var p=item.post();FlowPane tags=new FlowPane(8,6);
        tags.getChildren().add(ForumStyles.label(p.sectionName(),"forum-tag"));
        if(p.pinned())tags.getChildren().add(ForumStyles.label("置顶","forum-tag-gold"));
        if(p.featured())tags.getChildren().add(ForumStyles.label("精华","forum-tag-green"));
        if(item.announcement())tags.getChildren().add(ForumStyles.label("管理员公告","forum-tag"));
        if(p.status()!=ForumContentStatus.NORMAL)tags.getChildren().add(ForumStyles.label(ForumStyles.status(p.status()),"forum-tag-gold"));
        var time=ForumStyles.label(ForumStyles.time(p.createdAt()),"forum-caption");time.setMinWidth(85);
        var heading=ForumStyles.row(tags,ForumStyles.spacer(),time);
        Button title=ForumStyles.button(p.title(),null,()->open.accept(p.id()),"forum-post-title");
        title.setWrapText(true);title.setMaxWidth(Double.MAX_VALUE);title.setMinWidth(0);title.setId("forum-post-"+p.id());
        Label summary=ForumStyles.label(p.summary().replace('\n',' '),"forum-summary");summary.setMaxHeight(46);
        String name=p.authorDisplayName();
        var avatar=ForumStyles.label(name.isBlank()?"同":name.substring(0,1),"forum-avatar");
        avatar.setMinSize(36,36);
        var author=new VBox(3,ForumStyles.label(name,"forum-author"),ForumStyles.label("校园社区成员","forum-caption"));
        var counts=ForumStyles.label("赞同 "+item.engagement().likeCount()+"    评论 "+p.commentCount(),"forum-caption");
        var footer=ForumStyles.row(avatar,author,ForumStyles.spacer(),counts);footer.getStyleClass().add("forum-card-footer");
        getChildren().addAll(heading,title,summary,footer);
    }
}
