package com.vcampus.client.fx.forum;

import com.vcampus.client.ui.ForumViewData.*;
import com.vcampus.common.model.*;
import java.io.IOException;
import java.util.*;

public interface ForumGateway {
    List<SectionRow> sections() throws IOException;
    ForumData.FeedPage feed(ForumData.FeedFilter filter) throws IOException;
    List<ForumData.FeedRow> hot() throws IOException;
    PostDetail post(long id) throws IOException;
    ForumData.CommentPage comments(long id,int page) throws IOException;
    ForumData.Engagement engagement(long id) throws IOException;
    ForumData.Engagement setLiked(long id,boolean enabled) throws IOException;
    ForumData.Engagement setBookmarked(long id,boolean enabled) throws IOException;
    long createPost(long section,String title,String content) throws IOException;
    long createComment(long post,String content,Long reply) throws IOException;
    void deletePost(long id) throws IOException;
    void deleteComment(long id) throws IOException;
    List<SectionRow> adminSections() throws IOException;
    long saveSection(Map<String,String> values) throws IOException;
    void setSectionEnabled(long id,boolean enabled) throws IOException;
    AdminContentPage adminContent(ForumTargetType target,ForumContentStatus status,String keyword,int page) throws IOException;
    void moderatePost(long id,ForumModerationAction action,String reason) throws IOException;
    void moderateComment(long id,ForumModerationAction action,String reason) throws IOException;
    ModerationLogPage moderationLogs(int page) throws IOException;
}
