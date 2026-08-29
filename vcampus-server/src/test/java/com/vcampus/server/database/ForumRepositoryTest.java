package com.vcampus.server.database;

import com.vcampus.common.model.ForumContentStatus;
import com.vcampus.common.model.ForumModerationAction;
import com.vcampus.common.model.ForumSort;
import com.vcampus.common.model.ForumTargetType;
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
