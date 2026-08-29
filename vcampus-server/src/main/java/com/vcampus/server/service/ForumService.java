package com.vcampus.server.service;

import com.vcampus.common.model.ForumAccessPolicy;
import com.vcampus.common.model.ForumContentStatus;
import com.vcampus.common.model.ForumModerationAction;
import com.vcampus.common.model.ForumSort;
import com.vcampus.common.model.ForumTargetType;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.ForumStore;
import com.vcampus.server.database.ForumStore.AdminContentPage;
import com.vcampus.server.database.ForumStore.AdminContentQuery;
import com.vcampus.server.database.ForumStore.CommentPage;
import com.vcampus.server.database.ForumStore.CommentQuery;
import com.vcampus.server.database.ForumStore.CreatePost;
import com.vcampus.server.database.ForumStore.ModerationLogPage;
import com.vcampus.server.database.ForumStore.MutationResult;
import com.vcampus.server.database.ForumStore.PostPage;
import com.vcampus.server.database.ForumStore.PostQuery;
import com.vcampus.server.database.ForumStore.SaveSection;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;

import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class ForumService {
    private static final int PAGE_SIZE = 10;

    private final ForumStore forum;
    private final SessionManager sessions;

    public ForumService(ForumStore forum, SessionManager sessions) {
        this.forum = forum;
        this.sessions = sessions;
    }

    public ResponseMessage listSections(RequestMessage request) {
        Optional<UserSession> session = user(request);
        if (session.isEmpty()) return expired(request);
        try {
            boolean includeDisabled = ForumAccessPolicy.canManage(session.get().roles())
                    && booleanValue(request.parameters().get("includeDisabled"), false);
            var rows = forum.listSections(includeDisabled);
            Map<String, String> data = new LinkedHashMap<>();
            data.put("count", Integer.toString(rows.size()));
            for (int index = 0; index < rows.size(); index++) {
                var row = rows.get(index);
                data.put("row." + index, RowCodec.encode(
                        Long.toString(row.id()), row.code(), row.name(), row.description(),
                        Integer.toString(row.sortOrder()), Boolean.toString(row.enabled())));
            }
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (IllegalArgumentException exception) {
            return invalid(request, "板块查询条件无效");
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage searchPosts(RequestMessage request) {
        Optional<UserSession> session = user(request);
        if (session.isEmpty()) return expired(request);
        try {
            Map<String, String> values = request.parameters();
            PostPage page = forum.searchPosts(new PostQuery(
                    optionalPositiveLong(values.get("sectionId")),
                    text(values.get("keyword")),
                    enumValue(values.get("sort"), ForumSort.class, ForumSort.LATEST_REPLY),
                    positiveInt(values.get("page"), 1), PAGE_SIZE));
            return postPage(request, page);
        } catch (IllegalArgumentException exception) {
            return invalid(request, "帖子查询条件无效");
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage getPost(RequestMessage request) {
        Optional<UserSession> session = user(request);
        if (session.isEmpty()) return expired(request);
        try {
            long postId = positiveLong(request.parameters().get("postId"));
            boolean administrator = ForumAccessPolicy.canManage(session.get().roles());
            var found = forum.findPost(postId, session.get().userId(), administrator);
            if (found.isEmpty()) return inaccessible(request);
            var post = found.get();
            Map<String, String> data = new LinkedHashMap<>();
            data.put("id", Long.toString(post.id()));
            data.put("sectionId", Long.toString(post.sectionId()));
            data.put("sectionName", post.sectionName());
            data.put("authorUserId", Long.toString(post.authorUserId()));
            data.put("authorDisplayName", post.authorDisplayName());
            data.put("title", post.title());
            data.put("content", post.content());
            data.put("status", post.status().name());
            data.put("locked", Boolean.toString(post.locked()));
            data.put("pinned", Boolean.toString(post.pinned()));
            data.put("featured", Boolean.toString(post.featured()));
            data.put("viewCount", Integer.toString(post.viewCount()));
            data.put("commentCount", Integer.toString(post.commentCount()));
            data.put("createdAt", post.createdAt().toString());
            data.put("updatedAt", post.updatedAt().toString());
            data.put("lastCommentedAt", instant(post.lastCommentedAt()));
            data.put("canDelete", Boolean.toString(post.canDelete()));
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (IllegalArgumentException exception) {
            return inaccessible(request);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage createPost(RequestMessage request) {
        Optional<UserSession> session = user(request);
        if (session.isEmpty()) return expired(request);
        try {
            Map<String, String> values = request.parameters();
            long postId = forum.createPost(session.get().userId(), new CreatePost(
                    positiveLong(values.get("sectionId")),
                    bounded(values.get("title"), 4, 160, "标题"),
                    bounded(values.get("content"), 1, 10_000, "正文")));
            return ResponseMessage.success(request.requestId(), "发布成功",
                    Map.of("postId", Long.toString(postId)));
        } catch (IllegalStateException exception) {
            return invalid(request, "板块已停用或不存在");
        } catch (IllegalArgumentException exception) {
            return invalid(request, exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage deletePost(RequestMessage request) {
        Optional<UserSession> session = user(request);
        if (session.isEmpty()) return expired(request);
        try {
            MutationResult result = forum.deletePost(
                    positiveLong(request.parameters().get("postId")),
                    session.get().userId(), ForumAccessPolicy.canManage(session.get().roles()));
            return mutation(request, result, "帖子已删除");
        } catch (IllegalArgumentException exception) {
            return inaccessible(request);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage listComments(RequestMessage request) {
        Optional<UserSession> session = user(request);
        if (session.isEmpty()) return expired(request);
        try {
            Map<String, String> values = request.parameters();
            CommentPage page = forum.listComments(new CommentQuery(
                    positiveLong(values.get("postId")), session.get().userId(),
                    ForumAccessPolicy.canManage(session.get().roles()),
                    positiveInt(values.get("page"), 1), PAGE_SIZE));
            Map<String, String> data = pageData(
                    page.page(), page.pageSize(), page.total(), page.rows().size());
            for (int index = 0; index < page.rows().size(); index++) {
                var row = page.rows().get(index);
                data.put("row." + index, RowCodec.encode(
                        Long.toString(row.id()), Long.toString(row.postId()),
                        Long.toString(row.authorUserId()), row.authorDisplayName(),
                        row.content(), row.status().name(), row.createdAt().toString(),
                        Boolean.toString(row.canDelete())));
            }
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (IllegalArgumentException exception) {
            return invalid(request, "评论查询条件无效");
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage createComment(RequestMessage request) {
        Optional<UserSession> session = user(request);
        if (session.isEmpty()) return expired(request);
        try {
            Map<String, String> values = request.parameters();
            long id = forum.createComment(
                    positiveLong(values.get("postId")), session.get().userId(),
                    bounded(values.get("content"), 1, 2_000, "评论"));
            return ResponseMessage.success(request.requestId(), "评论成功",
                    Map.of("commentId", Long.toString(id)));
        } catch (IllegalStateException exception) {
            return invalid(request, "帖子已锁定或不可访问");
        } catch (IllegalArgumentException exception) {
            return invalid(request, exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage deleteComment(RequestMessage request) {
        Optional<UserSession> session = user(request);
        if (session.isEmpty()) return expired(request);
        try {
            MutationResult result = forum.deleteComment(
                    positiveLong(request.parameters().get("commentId")),
                    session.get().userId(), ForumAccessPolicy.canManage(session.get().roles()));
            return mutation(request, result, "评论已删除");
        } catch (IllegalArgumentException exception) {
            return inaccessible(request);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage saveSection(RequestMessage request) {
        Optional<UserSession> session = manager(request);
        if (session.isEmpty()) return managerFailure(request);
        try {
            Map<String, String> values = request.parameters();
            String code = bounded(values.get("code"), 2, 40, "板块代码").toUpperCase();
            if (!code.matches("[A-Z][A-Z0-9_]{1,39}")) {
                throw new IllegalArgumentException("板块代码格式无效");
            }
            long id = forum.saveSection(session.get().userId(), new SaveSection(
                    optionalPositiveLong(values.get("sectionId")), code,
                    bounded(values.get("name"), 2, 80, "板块名称"),
                    bounded(values.get("description"), 1, 255, "板块简介"),
                    nonNegativeInt(values.get("sortOrder"), 0)));
            return ResponseMessage.success(request.requestId(), "板块已保存",
                    Map.of("sectionId", Long.toString(id)));
        } catch (IllegalArgumentException exception) {
            return invalid(request, exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage setSectionEnabled(RequestMessage request) {
        Optional<UserSession> session = manager(request);
        if (session.isEmpty()) return managerFailure(request);
        try {
            MutationResult result = forum.setSectionEnabled(
                    positiveLong(request.parameters().get("sectionId")),
                    requiredBoolean(request.parameters().get("enabled")),
                    session.get().userId());
            return mutation(request, result, "板块状态已更新");
        } catch (IllegalArgumentException exception) {
            return invalid(request, "板块状态参数无效");
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage searchAdminContent(RequestMessage request) {
        Optional<UserSession> session = manager(request);
        if (session.isEmpty()) return managerFailure(request);
        try {
            Map<String, String> values = request.parameters();
            AdminContentPage page = forum.searchAdminContent(new AdminContentQuery(
                    enumValue(values.get("targetType"), ForumTargetType.class, null),
                    optionalEnum(values.get("status"), ForumContentStatus.class),
                    text(values.get("keyword")), positiveInt(values.get("page"), 1),
                    PAGE_SIZE));
            Map<String, String> data = pageData(
                    page.page(), page.pageSize(), page.total(), page.rows().size());
            for (int index = 0; index < page.rows().size(); index++) {
                var row = page.rows().get(index);
                data.put("row." + index, RowCodec.encode(
                        row.targetType().name(), Long.toString(row.id()),
                        nullableLong(row.parentId()), row.sectionName(),
                        Long.toString(row.authorUserId()), row.authorDisplayName(),
                        row.title(), row.content(), row.status().name(),
                        Boolean.toString(row.locked()), Boolean.toString(row.pinned()),
                        Boolean.toString(row.featured()), row.createdAt().toString()));
            }
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (IllegalArgumentException exception) {
            return invalid(request, "管理筛选条件无效");
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage moderatePost(RequestMessage request) {
        Optional<UserSession> session = manager(request);
        if (session.isEmpty()) return managerFailure(request);
        try {
            Map<String, String> values = request.parameters();
            ForumModerationAction action = enumValue(
                    values.get("action"), ForumModerationAction.class, null);
            String reason = moderationReason(action, values.get("reason"));
            MutationResult result = forum.moderatePost(
                    positiveLong(values.get("postId")), action, reason,
                    session.get().userId());
            return mutation(request, result, "帖子状态已更新");
        } catch (IllegalArgumentException exception) {
            return invalid(request, exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage moderateComment(RequestMessage request) {
        Optional<UserSession> session = manager(request);
        if (session.isEmpty()) return managerFailure(request);
        try {
            Map<String, String> values = request.parameters();
            ForumModerationAction action = enumValue(
                    values.get("action"), ForumModerationAction.class, null);
            if (action != ForumModerationAction.HIDE
                    && action != ForumModerationAction.RESTORE) {
                throw new IllegalArgumentException("评论审核动作无效");
            }
            MutationResult result = forum.moderateComment(
                    positiveLong(values.get("commentId")), action,
                    moderationReason(action, values.get("reason")),
                    session.get().userId());
            return mutation(request, result, "评论状态已更新");
        } catch (IllegalArgumentException exception) {
            return invalid(request, exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage searchModerationLogs(RequestMessage request) {
        Optional<UserSession> session = manager(request);
        if (session.isEmpty()) return managerFailure(request);
        try {
            ModerationLogPage page = forum.searchModerationLogs(
                    positiveInt(request.parameters().get("page"), 1), PAGE_SIZE);
            Map<String, String> data = pageData(
                    page.page(), page.pageSize(), page.total(), page.rows().size());
            for (int index = 0; index < page.rows().size(); index++) {
                var row = page.rows().get(index);
                data.put("row." + index, RowCodec.encode(
                        Long.toString(row.id()), Long.toString(row.operatorUserId()),
                        row.operatorDisplayName(), row.targetType().name(),
                        Long.toString(row.targetId()), row.action().name(), row.reason(),
                        row.createdAt().toString()));
            }
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (IllegalArgumentException exception) {
            return invalid(request, "日志查询条件无效");
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    private ResponseMessage postPage(RequestMessage request, PostPage page) {
        Map<String, String> data = pageData(
                page.page(), page.pageSize(), page.total(), page.rows().size());
        for (int index = 0; index < page.rows().size(); index++) {
            var row = page.rows().get(index);
            data.put("row." + index, RowCodec.encode(
                    Long.toString(row.id()), Long.toString(row.sectionId()),
                    row.sectionName(), Long.toString(row.authorUserId()),
                    row.authorDisplayName(), row.title(), row.summary(), row.status().name(),
                    Boolean.toString(row.locked()), Boolean.toString(row.pinned()),
                    Boolean.toString(row.featured()), Integer.toString(row.viewCount()),
                    Integer.toString(row.commentCount()), row.createdAt().toString(),
                    instant(row.lastCommentedAt())));
        }
        return ResponseMessage.success(request.requestId(), "查询成功", data);
    }

    private Map<String, String> pageData(
            int page, int pageSize, int total, int count) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("page", Integer.toString(page));
        data.put("pageSize", Integer.toString(pageSize));
        data.put("total", Integer.toString(total));
        data.put("count", Integer.toString(count));
        return data;
    }

    private Optional<UserSession> user(RequestMessage request) {
        Optional<UserSession> session = sessions.find(
                request.parameters().get("sessionToken"));
        return session.filter(value -> ForumAccessPolicy.canUse(value.roles()));
    }

    private Optional<UserSession> manager(RequestMessage request) {
        return sessions.find(request.parameters().get("sessionToken"))
                .filter(value -> ForumAccessPolicy.canManage(value.roles()));
    }

    private ResponseMessage managerFailure(RequestMessage request) {
        return sessions.find(request.parameters().get("sessionToken")).isEmpty()
                ? expired(request)
                : ResponseMessage.failure(request.requestId(), "无权管理论坛内容");
    }

    private ResponseMessage mutation(
            RequestMessage request, MutationResult result, String successMessage) {
        return switch (result) {
            case CHANGED -> ResponseMessage.success(request.requestId(), successMessage, Map.of());
            case UNCHANGED -> ResponseMessage.failure(request.requestId(), "内容状态没有变化");
            case FORBIDDEN -> ResponseMessage.failure(request.requestId(), "只能删除自己的内容");
            case CONFLICT -> ResponseMessage.failure(request.requestId(), "内容状态已变化，请刷新后重试");
            case NOT_FOUND -> inaccessible(request);
        };
    }

    private String moderationReason(ForumModerationAction action, String value) {
        if (action == ForumModerationAction.HIDE
                || action == ForumModerationAction.RESTORE) {
            return bounded(value, 2, 255, "管理原因");
        }
        String normalized = text(value);
        return normalized.isEmpty() ? "管理员调整内容状态"
                : bounded(normalized, 2, 255, "管理原因");
    }

    private String bounded(String value, int min, int max, String label) {
        String normalized = text(value);
        if (normalized.length() < min || normalized.length() > max) {
            throw new IllegalArgumentException(label + "长度应为 " + min + " 至 " + max + " 个字符");
        }
        return normalized;
    }

    private String text(String value) {
        return value == null ? "" : value.strip();
    }

    private long positiveLong(String value) {
        long parsed = Long.parseLong(value == null ? "" : value);
        if (parsed < 1) throw new IllegalArgumentException("编号无效");
        return parsed;
    }

    private Long optionalPositiveLong(String value) {
        return value == null || value.isBlank() ? null : positiveLong(value);
    }

    private int positiveInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        int parsed = Integer.parseInt(value);
        if (parsed < 1) throw new IllegalArgumentException("页码无效");
        return parsed;
    }

    private int nonNegativeInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        int parsed = Integer.parseInt(value);
        if (parsed < 0) throw new IllegalArgumentException("排序无效");
        return parsed;
    }

    private boolean booleanValue(String value, boolean defaultValue) {
        return value == null || value.isBlank() ? defaultValue : requiredBoolean(value);
    }

    private boolean requiredBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException("布尔值无效");
    }

    private <E extends Enum<E>> E enumValue(String value, Class<E> type, E defaultValue) {
        if (value == null || value.isBlank()) {
            if (defaultValue != null) return defaultValue;
            throw new IllegalArgumentException("枚举值不能为空");
        }
        return Enum.valueOf(type, value.trim());
    }

    private <E extends Enum<E>> E optionalEnum(String value, Class<E> type) {
        return value == null || value.isBlank() ? null : Enum.valueOf(type, value.trim());
    }

    private String instant(Instant value) {
        return value == null ? "" : value.toString();
    }

    private String nullableLong(Long value) {
        return value == null ? "" : Long.toString(value);
    }

    private ResponseMessage expired(RequestMessage request) {
        return ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录");
    }

    private ResponseMessage inaccessible(RequestMessage request) {
        return ResponseMessage.failure(request.requestId(), "内容不存在或不可访问");
    }

    private ResponseMessage invalid(RequestMessage request, String message) {
        return ResponseMessage.failure(request.requestId(), message);
    }

    private ResponseMessage databaseFailure(RequestMessage request, SQLException exception) {
        System.err.println("Forum database operation failed: " + exception.getMessage());
        return ResponseMessage.failure(request.requestId(), "数据库操作失败，请稍后重试");
    }
}
