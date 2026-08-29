package com.vcampus.server.database;

import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import com.vcampus.common.model.StudentStatus;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.NotificationStore.NotificationQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudentStatusNotificationTest {
    private ConnectionFactory connections;
    private NotificationRepository notifications;

    @BeforeEach
    void setUp() throws SQLException {
        connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", ""));
        notifications = new NotificationRepository(connections);
        createSchema();
    }

    @Test
    void realStatusChangeNotifiesStudentWithOldNewStatusAndReason() throws Exception {
        StudentRepository repository = new StudentRepository(connections, notifications);

        StudentStatus old = repository.changeStatus(
                10L, StudentStatus.SUSPENDED, "个人申请", 900L, "李老师");

        assertEquals(StudentStatus.ENROLLED, old);
        var page = notifications.search(20L,
                new NotificationQuery("", null, null, 1, 10));
        assertEquals(1, page.total());
        var message = page.rows().getFirst();
        assertEquals(NotificationType.STUDENT_STATUS_CHANGED, message.type());
        assertEquals(NotificationTarget.STUDENT_PROFILE, message.target());
        assertEquals(10L, message.relatedEntityId());
        assertTrue(message.content().contains("李老师"));
        assertTrue(message.content().contains("在读"));
        assertTrue(message.content().contains("休学"));
        assertTrue(message.content().contains("个人申请"));
        assertEquals(1, count("student_status_history"));
    }

    @Test
    void unchangedStatusCreatesNeitherHistoryNorNotification() throws Exception {
        StudentRepository repository = new StudentRepository(connections, notifications);

        StudentStatus old = repository.changeStatus(
                10L, StudentStatus.ENROLLED, "无需变更", 900L, "李老师");

        assertEquals(StudentStatus.ENROLLED, old);
        assertEquals(0, count("student_status_history"));
        assertEquals(0, notifications.unreadCount(20L));
    }

    @Test
    void notificationFailureRollsBackStatusAndHistory() {
        StudentRepository repository = new StudentRepository(
                connections, new FailingNotificationWriter());

        assertThrows(SQLException.class, () -> repository.changeStatus(
                10L, StudentStatus.SUSPENDED, "个人申请", 900L, "李老师"));

        assertEquals("ENROLLED", status());
        assertEquals(0, countUnchecked("student_status_history"));
    }

    private void createSchema() throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, display_name VARCHAR(100))");
            statement.execute("INSERT INTO users VALUES (20, '学生'), (900, '李老师')");
            statement.execute("CREATE TABLE student_profiles (id BIGINT PRIMARY KEY, user_id BIGINT, status VARCHAR(16))");
            statement.execute("INSERT INTO student_profiles VALUES (10, 20, 'ENROLLED')");
            statement.execute("CREATE TABLE student_status_history (id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT, old_status VARCHAR(16), new_status VARCHAR(16), reason VARCHAR(255), changed_by_user_id BIGINT, changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
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
                        is_read BOOLEAN DEFAULT FALSE,
                        read_at TIMESTAMP,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                    """);
        }
    }

    private int count(String table) throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private int countUnchecked(String table) {
        try {
            return count(table);
        } catch (SQLException exception) {
            throw new AssertionError(exception);
        }
    }

    private String status() {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT status FROM student_profiles WHERE id = 10")) {
            result.next();
            return result.getString(1);
        } catch (SQLException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class FailingNotificationWriter implements NotificationWriter {
        @Override
        public void insert(Connection connection, NotificationDraft draft) throws SQLException {
            throw new SQLException("notification failed");
        }

        @Override
        public void insertBatch(Connection connection, List<NotificationDraft> drafts)
                throws SQLException {
            throw new SQLException("notification failed");
        }
    }
}
