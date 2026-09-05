package com.vcampus.server.service;

import com.vcampus.common.model.*;
import com.vcampus.common.protocol.*;
import com.vcampus.server.database.ForumCommunityStore;
import com.vcampus.server.database.ForumCommunityStore.*;
import com.vcampus.server.security.SessionManager;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

public final class ForumCommunityService {
    private final ForumCommunityStore store;
    private final SessionManager sessions;
    private final Clock clock;
    public ForumCommunityService(ForumCommunityStore store, SessionManager sessions, Clock clock) {
        this.store=store; this.sessions=sessions; this.clock=clock;
    }
    public ResponseMessage handle(RequestMessage request) {
        var session=sessions.find(request.parameters().get("sessionToken"));
        if(session.isEmpty()) return ResponseMessage.failure(request.requestId(),"登录已失效，请重新登录");
        if(!ForumAccessPolicy.canUse(session.get().roles())) return ResponseMessage.failure(request.requestId(),"无权访问论坛");
        if(session.get().forcePasswordChange()) return ResponseMessage.failure(request.requestId(),"请先修改初始密码");
        var v=request.parameters(); long user=session.get().userId();
        try {
            Map<String,String> data;
            switch(request.action()) {
                case Actions.FORUM_FEED_SEARCH -> {
                    String keyword=v.getOrDefault("keyword","");
                    if(keyword.length()>200) throw new IllegalArgumentException("搜索关键词不能超过200字");
                    int page=Math.toIntExact(positive(v.getOrDefault("page","1")));
                    if(page>100000) throw new IllegalArgumentException("页码过大");
                    var q=new FeedQuery(user,ForumFeedScope.valueOf(v.getOrDefault("scope","HOME")),
                            ForumFeedOrder.valueOf(v.getOrDefault("order","LATEST")),
                            v.containsKey("sectionId")?positive(v.get("sectionId")):null,keyword,page,20);
                    data=feed(store.searchFeed(q));
                }
                case Actions.FORUM_HOT_LIST -> {
                    var day=LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
                    Instant from=day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
                    Instant to=day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
                    var rows=store.hotToday(user,from,to); data=feed(new FeedPage(rows,1,5,rows.size()));
                }
                case Actions.FORUM_ENGAGEMENT_GET -> data=engagement(store.engagement(positive(v.get("postId")),user));
                case Actions.FORUM_LIKE_SET -> data=engagement(store.setLiked(positive(v.get("postId")),user,bool(v.get("enabled"))));
                case Actions.FORUM_BOOKMARK_SET -> data=engagement(store.setBookmarked(positive(v.get("postId")),user,bool(v.get("enabled"))));
                default -> throw new IllegalArgumentException("不支持的论坛操作");
            }
            data.put("forumVersion","2");
            return ResponseMessage.success(request.requestId(),"操作成功",data);
        } catch(IllegalArgumentException | ArithmeticException e) {
            return ResponseMessage.failure(request.requestId(),"论坛操作无效：" + e.getMessage());
        } catch(SQLException e) {
            System.err.println("Forum community database operation failed: " + e.getMessage());
            return ResponseMessage.failure(request.requestId(),"论坛数据库操作失败，请确认已运行最新 schema.sql");
        }
    }
    private long positive(String value) {
        long result=Long.parseLong(value); if(result<=0)throw new IllegalArgumentException("编号或页码无效"); return result;
    }
    private boolean bool(String value) {
        if(!"true".equals(value)&&!"false".equals(value)) throw new IllegalArgumentException("操作状态无效");
        return Boolean.parseBoolean(value);
    }
    private Map<String,String> engagement(Engagement value) {
        return new LinkedHashMap<>(Map.of("likeCount",Integer.toString(value.likeCount()),
                "liked",Boolean.toString(value.liked()),"bookmarked",Boolean.toString(value.bookmarked())));
    }
    private Map<String,String> feed(FeedPage page) {
        Map<String,String> data=new LinkedHashMap<>(Map.of("page",""+page.page(),"pageSize",""+page.pageSize(),
                "total",""+page.total(),"count",""+page.rows().size()));
        for(int i=0;i<page.rows().size();i++) {
            var row=page.rows().get(i); var p=row.post(); var e=row.engagement();
            data.put("row."+i,RowCodec.encode(""+p.id(),""+p.sectionId(),p.sectionName(),""+p.authorUserId(),
                    p.authorDisplayName(),p.title(),p.summary(),p.status().name(),""+p.locked(),""+p.pinned(),
                    ""+p.featured(),""+p.viewCount(),""+p.commentCount(),p.createdAt().toString(),
                    p.lastCommentedAt()==null?"":p.lastCommentedAt().toString()));
            data.put("engagement."+i,RowCodec.encode(""+e.likeCount(),""+e.liked(),""+e.bookmarked(),""+row.announcement()));
        }
        return data;
    }
}
