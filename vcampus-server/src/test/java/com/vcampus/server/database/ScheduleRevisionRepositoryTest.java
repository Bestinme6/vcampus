package com.vcampus.server.database;

import com.vcampus.common.model.ScheduleRevisionStatus;
import com.vcampus.common.model.ScheduleSlot;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.NotificationWriter.NotificationDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ScheduleRevisionRepositoryTest {
    private ConnectionFactory connections;
    private ScheduleRevisionRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connections = database();
        repository = new ScheduleRevisionRepository(connections, new NoopNotifications());
    }

    @Test
    void studentsKeepPublishedScheduleWhileAdminEditsDraft() throws Exception {
        ScheduleRevisionRepository.ScheduleDraft draft = repository.getOrCreateDraft(700, 1);
        repository.saveDraft(new ScheduleRevisionRepository.SaveScheduleDraft(
                700, draft.revisionId(), draft.revisionNo(), List.of(wednesday())), 1);
        AcademicRepository academic = new AcademicRepository(connections, new NoopNotifications());

        var visible = academic.mySchedule(501, 1).getFirst();
        assertEquals(1, visible.dayOfWeek());
        assertEquals("教一-101", visible.classroom());
        assertEquals(new BigDecimal("3.0"), visible.credits());
    }

    @Test
    void saveRejectsStaleRevisionAndInternalOverlap() throws Exception {
        var draft = repository.getOrCreateDraft(700, 1);
        var saved = repository.saveDraft(new ScheduleRevisionRepository.SaveScheduleDraft(
                700, draft.revisionId(), draft.revisionNo(), List.of(wednesday())), 1);

        assertThrows(IllegalStateException.class, () -> repository.saveDraft(
                new ScheduleRevisionRepository.SaveScheduleDraft(
                        700, draft.revisionId(), draft.revisionNo(), List.of(monday())), 1));
        assertThrows(IllegalArgumentException.class, () -> repository.saveDraft(
                new ScheduleRevisionRepository.SaveScheduleDraft(
                        700, saved.revisionId(), saved.revisionNo(),
                        List.of(wednesday(), new ScheduleSlot(3, 4, 6, 1, 16, "教一-201"))), 1));
    }

    @Test
    void publishRejectsTeacherAndRoomConflicts() throws Exception {
        var draft = repository.getOrCreateDraft(700, 1);
        var saved = repository.saveDraft(new ScheduleRevisionRepository.SaveScheduleDraft(
                700, draft.revisionId(), draft.revisionNo(),
                List.of(new ScheduleSlot(2, 1, 2, 1, 16, "教二-202"))), 1);

        ScheduleRevisionRepository.ScheduleConflictException failure = assertThrows(
                ScheduleRevisionRepository.ScheduleConflictException.class,
                () -> repository.publish(new ScheduleRevisionRepository.PublishSchedule(
                        700, saved.revisionId(), saved.revisionNo()), 1, "管理员"));

        assertEquals(ScheduleRevisionRepository.ConflictKind.TEACHER,
                failure.conflicts().getFirst().kind());
    }

    @Test
    void publishReportsRoomConflictWhenTeacherIsAvailable() throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users VALUES (21, '李老师')");
            statement.executeUpdate("UPDATE course_sections SET teacher_user_id = 21 WHERE id = 700");
        }
        var draft = repository.getOrCreateDraft(700, 1);
        var saved = repository.saveDraft(new ScheduleRevisionRepository.SaveScheduleDraft(
                700, draft.revisionId(), draft.revisionNo(),
                List.of(new ScheduleSlot(2, 2, 3, 1, 16, "教二-202"))), 1);

        var failure = assertThrows(ScheduleRevisionRepository.ScheduleConflictException.class,
                () -> repository.publish(new ScheduleRevisionRepository.PublishSchedule(
                        700, saved.revisionId(), saved.revisionNo()), 1, "管理员"));

        assertEquals(ScheduleRevisionRepository.ConflictKind.CLASSROOM,
                failure.conflicts().getFirst().kind());
    }

    @Test
    void successfulPublishSupersedesPreviousRevision() throws Exception {
        var draft = repository.getOrCreateDraft(700, 1);
        var saved = repository.saveDraft(new ScheduleRevisionRepository.SaveScheduleDraft(
                700, draft.revisionId(), draft.revisionNo(), List.of(wednesday())), 1);

        var published = repository.publish(new ScheduleRevisionRepository.PublishSchedule(
                700, saved.revisionId(), saved.revisionNo()), 1, "管理员");

        assertEquals(ScheduleRevisionStatus.SUPERSEDED, status(1000));
        assertEquals(ScheduleRevisionStatus.PUBLISHED, published.status());
    }

    @Test
    void notificationFailureRollsBackPublishAndSupersede() throws Exception {
        repository = new ScheduleRevisionRepository(connections, new FailingNotifications());
        var draft = repository.getOrCreateDraft(700, 1);
        var saved = repository.saveDraft(new ScheduleRevisionRepository.SaveScheduleDraft(
                700, draft.revisionId(), draft.revisionNo(), List.of(wednesday())), 1);

        assertThrows(SQLException.class, () -> repository.publish(
                new ScheduleRevisionRepository.PublishSchedule(
                        700, saved.revisionId(), saved.revisionNo()), 1, "管理员"));

        assertEquals(ScheduleRevisionStatus.PUBLISHED, status(1000));
        assertEquals(ScheduleRevisionStatus.DRAFT, status(saved.revisionId()));
    }

    private ScheduleRevisionStatus status(long revisionId) throws Exception {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement(
                     "SELECT status FROM course_section_schedule_revisions WHERE id = ?")) {
            statement.setLong(1, revisionId);
            try (var result = statement.executeQuery()) {
                result.next();
                return ScheduleRevisionStatus.valueOf(result.getString(1));
            }
        }
    }

    private ScheduleSlot monday() {
        return new ScheduleSlot(1, 1, 2, 1, 16, "教一-101");
    }

    private ScheduleSlot wednesday() {
        return new ScheduleSlot(3, 5, 6, 1, 16, "教一-201");
    }

    public static ConnectionFactory database() throws Exception {
        ConnectionFactory connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:schedule-revision-" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, display_name VARCHAR(100))");
            statement.execute("CREATE TABLE courses (id BIGINT PRIMARY KEY, course_code VARCHAR(7), course_name VARCHAR(120), credits DECIMAL(3,1))");
            statement.execute("CREATE TABLE academic_terms (id BIGINT PRIMARY KEY, term_name VARCHAR(100))");
            statement.execute("CREATE TABLE student_profiles (id BIGINT PRIMARY KEY, user_id BIGINT UNIQUE)");
            statement.execute("""
                    CREATE TABLE course_sections (
                        id BIGINT PRIMARY KEY, term_id BIGINT, course_id BIGINT,
                        section_code VARCHAR(24), teacher_user_id BIGINT, capacity INT,
                        enrolled_count INT, status VARCHAR(16), grades_published BOOLEAN)
                    """);
            statement.execute("CREATE TABLE course_enrollments (id BIGINT PRIMARY KEY, section_id BIGINT, student_id BIGINT, status VARCHAR(16))");
            statement.execute("""
                    CREATE TABLE course_section_schedule_revisions (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY, section_id BIGINT,
                        revision_no INT, status VARCHAR(16), created_by_user_id BIGINT,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        published_by_user_id BIGINT, published_at TIMESTAMP,
                        UNIQUE(section_id, revision_no))
                    """);
            statement.execute("""
                    CREATE TABLE class_schedules (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY, section_id BIGINT,
                        revision_id BIGINT, day_of_week INT, start_period INT, end_period INT,
                        start_week INT, end_week INT, classroom VARCHAR(100))
                    """);
            statement.executeUpdate("INSERT INTO users VALUES (1, '管理员'), (20, '王老师'), (501, '学生')");
            statement.executeUpdate("INSERT INTO courses VALUES (100, 'C000100', '程序设计', 3.0)");
            statement.executeUpdate("INSERT INTO academic_terms VALUES (1, '2026春')");
            statement.executeUpdate("INSERT INTO student_profiles VALUES (601, 501)");
            statement.executeUpdate("INSERT INTO course_sections VALUES (700, 1, 100, 'CS-01', 20, 30, 1, 'OPEN', FALSE), (701, 1, 100, 'CS-02', 20, 30, 0, 'OPEN', FALSE)");
            statement.executeUpdate("INSERT INTO course_enrollments VALUES (800, 700, 601, 'ENROLLED')");
            statement.executeUpdate("INSERT INTO course_section_schedule_revisions VALUES (1000, 700, 1, 'PUBLISHED', 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP), (1001, 701, 1, 'PUBLISHED', 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)");
            statement.executeUpdate("INSERT INTO class_schedules(section_id, revision_id, day_of_week, start_period, end_period, start_week, end_week, classroom) VALUES (700, 1000, 1, 1, 2, 1, 16, '教一-101'), (701, 1001, 2, 1, 2, 1, 16, '教二-202')");
        }
        return connections;
    }

    public static class NoopNotifications implements NotificationWriter {
        @Override public void insert(Connection connection, NotificationDraft draft)
                throws SQLException { }
        @Override public void insertBatch(Connection connection, List<NotificationDraft> drafts)
                throws SQLException { }
    }

    static final class FailingNotifications extends NoopNotifications {
        @Override
        public void insertBatch(Connection connection, List<NotificationDraft> drafts)
                throws SQLException {
            throw new SQLException("notification failed");
        }
    }
}
