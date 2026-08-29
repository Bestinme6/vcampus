package com.vcampus.server.database;

import com.vcampus.common.model.ForumContentStatus;
import com.vcampus.common.model.ForumModerationAction;
import com.vcampus.common.model.ForumSort;
import com.vcampus.common.model.ForumTargetType;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ForumStore {
    List<SectionRecord> listSections(boolean includeDisabled) throws SQLException;

    PostPage searchPosts(PostQuery query) throws SQLException;

    Optional<PostDetail> findPost(long postId, long viewerUserId, boolean administrator)
            throws SQLException;

    long createPost(long authorUserId, CreatePost command) throws SQLException;

    MutationResult deletePost(long postId, long actorUserId, boolean administrator)
            throws SQLException;

    CommentPage listComments(CommentQuery query) throws SQLException;

    long createComment(long postId, long authorUserId, String content) throws SQLException;

    MutationResult deleteComment(long commentId, long actorUserId, boolean administrator)
            throws SQLException;

    long saveSection(long operatorUserId, SaveSection command) throws SQLException;

    MutationResult setSectionEnabled(long sectionId, boolean enabled, long operatorUserId)
            throws SQLException;

    AdminContentPage searchAdminContent(AdminContentQuery query) throws SQLException;

    MutationResult moderatePost(long postId, ForumModerationAction action,
                                String reason, long operatorUserId) throws SQLException;

    MutationResult moderateComment(long commentId, ForumModerationAction action,
                                   String reason, long operatorUserId) throws SQLException;

    ModerationLogPage searchModerationLogs(int page, int pageSize) throws SQLException;

    enum MutationResult {
        NOT_FOUND,
        UNCHANGED,
        CHANGED,
        CONFLICT,
        FORBIDDEN
    }

    record SectionRecord(long id, String code, String name, String description,
                         int sortOrder, boolean enabled) {
    }

    record PostQuery(Long sectionId, String keyword, ForumSort sort,
                     int page, int pageSize) {
    }

    record PostRow(long id, long sectionId, String sectionName, long authorUserId,
                   String authorDisplayName, String title, String summary,
                   ForumContentStatus status, boolean locked, boolean pinned,
                   boolean featured, int viewCount, int commentCount,
                   Instant createdAt, Instant lastCommentedAt) {
    }

    record PostPage(List<PostRow> rows, int page, int pageSize, int total) {
        public PostPage {
            rows = List.copyOf(rows);
        }
    }

    record PostDetail(long id, long sectionId, String sectionName, long authorUserId,
                      String authorDisplayName, String title, String content,
                      ForumContentStatus status, boolean locked, boolean pinned,
                      boolean featured, int viewCount, int commentCount,
                      Instant createdAt, Instant updatedAt, Instant lastCommentedAt,
                      boolean canDelete) {
    }

    record CreatePost(long sectionId, String title, String content) {
    }

    record CommentQuery(long postId, long viewerUserId, boolean administrator,
                        int page, int pageSize) {
    }

    record CommentRow(long id, long postId, long authorUserId,
                      String authorDisplayName, String content,
                      ForumContentStatus status, Instant createdAt,
                      boolean canDelete) {
    }

    record CommentPage(List<CommentRow> rows, int page, int pageSize, int total) {
        public CommentPage {
            rows = List.copyOf(rows);
        }
    }

    record SaveSection(Long id, String code, String name,
                       String description, int sortOrder) {
    }

    record AdminContentQuery(ForumTargetType targetType, ForumContentStatus status,
                             String keyword, int page, int pageSize) {
    }

    record AdminContentRow(ForumTargetType targetType, long id, Long parentId,
                           String sectionName, long authorUserId,
                           String authorDisplayName, String title, String content,
                           ForumContentStatus status, boolean locked, boolean pinned,
                           boolean featured, Instant createdAt) {
    }

    record AdminContentPage(List<AdminContentRow> rows, int page,
                            int pageSize, int total) {
        public AdminContentPage {
            rows = List.copyOf(rows);
        }
    }

    record ModerationLogRow(long id, long operatorUserId, String operatorDisplayName,
                            ForumTargetType targetType, long targetId,
                            ForumModerationAction action, String reason,
                            Instant createdAt) {
    }

    record ModerationLogPage(List<ModerationLogRow> rows, int page,
                             int pageSize, int total) {
        public ModerationLogPage {
            rows = List.copyOf(rows);
        }
    }
}
