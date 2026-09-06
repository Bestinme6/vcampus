package com.vcampus.server.database;

import com.vcampus.common.model.CurriculumPlanStatus;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.AcademicRepository.AcademicRuleException;
import com.vcampus.server.database.CurriculumRepository.CreateCurriculum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static com.vcampus.common.model.CourseRequirementType.ELECTIVE;
import static com.vcampus.common.model.CourseRequirementType.REQUIRED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CurriculumRepositoryTest {
    private ConnectionFactory connections;
    private CurriculumRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:curriculum-" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        createSchema();
        seedReferences();
        repository = new CurriculumRepository(connections);
    }

    @Test
    void sameCourseCanHaveDifferentRequirementByMajor() throws Exception {
        long computerScience = repository.create(plan(10, 2025, 2027), 1);
        long art = repository.create(plan(20, 2025, 2027), 1);
        repository.addCourse(computerScience, 100, REQUIRED, 2, 1);
        repository.addCourse(art, 100, ELECTIVE, 3, 1);
        repository.publish(computerScience, 1);
        repository.publish(art, 1);

        assertEquals(REQUIRED,
                repository.matchForStudent(501).courses().getFirst().requirementType());
        assertEquals(ELECTIVE,
                repository.matchForStudent(502).courses().getFirst().requirementType());
    }

    @Test
    void publishedRangesForOneMajorCannotOverlap() throws Exception {
        long first = repository.create(plan(10, 2024, 2026), 1);
        repository.addCourse(first, 100, REQUIRED, 1, 1);
        repository.publish(first, 1);
        long second = repository.create(
                new CreateCurriculum(10, "计算机方案二", 2, 2026, 2028), 1);
        repository.addCourse(second, 100, REQUIRED, 1, 1);

        AcademicRuleException failure = assertThrows(
                AcademicRuleException.class, () -> repository.publish(second, 1));

        assertEquals("同一专业的已发布培养方案适用年份不能重叠", failure.getMessage());
        assertEquals(CurriculumPlanStatus.DRAFT, repository.get(second).status());
    }

    @Test
    void publishedCourseRowsRejectMutation() throws Exception {
        long plan = repository.create(plan(10, 2024, 2026), 1);
        repository.addCourse(plan, 100, REQUIRED, 1, 1);
        repository.publish(plan, 1);

        assertThrows(IllegalStateException.class,
                () -> repository.updateCourse(plan, 100, ELECTIVE, 2, 1));
        assertThrows(IllegalStateException.class,
                () -> repository.removeCourse(plan, 100, 1));
        assertEquals(REQUIRED, repository.get(plan).courses().getFirst().requirementType());
    }

    @Test
    void copyCreatesEditableHigherVersionWithIndependentCourseRows() throws Exception {
        long source = repository.create(plan(10, 2024, 2026), 1);
        repository.addCourse(source, 100, REQUIRED, 2, 1);
        repository.publish(source, 1);

        long copy = repository.copy(source,
                new CreateCurriculum(10, "计算机方案续版", 0, 2027, 2029), 1);
        repository.updateCourse(copy, 100, ELECTIVE, 3, 1);

        assertEquals(2, repository.get(copy).versionNo());
        assertEquals(CurriculumPlanStatus.DRAFT, repository.get(copy).status());
        assertEquals(ELECTIVE, repository.get(copy).courses().getFirst().requirementType());
        assertEquals(REQUIRED, repository.get(source).courses().getFirst().requirementType());
    }

    @Test
    void archiveRejectsPlanStillMatchedByActiveStudents() throws Exception {
        long plan = repository.create(plan(10, 2025, 2027), 1);
        repository.addCourse(plan, 100, REQUIRED, 2, 1);
        repository.publish(plan, 1);

        AcademicRuleException failure = assertThrows(
                AcademicRuleException.class, () -> repository.archive(plan, 1));

        assertEquals("仍有在籍学生适用该培养方案，不能归档", failure.getMessage());
        assertEquals(CurriculumPlanStatus.PUBLISHED, repository.get(plan).status());
    }

    @Test
    void missingPublishedMatchReturnsExplicitRuleError() {
        AcademicRuleException failure = assertThrows(
                AcademicRuleException.class, () -> repository.matchForStudent(501));

        assertEquals("未配置适用培养方案", failure.getMessage());
    }

    @Test
    void searchReturnsStableMajorAndCourseCountMetadata() throws Exception {
        long plan = repository.create(plan(10, 2025, 2027), 1);
        repository.addCourse(plan, 100, REQUIRED, 2, 1);

        var page = repository.search(new CurriculumRepository.CurriculumQuery(
                10L, CurriculumPlanStatus.DRAFT, "计算机", 1, 20));

        assertEquals(1, page.total());
        assertEquals("计算机科学", page.rows().getFirst().majorName());
        assertEquals(1, page.rows().getFirst().courseCount());
    }

    private CreateCurriculum plan(long majorId, int from, int to) {
        return new CreateCurriculum(majorId, "计算机方案", 1, from, to);
    }

    private void createSchema() throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, display_name VARCHAR(100))");
            statement.execute("CREATE TABLE majors (id BIGINT PRIMARY KEY, major_name VARCHAR(100))");
            statement.execute("CREATE TABLE courses (id BIGINT PRIMARY KEY, course_code VARCHAR(7), course_name VARCHAR(120), credits DECIMAL(3,1), enabled BOOLEAN)");
            statement.execute("CREATE TABLE student_profiles (id BIGINT PRIMARY KEY, user_id BIGINT UNIQUE, major_id BIGINT, enrollment_year INT, status VARCHAR(16))");
            statement.execute("""
                    CREATE TABLE curriculum_plans (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        major_id BIGINT NOT NULL,
                        plan_name VARCHAR(120) NOT NULL,
                        version_no INT NOT NULL,
                        enrollment_year_start INT NOT NULL,
                        enrollment_year_end INT NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        created_by_user_id BIGINT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        published_by_user_id BIGINT,
                        published_at TIMESTAMP,
                        UNIQUE (major_id, version_no))
                    """);
            statement.execute("""
                    CREATE TABLE curriculum_plan_courses (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        plan_id BIGINT NOT NULL,
                        course_id BIGINT NOT NULL,
                        requirement_type VARCHAR(16) NOT NULL,
                        recommended_term_number INT NOT NULL,
                        UNIQUE (plan_id, course_id))
                    """);
        }
    }

    private void seedReferences() throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users VALUES (1, '教务管理员'), (501, '学生甲'), (502, '学生乙')");
            statement.executeUpdate("INSERT INTO majors VALUES (10, '计算机科学'), (20, '艺术设计')");
            statement.executeUpdate("INSERT INTO courses VALUES (100, 'C000100', '大学写作', 2.0, TRUE)");
            statement.executeUpdate("INSERT INTO student_profiles VALUES (601, 501, 10, 2025, 'ENROLLED'), (602, 502, 20, 2025, 'ENROLLED')");
        }
    }

}
