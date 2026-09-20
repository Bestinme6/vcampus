package com.vcampus.client.fx.forum;

import com.vcampus.client.ui.ForumViewData.*;
import com.vcampus.common.model.*;
import java.io.IOException;
import java.util.*;

/**
 * 论坛控制器所需的远程用例边界。
 *
 * @since 1.0
 */
public interface ForumGateway {
    final class BusinessException extends IOException {
        public BusinessException(String message){super(message);}
    }
    List<SectionRow> sections() throws IOException;
    ForumData.FeedPage feed(ForumData.FeedFilter filter) throws IOException;
    List<ForumData.FeedRow> hot() throws IOException;
    PostDetail post(long id) throws IOException;
    ForumData.CommentPage comments(long id,int page) throws IOException;
    ForumData.Engagement engagement(long id) throws IOException;
    ForumData.Engagement setLiked(long id,boolean enabled) throws IOException;
    ForumData.Engagement setBookmarked(long id,boolean enabled) throws IOException;
    ForumData.BoostState boostState(long postId) throws IOException;
    ForumData.BoostReceipt createBoost(long postId,ForumBoostTier tier,UUID operationId) throws IOException;
    default long createPost(long section,String title,String content)throws IOException{return createPost(section,title,content,List.of());}
    long createPost(long section,String title,String content,List<String> imageUploadIds) throws IOException;
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
    void report(ForumTargetType target,long id,ForumReportReason reason,String detail) throws IOException;
    boolean reported(ForumTargetType target,long id) throws IOException;
    ForumData.ReportPage reports(ForumReportStatus status,ForumTargetType target,
                                 ForumReportReason reason,int page) throws IOException;
    void resolveReport(long id,ForumReportResolution resolution,String note) throws IOException;
    ForumData.UploadTicket imageUploadStart(String mimeType,long bytes)throws IOException;
    void imageUploadChunk(String uploadId,int index,byte[] bytes)throws IOException;
    void imageUploadComplete(String uploadId)throws IOException;
    ForumData.ImageChunk imageChunk(long imageId,ForumData.ImageVariant variant,int index)throws IOException;
}
