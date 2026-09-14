package com.vcampus.server.database;

import com.vcampus.common.model.*;
import com.vcampus.server.config.DatabaseConfig;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ForumReportRepositoryTest {
    private ConnectionFactory connections;
    private ForumReportRepository reports;
    private List<NotificationWriter.NotificationDraft> notifications;

    @BeforeEach void setUp() throws Exception {
        connections=new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:"+ UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        try(Connection c=connections.openConnection();Statement s=c.createStatement()){
            s.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,display_name VARCHAR(100) NOT NULL,enabled BOOLEAN NOT NULL)");
            s.execute("CREATE TABLE roles(id BIGINT PRIMARY KEY,role_code VARCHAR(40) NOT NULL)");
            s.execute("CREATE TABLE user_roles(user_id BIGINT NOT NULL,role_id BIGINT NOT NULL)");
            s.execute("CREATE TABLE forum_sections(id BIGINT PRIMARY KEY,name VARCHAR(80),enabled BOOLEAN)");
            s.execute("CREATE TABLE forum_posts(id BIGINT PRIMARY KEY,section_id BIGINT,author_user_id BIGINT,title VARCHAR(160),content CLOB,status VARCHAR(16),comment_count INT,deleted_at TIMESTAMP)");
            s.execute("CREATE TABLE forum_comments(id BIGINT PRIMARY KEY,post_id BIGINT,author_user_id BIGINT,content VARCHAR(2000),status VARCHAR(16),deleted_at TIMESTAMP)");
            s.execute("CREATE TABLE forum_moderation_logs(id BIGINT AUTO_INCREMENT PRIMARY KEY,operator_user_id BIGINT,target_type VARCHAR(16),target_id BIGINT,action VARCHAR(32),reason VARCHAR(255),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE forum_reports(id BIGINT AUTO_INCREMENT PRIMARY KEY,reporter_user_id BIGINT NOT NULL,target_type VARCHAR(16) NOT NULL,target_id BIGINT NOT NULL,reason VARCHAR(24) NOT NULL,detail VARCHAR(300) NOT NULL DEFAULT '',status VARCHAR(16) NOT NULL DEFAULT 'PENDING',resolution_action VARCHAR(16),resolution_note VARCHAR(255),handled_by_user_id BIGINT,handled_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(reporter_user_id,target_type,target_id))");
            s.execute("INSERT INTO users VALUES(1,'作者',TRUE),(2,'评论者',TRUE),(3,'举报人',TRUE),(4,'论坛管理员',TRUE),(5,'超级管理员',TRUE),(6,'普通用户',TRUE),(7,'停用管理员',FALSE)");
            s.execute("INSERT INTO roles VALUES(1,'FORUM_ADMIN'),(2,'SUPER_ADMIN'),(3,'STUDENT')");
            s.execute("INSERT INTO user_roles VALUES(4,1),(5,2),(6,3),(7,1)");
            s.execute("INSERT INTO forum_sections VALUES(1,'校园生活',TRUE)");
            s.execute("INSERT INTO forum_posts VALUES(10,1,1,'帖子标题','帖子正文','NORMAL',1,NULL)");
            s.execute("INSERT INTO forum_comments VALUES(20,10,2,'评论正文','NORMAL',NULL)");
        }
        notifications=Collections.synchronizedList(new ArrayList<>());
        reports=new ForumReportRepository(connections,new NotificationWriter(){
            @Override public void insert(Connection connection,NotificationDraft draft){notifications.add(draft);}
            @Override public void insertBatch(Connection connection,List<NotificationDraft> drafts){notifications.addAll(drafts);}
        });
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
        notifications.clear();
        assertEquals(ForumStore.MutationResult.CHANGED,reports.resolve(id,ForumReportResolution.DISMISS,"未发现违规",4));
        assertEquals("NORMAL",scalar("SELECT status FROM forum_comments WHERE id=20"));
        assertEquals(ForumReportStatus.DISMISSED,reports.search(new ForumReportStore.ReportQuery(null,null,null,1,10)).rows().getFirst().status());
        assertEquals(1,notifications.size());
        assertEquals(3L,notifications.getFirst().recipientUserId());
        assertEquals(NotificationType.FORUM_REPORT_RESOLVED,notifications.getFirst().type());
        assertTrue(notifications.getFirst().title().contains("未通过"));
        assertEquals(ForumStore.MutationResult.UNCHANGED,reports.resolve(id,ForumReportResolution.DELETE,"重复处理",4));
    }

    @Test void deletingReportedCommentResolvesAllReportsAndRefreshesCount() throws Exception {
        long id=reports.create(3,ForumTargetType.COMMENT,20,ForumReportReason.PRIVACY,"包含个人信息");
        reports.create(6,ForumTargetType.COMMENT,20,ForumReportReason.HARASSMENT,"持续骚扰");
        notifications.clear();
        assertEquals(ForumStore.MutationResult.CHANGED,reports.resolve(id,ForumReportResolution.DELETE,"确认泄露隐私",4));
        assertEquals("DELETED",scalar("SELECT status FROM forum_comments WHERE id=20"));
        assertEquals("0",scalar("SELECT comment_count FROM forum_posts WHERE id=10"));
        assertEquals("ACTIONED",scalar("SELECT status FROM forum_reports WHERE id="+id));
        assertEquals("DELETE",scalar("SELECT action FROM forum_moderation_logs"));
        assertEquals(List.of(2L,3L,6L),notifications.stream()
                .map(NotificationWriter.NotificationDraft::recipientUserId).sorted().toList());
        assertEquals(2,notifications.stream()
                .filter(draft->draft.type()==NotificationType.FORUM_REPORT_RESOLVED).count());
        var warning=notifications.stream()
                .filter(draft->draft.type()==NotificationType.FORUM_COMMENT_MODERATED)
                .findFirst().orElseThrow();
        assertEquals(2L,warning.recipientUserId());
        assertNotEquals(1L,warning.recipientUserId());
        assertTrue(warning.content().contains("确认泄露隐私"));
    }

    @Test void rejectsInvisibleAndMissingTargets() throws Exception {
        execute("UPDATE forum_posts SET status='HIDDEN' WHERE id=10");
        assertThrows(IllegalArgumentException.class,()->reports.create(3,ForumTargetType.POST,10,ForumReportReason.SPAM,"隐藏"));
        assertThrows(IllegalArgumentException.class,()->reports.create(3,ForumTargetType.COMMENT,20,ForumReportReason.SPAM,"父帖隐藏"));
        assertThrows(IllegalArgumentException.class,()->reports.create(3,ForumTargetType.POST,999,ForumReportReason.SPAM,"不存在"));
    }

    @Test void reportingNotifiesEveryEnabledForumOrSuperAdministrator()throws Exception{
        reports.create(3,ForumTargetType.COMMENT,20,ForumReportReason.HARASSMENT,"持续辱骂");

        assertEquals(List.of(4L,5L),notifications.stream()
                .map(NotificationWriter.NotificationDraft::recipientUserId).sorted().toList());
        assertTrue(notifications.stream().allMatch(draft->
                draft.type()==NotificationType.FORUM_CONTENT_REPORTED
                        && draft.target()==NotificationTarget.FORUM_POST
                        && Long.valueOf(10L).equals(draft.relatedEntityId())));
    }

    @Test void deletingAReportedPostNotifiesItsAuthor()throws Exception{
        long reportId=reports.create(3,ForumTargetType.POST,10,ForumReportReason.SPAM,"重复广告");
        notifications.clear();

        assertEquals(ForumStore.MutationResult.CHANGED,
                reports.resolve(reportId,ForumReportResolution.DELETE,"确认违规",4));
        assertEquals(2,notifications.size());
        var notification=notifications.stream()
                .filter(draft->draft.type()==NotificationType.FORUM_POST_MODERATED)
                .findFirst().orElseThrow();
        assertEquals(1L,notification.recipientUserId());
        assertEquals(NotificationType.FORUM_POST_MODERATED,notification.type());
        assertEquals(10L,notification.relatedEntityId());
        assertTrue(notification.content().contains("确认违规"));
        assertEquals(3L,notifications.stream()
                .filter(draft->draft.type()==NotificationType.FORUM_REPORT_RESOLVED)
                .findFirst().orElseThrow().recipientUserId());
    }

    @Test void hidingAReportedPostNotifiesReporterAndWarnsPostAuthor()throws Exception{
        long reportId=reports.create(3,ForumTargetType.POST,10,ForumReportReason.MISLEADING,"虚假信息");
        notifications.clear();

        assertEquals(ForumStore.MutationResult.CHANGED,
                reports.resolve(reportId,ForumReportResolution.HIDE,"确认存在误导",4));

        assertEquals("HIDDEN",scalar("SELECT status FROM forum_posts WHERE id=10"));
        assertEquals(List.of(1L,3L),notifications.stream()
                .map(NotificationWriter.NotificationDraft::recipientUserId).sorted().toList());
        assertTrue(notifications.stream().anyMatch(draft->
                draft.type()==NotificationType.FORUM_POST_MODERATED
                        && draft.recipientUserId()==1L
                        && draft.content().contains("隐藏")));
    }

    @Test void hidingAReportedCommentWarnsOnlyTheCommentAuthor()throws Exception{
        long reportId=reports.create(3,ForumTargetType.COMMENT,20,ForumReportReason.HARASSMENT,"辱骂");
        notifications.clear();

        assertEquals(ForumStore.MutationResult.CHANGED,
                reports.resolve(reportId,ForumReportResolution.HIDE,"确认存在辱骂",4));

        assertEquals("HIDDEN",scalar("SELECT status FROM forum_comments WHERE id=20"));
        assertEquals(List.of(2L,3L),notifications.stream()
                .map(NotificationWriter.NotificationDraft::recipientUserId).sorted().toList());
        assertFalse(notifications.stream().anyMatch(draft->draft.recipientUserId()==1L));
        assertTrue(notifications.stream().anyMatch(draft->
                draft.type()==NotificationType.FORUM_COMMENT_MODERATED
                        && draft.recipientUserId()==2L
                        && draft.content().contains("隐藏")));
    }

    @Test void notificationFailureRollsBackNewReport()throws Exception{
        var failing=new ForumReportRepository(connections,new NotificationWriter(){
            @Override public void insert(Connection connection,NotificationDraft draft)throws SQLException{throw new SQLException("notification failed");}
            @Override public void insertBatch(Connection connection,List<NotificationDraft> drafts)throws SQLException{throw new SQLException("notification failed");}
        });

        assertThrows(SQLException.class,()->failing.create(
                3,ForumTargetType.POST,10,ForumReportReason.SPAM,"事务测试"));
        assertEquals("0",scalar("SELECT COUNT(*) FROM forum_reports"));
    }

    @Test void notificationFailureRollsBackReportedPostDeletionAndResolution()throws Exception{
        long reportId=reports.create(3,ForumTargetType.POST,10,ForumReportReason.SPAM,"事务测试");
        var failing=new ForumReportRepository(connections,new NotificationWriter(){
            @Override public void insert(Connection connection,NotificationDraft draft)throws SQLException{throw new SQLException("notification failed");}
            @Override public void insertBatch(Connection connection,List<NotificationDraft> drafts)throws SQLException{throw new SQLException("notification failed");}
        });

        assertThrows(SQLException.class,()->failing.resolve(
                reportId,ForumReportResolution.DELETE,"确认违规",4));
        assertEquals("NORMAL",scalar("SELECT status FROM forum_posts WHERE id=10"));
        assertEquals("PENDING",scalar("SELECT status FROM forum_reports WHERE id="+reportId));
        assertEquals("0",scalar("SELECT COUNT(*) FROM forum_moderation_logs"));
    }

    @Test void notificationFailureRollsBackDismissal()throws Exception{
        long reportId=reports.create(3,ForumTargetType.POST,10,ForumReportReason.SPAM,"事务测试");
        var failing=new ForumReportRepository(connections,new NotificationWriter(){
            @Override public void insert(Connection connection,NotificationDraft draft)throws SQLException{throw new SQLException("notification failed");}
            @Override public void insertBatch(Connection connection,List<NotificationDraft> drafts)throws SQLException{throw new SQLException("notification failed");}
        });

        assertThrows(SQLException.class,()->failing.resolve(
                reportId,ForumReportResolution.DISMISS,"未发现违规",4));
        assertEquals("PENDING",scalar("SELECT status FROM forum_reports WHERE id="+reportId));
    }

    @Test void reportCreatedWhileActionCommitsCannotBeSilentlyActioned()throws Exception{
        long reportId=reports.create(3,ForumTargetType.POST,10,ForumReportReason.SPAM,"并发测试");
        notifications.clear();
        CountDownLatch actionAtNotification=new CountDownLatch(1);
        CountDownLatch allowActionCommit=new CountDownLatch(1);
        var concurrentReports=new ForumReportRepository(connections,new NotificationWriter(){
            @Override public void insert(Connection connection,NotificationDraft draft){notifications.add(draft);}
            @Override public void insertBatch(Connection connection,List<NotificationDraft> drafts)throws SQLException{
                notifications.addAll(drafts);
                actionAtNotification.countDown();
                await(allowActionCommit);
            }
        });
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try{
            Future<ForumStore.MutationResult> action=executor.submit(()->concurrentReports.resolve(
                    reportId,ForumReportResolution.HIDE,"确认违规",4));
            assertTrue(actionAtNotification.await(2,TimeUnit.SECONDS));
            Future<Long> lateReport=executor.submit(()->concurrentReports.create(
                    6,ForumTargetType.POST,10,ForumReportReason.OTHER,"稍后提交"));

            assertThrows(TimeoutException.class,()->lateReport.get(300,TimeUnit.MILLISECONDS));
            allowActionCommit.countDown();

            assertEquals(ForumStore.MutationResult.CHANGED,action.get(2,TimeUnit.SECONDS));
            ExecutionException failure=assertThrows(ExecutionException.class,
                    ()->lateReport.get(2,TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class,failure.getCause());
            assertEquals("1",scalar("SELECT COUNT(*) FROM forum_reports"));
        }finally{
            allowActionCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test void concurrentDismissalCannotAlsoNotifyTheReporterThatItWasUpheld()throws Exception{
        long actionedReport=reports.create(3,ForumTargetType.POST,10,ForumReportReason.SPAM,"举报一");
        long dismissedReport=reports.create(6,ForumTargetType.POST,10,ForumReportReason.OTHER,"举报二");
        notifications.clear();
        CountDownLatch dismissalAtNotification=new CountDownLatch(1);
        CountDownLatch allowDismissalCommit=new CountDownLatch(1);
        var concurrentReports=new ForumReportRepository(connections,new NotificationWriter(){
            @Override public void insert(Connection connection,NotificationDraft draft)throws SQLException{
                notifications.add(draft);
                if(draft.type()==NotificationType.FORUM_REPORT_RESOLVED
                        && draft.recipientUserId()==6L){
                    dismissalAtNotification.countDown();
                    await(allowDismissalCommit);
                }
            }
            @Override public void insertBatch(Connection connection,List<NotificationDraft> drafts){notifications.addAll(drafts);}
        });
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try{
            Future<ForumStore.MutationResult> dismissal=executor.submit(()->concurrentReports.resolve(
                    dismissedReport,ForumReportResolution.DISMISS,"未发现违规",4));
            assertTrue(dismissalAtNotification.await(2,TimeUnit.SECONDS));
            CountDownLatch actionStarted=new CountDownLatch(1);
            Future<ForumStore.MutationResult> action=executor.submit(()->{
                actionStarted.countDown();
                return concurrentReports.resolve(actionedReport,ForumReportResolution.HIDE,"确认违规",4);
            });
            assertTrue(actionStarted.await(2,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->action.get(300,TimeUnit.MILLISECONDS));
            allowDismissalCommit.countDown();

            assertEquals(ForumStore.MutationResult.CHANGED,dismissal.get(2,TimeUnit.SECONDS));
            assertEquals(ForumStore.MutationResult.CHANGED,action.get(2,TimeUnit.SECONDS));
            List<NotificationWriter.NotificationDraft> reporterResults=notifications.stream()
                    .filter(draft->draft.type()==NotificationType.FORUM_REPORT_RESOLVED
                            && draft.recipientUserId()==6L).toList();
            assertEquals(1,reporterResults.size());
            assertTrue(reporterResults.getFirst().title().contains("未通过"));
        }finally{
            allowDismissalCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test void reportedCommentResolutionUsesParentThenCommentLockOrder()throws Exception{
        long reportId=reports.create(3,ForumTargetType.COMMENT,20,ForumReportReason.HARASSMENT,"锁顺序测试");
        notifications.clear();
        CountDownLatch parentLocked=new CountDownLatch(1);
        CountDownLatch allowCommentLock=new CountDownLatch(1);
        CountDownLatch resolutionStarted=new CountDownLatch(1);
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try{
            Future<Boolean> parentFirstMutation=executor.submit(()->{
                try(Connection c=connections.openConnection()){
                    c.setAutoCommit(false);
                    try(PreparedStatement post=c.prepareStatement(
                            "SELECT id FROM forum_posts WHERE id=10 FOR UPDATE");
                        ResultSet postRow=post.executeQuery()){
                        if(!postRow.next())throw new SQLException("父帖不存在");
                    }
                    parentLocked.countDown();
                    await(allowCommentLock);
                    try(PreparedStatement comment=c.prepareStatement(
                            "SELECT id FROM forum_comments WHERE id=20 FOR UPDATE");
                        ResultSet commentRow=comment.executeQuery()){
                        if(!commentRow.next())throw new SQLException("评论不存在");
                    }
                    c.commit();
                    return true;
                }
            });
            assertTrue(parentLocked.await(2,TimeUnit.SECONDS));
            Future<ForumStore.MutationResult> resolution=executor.submit(()->{
                resolutionStarted.countDown();
                return reports.resolve(reportId,ForumReportResolution.HIDE,"确认违规",4);
            });
            assertTrue(resolutionStarted.await(2,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->resolution.get(300,TimeUnit.MILLISECONDS));
            allowCommentLock.countDown();

            assertTrue(parentFirstMutation.get(2,TimeUnit.SECONDS));
            assertEquals(ForumStore.MutationResult.CHANGED,resolution.get(2,TimeUnit.SECONDS));
        }finally{
            allowCommentLock.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch)throws SQLException{
        try{
            if(!latch.await(2,TimeUnit.SECONDS))throw new SQLException("并发测试等待超时");
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            throw new SQLException("并发测试被中断",e);
        }
    }

    private void execute(String sql)throws Exception{try(Connection c=connections.openConnection();Statement s=c.createStatement()){s.executeUpdate(sql);}}
    private String scalar(String sql)throws Exception{try(Connection c=connections.openConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
}
