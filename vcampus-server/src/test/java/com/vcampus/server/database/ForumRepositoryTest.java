package com.vcampus.server.database;

import com.vcampus.common.model.ForumContentStatus;
import com.vcampus.common.model.ForumModerationAction;
import com.vcampus.common.model.ForumSort;
import com.vcampus.common.model.ForumTargetType;
import com.vcampus.common.model.ForumFeedScope;
import com.vcampus.common.model.ForumFeedOrder;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.ForumStore.AdminContentQuery;
import com.vcampus.server.database.ForumStore.CommentQuery;
import com.vcampus.server.database.ForumStore.CreatePost;
import com.vcampus.server.database.ForumStore.MutationResult;
import com.vcampus.server.database.ForumStore.PostQuery;
import com.vcampus.server.database.ForumStore.SaveSection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForumRepositoryTest {
    private ConnectionFactory connections;
    private ForumRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", ""));
        createSchema();
        seedUsersAndSection();
        repository = new ForumRepository(
                connections, new NotificationRepository(connections));
    }

    @Test
    void createsSearchesAndSoftDeletesOwnPost() throws SQLException {
        long postId = repository.createPost(1L,
                new CreatePost(1L, "食堂窗口建议", "希望增加清真窗口。"));

        var page = repository.searchPosts(new PostQuery(
                1L, "食堂", ForumSort.LATEST_REPLY, 1, 10));
        assertEquals(1, page.total());
        assertEquals("张同学", page.rows().getFirst().authorDisplayName());
        assertTrue(repository.findPost(postId, 1L, false).orElseThrow().canDelete());

        assertEquals(MutationResult.FORBIDDEN,
                repository.deletePost(postId, 2L, false));
        assertEquals(MutationResult.CHANGED,
                repository.deletePost(postId, 1L, false));
        assertTrue(repository.searchPosts(new PostQuery(
                null, "", ForumSort.LATEST_REPLY, 1, 10)).rows().isEmpty());
        assertEquals("DELETED", scalarString(
                "SELECT status FROM forum_posts WHERE id = " + postId));
    }

    @Test
    void commentCreationAndDeletionMaintainVisibleCount() throws SQLException {
        long postId = repository.createPost(1L,
                new CreatePost(1L, "课程交流帖", "讨论课程安排。"));
        long commentId = repository.createComment(postId, 2L, "第一条评论");

        var detail = repository.findPost(postId, 1L, false).orElseThrow();
        assertEquals(1, detail.commentCount());
        assertNotNull(detail.lastCommentedAt());
        var comments = repository.listComments(new CommentQuery(
                postId, 2L, false, 1, 10));
        assertEquals(1, comments.total());
        assertTrue(comments.rows().getFirst().canDelete());

        assertEquals(MutationResult.CHANGED,
                repository.deleteComment(commentId, 2L, false));
        assertEquals(0, repository.findPost(postId, 1L, false)
                .orElseThrow().commentCount());
    }

    @Test
    void commentByAnotherUserCreatesForumNotification() throws SQLException {
        long postId = repository.createPost(1L,
                new CreatePost(1L, "课程交流帖", "讨论课程安排。"));
        repository.createComment(postId, 2L, "第一条评论");

        assertEquals(1, scalarInt("SELECT COUNT(*) FROM notifications"));
        assertEquals("FORUM_POST_COMMENTED", scalarString(
                "SELECT notification_type FROM notifications"));
        assertEquals(postId, scalarLong(
                "SELECT related_entity_id FROM notifications"));
    }

    @Test
    void administratorCannotDeleteAnotherAuthorsComment() throws SQLException {
        long post = repository.createPost(1L, new CreatePost(1L, "权限测试", "正文"));
        long comment = repository.createComment(post, 2L, "作者评论");
        assertFalse(repository.listComments(new CommentQuery(post, 3L, true, 1, 20))
                .rows().getFirst().canDelete());
        assertEquals(MutationResult.FORBIDDEN, repository.deleteComment(comment, 3L, true));
        assertEquals("NORMAL", scalarString("SELECT status FROM forum_comments WHERE id=" + comment));
    }

    @Test
    void readingListHidesRemovedCommentsEvenForAdministratorButAuditKeepsThem() throws SQLException {
        long post = repository.createPost(1L, new CreatePost(1L, "列表测试", "正文"));
        long deleted = repository.createComment(post, 2L, "自行删除");
        long hidden = repository.createComment(post, 2L, "管理隐藏");
        repository.deleteComment(deleted, 2L, false);
        repository.moderateComment(hidden, ForumModerationAction.HIDE, "审核原因", 3L);
        for (boolean administrator : new boolean[]{false, true}) {
            var page = repository.listComments(new CommentQuery(post, 3L, administrator, 1, 20));
            assertEquals(0, page.total());
            assertTrue(page.rows().isEmpty());
        }
        assertEquals(2, repository.searchAdminContent(new AdminContentQuery(
                ForumTargetType.COMMENT, null, "", 1, 20)).total());
        assertEquals(MutationResult.UNCHANGED, repository.moderateComment(
                deleted, ForumModerationAction.RESTORE, "无法恢复", 3L));
    }

    @Test
    void administratorCanStillDeleteTheirOwnComment() throws SQLException {
        long post = repository.createPost(1L, new CreatePost(1L, "自删测试", "正文"));
        long comment = repository.createComment(post, 3L, "管理员评论");
        assertTrue(repository.listComments(new CommentQuery(post, 3L, true, 1, 20))
                .rows().getFirst().canDelete());
        assertEquals(MutationResult.CHANGED, repository.deleteComment(comment, 3L, true));
        assertEquals(0, repository.listComments(new CommentQuery(post, 3L, true, 1, 20)).total());
    }

    @Test
    void commentingOnOwnPostDoesNotNotifySelf() throws SQLException {
        long postId = repository.createPost(1L,
                new CreatePost(1L, "自己的帖子", "正文"));
        repository.createComment(postId, 1L, "补充说明");

        assertEquals(0, scalarInt("SELECT COUNT(*) FROM notifications"));
    }

    @Test
    void notificationFailureRollsBackCommentAndCounter() throws SQLException {
        long postId = repository.createPost(1L,
                new CreatePost(1L, "事务测试", "正文"));
        ForumRepository failingRepository = new ForumRepository(
                connections, failingNotificationWriter());

        assertThrows(SQLException.class,
                () -> failingRepository.createComment(postId, 2L, "不会提交"));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM forum_comments"));
        assertEquals(0, scalarInt(
                "SELECT comment_count FROM forum_posts WHERE id = " + postId));
    }

    @ParameterizedTest
    @EnumSource(value = ForumModerationAction.class, names = {
            "HIDE", "RESTORE", "LOCK", "UNLOCK",
            "PIN", "UNPIN", "FEATURE", "UNFEATURE"})
    void everySuccessfulPostModerationNotifiesAuthor(ForumModerationAction action)
            throws SQLException {
        long postId = preparePostFor(action);

        assertEquals(MutationResult.CHANGED,
                repository.moderatePost(postId, action, "审核原因", 3L));
        assertEquals("FORUM_POST_MODERATED", scalarString(
                "SELECT notification_type FROM notifications ORDER BY id DESC LIMIT 1"));
        assertEquals(postId, scalarLong(
                "SELECT related_entity_id FROM notifications ORDER BY id DESC LIMIT 1"));
    }

    @Test
    void unchangedAndSelfPostModerationDoNotNotify() throws SQLException {
        long unchangedPostId = repository.createPost(1L,
                new CreatePost(1L, "未变化", "正文"));
        assertEquals(MutationResult.UNCHANGED, repository.moderatePost(
                unchangedPostId, ForumModerationAction.UNLOCK, "无变化", 3L));

        long selfPostId = repository.createPost(3L,
                new CreatePost(1L, "管理员自己的帖子", "正文"));
        assertEquals(MutationResult.CHANGED, repository.moderatePost(
                selfPostId, ForumModerationAction.LOCK, "自主管理", 3L));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM notifications"));
    }

    @Test
    void commentHideAndRestoreNotifyCommentAuthorAndTargetParentPost()
            throws SQLException {
        long postId = repository.createPost(1L,
                new CreatePost(1L, "评论审核帖", "正文"));
        long commentId = repository.createComment(postId, 2L, "待审核评论");
        executeUpdate("DELETE FROM notifications");

        assertEquals(MutationResult.CHANGED, repository.moderateComment(
                commentId, ForumModerationAction.HIDE, "隐藏原因", 3L));
        assertEquals(MutationResult.CHANGED, repository.moderateComment(
                commentId, ForumModerationAction.RESTORE, "恢复原因", 3L));

        assertEquals(2, scalarInt("SELECT COUNT(*) FROM notifications"));
        assertEquals(2, scalarInt("SELECT COUNT(*) FROM notifications"
                + " WHERE recipient_user_id = 2"
                + " AND notification_type = 'FORUM_COMMENT_MODERATED'"
                + " AND related_entity_id = " + postId));
    }

    @Test
    void selfCommentModerationDoesNotNotify() throws SQLException {
        long postId = repository.createPost(1L,
                new CreatePost(1L, "自审评论帖", "正文"));
        long commentId = repository.createComment(postId, 3L, "管理员评论");
        executeUpdate("DELETE FROM notifications");

        assertEquals(MutationResult.CHANGED, repository.moderateComment(
                commentId, ForumModerationAction.HIDE, "自主管理", 3L));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM notifications"));
    }

    @Test
    void notificationFailureRollsBackPostModerationAndLog() throws SQLException {
        long postId = repository.createPost(1L,
                new CreatePost(1L, "管理事务测试", "正文"));
        ForumRepository failingRepository = new ForumRepository(
                connections, failingNotificationWriter());

        assertThrows(SQLException.class, () -> failingRepository.moderatePost(
                postId, ForumModerationAction.HIDE, "不会提交", 3L));
        assertEquals("NORMAL", scalarString(
                "SELECT status FROM forum_posts WHERE id = " + postId));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM forum_moderation_logs"));
    }

    @Test
    void moderationChangesStateAndAppendsLogAtomically() throws SQLException {
        long postId = repository.createPost(1L,
                new CreatePost(1L, "需要审核的帖子", "内容。"));

        assertEquals(MutationResult.CHANGED, repository.moderatePost(
                postId, ForumModerationAction.HIDE, "违反社区规范", 3L));
        assertTrue(repository.findPost(postId, 1L, false).isEmpty());
        assertEquals(1, repository.searchAdminContent(new AdminContentQuery(
                ForumTargetType.POST, ForumContentStatus.HIDDEN, "", 1, 10)).total());
        assertEquals("HIDE", repository.searchModerationLogs(1, 10)
                .rows().getFirst().action().name());

        assertEquals(MutationResult.CHANGED, repository.moderatePost(
                postId, ForumModerationAction.RESTORE, "复核后恢复", 3L));
        assertTrue(repository.findPost(postId, 1L, false).isPresent());
    }

    @Test
    void disabledSectionRejectsPostAndSectionSaveIsIdempotent() throws SQLException {
        assertEquals(MutationResult.CHANGED,
                repository.setSectionEnabled(1L, false, 3L));
        assertEquals(MutationResult.UNCHANGED,
                repository.setSectionEnabled(1L, false, 3L));

        boolean rejected = false;
        try {
            repository.createPost(1L,
                    new CreatePost(1L, "停用板块发帖", "正文"));
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        assertTrue(rejected);

        long newSectionId = repository.saveSection(3L,
                new SaveSection(null, "HELP", "互助问答", "校园互助", 60));
        assertFalse(repository.listSections(true).stream()
                .filter(section -> section.id() == newSectionId)
                .findFirst().orElseThrow().enabled());
    }

    private void createSchema() throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, display_name VARCHAR(100) NOT NULL)");
            statement.execute("CREATE TABLE notifications (id BIGINT AUTO_INCREMENT PRIMARY KEY, recipient_user_id BIGINT NOT NULL, sender_user_id BIGINT, notification_type VARCHAR(40) NOT NULL, source_module VARCHAR(40) NOT NULL, title VARCHAR(160) NOT NULL, content VARCHAR(1000) NOT NULL, target VARCHAR(40) NOT NULL, related_entity_id BIGINT, is_read BOOLEAN DEFAULT FALSE, read_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE forum_sections (id BIGINT AUTO_INCREMENT PRIMARY KEY, code VARCHAR(40) UNIQUE NOT NULL, name VARCHAR(80) NOT NULL, description VARCHAR(255) NOT NULL, sort_order INT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, created_by_user_id BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY (created_by_user_id) REFERENCES users(id))");
            statement.execute("CREATE TABLE forum_posts (id BIGINT AUTO_INCREMENT PRIMARY KEY, section_id BIGINT NOT NULL, author_user_id BIGINT NOT NULL, title VARCHAR(160) NOT NULL, content CLOB NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'NORMAL', locked BOOLEAN NOT NULL DEFAULT FALSE, pinned BOOLEAN NOT NULL DEFAULT FALSE, featured BOOLEAN NOT NULL DEFAULT FALSE, view_count INT NOT NULL DEFAULT 0, comment_count INT NOT NULL DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, last_commented_at TIMESTAMP, deleted_at TIMESTAMP, FOREIGN KEY (section_id) REFERENCES forum_sections(id), FOREIGN KEY (author_user_id) REFERENCES users(id))");
            statement.execute("CREATE TABLE forum_comments (id BIGINT AUTO_INCREMENT PRIMARY KEY, post_id BIGINT NOT NULL, author_user_id BIGINT NOT NULL, content VARCHAR(2000) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'NORMAL', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, deleted_at TIMESTAMP, FOREIGN KEY (post_id) REFERENCES forum_posts(id), FOREIGN KEY (author_user_id) REFERENCES users(id))");
            statement.execute("CREATE TABLE forum_moderation_logs (id BIGINT AUTO_INCREMENT PRIMARY KEY, operator_user_id BIGINT NOT NULL, target_type VARCHAR(16) NOT NULL, target_id BIGINT NOT NULL, action VARCHAR(32) NOT NULL, reason VARCHAR(255) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY (operator_user_id) REFERENCES users(id))");
            statement.execute("ALTER TABLE forum_posts ADD is_announcement BOOLEAN DEFAULT FALSE NOT NULL");
            statement.execute("ALTER TABLE forum_posts ADD announced_at TIMESTAMP");
            statement.execute("ALTER TABLE forum_comments ADD reply_to_comment_id BIGINT REFERENCES forum_comments(id)");
            for (String table : List.of("forum_post_likes", "forum_post_bookmarks")) {
                statement.execute("CREATE TABLE " + table + " (post_id BIGINT REFERENCES forum_posts(id), user_id BIGINT REFERENCES users(id), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(post_id,user_id))");
            }
        }
    }

    @Test void likesAndBookmarksAreIdempotentAndPrivate() throws SQLException {
        long post = repository.createPost(1L, new CreatePost(1L, "互动测试", "正文"));
        var community = new ForumCommunityRepository(connections);
        community.setLiked(post, 2L, true);
        assertEquals(1, community.setLiked(post, 2L, true).likeCount());
        assertTrue(community.engagement(post, 2L).liked());
        assertFalse(community.engagement(post, 1L).liked());
        community.setBookmarked(post, 2L, true);
        assertTrue(community.setBookmarked(post, 2L, true).bookmarked());
        assertFalse(community.engagement(post, 1L).bookmarked());
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM notifications"));
        assertEquals(0, community.setLiked(post, 2L, false).likeCount());
        assertEquals(0, community.setLiked(post, 2L, false).likeCount());
        repository.moderatePost(post, ForumModerationAction.HIDE, "审核原因", 3L);
        assertThrows(IllegalArgumentException.class, () -> community.setLiked(post, 2L, true));
        assertThrows(IllegalArgumentException.class, () -> community.setBookmarked(post, 2L, true));
        community.setBookmarked(post, 2L, false);
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM forum_post_bookmarks"));
    }

    @Test void feedFiltersOwnershipStatusAndSortsRealInteractions() throws SQLException {
        var community = new ForumCommunityRepository(connections);
        long first = repository.createPost(1L, new CreatePost(1L, "第一篇", "正文"));
        long second = repository.createPost(2L, new CreatePost(1L, "第二篇", "正文"));
        community.setLiked(first, 2L, true);
        community.setBookmarked(first, 2L, true);
        var home = new ForumCommunityStore.FeedQuery(2, ForumFeedScope.HOME, ForumFeedOrder.HOT, null, "", 1, 20);
        assertEquals(first, community.searchFeed(home).rows().getFirst().post().id());
        assertEquals(second, community.searchFeed(new ForumCommunityStore.FeedQuery(
                2, ForumFeedScope.MINE, ForumFeedOrder.LATEST, null, "", 1, 20)).rows().getFirst().post().id());
        assertEquals(1, community.searchFeed(new ForumCommunityStore.FeedQuery(
                2, ForumFeedScope.BOOKMARKS, ForumFeedOrder.LATEST, null, "", 1, 20)).total());
        repository.moderatePost(first, ForumModerationAction.HIDE, "隐藏原因", 3);
        assertEquals(1, community.searchFeed(home).total());
        assertEquals(0, community.searchFeed(new ForumCommunityStore.FeedQuery(
                2, ForumFeedScope.BOOKMARKS, ForumFeedOrder.LATEST, null, "", 1, 20)).total());
        assertEquals(1, community.searchFeed(new ForumCommunityStore.FeedQuery(
                1, ForumFeedScope.MINE, ForumFeedOrder.LATEST, null, "", 1, 20)).total());
    }

    @Test void repliesNotifyDistinctRecipientsAndRejectInvisibleOrForeignTargets() throws SQLException {
        long post = repository.createPost(1L, new CreatePost(1L, "回复测试", "正文"));
        long parent = repository.createComment(post, 2, "原评论");
        executeUpdate("DELETE FROM notifications");
        repository.createComment(post, 3, "回复内容", parent);
        assertEquals(2, scalarInt("SELECT COUNT(*) FROM notifications"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM notifications WHERE recipient_user_id=2 AND notification_type='FORUM_COMMENT_REPLIED'"));
        long another = repository.createPost(1L, new CreatePost(1L, "另一篇", "正文"));
        assertThrows(IllegalArgumentException.class, () -> repository.createComment(another, 3, "跨帖回复", parent));
        repository.moderateComment(parent, ForumModerationAction.HIDE, "隐藏原因", 3);
        assertThrows(IllegalArgumentException.class, () -> repository.createComment(post, 3, "隐藏回复", parent));
        var reply = repository.listComments(new CommentQuery(post, 3, true, 1, 20)).rows().getFirst();
        assertFalse(reply.replyTargetVisible());
        assertEquals("", reply.replyToDisplayName());
    }

    @Test void replyToPostAuthorDoesNotDuplicateNotificationAndRollsBackOnFailure() throws SQLException {
        long post = repository.createPost(1L, new CreatePost(1L, "去重测试", "正文"));
        long parent = repository.createComment(post, 1, "楼主补充");
        repository.createComment(post, 2, "回复楼主", parent);
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM notifications"));
        assertEquals("FORUM_COMMENT_REPLIED", scalarString("SELECT notification_type FROM notifications"));
        ForumRepository failing = new ForumRepository(connections, failingNotificationWriter());
        assertThrows(SQLException.class, () -> failing.createComment(post, 3, "回滚回复", parent));
        assertEquals(2, scalarInt("SELECT COUNT(*) FROM forum_comments"));
    }

    @Test void announcementsAreExplicitAndHotListUsesOnlyCurrentInterval() throws SQLException {
        var community = new ForumCommunityRepository(connections);
        long post = repository.createPost(1L, new CreatePost(1L, "公告测试", "正文"));
        var announcements = new ForumCommunityStore.FeedQuery(2, ForumFeedScope.ANNOUNCEMENTS, ForumFeedOrder.LATEST, null, "", 1, 20);
        repository.moderatePost(post, ForumModerationAction.PIN, "置顶原因", 3);
        assertEquals(0, community.searchFeed(announcements).total());
        assertEquals(MutationResult.CHANGED, repository.moderatePost(post, ForumModerationAction.ANNOUNCE, "公告原因", 3));
        assertEquals(1, community.searchFeed(announcements).total());
        assertEquals(MutationResult.UNCHANGED, repository.moderatePost(post, ForumModerationAction.ANNOUNCE, "重复操作", 3));
        community.setLiked(post, 2, true);
        try (Connection c = connections.openConnection(); var s = c.prepareStatement("UPDATE forum_post_likes SET created_at=? WHERE post_id=?")) {
            s.setTimestamp(1, java.sql.Timestamp.from(java.time.Instant.parse("2026-08-30T16:00:00Z")));
            s.setLong(2, post); s.executeUpdate();
        }
        assertEquals(1, community.hotToday(2, java.time.Instant.parse("2026-08-30T16:00:00Z"), java.time.Instant.parse("2026-08-31T16:00:00Z")).size());
        assertEquals(0, community.hotToday(2, java.time.Instant.parse("2026-08-31T16:00:00Z"), java.time.Instant.parse("2026-09-01T16:00:00Z")).size());
    }

    @Test void concurrentRepeatedLikesKeepOneRowPerUserAndDoNotNotify() throws Exception {
        var community = new ForumCommunityRepository(connections);
        long post = repository.createPost(1, new CreatePost(1L, "并发点赞测试", "正文"));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(6)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 12; i++) {
                long actor = i % 3 + 1;
                results.add(workers.submit(() -> { start.await(); return community.setLiked(post, actor, true); }));
            }
            start.countDown();
            for (var result : results) result.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(3, community.engagement(post, 1).likeCount());
        assertEquals(3, scalarInt("SELECT COUNT(*) FROM forum_post_likes"));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM notifications"));
    }

    @Test void invalidCrossPostReplyDoesNotWaitForAnotherPostsLock() throws Exception {
        long post = repository.createPost(1, new CreatePost(1L, "当前帖子", "正文"));
        long foreignPost = repository.createPost(2, new CreatePost(1L, "另一个帖", "正文"));
        long foreignComment = repository.createComment(foreignPost, 2, "原评论");
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            try (Connection locked = connections.openConnection()) {
                locked.setAutoCommit(false);
                try (var s = locked.prepareStatement("SELECT id FROM forum_posts WHERE id=? FOR UPDATE")) {
                    s.setLong(1, foreignPost);try (var rows = s.executeQuery()) { assertTrue(rows.next()); }
                }
                var attempt = worker.submit(() -> assertThrows(IllegalArgumentException.class,
                        () -> repository.createComment(post, 3, "无效的跨帖回复", foreignComment)));
                try { attempt.get(1, java.util.concurrent.TimeUnit.SECONDS); }
                finally { locked.rollback(); }
            }
        }
    }

    private void seedUsersAndSection() throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users VALUES (1, '张同学'), (2, '李老师'), (3, '论坛管理员')");
            statement.executeUpdate("INSERT INTO forum_sections (id, code, name, description, sort_order, enabled) VALUES (1, 'CAMPUS', '校园生活', '校园见闻', 10, TRUE)");
        }
    }

    private String scalarString(String sql) throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private long preparePostFor(ForumModerationAction action) throws SQLException {
        long postId = repository.createPost(1L,
                new CreatePost(1L, "需要审核的帖子", "正文"));
        switch (action) {
            case RESTORE -> repository.moderatePost(
                    postId, ForumModerationAction.HIDE, "准备隐藏状态", 3L);
            case UNLOCK -> repository.moderatePost(
                    postId, ForumModerationAction.LOCK, "准备锁定状态", 3L);
            case UNPIN -> repository.moderatePost(
                    postId, ForumModerationAction.PIN, "准备置顶状态", 3L);
            case UNFEATURE -> repository.moderatePost(
                    postId, ForumModerationAction.FEATURE, "准备精华状态", 3L);
            default -> {
            }
        }
        executeUpdate("DELETE FROM notifications");
        return postId;
    }

    private void executeUpdate(String sql) throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private int scalarInt(String sql) throws SQLException {
        return Math.toIntExact(scalarLong(sql));
    }

    private long scalarLong(String sql) throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private NotificationWriter failingNotificationWriter() {
        return new NotificationWriter() {
            @Override
            public void insert(
                    Connection connection, NotificationDraft draft) throws SQLException {
                throw new SQLException("notification failed");
            }

            @Override
            public void insertBatch(
                    Connection connection, List<NotificationDraft> drafts) throws SQLException {
                throw new SQLException("notification failed");
            }
        };
    }
}
