package com.vcampus.server.database;

import com.vcampus.common.model.*;
import com.vcampus.server.config.DatabaseConfig;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ForumReportRepositoryTest {
    private ConnectionFactory connections;
    private ForumReportRepository reports;

    @BeforeEach void setUp() throws Exception {
        connections=new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:"+ UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        try(Connection c=connections.openConnection();Statement s=c.createStatement()){
            s.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,display_name VARCHAR(100) NOT NULL)");
            s.execute("CREATE TABLE forum_sections(id BIGINT PRIMARY KEY,name VARCHAR(80),enabled BOOLEAN)");
            s.execute("CREATE TABLE forum_posts(id BIGINT PRIMARY KEY,section_id BIGINT,author_user_id BIGINT,title VARCHAR(160),content CLOB,status VARCHAR(16),comment_count INT,deleted_at TIMESTAMP)");
            s.execute("CREATE TABLE forum_comments(id BIGINT PRIMARY KEY,post_id BIGINT,author_user_id BIGINT,content VARCHAR(2000),status VARCHAR(16),deleted_at TIMESTAMP)");
            s.execute("CREATE TABLE forum_moderation_logs(id BIGINT AUTO_INCREMENT PRIMARY KEY,operator_user_id BIGINT,target_type VARCHAR(16),target_id BIGINT,action VARCHAR(32),reason VARCHAR(255),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE forum_reports(id BIGINT AUTO_INCREMENT PRIMARY KEY,reporter_user_id BIGINT NOT NULL,target_type VARCHAR(16) NOT NULL,target_id BIGINT NOT NULL,reason VARCHAR(24) NOT NULL,detail VARCHAR(300) NOT NULL DEFAULT '',status VARCHAR(16) NOT NULL DEFAULT 'PENDING',resolution_action VARCHAR(16),resolution_note VARCHAR(255),handled_by_user_id BIGINT,handled_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(reporter_user_id,target_type,target_id))");
            s.execute("INSERT INTO users VALUES(1,'作者'),(2,'评论者'),(3,'举报人'),(4,'管理员')");
            s.execute("INSERT INTO forum_sections VALUES(1,'校园生活',TRUE)");
            s.execute("INSERT INTO forum_posts VALUES(10,1,1,'帖子标题','帖子正文','NORMAL',1,NULL)");
            s.execute("INSERT INTO forum_comments VALUES(20,10,2,'评论正文','NORMAL',NULL)");
        }
        reports=new ForumReportRepository(connections);
    }

    @Test void createsReportsForPostAndCommentAndRejectsDuplicateOrSelf() throws Exception {
        long postReport=reports.create(3,ForumTargetType.POST,10,ForumReportReason.SPAM,"重复广告");
        long commentReport=reports.create(3,ForumTargetType.COMMENT,20,ForumReportReason.HARASSMENT,"");
        assertTrue(postReport>0&&commentReport>postReport);
        assertTrue(reports.reportedBy(3,ForumTargetType.POST,10));
        assertThrows(IllegalArgumentException.class,()->reports.create(3,ForumTargetType.POST,10,ForumReportReason.OTHER,"再次举报"));
        assertThrows(IllegalArgumentException.class,()->reports.create(1,ForumTargetType.POST,10,ForumReportReason.OTHER,"举报自己"));
        assertThrows(IllegalArgumentException.class,()->reports.create(2,ForumTargetType.COMMENT,20,ForumReportReason.OTHER,"举报自己"));
        assertEquals(2,reports.search(new ForumReportStore.ReportQuery(null,null,null,1,10)).total());
    }

    @Test void dismissesOneReportWithoutChangingContent() throws Exception {
        long id=reports.create(3,ForumTargetType.COMMENT,20,ForumReportReason.OTHER,"需要复核");
        assertEquals(ForumStore.MutationResult.CHANGED,reports.resolve(id,ForumReportResolution.DISMISS,"未发现违规",4));
        assertEquals("NORMAL",scalar("SELECT status FROM forum_comments WHERE id=20"));
        assertEquals(ForumReportStatus.DISMISSED,reports.search(new ForumReportStore.ReportQuery(null,null,null,1,10)).rows().getFirst().status());
        assertEquals(ForumStore.MutationResult.UNCHANGED,reports.resolve(id,ForumReportResolution.DELETE,"重复处理",4));
    }

    @Test void deletingReportedCommentResolvesAllReportsAndRefreshesCount() throws Exception {
        long id=reports.create(3,ForumTargetType.COMMENT,20,ForumReportReason.PRIVACY,"包含个人信息");
        assertEquals(ForumStore.MutationResult.CHANGED,reports.resolve(id,ForumReportResolution.DELETE,"确认泄露隐私",4));
        assertEquals("DELETED",scalar("SELECT status FROM forum_comments WHERE id=20"));
        assertEquals("0",scalar("SELECT comment_count FROM forum_posts WHERE id=10"));
        assertEquals("ACTIONED",scalar("SELECT status FROM forum_reports WHERE id="+id));
        assertEquals("DELETE",scalar("SELECT action FROM forum_moderation_logs"));
    }

    @Test void rejectsInvisibleAndMissingTargets() throws Exception {
        execute("UPDATE forum_posts SET status='HIDDEN' WHERE id=10");
        assertThrows(IllegalArgumentException.class,()->reports.create(3,ForumTargetType.POST,10,ForumReportReason.SPAM,"隐藏"));
        assertThrows(IllegalArgumentException.class,()->reports.create(3,ForumTargetType.COMMENT,20,ForumReportReason.SPAM,"父帖隐藏"));
        assertThrows(IllegalArgumentException.class,()->reports.create(3,ForumTargetType.POST,999,ForumReportReason.SPAM,"不存在"));
    }

    private void execute(String sql)throws Exception{try(Connection c=connections.openConnection();Statement s=c.createStatement()){s.executeUpdate(sql);}}
    private String scalar(String sql)throws Exception{try(Connection c=connections.openConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
}
