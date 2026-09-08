package com.vcampus.client.fx.forum;

import com.vcampus.common.model.*;
import com.vcampus.common.protocol.*;
import com.vcampus.client.ui.ForumViewData;
import java.time.Instant;
import java.util.*;

public final class ForumData {
    private ForumData() { }
    public record FeedFilter(ForumFeedScope scope, ForumFeedOrder order, Long sectionId, String keyword, int page) {
        public static FeedFilter home() { return new FeedFilter(ForumFeedScope.HOME,ForumFeedOrder.LATEST,null,"",1); }
    }
    public record Engagement(int likeCount, boolean liked, boolean bookmarked) {
        public Engagement { if(likeCount<0)throw new IllegalArgumentException("点赞数无效"); }
    }
    public record FeedRow(ForumViewData.PostRow post, Engagement engagement, boolean announcement) { }
    public record FeedPage(List<FeedRow> rows,int page,int pageSize,int total) {
        public FeedPage { rows=List.copyOf(rows); }
    }
    public record Comment(ForumViewData.CommentRow row,Long replyToId,String replyToName,boolean replyVisible) { }
    public record CommentPage(List<Comment> rows,int page,int pageSize,int total) {
        public CommentPage { rows=List.copyOf(rows); }
    }
    public record ReportRow(long id,ForumTargetType targetType,long targetId,long postId,
                            String content,long reporterUserId,String reporterDisplayName,
                            ForumReportReason reason,String detail,ForumReportStatus status,
                            ForumReportResolution resolution,String handlerDisplayName,
                            Instant createdAt,Instant handledAt) { }
    public record ReportPage(List<ReportRow> rows,int page,int pageSize,int total) {
        public ReportPage { rows=List.copyOf(rows); }
    }
    public enum ImageVariant {THUMBNAIL,DETAIL}
    public record UploadTicket(String uploadId,long expectedBytes,int chunkBytes){}
    public record ImageChunk(long imageId,ImageVariant variant,String mimeType,String sha256,
                             long totalBytes,int totalChunks,int chunkIndex,byte[] content){public ImageChunk{content=content.clone();}public byte[] content(){return content.clone();}}
    public static Engagement engagement(ResponseMessage r) {
        version(r);
        return new Engagement(Integer.parseInt(r.data().get("likeCount")),bool(r.data().get("liked")),bool(r.data().get("bookmarked")));
    }
    public static FeedPage feed(ResponseMessage r) {
        version(r); var page=ForumViewData.postPage(r); List<FeedRow> rows=new ArrayList<>();
        for(int i=0;i<page.rows().size();i++) {
            var e=RowCodec.decode(r.data().get("engagement."+i));
            if(e.size()!=4)throw new IllegalArgumentException("论坛互动数据不完整");
            rows.add(new FeedRow(page.rows().get(i),new Engagement(Integer.parseInt(e.get(0)),bool(e.get(1)),bool(e.get(2))),bool(e.get(3))));
        }
        return new FeedPage(rows,page.page(),page.pageSize(),page.total());
    }
    public static CommentPage comments(ResponseMessage r) {
        var page=ForumViewData.commentPage(r); List<Comment> rows=new ArrayList<>();
        for(int i=0;i<page.rows().size();i++) {
            String extra=r.data().get("reply."+i);
            if(extra==null) throw new IllegalArgumentException("论坛服务版本不兼容，请更新服务端");
            var fields=RowCodec.decode(extra);
            if(fields.size()!=3)throw new IllegalArgumentException("评论回复数据不完整");
            Long id=fields.get(0).isBlank()?null:Long.valueOf(fields.get(0));
            rows.add(new Comment(page.rows().get(i),id,fields.get(1),bool(fields.get(2))));
        }
        return new CommentPage(rows,page.page(),page.pageSize(),page.total());
    }
    public static ReportPage reports(ResponseMessage r) {
        int count=Integer.parseInt(r.data().getOrDefault("count","0"));
        List<ReportRow> rows=new ArrayList<>();
        for(int i=0;i<count;i++) {
            var f=RowCodec.decode(r.data().get("row."+i));
            if(f.size()!=14)throw new IllegalArgumentException("举报数据不完整");
            rows.add(new ReportRow(Long.parseLong(f.get(0)),ForumTargetType.valueOf(f.get(1)),
                    Long.parseLong(f.get(2)),Long.parseLong(f.get(3)),f.get(4),Long.parseLong(f.get(5)),
                    f.get(6),ForumReportReason.valueOf(f.get(7)),f.get(8),ForumReportStatus.valueOf(f.get(9)),
                    f.get(10).isBlank()?null:ForumReportResolution.valueOf(f.get(10)),f.get(11),
                    Instant.parse(f.get(12)),f.get(13).isBlank()?null:Instant.parse(f.get(13))));
        }
        return new ReportPage(rows,Integer.parseInt(r.data().getOrDefault("page","1")),
                Integer.parseInt(r.data().getOrDefault("pageSize","10")),
                Integer.parseInt(r.data().getOrDefault("total","0")));
    }
    public static UploadTicket uploadTicket(ResponseMessage r){return new UploadTicket(r.data().get("uploadId"),Long.parseLong(r.data().get("expectedBytes")),Integer.parseInt(r.data().get("chunkBytes")));}
    public static ImageChunk imageChunk(ResponseMessage r){try{var d=r.data();return new ImageChunk(Long.parseLong(d.get("imageId")),ImageVariant.valueOf(d.get("variant")),d.get("mimeType"),d.get("sha256"),Long.parseLong(d.get("totalBytes")),Integer.parseInt(d.get("totalChunks")),Integer.parseInt(d.get("chunkIndex")),Base64.getDecoder().decode(d.get("contentBase64")));}catch(RuntimeException e){throw new IllegalArgumentException("图片分块数据不完整",e);}}
    private static void version(ResponseMessage r) {
        if(!r.success() || !"2".equals(r.data().get("forumVersion")))
            throw new IllegalArgumentException("论坛服务版本不兼容，请更新服务端");
    }
    private static boolean bool(String s) {
        if(!"true".equals(s)&&!"false".equals(s))throw new IllegalArgumentException("论坛状态数据无效");
        return Boolean.parseBoolean(s);
    }
}
