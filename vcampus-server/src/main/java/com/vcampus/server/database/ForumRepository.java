package com.vcampus.server.database;

import com.vcampus.common.model.ForumContentStatus;
import com.vcampus.common.model.ForumModerationAction;
import com.vcampus.common.model.ForumSort;
import com.vcampus.common.model.ForumTargetType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ForumRepository implements ForumStore {
    private static final String POST_COLUMNS = """
            p.id, p.section_id, s.name AS section_name, p.author_user_id,
            u.display_name AS author_display_name, p.title, p.content, p.status,
            p.locked, p.pinned, p.featured, p.view_count, p.comment_count,
            p.created_at, p.updated_at, p.last_commented_at
            """;

    private final ConnectionFactory connectionFactory;
    private final NotificationWriter notifications;
    private final ForumNotificationFactory notificationFactory = new ForumNotificationFactory();

    public ForumRepository(ConnectionFactory connectionFactory) {
        this(connectionFactory, new NotificationRepository(connectionFactory));
    }

    public ForumRepository(
            ConnectionFactory connectionFactory, NotificationWriter notifications) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
    }

    @Override
    public List<SectionRecord> listSections(boolean includeDisabled) throws SQLException {
        String sql = "SELECT id, code, name, description, sort_order, enabled"
                + " FROM forum_sections"
                + (includeDisabled ? "" : " WHERE enabled = TRUE")
                + " ORDER BY sort_order, id";
        List<SectionRecord> rows = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(new SectionRecord(
                        result.getLong("id"), result.getString("code"),
                        result.getString("name"), result.getString("description"),
                        result.getInt("sort_order"), result.getBoolean("enabled")));
            }
        }
        return List.copyOf(rows);
    }

    @Override
    public PostPage searchPosts(PostQuery query) throws SQLException {
        List<Object> parameters = new ArrayList<>();
        String where = postSearchWhere(query, parameters);
        int total;
        List<PostRow> rows = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM forum_posts p JOIN forum_sections s"
                            + " ON s.id = p.section_id" + where)) {
                bind(statement, parameters);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    total = result.getInt(1);
                }
            }
            String order = query.sort() == ForumSort.LATEST_CREATED
                    ? "p.pinned DESC, p.created_at DESC, p.id DESC"
                    : "p.pinned DESC, COALESCE(p.last_commented_at, p.created_at) DESC, p.id DESC";
            String sql = "SELECT " + POST_COLUMNS
                    + " FROM forum_posts p JOIN forum_sections s ON s.id = p.section_id"
                    + " JOIN users u ON u.id = p.author_user_id" + where
                    + " ORDER BY " + order + " LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                List<Object> pageParameters = new ArrayList<>(parameters);
                pageParameters.add(query.pageSize());
                pageParameters.add((query.page() - 1) * query.pageSize());
                bind(statement, pageParameters);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        rows.add(readPostRow(result));
                    }
                }
            }
        }
        return new PostPage(rows, query.page(), query.pageSize(), total);
    }

    @Override
    public Optional<PostDetail> findPost(
            long postId, long viewerUserId, boolean administrator) throws SQLException {
        String visible = administrator
                ? ""
                : " AND p.status = 'NORMAL' AND s.enabled = TRUE";
        String sql = "SELECT " + POST_COLUMNS
                + " FROM forum_posts p JOIN forum_sections s ON s.id = p.section_id"
                + " JOIN users u ON u.id = p.author_user_id"
                + " WHERE p.id = ?" + visible;
        try (Connection connection = connectionFactory.openConnection()) {
            PostDetail detail;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, postId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    detail = readPostDetail(result, viewerUserId, administrator);
                }
            }
            if (detail.status() == ForumContentStatus.NORMAL) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE forum_posts SET view_count = view_count + 1 WHERE id = ?")) {
                    statement.setLong(1, postId);
                    statement.executeUpdate();
                }
                detail = new PostDetail(
                        detail.id(), detail.sectionId(), detail.sectionName(),
                        detail.authorUserId(), detail.authorDisplayName(), detail.title(),
                        detail.content(), detail.status(), detail.locked(), detail.pinned(),
                        detail.featured(), detail.viewCount() + 1, detail.commentCount(),
                        detail.createdAt(), detail.updatedAt(), detail.lastCommentedAt(),
                        detail.canDelete());
            }
            return Optional.of(detail);
        }
    }

    @Override
    public long createPost(long authorUserId, CreatePost command) throws SQLException {
        String sql = "INSERT INTO forum_posts"
                + " (section_id, author_user_id, title, content) VALUES (?, ?, ?, ?)";
        try (Connection connection = connectionFactory.openConnection()) {
            if (!sectionEnabled(connection, command.sectionId())) {
                throw new IllegalStateException("板块已停用或不存在");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, command.sectionId());
                statement.setLong(2, authorUserId);
                statement.setString(3, command.title());
                statement.setString(4, command.content());
                statement.executeUpdate();
                return generatedId(statement);
            }
        }
    }

    @Override
    public MutationResult deletePost(
            long postId, long actorUserId, boolean administrator) throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            connection.setAutoCommit(false);
            try {
                OwnedState state = lockOwnedState(connection, "forum_posts", postId);
                if (state == null) return rollback(connection, MutationResult.NOT_FOUND);
                if (!administrator && state.authorUserId() != actorUserId) {
                    return rollback(connection, MutationResult.FORBIDDEN);
                }
                if (state.status() != ForumContentStatus.NORMAL) {
                    return rollback(connection, MutationResult.UNCHANGED);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE forum_posts SET status = 'DELETED', deleted_at = CURRENT_TIMESTAMP"
                                + " WHERE id = ? AND status = 'NORMAL'")) {
                    statement.setLong(1, postId);
                    if (statement.executeUpdate() != 1) {
                        return rollback(connection, MutationResult.CONFLICT);
                    }
                }
                connection.commit();
                return MutationResult.CHANGED;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    @Override
    public CommentPage listComments(CommentQuery query) throws SQLException {
        String visible = query.administrator() ? "" : " AND c.status = 'NORMAL'";
        String where = " WHERE c.post_id = ?" + visible;
        int total;
        List<CommentRow> rows = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM forum_comments c" + where)) {
                statement.setLong(1, query.postId());
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    total = result.getInt(1);
                }
            }
            String sql = """
                    SELECT c.id, c.post_id, c.author_user_id, u.display_name,
                           c.content, c.status, c.created_at
                    FROM forum_comments c JOIN users u ON u.id = c.author_user_id
                    """ + where + " ORDER BY c.created_at, c.id LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, query.postId());
                statement.setInt(2, query.pageSize());
                statement.setInt(3, (query.page() - 1) * query.pageSize());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        ForumContentStatus status = ForumContentStatus.valueOf(
                                result.getString("status"));
                        long author = result.getLong("author_user_id");
                        rows.add(new CommentRow(
                                result.getLong("id"), result.getLong("post_id"), author,
                                result.getString("display_name"), result.getString("content"),
                                status, instant(result, "created_at"),
                                status == ForumContentStatus.NORMAL
                                        && (query.administrator()
                                        || author == query.viewerUserId())));
                    }
                }
            }
        }
        return new CommentPage(rows, query.page(), query.pageSize(), total);
    }

    @Override
    public long createComment(long postId, long authorUserId, String content)
            throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            connection.setAutoCommit(false);
            try {
                long postAuthorId;
                String postTitle;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT p.status, p.locked, p.author_user_id, p.title, s.enabled"
                                + " FROM forum_posts p"
                                + " JOIN forum_sections s ON s.id = p.section_id"
                                + " WHERE p.id = ? FOR UPDATE")) {
                    statement.setLong(1, postId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()
                                || !"NORMAL".equals(result.getString("status"))
                                || result.getBoolean("locked")
                                || !result.getBoolean("enabled")) {
                            connection.rollback();
                            throw new IllegalStateException("帖子不可评论");
                        }
                        postAuthorId = result.getLong("author_user_id");
                        postTitle = result.getString("title");
                    }
                }
                long commentId;
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO forum_comments (post_id, author_user_id, content)"
                                + " VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, postId);
                    statement.setLong(2, authorUserId);
                    statement.setString(3, content);
                    statement.executeUpdate();
                    commentId = generatedId(statement);
                }
                refreshPostCommentStats(connection, postId);
                String commenterName = userDisplayName(connection, authorUserId);
                var draft = notificationFactory.commentCreated(
                        postAuthorId, authorUserId, commenterName,
                        postId, postTitle, content);
                if (draft.isPresent()) {
                    notifications.insert(connection, draft.orElseThrow());
                }
                connection.commit();
                return commentId;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    @Override
    public MutationResult deleteComment(
            long commentId, long actorUserId, boolean administrator) throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            connection.setAutoCommit(false);
            try {
                CommentState state = lockComment(connection, commentId);
                if (state == null) return rollback(connection, MutationResult.NOT_FOUND);
                if (!administrator && state.authorUserId() != actorUserId) {
                    return rollback(connection, MutationResult.FORBIDDEN);
                }
                if (state.status() != ForumContentStatus.NORMAL) {
                    return rollback(connection, MutationResult.UNCHANGED);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE forum_comments SET status = 'DELETED',"
                                + " deleted_at = CURRENT_TIMESTAMP WHERE id = ?"
                                + " AND status = 'NORMAL'")) {
                    statement.setLong(1, commentId);
                    if (statement.executeUpdate() != 1) {
                        return rollback(connection, MutationResult.CONFLICT);
                    }
                }
                refreshPostCommentStats(connection, state.postId());
                connection.commit();
                return MutationResult.CHANGED;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    @Override
    public long saveSection(long operatorUserId, SaveSection command) throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            connection.setAutoCommit(false);
            try {
                long sectionId;
                if (command.id() == null) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO forum_sections"
                                    + " (code, name, description, sort_order, enabled, created_by_user_id)"
                                    + " VALUES (?, ?, ?, ?, FALSE, ?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                        bindSection(statement, command);
                        statement.setLong(5, operatorUserId);
                        statement.executeUpdate();
                        sectionId = generatedId(statement);
                    }
                } else {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE forum_sections SET code = ?, name = ?, description = ?,"
                                    + " sort_order = ? WHERE id = ?")) {
                        bindSection(statement, command);
                        statement.setLong(5, command.id());
                        if (statement.executeUpdate() != 1) {
                            connection.rollback();
                            throw new IllegalArgumentException("板块不存在");
                        }
                        sectionId = command.id();
                    }
                }
                insertLog(connection, operatorUserId, ForumTargetType.SECTION,
                        sectionId, ForumModerationAction.SECTION_SAVE, "保存板块");
                connection.commit();
                return sectionId;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    @Override
    public MutationResult setSectionEnabled(
            long sectionId, boolean enabled, long operatorUserId) throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Boolean current = lockSectionEnabled(connection, sectionId);
                if (current == null) return rollback(connection, MutationResult.NOT_FOUND);
                if (current == enabled) return rollback(connection, MutationResult.UNCHANGED);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE forum_sections SET enabled = ? WHERE id = ?")) {
                    statement.setBoolean(1, enabled);
                    statement.setLong(2, sectionId);
                    statement.executeUpdate();
                }
                insertLog(connection, operatorUserId, ForumTargetType.SECTION, sectionId,
                        enabled ? ForumModerationAction.SECTION_ENABLE
                                : ForumModerationAction.SECTION_DISABLE,
                        enabled ? "启用板块" : "停用板块");
                connection.commit();
                return MutationResult.CHANGED;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    @Override
    public AdminContentPage searchAdminContent(AdminContentQuery query) throws SQLException {
        if (query.targetType() == ForumTargetType.POST) {
            return searchAdminPosts(query);
        }
        if (query.targetType() == ForumTargetType.COMMENT) {
            return searchAdminComments(query);
        }
        throw new IllegalArgumentException("管理内容类型无效");
    }

    @Override
    public MutationResult moderatePost(long postId, ForumModerationAction action,
                                       String reason, long operatorUserId) throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            connection.setAutoCommit(false);
            try {
                PostModerationState state = lockPostState(connection, postId);
                if (state == null) return rollback(connection, MutationResult.NOT_FOUND);
                String assignment = postAssignment(state, action);
                if (assignment == null) return rollback(connection, MutationResult.UNCHANGED);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE forum_posts SET " + assignment + " WHERE id = ?")) {
                    statement.setLong(1, postId);
                    statement.executeUpdate();
                }
                insertLog(connection, operatorUserId, ForumTargetType.POST,
                        postId, action, reason);
                String operatorName = userDisplayName(connection, operatorUserId);
                var draft = notificationFactory.postModerated(
                        state.authorUserId(), operatorUserId, operatorName,
                        postId, state.title(), action, reason == null ? "" : reason);
                if (draft.isPresent()) {
                    notifications.insert(connection, draft.orElseThrow());
                }
                connection.commit();
                return MutationResult.CHANGED;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    @Override
    public MutationResult moderateComment(long commentId, ForumModerationAction action,
                                          String reason, long operatorUserId) throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            connection.setAutoCommit(false);
            try {
                CommentState state = lockComment(connection, commentId);
                if (state == null) return rollback(connection, MutationResult.NOT_FOUND);
                String newStatus;
                if (action == ForumModerationAction.HIDE
                        && state.status() == ForumContentStatus.NORMAL) {
                    newStatus = "HIDDEN";
                } else if (action == ForumModerationAction.RESTORE
                        && state.status() == ForumContentStatus.HIDDEN) {
                    newStatus = "NORMAL";
                } else {
                    return rollback(connection, MutationResult.UNCHANGED);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE forum_comments SET status = ? WHERE id = ?")) {
                    statement.setString(1, newStatus);
                    statement.setLong(2, commentId);
                    statement.executeUpdate();
                }
                refreshPostCommentStats(connection, state.postId());
                insertLog(connection, operatorUserId, ForumTargetType.COMMENT,
                        commentId, action, reason);
                String operatorName = userDisplayName(connection, operatorUserId);
                var draft = notificationFactory.commentModerated(
                        state.authorUserId(), operatorUserId, operatorName,
                        state.postId(), state.postTitle(), action,
                        reason == null ? "" : reason);
                if (draft.isPresent()) {
                    notifications.insert(connection, draft.orElseThrow());
                }
                connection.commit();
                return MutationResult.CHANGED;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    @Override
    public ModerationLogPage searchModerationLogs(int page, int pageSize)
            throws SQLException {
        int total;
        List<ModerationLogRow> rows = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM forum_moderation_logs");
                 ResultSet result = statement.executeQuery()) {
                result.next();
                total = result.getInt(1);
            }
            String sql = """
                    SELECT l.id, l.operator_user_id, u.display_name, l.target_type,
                           l.target_id, l.action, l.reason, l.created_at
                    FROM forum_moderation_logs l
                    JOIN users u ON u.id = l.operator_user_id
                    ORDER BY l.created_at DESC, l.id DESC LIMIT ? OFFSET ?
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, pageSize);
                statement.setInt(2, (page - 1) * pageSize);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        rows.add(new ModerationLogRow(
                                result.getLong("id"), result.getLong("operator_user_id"),
                                result.getString("display_name"),
                                ForumTargetType.valueOf(result.getString("target_type")),
                                result.getLong("target_id"),
                                ForumModerationAction.valueOf(result.getString("action")),
                                result.getString("reason"), instant(result, "created_at")));
                    }
                }
            }
        }
        return new ModerationLogPage(rows, page, pageSize, total);
    }

    private AdminContentPage searchAdminPosts(AdminContentQuery query) throws SQLException {
        List<Object> parameters = new ArrayList<>();
        String where = adminWhere("p", query, parameters, "p.title", "p.content");
        int total;
        List<AdminContentRow> rows = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection()) {
            total = count(connection, "forum_posts p", where, parameters);
            String sql = "SELECT " + POST_COLUMNS
                    + " FROM forum_posts p JOIN forum_sections s ON s.id = p.section_id"
                    + " JOIN users u ON u.id = p.author_user_id" + where
                    + " ORDER BY p.created_at DESC, p.id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindPage(statement, parameters, query.page(), query.pageSize());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        rows.add(new AdminContentRow(
                                ForumTargetType.POST, result.getLong("id"),
                                result.getLong("section_id"), result.getString("section_name"),
                                result.getLong("author_user_id"),
                                result.getString("author_display_name"),
                                result.getString("title"), result.getString("content"),
                                ForumContentStatus.valueOf(result.getString("status")),
                                result.getBoolean("locked"), result.getBoolean("pinned"),
                                result.getBoolean("featured"), instant(result, "created_at")));
                    }
                }
            }
        }
        return new AdminContentPage(rows, query.page(), query.pageSize(), total);
    }

    private AdminContentPage searchAdminComments(AdminContentQuery query) throws SQLException {
        List<Object> parameters = new ArrayList<>();
        String where = adminWhere("c", query, parameters, "p.title", "c.content");
        int total;
        List<AdminContentRow> rows = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection()) {
            total = count(connection,
                    "forum_comments c JOIN forum_posts p ON p.id = c.post_id", where, parameters);
            String sql = """
                    SELECT c.id, c.post_id, s.name AS section_name, c.author_user_id,
                           u.display_name AS author_display_name, p.title, c.content,
                           c.status, c.created_at
                    FROM forum_comments c
                    JOIN forum_posts p ON p.id = c.post_id
                    JOIN forum_sections s ON s.id = p.section_id
                    JOIN users u ON u.id = c.author_user_id
                    """ + where + " ORDER BY c.created_at DESC, c.id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindPage(statement, parameters, query.page(), query.pageSize());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        rows.add(new AdminContentRow(
                                ForumTargetType.COMMENT, result.getLong("id"),
                                result.getLong("post_id"), result.getString("section_name"),
                                result.getLong("author_user_id"),
                                result.getString("author_display_name"),
                                result.getString("title"), result.getString("content"),
                                ForumContentStatus.valueOf(result.getString("status")),
                                false, false, false, instant(result, "created_at")));
                    }
                }
            }
        }
        return new AdminContentPage(rows, query.page(), query.pageSize(), total);
    }

    private String postSearchWhere(PostQuery query, List<Object> parameters) {
        StringBuilder where = new StringBuilder(
                " WHERE p.status = 'NORMAL' AND s.enabled = TRUE");
        if (query.sectionId() != null) {
            where.append(" AND p.section_id = ?");
            parameters.add(query.sectionId());
        }
        String keyword = query.keyword() == null ? "" : query.keyword().trim();
        if (!keyword.isEmpty()) {
            where.append(" AND LOWER(CONCAT(p.title, ' ', p.content)) LIKE ? ESCAPE '!'");
            parameters.add("%" + escapeLike(keyword.toLowerCase()) + "%");
        }
        return where.toString();
    }

    private String adminWhere(String alias, AdminContentQuery query,
                              List<Object> parameters, String title, String content) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (query.status() != null) {
            where.append(" AND ").append(alias).append(".status = ?");
            parameters.add(query.status().name());
        }
        String keyword = query.keyword() == null ? "" : query.keyword().trim();
        if (!keyword.isEmpty()) {
            where.append(" AND LOWER(CONCAT(").append(title).append(", ' ', ")
                    .append(content).append(")) LIKE ? ESCAPE '!'");
            parameters.add("%" + escapeLike(keyword.toLowerCase()) + "%");
        }
        return where.toString();
    }

    private PostRow readPostRow(ResultSet result) throws SQLException {
        return new PostRow(
                result.getLong("id"), result.getLong("section_id"),
                result.getString("section_name"), result.getLong("author_user_id"),
                result.getString("author_display_name"), result.getString("title"),
                summary(result.getString("content")),
                ForumContentStatus.valueOf(result.getString("status")),
                result.getBoolean("locked"), result.getBoolean("pinned"),
                result.getBoolean("featured"), result.getInt("view_count"),
                result.getInt("comment_count"), instant(result, "created_at"),
                nullableInstant(result, "last_commented_at"));
    }

    private PostDetail readPostDetail(
            ResultSet result, long viewerUserId, boolean administrator) throws SQLException {
        ForumContentStatus status = ForumContentStatus.valueOf(result.getString("status"));
        long author = result.getLong("author_user_id");
        return new PostDetail(
                result.getLong("id"), result.getLong("section_id"),
                result.getString("section_name"), author,
                result.getString("author_display_name"), result.getString("title"),
                result.getString("content"), status, result.getBoolean("locked"),
                result.getBoolean("pinned"), result.getBoolean("featured"),
                result.getInt("view_count"), result.getInt("comment_count"),
                instant(result, "created_at"), instant(result, "updated_at"),
                nullableInstant(result, "last_commented_at"),
                status == ForumContentStatus.NORMAL
                        && (administrator || author == viewerUserId));
    }

    private OwnedState lockOwnedState(Connection connection, String table, long id)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT author_user_id, status FROM " + table + " WHERE id = ? FOR UPDATE")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? new OwnedState(result.getLong("author_user_id"),
                        ForumContentStatus.valueOf(result.getString("status")))
                        : null;
            }
        }
    }

    private CommentState lockComment(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT c.post_id, c.author_user_id, c.status, p.title"
                        + " FROM forum_comments c"
                        + " JOIN forum_posts p ON p.id = c.post_id"
                        + " WHERE c.id = ? FOR UPDATE")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? new CommentState(result.getLong("post_id"),
                        result.getLong("author_user_id"),
                        result.getString("title"),
                        ForumContentStatus.valueOf(result.getString("status")))
                        : null;
            }
        }
    }

    private PostModerationState lockPostState(Connection connection, long id)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT author_user_id, title, status, locked, pinned, featured"
                        + " FROM forum_posts"
                        + " WHERE id = ? FOR UPDATE")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new PostModerationState(
                        result.getLong("author_user_id"), result.getString("title"),
                        ForumContentStatus.valueOf(result.getString("status")),
                        result.getBoolean("locked"), result.getBoolean("pinned"),
                        result.getBoolean("featured")) : null;
            }
        }
    }

    private String postAssignment(PostModerationState state, ForumModerationAction action) {
        return switch (action) {
            case HIDE -> state.status() == ForumContentStatus.NORMAL ? "status = 'HIDDEN'" : null;
            case RESTORE -> state.status() == ForumContentStatus.HIDDEN ? "status = 'NORMAL'" : null;
            case LOCK -> !state.locked() && state.status() != ForumContentStatus.DELETED
                    ? "locked = TRUE" : null;
            case UNLOCK -> state.locked() ? "locked = FALSE" : null;
            case PIN -> !state.pinned() && state.status() != ForumContentStatus.DELETED
                    ? "pinned = TRUE" : null;
            case UNPIN -> state.pinned() ? "pinned = FALSE" : null;
            case FEATURE -> !state.featured() && state.status() != ForumContentStatus.DELETED
                    ? "featured = TRUE" : null;
            case UNFEATURE -> state.featured() ? "featured = FALSE" : null;
            default -> throw new IllegalArgumentException("帖子审核动作无效");
        };
    }

    private Boolean lockSectionEnabled(Connection connection, long sectionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT enabled FROM forum_sections WHERE id = ? FOR UPDATE")) {
            statement.setLong(1, sectionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getBoolean(1) : null;
            }
        }
    }

    private boolean sectionEnabled(Connection connection, long sectionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT enabled FROM forum_sections WHERE id = ?")) {
            statement.setLong(1, sectionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void refreshPostCommentStats(Connection connection, long postId)
            throws SQLException {
        String sql = """
                UPDATE forum_posts
                SET comment_count = (SELECT COUNT(*) FROM forum_comments
                                     WHERE post_id = ? AND status = 'NORMAL'),
                    last_commented_at = (SELECT MAX(created_at) FROM forum_comments
                                         WHERE post_id = ? AND status = 'NORMAL')
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, postId);
            statement.setLong(2, postId);
            statement.setLong(3, postId);
            statement.executeUpdate();
        }
    }

    private void insertLog(Connection connection, long operatorUserId,
                           ForumTargetType targetType, long targetId,
                           ForumModerationAction action, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO forum_moderation_logs"
                        + " (operator_user_id, target_type, target_id, action, reason)"
                        + " VALUES (?, ?, ?, ?, ?)")) {
            statement.setLong(1, operatorUserId);
            statement.setString(2, targetType.name());
            statement.setLong(3, targetId);
            statement.setString(4, action.name());
            statement.setString(5, reason == null ? "" : reason);
            statement.executeUpdate();
        }
    }

    private int count(Connection connection, String from, String where,
                      List<Object> parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + from + where)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private void bindPage(PreparedStatement statement, List<Object> parameters,
                          int page, int pageSize) throws SQLException {
        bind(statement, parameters);
        statement.setInt(parameters.size() + 1, pageSize);
        statement.setInt(parameters.size() + 2, (page - 1) * pageSize);
    }

    private void bind(PreparedStatement statement, List<Object> parameters)
            throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            statement.setObject(index + 1, parameters.get(index));
        }
    }

    private void bindSection(PreparedStatement statement, SaveSection command)
            throws SQLException {
        statement.setString(1, command.code());
        statement.setString(2, command.name());
        statement.setString(3, command.description());
        statement.setInt(4, command.sortOrder());
    }

    private long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) throw new SQLException("数据库未返回新记录编号");
            return keys.getLong(1);
        }
    }

    private String userDisplayName(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT display_name FROM users WHERE id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("用户不存在: " + userId);
                }
                return result.getString("display_name");
            }
        }
    }

    private MutationResult rollback(Connection connection, MutationResult result)
            throws SQLException {
        connection.rollback();
        return result;
    }

    private String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private String summary(String content) {
        return content.length() <= 120 ? content : content.substring(0, 120) + "…";
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        return result.getTimestamp(column).toInstant();
    }

    private Instant nullableInstant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record OwnedState(long authorUserId, ForumContentStatus status) {
    }

    private record CommentState(long postId, long authorUserId, String postTitle,
                                ForumContentStatus status) {
    }

    private record PostModerationState(long authorUserId, String title,
                                       ForumContentStatus status, boolean locked,
                                       boolean pinned, boolean featured) {
    }
}
