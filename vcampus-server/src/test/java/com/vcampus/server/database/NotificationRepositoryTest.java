package com.vcampus.server.database;

import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.NotificationStore.NotificationPage;
import com.vcampus.server.database.NotificationStore.NotificationQuery;
import com.vcampus.server.database.NotificationWriter.NotificationDraft;
import com.vcampus.server.model.NotificationRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationRepositoryTest {
    private ConnectionFactory connections;
    private NotificationRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", ""));
        repository = new NotificationRepository(connections);
        createSchema();
    }

    @Test
    void searchIsolatesRecipientAndAppliesSourceAndReadFilters() throws Exception {
        insertAt(1L, "教务未读", NotificationSource.ACADEMIC, false, "2026-08-26 08:00:00");
        insertAt(1L, "账号已读", NotificationSource.ACCOUNT_SECURITY, true, "2026-08-26 09:00:00");
        insertAt(2L, "他人教务未读", NotificationSource.ACADEMIC, false, "2026-08-26 10:00:00");

        NotificationPage page = repository.search(1L,
                new NotificationQuery("教务", NotificationSource.ACADEMIC, false, 1, 10));

        assertEquals(1, page.total());
        assertEquals(List.of("教务未读"),
                page.rows().stream().map(NotificationRecord::title).toList());
    }

    @Test
    void searchTreatsSqlWildcardsLiterallyAndUsesStableNewestFirstOrder() throws Exception {
        long first = insertAt(1L, "完成 100%", NotificationSource.ACADEMIC,
                false, "2026-08-26 10:00:00");
        long second = insertAt(1L, "再次完成 100%", NotificationSource.ACADEMIC,
                false, "2026-08-26 10:00:00");
        insertAt(1L, "完成 100X", NotificationSource.ACADEMIC,
                false, "2026-08-26 11:00:00");

        NotificationPage page = repository.search(1L,
                new NotificationQuery("100%", null, null, 1, 10));

        assertEquals(2, page.total());
        assertEquals(List.of(second, first),
                page.rows().stream().map(NotificationRecord::id).toList());
    }

    @Test
    void readOperationsCannotAffectAnotherRecipientAndAreIdempotent() throws Exception {
        long ownId = insertAt(1L, "自己的", NotificationSource.STUDENT_STATUS,
                false, "2026-08-26 08:00:00");
        long foreignId = insertAt(2L, "他人的", NotificationSource.ACADEMIC,
                false, "2026-08-26 09:00:00");

        assertTrue(repository.findOwned(1L, foreignId).isEmpty());
        assertFalse(repository.markRead(1L, foreignId));
        assertEquals(1, repository.unreadCount(2L));

        assertTrue(repository.markRead(1L, ownId));
        assertTrue(repository.markRead(1L, ownId));
        assertEquals(0, repository.unreadCount(1L));
        assertEquals(0, repository.markAllRead(1L));
    }

    @Test
    void writerUsesCallerTransactionForSingleAndBatchInserts() throws Exception {
        NotificationDraft first = draft(1L, "单条");
        NotificationDraft second = draft(1L, "批量一");
        NotificationDraft third = draft(2L, "批量二");

        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            repository.insert(connection, first);
            repository.insertBatch(connection, List.of(second, third));
            connection.rollback();
        }

        assertEquals(0, repository.unreadCount(1L));
        assertEquals(0, repository.unreadCount(2L));
    }

    private NotificationDraft draft(long recipientUserId, String title) {
        return new NotificationDraft(
                recipientUserId, null, NotificationType.PASSWORD_RESET,
                NotificationSource.ACCOUNT_SECURITY, title, title + "正文",
                NotificationTarget.NONE, null);
    }

    private long insertAt(
            long recipientUserId,
            String title,
            NotificationSource source,
            boolean read,
            String createdAt) throws SQLException {
        String sql = """
                INSERT INTO notifications
                    (recipient_user_id, notification_type, source_module, title, content,
                     target, is_read, read_at, created_at)
                VALUES (?, 'PASSWORD_RESET', ?, ?, ?, 'NONE', ?,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END, ?)
                """;
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, recipientUserId);
            statement.setString(2, source.name());
            statement.setString(3, title);
            statement.setString(4, title + "正文");
            statement.setBoolean(5, read);
            statement.setBoolean(6, read);
            statement.setString(7, createdAt);
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void createSchema() throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, display_name VARCHAR(100))");
            statement.execute("INSERT INTO users VALUES (1, '用户一'), (2, '用户二')");
            statement.execute("""
                    CREATE TABLE notifications (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        recipient_user_id BIGINT NOT NULL,
                        sender_user_id BIGINT,
                        notification_type VARCHAR(40) NOT NULL,
                        source_module VARCHAR(40) NOT NULL,
                        title VARCHAR(160) NOT NULL,
                        content VARCHAR(1000) NOT NULL,
                        target VARCHAR(40) NOT NULL,
                        related_entity_id BIGINT,
                        is_read BOOLEAN NOT NULL DEFAULT FALSE,
                        read_at TIMESTAMP,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (recipient_user_id) REFERENCES users(id),
                        FOREIGN KEY (sender_user_id) REFERENCES users(id)
                    )
                    """);
        }
    }
}
