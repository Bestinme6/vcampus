package com.vcampus.client.fx.forum;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.client.ui.ForumViewData;
import com.vcampus.client.ui.ForumViewData.*;
import com.vcampus.common.model.*;
import com.vcampus.common.protocol.ResponseMessage;
import java.io.IOException;
import java.util.*;

/** Synchronous network boundary: the controller always invokes this off the UI thread. */
public final class SocketForumGateway implements ForumGateway {
    private final VCampusClient client; private final String token;
    public SocketForumGateway(VCampusClient client,String token){this.client=client;this.token=token;}
    private ResponseMessage ok(ResponseMessage r) throws IOException {
        if(!r.success())throw new IOException(r.message()); return r;
    }
    public List<SectionRow> sections()throws IOException{return ForumViewData.sections(ok(client.listForumSections(token,false)));}
    public List<SectionRow> adminSections()throws IOException{return ForumViewData.sections(ok(client.listForumSections(token,true)));}
    public ForumData.FeedPage feed(ForumData.FeedFilter f)throws IOException{return ForumData.feed(ok(client.searchForumFeed(token,f.scope().name(),f.order().name(),f.sectionId(),f.keyword(),f.page())));}
    public List<ForumData.FeedRow> hot()throws IOException{return ForumData.feed(ok(client.listForumHot(token))).rows();}
    public PostDetail post(long id)throws IOException{return ForumViewData.postDetail(ok(client.getForumPost(token,id)));}
    public ForumData.CommentPage comments(long id,int page)throws IOException{return ForumData.comments(ok(client.listForumComments(token,id,page)));}
    public ForumData.Engagement engagement(long id)throws IOException{return ForumData.engagement(ok(client.getForumEngagement(token,id)));}
    public ForumData.Engagement setLiked(long id,boolean enabled)throws IOException{return ForumData.engagement(ok(client.setForumLiked(token,id,enabled)));}
    public ForumData.Engagement setBookmarked(long id,boolean enabled)throws IOException{return ForumData.engagement(ok(client.setForumBookmarked(token,id,enabled)));}
    public long createPost(long section,String title,String content)throws IOException{return Long.parseLong(ok(client.createForumPost(token,section,title,content)).data().get("postId"));}
    public long createComment(long post,String content,Long reply)throws IOException{return Long.parseLong(ok(client.createForumComment(token,post,content,reply)).data().get("commentId"));}
    public void deletePost(long id)throws IOException{ok(client.deleteForumPost(token,id));}
    public void deleteComment(long id)throws IOException{ok(client.deleteForumComment(token,id));}
    public long saveSection(Map<String,String> values)throws IOException{return Long.parseLong(ok(client.saveForumSection(token,values)).data().get("sectionId"));}
    public void setSectionEnabled(long id,boolean enabled)throws IOException{ok(client.setForumSectionEnabled(token,id,enabled));}
    public AdminContentPage adminContent(ForumTargetType target,ForumContentStatus status,String keyword,int page)throws IOException{return ForumViewData.adminContentPage(ok(client.searchForumAdminContent(token,target,status,keyword,page)));}
    public void moderatePost(long id,ForumModerationAction action,String reason)throws IOException{ok(client.moderateForumPost(token,id,action,reason));}
    public void moderateComment(long id,ForumModerationAction action,String reason)throws IOException{ok(client.moderateForumComment(token,id,action,reason));}
    public ModerationLogPage moderationLogs(int page)throws IOException{return ForumViewData.moderationLogPage(ok(client.searchForumModerationLogs(token,page)));}
}
