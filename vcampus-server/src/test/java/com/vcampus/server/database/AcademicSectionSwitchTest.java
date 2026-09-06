package com.vcampus.server.database;

import com.vcampus.common.model.EnrollmentStatus;
import com.vcampus.server.config.DatabaseConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class AcademicSectionSwitchTest {
    private ConnectionFactory connections;
    private AcademicRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connections = database();
        repository = new AcademicRepository(connections, null);
    }

    @Test
    void switchesSameCourseAndUpdatesBothCounts() throws Exception {
        repository.switchSection(501, 700, 701);

        assertEquals(EnrollmentStatus.DROPPED, enrollmentStatus(700));
        assertEquals(EnrollmentStatus.ENROLLED, enrollmentStatus(701));
        assertEquals(19, enrolledCount(700));
        assertEquals(21, enrolledCount(701));
    }

    @Test
    void fullTargetKeepsOriginalEnrollment() {
        assertThrows(AcademicRepository.AcademicRuleException.class,
                () -> repository.switchSection(501, 700, 702));

        assertEquals(EnrollmentStatus.ENROLLED, enrollmentStatusUnchecked(700));
        assertEquals(20, enrolledCountUnchecked(700));
        assertEquals(20, enrolledCountUnchecked(702));
    }

    @Test
    void rejectsDifferentCourseAndDifferentTerm() {
        assertThrows(AcademicRepository.AcademicRuleException.class,
                () -> repository.switchSection(501, 700, 703));
        assertThrows(AcademicRepository.AcademicRuleException.class,
                () -> repository.switchSection(501, 700, 704));
        assertEquals(EnrollmentStatus.ENROLLED, enrollmentStatusUnchecked(700));
    }

    @Test
    void rejectsClosedOutOfScopeAndExpiredTargets() {
        assertThrows(AcademicRepository.AcademicRuleException.class,
                () -> repository.switchSection(501, 700, 705));
        assertThrows(AcademicRepository.AcademicRuleException.class,
                () -> repository.switchSection(501, 700, 706));
        assertThrows(AcademicRepository.AcademicRuleException.class,
                () -> repository.switchSection(501, 700, 707));
        assertEquals(EnrollmentStatus.ENROLLED, enrollmentStatusUnchecked(700));
    }

    @Test
    void scheduleConflictIgnoresSectionBeingReplaced() throws Exception {
        repository.switchSection(501, 700, 701);

        assertEquals(EnrollmentStatus.ENROLLED, enrollmentStatus(701));
    }

    @Test
    void unrelatedScheduleConflictRollsBackSwitch() throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO course_enrollments VALUES (802, 708, 601, 'ENROLLED', CURRENT_TIMESTAMP, NULL)");
        }

        assertThrows(AcademicRepository.AcademicRuleException.class,
                () -> repository.switchSection(501, 700, 701));
        assertEquals(EnrollmentStatus.ENROLLED, enrollmentStatus(700));
        assertEquals(20, enrolledCount(700));
        assertEquals(20, enrolledCount(701));
    }

    private EnrollmentStatus enrollmentStatus(long sectionId) throws Exception {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement(
                     "SELECT status FROM course_enrollments WHERE student_id = 601 AND section_id = ?")) {
            statement.setLong(1, sectionId);
            try (var result = statement.executeQuery()) {
                result.next();
                return EnrollmentStatus.valueOf(result.getString(1));
            }
        }
    }

    private EnrollmentStatus enrollmentStatusUnchecked(long sectionId) {
        try { return enrollmentStatus(sectionId); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private int enrolledCount(long sectionId) throws Exception {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement(
                     "SELECT enrolled_count FROM course_sections WHERE id = ?")) {
            statement.setLong(1, sectionId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private int enrolledCountUnchecked(long sectionId) {
        try { return enrolledCount(sectionId); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    public static ConnectionFactory database() throws Exception {
        ConnectionFactory factory = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:section-switch-" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = factory.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, display_name VARCHAR(100))");
            statement.execute("CREATE TABLE student_profiles (id BIGINT PRIMARY KEY, user_id BIGINT UNIQUE, major_id BIGINT, enrollment_year INT)");
            statement.execute("CREATE TABLE academic_terms (id BIGINT PRIMARY KEY, status VARCHAR(16), selection_start TIMESTAMP, selection_end TIMESTAMP, drop_deadline TIMESTAMP)");
            statement.execute("CREATE TABLE courses (id BIGINT PRIMARY KEY, enabled BOOLEAN)");
            statement.execute("CREATE TABLE course_sections (id BIGINT PRIMARY KEY, term_id BIGINT, course_id BIGINT, section_code VARCHAR(24), teacher_user_id BIGINT, capacity INT, enrolled_count INT, status VARCHAR(16), grades_published BOOLEAN)");
            statement.execute("CREATE TABLE course_enrollments (id BIGINT AUTO_INCREMENT PRIMARY KEY, section_id BIGINT, student_id BIGINT, status VARCHAR(16), enrolled_at TIMESTAMP, dropped_at TIMESTAMP, UNIQUE(section_id, student_id))");
            statement.execute("CREATE TABLE curriculum_plans (id BIGINT PRIMARY KEY, major_id BIGINT, enrollment_year_start INT, enrollment_year_end INT, status VARCHAR(16))");
            statement.execute("CREATE TABLE curriculum_plan_courses (plan_id BIGINT, course_id BIGINT)");
            statement.execute("CREATE TABLE course_section_targets (section_id BIGINT, major_id BIGINT, enrollment_year_start INT, enrollment_year_end INT)");
            statement.execute("CREATE TABLE course_section_schedule_revisions (id BIGINT PRIMARY KEY, section_id BIGINT, status VARCHAR(16))");
            statement.execute("CREATE TABLE class_schedules (id BIGINT PRIMARY KEY, section_id BIGINT, revision_id BIGINT, day_of_week INT, start_period INT, end_period INT, start_week INT, end_week INT, classroom VARCHAR(100))");
            statement.executeUpdate("INSERT INTO users VALUES (20, '王老师')");
            statement.executeUpdate("INSERT INTO student_profiles VALUES (601, 501, 10, 2025)");
            statement.executeUpdate("INSERT INTO academic_terms VALUES (1, 'SELECTION', TIMESTAMP '2020-01-01 00:00:00', TIMESTAMP '2030-01-01 00:00:00', TIMESTAMP '2030-01-01 00:00:00'), (2, 'SELECTION', TIMESTAMP '2020-01-01 00:00:00', TIMESTAMP '2030-01-01 00:00:00', TIMESTAMP '2030-01-01 00:00:00'), (3, 'SELECTION', TIMESTAMP '2020-01-01 00:00:00', TIMESTAMP '2021-01-01 00:00:00', TIMESTAMP '2021-01-01 00:00:00')");
            statement.executeUpdate("INSERT INTO courses VALUES (100, TRUE), (101, TRUE)");
            statement.executeUpdate("INSERT INTO course_sections VALUES (700,1,100,'OLD',20,30,20,'OPEN',FALSE),(701,1,100,'NEW',20,30,20,'OPEN',FALSE),(702,1,100,'FULL',20,20,20,'OPEN',FALSE),(703,1,101,'OTHER-COURSE',20,30,0,'OPEN',FALSE),(704,2,100,'OTHER-TERM',20,30,0,'OPEN',FALSE),(705,1,100,'CLOSED',20,30,0,'CLOSED',FALSE),(706,1,100,'OUT-SCOPE',20,30,0,'OPEN',FALSE),(707,3,100,'EXPIRED',20,30,0,'OPEN',FALSE),(708,1,101,'CONFLICT',20,30,1,'OPEN',FALSE)");
            statement.executeUpdate("INSERT INTO course_enrollments VALUES (800,700,601,'ENROLLED',CURRENT_TIMESTAMP,NULL),(801,701,601,'DROPPED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            statement.executeUpdate("INSERT INTO curriculum_plans VALUES (300,10,2024,2028,'PUBLISHED')");
            statement.executeUpdate("INSERT INTO curriculum_plan_courses VALUES (300,100)");
            statement.executeUpdate("INSERT INTO course_section_targets VALUES (706,20,2024,2028)");
            statement.executeUpdate("INSERT INTO course_section_schedule_revisions VALUES (900,700,'PUBLISHED'),(901,701,'PUBLISHED'),(902,708,'PUBLISHED')");
            statement.executeUpdate("INSERT INTO class_schedules VALUES (1000,700,900,1,1,2,1,16,'A'),(1001,701,901,1,1,2,1,16,'B'),(1002,708,902,1,1,2,1,16,'C')");
        }
        return factory;
    }
}
