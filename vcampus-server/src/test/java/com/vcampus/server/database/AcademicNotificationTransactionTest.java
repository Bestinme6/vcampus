package com.vcampus.server.database;

import com.vcampus.common.model.CourseSectionStatus;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import com.vcampus.common.model.ScheduleSlot;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.AcademicRepository.AcademicRuleException;
import com.vcampus.server.database.AcademicRepository.CreateSection;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcademicNotificationTransactionTest {
    private ConnectionFactory connections;
    private NotificationRepository notifications;

    @BeforeEach
    void setUp() throws SQLException {
        connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", ""));
        notifications = new NotificationRepository(connections);
        createSchema();
        seedData();
    }

    @Test
    void sectionCreationNotifiesAssignedTeacherInsideBusinessTransaction() throws Exception {
        AcademicRepository repository = new AcademicRepository(connections, notifications);

        long sectionId = repository.createSection(sectionCommand(), 900L, "系统管理员");

        var page = notifications.search(100L,
                new NotificationQuery("", null, null, 1, 10));
        assertEquals(1, page.total());
        var message = page.rows().getFirst();
        assertEquals(NotificationType.SCHEDULE_ASSIGNED, message.type());
        assertEquals(NotificationTarget.TEACHER_SCHEDULE, message.target());
        assertEquals(sectionId, message.relatedEntityId());
        assertTrue(message.content().contains("系统管理员"));
        assertTrue(message.content().contains("Java程序设计"));
        assertEquals(1, count("course_sections", "id = " + sectionId));
        assertEquals(1, count("class_schedules", "section_id = " + sectionId));
    }

    @Test
    void notificationFailureRollsBackSectionAndSchedules() {
        AcademicRepository repository = new AcademicRepository(
                connections, new FailingNotificationWriter());

        assertThrows(SQLException.class,
                () -> repository.createSection(sectionCommand(), 900L, "系统管理员"));

        assertEquals(1, countUnchecked("course_sections", "id = 1000"));
        assertEquals(0, countUnchecked("course_sections", "section_code = 'JAVA-02'"));
        assertEquals(0, countUnchecked("class_schedules", "section_id <> 1000"));
    }

    @Test
    void gradePublicationNotifiesOnlyCurrentlyEnrolledStudentsOnce() throws Exception {
        AcademicRepository repository = new AcademicRepository(connections, notifications);

        repository.publishGrades(1000L, 100L, false);

        var first = notifications.search(201L,
                new NotificationQuery("", null, null, 1, 10));
        var second = notifications.search(202L,
                new NotificationQuery("", null, null, 1, 10));
        var dropped = notifications.search(203L,
                new NotificationQuery("", null, null, 1, 10));
        assertEquals(1, first.total());
        assertEquals(1, second.total());
        assertEquals(0, dropped.total());
        assertEquals(NotificationType.GRADE_PUBLISHED, first.rows().getFirst().type());
        assertEquals(NotificationTarget.STUDENT_GRADES, first.rows().getFirst().target());
        assertTrue(first.rows().getFirst().content().contains("王老师"));
        assertTrue(first.rows().getFirst().content().contains("数据库系统原理"));

        assertThrows(AcademicRuleException.class,
                () -> repository.publishGrades(1000L, 100L, false));
        assertEquals(1, notifications.search(201L,
                new NotificationQuery("", null, null, 1, 10)).total());
    }

    @Test
    void notificationFailureRollsBackGradePublication() {
        AcademicRepository repository = new AcademicRepository(
                connections, new FailingNotificationWriter());

        assertThrows(SQLException.class,
                () -> repository.publishGrades(1000L, 100L, false));

        assertFalse(booleanValue(
                "SELECT grades_published FROM course_sections WHERE id = 1000"));
        assertEquals(0, countUnchecked("grades", "published_at IS NOT NULL"));
    }

    @Test
    void teacherCannotPublishAnotherTeachersSection() {
        AcademicRepository repository = new AcademicRepository(connections, notifications);

        AcademicRuleException exception = assertThrows(
                AcademicRuleException.class,
                () -> repository.publishGrades(1000L, 999L, false));

        assertEquals("只能发布本人教学班的成绩", exception.getMessage());
        assertFalse(booleanValue(
                "SELECT grades_published FROM course_sections WHERE id = 1000"));
        assertEquals(0, countUnchecked("grades", "published_at IS NOT NULL"));
    }

    private CreateSection sectionCommand() {
        return new CreateSection(
                1L, 10L, "JAVA-02", 100L, 40, CourseSectionStatus.OPEN,
                List.of(new ScheduleSlot(2, 3, 4, 1, 16, "教一-201")));
    }

    private void createSchema() throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, display_name VARCHAR(100), enabled BOOLEAN)");
            statement.execute("CREATE TABLE roles (id BIGINT PRIMARY KEY, role_code VARCHAR(64))");
            statement.execute("CREATE TABLE user_roles (user_id BIGINT, role_id BIGINT)");
            statement.execute("CREATE TABLE academic_terms (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE courses (id BIGINT PRIMARY KEY, course_code VARCHAR(7), course_name VARCHAR(120), enabled BOOLEAN)");
            statement.execute("CREATE TABLE course_sections (id BIGINT AUTO_INCREMENT PRIMARY KEY, term_id BIGINT, course_id BIGINT, section_code VARCHAR(24) UNIQUE, teacher_user_id BIGINT, capacity INT, enrolled_count INT DEFAULT 0, status VARCHAR(16), grades_published BOOLEAN DEFAULT FALSE)");
            statement.execute("CREATE TABLE class_schedules (id BIGINT AUTO_INCREMENT PRIMARY KEY, section_id BIGINT, day_of_week INT, start_period INT, end_period INT, start_week INT, end_week INT, classroom VARCHAR(100))");
            statement.execute("CREATE TABLE student_profiles (id BIGINT PRIMARY KEY, user_id BIGINT)");
            statement.execute("CREATE TABLE course_enrollments (id BIGINT PRIMARY KEY, section_id BIGINT, student_id BIGINT, status VARCHAR(16))");
            statement.execute("CREATE TABLE grades (id BIGINT PRIMARY KEY, enrollment_id BIGINT, published_at TIMESTAMP)");
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

    private void seedData() throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users VALUES (900, '系统管理员', TRUE), (100, '王老师', TRUE), (201, '学生一', TRUE), (202, '学生二', TRUE), (203, '退课学生', TRUE)");
            statement.executeUpdate("INSERT INTO roles VALUES (1, 'TEACHER')");
            statement.executeUpdate("INSERT INTO user_roles VALUES (100, 1)");
            statement.executeUpdate("INSERT INTO academic_terms VALUES (1)");
            statement.executeUpdate("INSERT INTO courses VALUES (10, 'C000010', 'Java程序设计', TRUE), (11, 'C000011', '数据库系统原理', TRUE)");
            statement.executeUpdate("INSERT INTO course_sections (id, term_id, course_id, section_code, teacher_user_id, capacity, enrolled_count, status, grades_published) VALUES (1000, 1, 11, 'DB-01', 100, 40, 2, 'OPEN', FALSE)");
            statement.executeUpdate("INSERT INTO class_schedules VALUES (1, 1000, 1, 1, 2, 1, 16, '教一-101')");
            statement.executeUpdate("INSERT INTO student_profiles VALUES (301, 201), (302, 202), (303, 203)");
            statement.executeUpdate("INSERT INTO course_enrollments VALUES (401, 1000, 301, 'ENROLLED'), (402, 1000, 302, 'ENROLLED'), (403, 1000, 303, 'DROPPED')");
            statement.executeUpdate("INSERT INTO grades VALUES (501, 401, NULL), (502, 402, NULL)");
        }
    }

    private int count(String table, String where) throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM " + table + " WHERE " + where)) {
            result.next();
            return result.getInt(1);
        }
    }

    private int countUnchecked(String table, String where) {
        try {
            return count(table, where);
        } catch (SQLException exception) {
            throw new AssertionError(exception);
        }
    }

    private boolean booleanValue(String sql) {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getBoolean(1);
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
