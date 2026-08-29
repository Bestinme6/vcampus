package com.vcampus.server.service;

import com.vcampus.common.model.ForumContentStatus;
import com.vcampus.common.model.ForumModerationAction;
import com.vcampus.common.model.ForumSort;
import com.vcampus.common.model.ForumTargetType;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.ForumStore;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForumServiceTest {
    private final FakeForumStore store = new FakeForumStore();
    private final SessionManager sessions = new SessionManager();
    private ForumService service;
    private String studentToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        studentToken = sessions.create(account(
                11L, "2026000011", "张同学", Set.of(UserRole.STUDENT))).token();
        adminToken = sessions.create(account(
                12L, "T0000012", "李老师",
                Set.of(UserRole.TEACHER, UserRole.FORUM_ADMIN))).token();
        service = new ForumService(store, sessions);
    }

    @Test
    void ordinaryUserCannotModeratePost() {
        ResponseMessage response = service.moderatePost(request(studentToken, Map.of(
                "postId", "12", "action", "HIDE", "reason", "违规内容")));

        assertFalse(response.success());
        assertEquals("无权管理论坛内容", response.message());
        assertEquals(0, store.moderatePostCalls);
    }

    @Test
    void authorIdentityComesFromSessionNotRequest() {
        ResponseMessage response = service.createPost(request(studentToken, Map.of(
                "sectionId", "2", "title", "课程资料交流", "content", "正文内容",
                "authorUserId", "999")));

        assertTrue(response.success());
        assertEquals(11L, store.lastAuthorUserId);
        assertEquals("课程资料交流", store.lastPost.title());
    }

    @Test
    void lockedPostGetsReadableCommentFailure() {
        store.commentFailure = new IllegalStateException("帖子不可评论");

        ResponseMessage response = service.createComment(request(studentToken,
                Map.of("postId", "9", "content", "回复内容")));

        assertFalse(response.success());
        assertEquals("帖子已锁定或不可访问", response.message());
    }

    @Test
    void postSearchEncodesStableRowOrder() {
        store.postPage = new ForumStore.PostPage(List.of(new ForumStore.PostRow(
                7L, 2L, "校园生活", 11L, "张同学", "食堂窗口建议", "摘要",
                ForumContentStatus.NORMAL, false, true, false, 25, 3,
                Instant.parse("2026-08-28T08:00:00Z"),
                Instant.parse("2026-08-28T09:00:00Z"))), 1, 10, 1);

        ResponseMessage response = service.searchPosts(request(studentToken,
                Map.of("page", "1", "sort", "LATEST_REPLY")));

        assertTrue(response.success());
        List<String> fields = RowCodec.decode(response.data().get("row.0"));
        assertEquals(15, fields.size());
        assertEquals(List.of("7", "2", "校园生活", "11", "张同学"),
                fields.subList(0, 5));
        assertEquals("true", fields.get(9));
    }

    @Test
    void moderationRequiresReasonForHideAndUsesSessionOperator() {
        ResponseMessage missing = service.moderatePost(request(adminToken, Map.of(
                "postId", "12", "action", "HIDE", "reason", " ")));
        assertFalse(missing.success());

        ResponseMessage response = service.moderatePost(request(adminToken, Map.of(
                "postId", "12", "action", "HIDE", "reason", "违反社区规范")));
        assertTrue(response.success());
        assertEquals(12L, store.lastOperatorUserId);
    }

    private RequestMessage request(String token, Map<String, String> values) {
        Map<String, String> parameters = new LinkedHashMap<>(values);
        parameters.put("sessionToken", token);
        return RequestMessage.create("forum.test", parameters);
    }

    private UserAccount account(long id, String username, String name, Set<UserRole> roles) {
        return new UserAccount(id, username, "hash", "salt", name,
                true, false, roles);
    }

    private static final class FakeForumStore implements ForumStore {
        private int moderatePostCalls;
        private long lastAuthorUserId;
        private long lastOperatorUserId;
        private CreatePost lastPost;
        private RuntimeException commentFailure;
        private PostPage postPage = new PostPage(List.of(), 1, 10, 0);

        @Override public List<SectionRecord> listSections(boolean includeDisabled) {
            return List.of();
        }
        @Override public PostPage searchPosts(PostQuery query) { return postPage; }
        @Override public Optional<PostDetail> findPost(long postId, long viewerUserId,
                                                       boolean administrator) {
            return Optional.empty();
        }
        @Override public long createPost(long authorUserId, CreatePost command) {
            lastAuthorUserId = authorUserId;
            lastPost = command;
            return 41L;
        }
        @Override public MutationResult deletePost(long postId, long actorUserId,
                                                    boolean administrator) {
            return MutationResult.CHANGED;
        }
        @Override public CommentPage listComments(CommentQuery query) {
            return new CommentPage(List.of(), query.page(), query.pageSize(), 0);
        }
        @Override public long createComment(long postId, long authorUserId, String content) {
            if (commentFailure != null) throw commentFailure;
            return 51L;
        }
        @Override public MutationResult deleteComment(long commentId, long actorUserId,
                                                       boolean administrator) {
            return MutationResult.CHANGED;
        }
        @Override public long saveSection(long operatorUserId, SaveSection command) {
            lastOperatorUserId = operatorUserId;
            return command.id() == null ? 61L : command.id();
        }
        @Override public MutationResult setSectionEnabled(long sectionId, boolean enabled,
                                                          long operatorUserId) {
            lastOperatorUserId = operatorUserId;
            return MutationResult.CHANGED;
        }
        @Override public AdminContentPage searchAdminContent(AdminContentQuery query) {
            return new AdminContentPage(List.of(), query.page(), query.pageSize(), 0);
        }
        @Override public MutationResult moderatePost(long postId,
                ForumModerationAction action, String reason, long operatorUserId) {
            moderatePostCalls++;
            lastOperatorUserId = operatorUserId;
            return MutationResult.CHANGED;
        }
        @Override public MutationResult moderateComment(long commentId,
                ForumModerationAction action, String reason, long operatorUserId) {
            lastOperatorUserId = operatorUserId;
            return MutationResult.CHANGED;
        }
        @Override public ModerationLogPage searchModerationLogs(int page, int pageSize) {
            return new ModerationLogPage(List.of(), page, pageSize, 0);
        }
    }
}
