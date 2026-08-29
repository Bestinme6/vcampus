package com.vcampus.server.database;

import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import com.vcampus.server.database.NotificationStore.NotificationPage;
import com.vcampus.server.database.NotificationStore.NotificationQuery;
import com.vcampus.server.database.NotificationWriter.NotificationDraft;
import com.vcampus.server.model.NotificationRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class NotificationRepository implements NotificationStore, NotificationWriter {
    private static final String COLUMNS = """
            id, recipient_user_id, sender_user_id, notification_type, source_module,
            title, content, target, related_entity_id, is_read, read_at, created_at
            """;
    private static final String INSERT_SQL = """
            INSERT INTO notifications
                (recipient_user_id, sender_user_id, notification_type, source_module,
                 title, content, target, related_entity_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final ConnectionFactory connectionFactory;

    public NotificationRepository(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public NotificationPage search(long recipientUserId, NotificationQuery query)
            throws SQLException {
        List<Object> parameters = new ArrayList<>();
        String where = buildWhere(recipientUserId, query, parameters);
        int total;
        List<NotificationRecord> rows = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM notifications" + where)) {
                bind(statement, parameters);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    total = result.getInt(1);
                }
            }
            String sql = "SELECT " + COLUMNS + " FROM notifications" + where
                    + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                List<Object> pageParameters = new ArrayList<>(parameters);
                pageParameters.add(query.pageSize());
                pageParameters.add((query.page() - 1) * query.pageSize());
                bind(statement, pageParameters);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        rows.add(read(result));
                    }
                }
            }
        }
        return new NotificationPage(rows, query.page(), query.pageSize(), total);
    }

    @Override
    public Optional<NotificationRecord> findOwned(long recipientUserId, long notificationId)
            throws SQLException {
        String sql = "SELECT " + COLUMNS
                + " FROM notifications WHERE id = ? AND recipient_user_id = ?";
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, notificationId);
            statement.setLong(2, recipientUserId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    @Override
    public int unreadCount(long recipientUserId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM notifications"
                + " WHERE recipient_user_id = ? AND is_read = FALSE";
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, recipientUserId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    @Override
    public boolean markRead(long recipientUserId, long notificationId) throws SQLException {
        if (findOwned(recipientUserId, notificationId).isEmpty()) {
            return false;
        }
        String sql = """
                UPDATE notifications
                SET is_read = TRUE, read_at = COALESCE(read_at, CURRENT_TIMESTAMP)
                WHERE id = ? AND recipient_user_id = ?
                """;
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, notificationId);
            statement.setLong(2, recipientUserId);
            statement.executeUpdate();
            return true;
        }
    }

    @Override
    public int markAllRead(long recipientUserId) throws SQLException {
        String sql = """
                UPDATE notifications
                SET is_read = TRUE, read_at = COALESCE(read_at, CURRENT_TIMESTAMP)
                WHERE recipient_user_id = ? AND is_read = FALSE
                """;
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, recipientUserId);
            return statement.executeUpdate();
        }
    }

    @Override
    public void insert(Connection connection, NotificationDraft draft) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            bindDraft(statement, draft);
            statement.executeUpdate();
        }
    }

    @Override
    public void insertBatch(Connection connection, List<NotificationDraft> drafts)
            throws SQLException {
        if (drafts.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            for (NotificationDraft draft : drafts) {
                bindDraft(statement, draft);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private String buildWhere(
            long recipientUserId,
            NotificationQuery query,
            List<Object> parameters) {
        StringBuilder where = new StringBuilder(" WHERE recipient_user_id = ?");
        parameters.add(recipientUserId);
        if (!query.keyword().isBlank()) {
            where.append(" AND LOWER(CONCAT(title, ' ', content)) LIKE ? ESCAPE '!'");
            parameters.add("%" + escapeLike(query.keyword().toLowerCase()) + "%");
        }
        if (query.source() != null) {
            where.append(" AND source_module = ?");
            parameters.add(query.source().name());
        }
        if (query.read() != null) {
            where.append(" AND is_read = ?");
            parameters.add(query.read());
        }
        return where.toString();
    }

    private String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            statement.setObject(index + 1, parameters.get(index));
        }
    }

    private void bindDraft(PreparedStatement statement, NotificationDraft draft)
            throws SQLException {
        statement.setLong(1, draft.recipientUserId());
        if (draft.senderUserId() == null) {
            statement.setNull(2, Types.BIGINT);
        } else {
            statement.setLong(2, draft.senderUserId());
        }
        statement.setString(3, draft.type().name());
        statement.setString(4, draft.source().name());
        statement.setString(5, draft.title());
        statement.setString(6, draft.content());
        statement.setString(7, draft.target().name());
        if (draft.relatedEntityId() == null) {
            statement.setNull(8, Types.BIGINT);
        } else {
            statement.setLong(8, draft.relatedEntityId());
        }
    }

    private NotificationRecord read(ResultSet result) throws SQLException {
        long sender = result.getLong("sender_user_id");
        Long senderUserId = result.wasNull() ? null : sender;
        long related = result.getLong("related_entity_id");
        Long relatedEntityId = result.wasNull() ? null : related;
        Timestamp readAt = result.getTimestamp("read_at");
        return new NotificationRecord(
                result.getLong("id"),
                result.getLong("recipient_user_id"),
                senderUserId,
                NotificationType.valueOf(result.getString("notification_type")),
                NotificationSource.valueOf(result.getString("source_module")),
                result.getString("title"),
                result.getString("content"),
                NotificationTarget.valueOf(result.getString("target")),
                relatedEntityId,
                result.getBoolean("is_read"),
                readAt == null ? null : readAt.toInstant(),
                result.getTimestamp("created_at").toInstant());
    }
}
