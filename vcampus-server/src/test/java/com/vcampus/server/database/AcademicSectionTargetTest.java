package com.vcampus.server.database;

import com.vcampus.server.config.DatabaseConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AcademicSectionTargetTest {
    private ConnectionFactory connections;
    private AcademicRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connections = newDatabase();
        repository = new AcademicRepository(connections, null);
    }

    @Test
    void explicitTargetRequiresBothCurriculumAndRangeMatch() throws Exception {
        repository.saveSectionTargets(700,
                List.of(new AcademicRepository.SectionTarget(10, 2025, 2026)));

        assertTrue(repository.availableCourseGroups(501, 1).getFirst().sections().stream()
                .anyMatch(row -> row.sectionId() == 700));
        assertFalse(repository.availableCourseGroups(502, 1).stream()
                .flatMap(row -> row.sections().stream())
                .anyMatch(row -> row.sectionId() == 700));
    }

    @Test
    void emptyTargetsMakeSectionAvailableToEveryMatchingCurriculumStudent() throws Exception {
        repository.saveSectionTargets(700,
                List.of(new AcademicRepository.SectionTarget(10, 2025, 2025)));
        repository.saveSectionTargets(700, List.of());

        assertTrue(repository.availableCourseGroups(502, 1).getFirst().sections().stream()
                .anyMatch(row -> row.sectionId() == 700));
        assertTrue(repository.getSectionTargets(700).isEmpty());
    }

    @Test
    void replacingTargetsIsAtomicAndReturnsImmutableRows() throws Exception {
        repository.saveSectionTargets(700, List.of(
                new AcademicRepository.SectionTarget(10, 2025, 2026),
                new AcademicRepository.SectionTarget(20, 2024, 2028)));

        List<AcademicRepository.SectionTarget> targets = repository.getSectionTargets(700);

        assertEquals(2, targets.size());
        assertEquals(20, targets.get(1).majorId());
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> targets.add(new AcademicRepository.SectionTarget(10, 2027, 2028)));
    }

    @Test
    void groupedAvailabilityReportsOwnEnrollmentFullAndScheduleConflict() throws Exception {
        try (Connection connection = connections.openConnection();
            Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO course_sections VALUES (701, 1, 100, 'CS-02', 20, 1, 1, 'OPEN', FALSE)");
            statement.executeUpdate("INSERT INTO course_section_schedule_revisions VALUES (901, 701, 1, 'PUBLISHED', 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)");
            statement.executeUpdate("INSERT INTO class_schedules(section_id, revision_id, day_of_week, start_period, end_period, start_week, end_week, classroom) VALUES (701, 901, 1, 2, 3, 1, 16, '教一-102')");
            statement.executeUpdate("INSERT INTO course_enrollments(section_id, student_id, status) VALUES (700, 601, 'ENROLLED')");
        }

        var sections = repository.availableCourseGroups(501, 1).getFirst().sections();
        var current = sections.stream().filter(row -> row.sectionId() == 700).findFirst().orElseThrow();
        var alternative = sections.stream().filter(row -> row.sectionId() == 701).findFirst().orElseThrow();

        assertTrue(current.ownEnrollmentId() != null);
        assertTrue(alternative.full());
        assertTrue(alternative.scheduleConflict());
    }

    public static ConnectionFactory newDatabase() throws Exception {
        ConnectionFactory connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:section-target-" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, display_name VARCHAR(100))");
            statement.execute("CREATE TABLE majors (id BIGINT PRIMARY KEY, major_name VARCHAR(100))");
            statement.execute("CREATE TABLE student_profiles (id BIGINT PRIMARY KEY, user_id BIGINT UNIQUE, major_id BIGINT, enrollment_year INT, status VARCHAR(16))");
            statement.execute("CREATE TABLE courses (id BIGINT PRIMARY KEY, course_code VARCHAR(7), course_name VARCHAR(120), credits DECIMAL(3,1), enabled BOOLEAN)");
            statement.execute("CREATE TABLE academic_terms (id BIGINT PRIMARY KEY, term_name VARCHAR(100))");
            statement.execute("""
                    CREATE TABLE course_sections (
                        id BIGINT PRIMARY KEY, term_id BIGINT, course_id BIGINT,
                        section_code VARCHAR(24), teacher_user_id BIGINT, capacity INT,
                        enrolled_count INT, status VARCHAR(16), grades_published BOOLEAN)
                    """);
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
            statement.execute("""
                    CREATE TABLE course_enrollments (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY, section_id BIGINT,
                        student_id BIGINT, status VARCHAR(16), UNIQUE(section_id, student_id))
                    """);
            statement.execute("""
                    CREATE TABLE curriculum_plans (
                        id BIGINT PRIMARY KEY, major_id BIGINT, enrollment_year_start INT,
                        enrollment_year_end INT, status VARCHAR(16))
                    """);
            statement.execute("""
                    CREATE TABLE curriculum_plan_courses (
                        plan_id BIGINT, course_id BIGINT, requirement_type VARCHAR(16),
                        recommended_term_number INT, UNIQUE(plan_id, course_id))
                    """);
            statement.execute("""
                    CREATE TABLE course_section_targets (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY, section_id BIGINT, major_id BIGINT,
                        enrollment_year_start INT, enrollment_year_end INT,
                        UNIQUE(section_id, major_id, enrollment_year_start, enrollment_year_end))
                    """);
            statement.executeUpdate("INSERT INTO users VALUES (1, '教务管理员'), (20, '王老师')");
            statement.executeUpdate("INSERT INTO majors VALUES (10, '计算机科学'), (20, '艺术设计')");
            statement.executeUpdate("INSERT INTO student_profiles VALUES (601, 501, 10, 2025, 'ENROLLED'), (602, 502, 10, 2027, 'ENROLLED')");
            statement.executeUpdate("INSERT INTO courses VALUES (100, 'C000100', '程序设计', 3.0, TRUE)");
            statement.executeUpdate("INSERT INTO academic_terms VALUES (1, '2026春')");
            statement.executeUpdate("INSERT INTO curriculum_plans VALUES (300, 10, 2024, 2028, 'PUBLISHED')");
            statement.executeUpdate("INSERT INTO curriculum_plan_courses VALUES (300, 100, 'REQUIRED', 1)");
            statement.executeUpdate("INSERT INTO course_sections VALUES (700, 1, 100, 'CS-01', 20, 30, 0, 'OPEN', FALSE)");
            statement.executeUpdate("INSERT INTO course_section_schedule_revisions VALUES (900, 700, 1, 'PUBLISHED', 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)");
            statement.executeUpdate("INSERT INTO class_schedules(section_id, revision_id, day_of_week, start_period, end_period, start_week, end_week, classroom) VALUES (700, 900, 1, 1, 2, 1, 16, '教一-101')");
        }
        return connections;
    }
}
