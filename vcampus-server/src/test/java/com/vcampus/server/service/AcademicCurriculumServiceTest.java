package com.vcampus.server.service;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.AcademicRepository;
import com.vcampus.server.database.ConnectionFactory;
import com.vcampus.server.database.CurriculumRepository;
import com.vcampus.server.database.CurriculumRepository.CreateCurriculum;
import com.vcampus.server.database.ScheduleRevisionRepository;
import com.vcampus.server.database.ScheduleRevisionRepositoryTest;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.vcampus.common.model.CourseRequirementType.REQUIRED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcademicCurriculumServiceTest {
    private CurriculumRepository curricula;
    private AcademicService service;
    private String adminToken;
    private String studentToken;

    @BeforeEach
    void setUp() throws Exception {
        ConnectionFactory connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:academic-service-" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        createSchema(connections);
        curricula = new CurriculumRepository(connections);
        SessionManager sessions = new SessionManager();
        adminToken = session(sessions, 1, "admin", UserRole.ACADEMIC_ADMIN);
        studentToken = session(sessions, 2, "student", UserRole.STUDENT);
        ScheduleRevisionRepositoryTest.NoopNotifications notifications =
                new ScheduleRevisionRepositoryTest.NoopNotifications();
        service = new AcademicService(new AcademicRepository(connections, notifications), curricula,
                new ScheduleRevisionRepository(connections, notifications), sessions);
    }

    @Test
    void onlyManagersCanCreateCurricula() {
        ResponseMessage response = service.createCurriculum(request(
                Actions.ACADEMIC_CURRICULUM_CREATE, studentToken,
                Map.of("majorId", "10", "planName", "2026版", "versionNo", "1",
                        "yearFrom", "2026", "yearTo", "2029")));

        assertFalse(response.success());
        assertEquals("没有执行该操作的权限", response.message());
    }

    @Test
    void detailResponseCarriesStableVersionedRows() throws Exception {
        long planId = curricula.create(new CreateCurriculum(
                10, "计算机培养方案", 1, 2026, 2029), 1);
        curricula.addCourse(planId, 100, REQUIRED, 1, 1);

        ResponseMessage response = service.getCurriculum(request(
                Actions.ACADEMIC_CURRICULUM_GET, adminToken,
                Map.of("planId", Long.toString(planId))));

        assertTrue(response.success());
        assertEquals("2", response.data().get("schemaVersion"));
        assertEquals(11, RowCodec.decode(response.data().get("plan")).size());
        assertEquals("1", response.data().get("course.count"));
        assertEquals(6, RowCodec.decode(response.data().get("course.0")).size());
    }

    @Test
    void managerCanCreateSearchAndMaintainDraftCourses() throws Exception {
        ResponseMessage created = service.createCurriculum(request(
                Actions.ACADEMIC_CURRICULUM_CREATE, adminToken,
                Map.of("majorId", "10", "planName", "2026版", "versionNo", "1",
                        "yearFrom", "2026", "yearTo", "2029")));
        assertTrue(created.success());
        long planId = Long.parseLong(created.data().get("planId"));

        assertTrue(service.addCurriculumCourse(request(
                Actions.ACADEMIC_CURRICULUM_COURSE_ADD, adminToken,
                Map.of("planId", Long.toString(planId), "courseId", "100",
                        "requirementType", "REQUIRED", "recommendedTermNumber", "1"))).success());
        ResponseMessage searched = service.searchCurricula(request(
                Actions.ACADEMIC_CURRICULUM_SEARCH, adminToken,
                Map.of("majorId", "10", "status", "DRAFT", "keyword", "2026")));

        assertTrue(searched.success());
        assertEquals("2", searched.data().get("schemaVersion"));
        assertEquals("1", searched.data().get("count"));
        assertEquals(9, RowCodec.decode(searched.data().get("row.0")).size());
        assertEquals(REQUIRED, curricula.get(planId).courses().getFirst().requirementType());
    }

    private RequestMessage request(String action, String token, Map<String, String> values) {
        Map<String, String> parameters = new LinkedHashMap<>(values);
        parameters.put("sessionToken", token);
        return RequestMessage.create(action, parameters);
    }

    private String session(SessionManager sessions, long id, String username, UserRole role) {
        Set<UserRole> roles = role == UserRole.ACADEMIC_ADMIN
                ? Set.of(UserRole.TEACHER, role) : Set.of(role);
        return sessions.create(new UserAccount(
                id, username, "hash", "salt", username,
                true, false, roles)).token();
    }

    static void createSchema(ConnectionFactory connections) throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, display_name VARCHAR(100))");
            statement.execute("CREATE TABLE majors (id BIGINT PRIMARY KEY, major_name VARCHAR(100))");
            statement.execute("CREATE TABLE courses (id BIGINT PRIMARY KEY, course_code VARCHAR(7), course_name VARCHAR(120), credits DECIMAL(3,1), enabled BOOLEAN)");
            statement.execute("CREATE TABLE student_profiles (id BIGINT PRIMARY KEY, user_id BIGINT UNIQUE, major_id BIGINT, enrollment_year INT, status VARCHAR(16))");
            statement.execute("""
                    CREATE TABLE curriculum_plans (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY, major_id BIGINT NOT NULL,
                        plan_name VARCHAR(120) NOT NULL, version_no INT NOT NULL,
                        enrollment_year_start INT NOT NULL, enrollment_year_end INT NOT NULL,
                        status VARCHAR(16) NOT NULL, created_by_user_id BIGINT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        published_by_user_id BIGINT, published_at TIMESTAMP,
                        UNIQUE (major_id, version_no))
                    """);
            statement.execute("""
                    CREATE TABLE curriculum_plan_courses (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY, plan_id BIGINT NOT NULL,
                        course_id BIGINT NOT NULL, requirement_type VARCHAR(16) NOT NULL,
                        recommended_term_number INT NOT NULL, UNIQUE (plan_id, course_id))
                    """);
            statement.executeUpdate("INSERT INTO users VALUES (1, '教务管理员'), (2, '学生')");
            statement.executeUpdate("INSERT INTO majors VALUES (10, '计算机科学')");
            statement.executeUpdate("INSERT INTO courses VALUES (100, 'C000100', '程序设计', 3.0, TRUE)");
        }
    }
}
