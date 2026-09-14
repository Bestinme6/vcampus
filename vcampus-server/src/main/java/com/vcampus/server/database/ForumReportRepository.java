package com.vcampus.server.database;

import com.vcampus.common.model.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class ForumReportRepository implements ForumReportStore {
    private final ConnectionFactory connections;
    private final NotificationWriter notifications;
    private final ForumNotificationFactory notificationFactory=new ForumNotificationFactory();
    public ForumReportRepository(ConnectionFactory connections){this(connections,new NotificationRepository(connections));}
    public ForumReportRepository(ConnectionFactory connections,NotificationWriter notifications){
        this.connections=Objects.requireNonNull(connections,"connections");
        this.notifications=Objects.requireNonNull(notifications,"notifications");
    }

    @Override public long create(long reporterId,ForumTargetType type,long targetId,
                                 ForumReportReason reason,String detail)throws SQLException{
        if(type!=ForumTargetType.POST&&type!=ForumTargetType.COMMENT)throw new IllegalArgumentException("举报对象类型无效");
        String text=detail==null?"":detail.strip();
        if(text.length()>300)throw new IllegalArgumentException("补充说明不能超过300字");
        try(Connection c=connections.openConnection()){
            c.setAutoCommit(false);
            try {
                Target target=lockTarget(c,type,targetId);
                if(target==null||target.status()!=ForumContentStatus.NORMAL||!target.parentVisible())throw new IllegalArgumentException("内容不存在或不可举报");
                if(target.authorId()==reporterId)throw new IllegalArgumentException("不能举报自己的内容");
                long reportId;
                try {
                    try(PreparedStatement s=c.prepareStatement("INSERT INTO forum_reports"
                            + " (reporter_user_id,target_type,target_id,reason,detail) VALUES(?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
                        s.setLong(1,reporterId);s.setString(2,type.name());s.setLong(3,targetId);s.setString(4,reason.name());s.setString(5,text);s.executeUpdate();
                        try(ResultSet keys=s.getGeneratedKeys()){
                            if(!keys.next())throw new SQLException("未返回举报编号");
                            reportId=keys.getLong(1);
                        }
                    }
                } catch(SQLException e) {
                    if(e.getSQLState()!=null&&e.getSQLState().startsWith("23"))throw new IllegalArgumentException("该内容已举报",e);
                    throw e;
                }
                String reporterName=userDisplayName(c,reporterId);
                List<NotificationWriter.NotificationDraft> drafts=administratorIds(c).stream()
                        .map(administratorId->notificationFactory.contentReported(
                                administratorId,reporterId,reporterName,type,
                                target.postId(),target.postTitle(),reason,text)).toList();
                notifications.insertBatch(c,drafts);
                c.commit();
                return reportId;
            }catch(SQLException e){
                c.rollback();
                throw e;
            }catch(RuntimeException e){c.rollback();throw e;}
        }
    }

    @Override public boolean reportedBy(long userId,ForumTargetType type,long targetId)throws SQLException{
        try(Connection c=connections.openConnection();PreparedStatement s=c.prepareStatement(
                "SELECT 1 FROM forum_reports WHERE reporter_user_id=? AND target_type=? AND target_id=?")){
            s.setLong(1,userId);s.setString(2,type.name());s.setLong(3,targetId);try(ResultSet r=s.executeQuery()){return r.next();}
        }
    }

    @Override public ReportPage search(ReportQuery q)throws SQLException{
        List<Object> values=new ArrayList<>();StringBuilder where=new StringBuilder(" WHERE 1=1");
        if(q.status()!=null){where.append(" AND r.status=?");values.add(q.status().name());}
        if(q.targetType()!=null){where.append(" AND r.target_type=?");values.add(q.targetType().name());}
        if(q.reason()!=null){where.append(" AND r.reason=?");values.add(q.reason().name());}
        try(Connection c=connections.openConnection()){
            int total;try(PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM forum_reports r"+where)){bind(s,values);try(ResultSet r=s.executeQuery()){r.next();total=r.getInt(1);}}
            String sql="""
                    SELECT r.*,u.display_name reporter_name,h.display_name handler_name,
                      CASE WHEN r.target_type='POST' THEN r.target_id ELSE c.post_id END post_id,
                      CASE WHEN r.target_type='POST' THEN CONCAT(p.title,'\n',p.content) ELSE c.content END target_content
                    FROM forum_reports r JOIN users u ON u.id=r.reporter_user_id
                    LEFT JOIN users h ON h.id=r.handled_by_user_id
                    LEFT JOIN forum_posts p ON r.target_type='POST' AND p.id=r.target_id
                    LEFT JOIN forum_comments c ON r.target_type='COMMENT' AND c.id=r.target_id
                    """+where+" ORDER BY CASE WHEN r.status='PENDING' THEN 0 ELSE 1 END,r.created_at,r.id LIMIT ? OFFSET ?";
            List<ReportRow> rows=new ArrayList<>();try(PreparedStatement s=c.prepareStatement(sql)){
                bind(s,values);s.setInt(values.size()+1,q.pageSize());s.setInt(values.size()+2,(q.page()-1)*q.pageSize());
                try(ResultSet r=s.executeQuery()){while(r.next())rows.add(row(r));}
            }
            return new ReportPage(rows,q.page(),q.pageSize(),total);
        }
    }

    @Override public ForumStore.MutationResult resolve(long reportId,ForumReportResolution resolution,
                                                        String note,long operatorId)throws SQLException{
        try(Connection c=connections.openConnection()){
            c.setAutoCommit(false);try{
                ReportTarget report=findReport(c,reportId);
                if(report==null)return rollback(c,ForumStore.MutationResult.NOT_FOUND);
                ForumTargetType expectedType=report.type();long expectedTargetId=report.targetId();
                Target target=lockTarget(c,expectedType,expectedTargetId);
                if(target==null)return rollback(c,ForumStore.MutationResult.NOT_FOUND);
                report=lockReport(c,reportId);
                if(report==null)return rollback(c,ForumStore.MutationResult.NOT_FOUND);
                if(report.type()!=expectedType||report.targetId()!=expectedTargetId)return rollback(c,ForumStore.MutationResult.CONFLICT);
                if(report.status()!=ForumReportStatus.PENDING)return rollback(c,ForumStore.MutationResult.UNCHANGED);
                String operatorName=userDisplayName(c,operatorId);
                if(resolution==ForumReportResolution.DISMISS){
                    updateOne(c,reportId,"DISMISSED",resolution,note,operatorId);
                    notifications.insert(c,notificationFactory.reportResolved(
                            report.reporterId(),operatorId,operatorName,report.type(),
                            target.postId(),target.postTitle(),resolution,note));
                    c.commit();return ForumStore.MutationResult.CHANGED;
                }
                List<Long> reporterIds=lockPendingReporterIds(c,report.type(),report.targetId());
                int changed;long postId=target.postId();
                if(report.type()==ForumTargetType.POST){
                    changed=updateTarget(c,"forum_posts",report.targetId(),resolution);
                }else{
                    changed=updateTarget(c,"forum_comments",report.targetId(),resolution);
                }
                if(changed!=1)return rollback(c,ForumStore.MutationResult.CONFLICT);
                if(report.type()==ForumTargetType.COMMENT)refreshCount(c,postId);
                try(PreparedStatement s=c.prepareStatement("UPDATE forum_reports SET status='ACTIONED',resolution_action=?,resolution_note=?,handled_by_user_id=?,handled_at=CURRENT_TIMESTAMP WHERE target_type=? AND target_id=? AND status='PENDING'")){
                    s.setString(1,resolution.name());s.setString(2,note);s.setLong(3,operatorId);s.setString(4,report.type().name());s.setLong(5,report.targetId());
                    if(s.executeUpdate()!=reporterIds.size())throw new SQLException("举报状态在处理期间发生变化");
                }
                try(PreparedStatement s=c.prepareStatement("INSERT INTO forum_moderation_logs(operator_user_id,target_type,target_id,action,reason) VALUES(?,?,?,?,?)")){
                    s.setLong(1,operatorId);s.setString(2,report.type().name());s.setLong(3,report.targetId());s.setString(4,resolution.name());s.setString(5,note);s.executeUpdate();
                }
                List<NotificationWriter.NotificationDraft> drafts=new ArrayList<>();
                for(long reporterId:reporterIds){
                    drafts.add(notificationFactory.reportResolved(
                            reporterId,operatorId,operatorName,report.type(),target.postId(),
                            target.postTitle(),resolution,note));
                }
                ForumModerationAction moderationAction=ForumModerationAction.valueOf(resolution.name());
                Optional<NotificationWriter.NotificationDraft> authorWarning=report.type()==ForumTargetType.POST
                        ?notificationFactory.postModerated(
                                target.authorId(),operatorId,operatorName,target.postId(),
                                target.postTitle(),moderationAction,note)
                        :notificationFactory.commentModerated(
                                target.authorId(),operatorId,operatorName,target.postId(),
                                target.postTitle(),moderationAction,note);
                authorWarning.ifPresent(drafts::add);
                notifications.insertBatch(c,drafts);
                c.commit();return ForumStore.MutationResult.CHANGED;
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }

    private int updateTarget(Connection c,String table,long id,ForumReportResolution resolution)throws SQLException{
        String deleted=resolution==ForumReportResolution.DELETE?",deleted_at=CURRENT_TIMESTAMP":"";
        try(PreparedStatement s=c.prepareStatement("UPDATE "+table+" SET status=?"+deleted+" WHERE id=? AND status='NORMAL'")){
            s.setString(1,resolution==ForumReportResolution.HIDE?"HIDDEN":"DELETED");s.setLong(2,id);return s.executeUpdate();
        }
    }
    private void refreshCount(Connection c,long postId)throws SQLException{try(PreparedStatement s=c.prepareStatement("UPDATE forum_posts SET comment_count=(SELECT COUNT(*) FROM forum_comments WHERE post_id=? AND status='NORMAL') WHERE id=?")){s.setLong(1,postId);s.setLong(2,postId);s.executeUpdate();}}
    private void updateOne(Connection c,long id,String status,ForumReportResolution action,String note,long operator)throws SQLException{try(PreparedStatement s=c.prepareStatement("UPDATE forum_reports SET status=?,resolution_action=?,resolution_note=?,handled_by_user_id=?,handled_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'")){s.setString(1,status);s.setString(2,action.name());s.setString(3,note);s.setLong(4,operator);s.setLong(5,id);s.executeUpdate();}}
    private ReportTarget findReport(Connection c,long id)throws SQLException{return report(c,id,false);}
    private ReportTarget lockReport(Connection c,long id)throws SQLException{return report(c,id,true);}
    private ReportTarget report(Connection c,long id,boolean lock)throws SQLException{
        String sql="SELECT reporter_user_id,target_type,target_id,status FROM forum_reports WHERE id=?"+(lock?" FOR UPDATE":"");
        try(PreparedStatement s=c.prepareStatement(sql)){s.setLong(1,id);try(ResultSet r=s.executeQuery()){return r.next()?new ReportTarget(r.getLong(1),ForumTargetType.valueOf(r.getString(2)),r.getLong(3),ForumReportStatus.valueOf(r.getString(4))):null;}}
    }
    private Target lockTarget(Connection c,ForumTargetType type,long targetId)throws SQLException{
        if(type==ForumTargetType.POST){
            return lockRow(c,"forum_posts",targetId)?target(c,type,targetId):null;
        }
        Long postId=commentPostId(c,targetId);
        if(postId==null||!lockRow(c,"forum_posts",postId)||!lockRow(c,"forum_comments",targetId))return null;
        Target locked=target(c,type,targetId);
        return locked!=null&&locked.postId()==postId?locked:null;
    }
    private Long commentPostId(Connection c,long commentId)throws SQLException{
        try(PreparedStatement s=c.prepareStatement("SELECT post_id FROM forum_comments WHERE id=?")){
            s.setLong(1,commentId);try(ResultSet r=s.executeQuery()){return r.next()?r.getLong(1):null;}
        }
    }
    private boolean lockRow(Connection c,String table,long id)throws SQLException{
        // ForumRepository uses the same parent-post -> comment order for comment mutations.
        try(PreparedStatement s=c.prepareStatement("SELECT id FROM "+table+" WHERE id=? FOR UPDATE")){
            s.setLong(1,id);try(ResultSet r=s.executeQuery()){return r.next();}
        }
    }
    private List<Long> lockPendingReporterIds(Connection c,ForumTargetType type,long targetId)throws SQLException{
        List<Long> ids=new ArrayList<>();
        try(PreparedStatement s=c.prepareStatement("SELECT reporter_user_id FROM forum_reports WHERE target_type=? AND target_id=? AND status='PENDING' ORDER BY id FOR UPDATE")){
            s.setString(1,type.name());s.setLong(2,targetId);
            try(ResultSet r=s.executeQuery()){while(r.next())ids.add(r.getLong(1));}
        }
        return ids;
    }
    private Target target(Connection c,ForumTargetType type,long id)throws SQLException{
        String sql=type==ForumTargetType.POST
                ?"SELECT p.author_user_id,p.status,s.enabled,p.id,p.title FROM forum_posts p JOIN forum_sections s ON s.id=p.section_id WHERE p.id=?"
                :"SELECT c.author_user_id,c.status,(p.status='NORMAL' AND s.enabled),p.id,p.title FROM forum_comments c JOIN forum_posts p ON p.id=c.post_id JOIN forum_sections s ON s.id=p.section_id WHERE c.id=?";
        try(PreparedStatement s=c.prepareStatement(sql)){s.setLong(1,id);try(ResultSet r=s.executeQuery()){return r.next()?new Target(r.getLong(1),ForumContentStatus.valueOf(r.getString(2)),r.getBoolean(3),r.getLong(4),r.getString(5)):null;}}
    }
    private List<Long> administratorIds(Connection c)throws SQLException{
        String sql="SELECT DISTINCT u.id FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE u.enabled=TRUE AND r.role_code IN ('FORUM_ADMIN','SUPER_ADMIN') ORDER BY u.id";
        List<Long> ids=new ArrayList<>();try(PreparedStatement s=c.prepareStatement(sql);ResultSet r=s.executeQuery()){while(r.next())ids.add(r.getLong(1));}return ids;
    }
    private String userDisplayName(Connection c,long userId)throws SQLException{
        try(PreparedStatement s=c.prepareStatement("SELECT display_name FROM users WHERE id=?")){s.setLong(1,userId);try(ResultSet r=s.executeQuery()){return r.next()?r.getString(1):"校园用户";}}
    }
    private ReportRow row(ResultSet r)throws SQLException{Timestamp handled=r.getTimestamp("handled_at");String action=r.getString("resolution_action");return new ReportRow(r.getLong("id"),ForumTargetType.valueOf(r.getString("target_type")),r.getLong("target_id"),r.getLong("post_id"),Objects.toString(r.getString("target_content"),""),r.getLong("reporter_user_id"),r.getString("reporter_name"),ForumReportReason.valueOf(r.getString("reason")),r.getString("detail"),ForumReportStatus.valueOf(r.getString("status")),action==null?null:ForumReportResolution.valueOf(action),Objects.toString(r.getString("handler_name"),""),r.getTimestamp("created_at").toInstant(),handled==null?null:handled.toInstant());}
    private void bind(PreparedStatement s,List<Object> values)throws SQLException{for(int i=0;i<values.size();i++)s.setObject(i+1,values.get(i));}
    private ForumStore.MutationResult rollback(Connection c,ForumStore.MutationResult result)throws SQLException{c.rollback();return result;}
    private record Target(long authorId,ForumContentStatus status,boolean parentVisible,long postId,String postTitle){}
    private record ReportTarget(long reporterId,ForumTargetType type,long targetId,ForumReportStatus status){}
}
