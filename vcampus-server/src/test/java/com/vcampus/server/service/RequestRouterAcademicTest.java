package com.vcampus.server.service;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.AcademicRepository;
import com.vcampus.server.database.AcademicSectionSwitchTest;
import com.vcampus.server.database.ConnectionFactory;
import com.vcampus.server.database.CurriculumRepository;
import com.vcampus.server.database.ScheduleRevisionRepository;
import com.vcampus.server.database.ScheduleRevisionRepositoryTest;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.support.RecordingAuditStore;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import com.vcampus.common.model.ClassPeriodTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestRouterAcademicTest {
    @Test
    void referenceDataPublishesTwelvePeriodsToEveryAcademicIdentity() throws Exception {
        ConnectionFactory connections = connections();
        try (var connection = connections.openConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE academic_terms(id BIGINT PRIMARY KEY,term_name VARCHAR(100),status VARCHAR(16),start_date DATE,end_date DATE)");
            statement.execute("INSERT INTO academic_terms VALUES(1,'第一学期','IN_PROGRESS','2026-09-01','2027-01-15')");
            statement.execute("CREATE TABLE courses(id BIGINT PRIMARY KEY,course_code VARCHAR(20),course_name VARCHAR(100),credits DECIMAL(3,1),enabled BOOLEAN)");
            statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64),display_name VARCHAR(100),enabled BOOLEAN)");
            statement.execute("CREATE TABLE roles(id BIGINT PRIMARY KEY,role_code VARCHAR(64))");
            statement.execute("CREATE TABLE user_roles(user_id BIGINT,role_id BIGINT)");
            statement.execute("CREATE TABLE majors(id BIGINT PRIMARY KEY,department_id BIGINT,major_code VARCHAR(20),major_name VARCHAR(100),enabled BOOLEAN)");
            statement.execute("CREATE TABLE class_period_times(period_number INT PRIMARY KEY,start_time TIME,end_time TIME)");
            for (int number = 1; number <= 12; number++) {
                statement.execute("INSERT INTO class_period_times(period_number) VALUES(" + number + ")");
            }
        }
        SessionManager sessions = new SessionManager();
        var notifications = new ScheduleRevisionRepositoryTest.NoopNotifications();
        AcademicService service = new AcademicService(new AcademicRepository(connections, notifications),
                new CurriculumRepository(connections), new ScheduleRevisionRepository(connections, notifications), sessions);

        for (UserRole role : List.of(UserRole.STUDENT, UserRole.TEACHER,
                UserRole.ACADEMIC_ADMIN, UserRole.SUPER_ADMIN)) {
            String token = sessions.create(account(role.ordinal() + 10, false, role)).token();
            ResponseMessage response = service.referenceData(RequestMessage.create(
                    Actions.ACADEMIC_REFERENCE_DATA, Map.of("sessionToken", token)));
            assertTrue(response.success(), role.name());
            assertEquals("12", response.data().get("period.count"));
            assertEquals(new ClassPeriodTime(12, null, null),
                    ClassPeriodTime.decode(response.data().get("period.11")));
        }
    }
    @Test
    void routesAtomicSectionSwitchForStudent() throws Exception {
        ConnectionFactory connections = AcademicSectionSwitchTest.database();
        SessionManager sessions = new SessionManager();
        String token = sessions.create(new UserAccount(
                501, "student", "hash", "salt", "学生", true, false,
                Set.of(UserRole.STUDENT))).token();
        ScheduleRevisionRepositoryTest.NoopNotifications notifications =
                new ScheduleRevisionRepositoryTest.NoopNotifications();
        RecordingAuditStore audit = new RecordingAuditStore();
        RequestRouter router = new RequestRouter(
                null, null, new AcademicService(
                        new AcademicRepository(connections, notifications),
                        new CurriculumRepository(connections),
                        new ScheduleRevisionRepository(connections, notifications), sessions, audit),
                null, null, null, null, null, sessions);

        ResponseMessage response = router.route(RequestMessage.create(
                Actions.ACADEMIC_ENROLLMENT_SWITCH_SECTION,
                Map.of("sessionToken", token, "fromSectionId", "700",
                        "toSectionId", "701")), "127.0.0.1");

        assertTrue(response.success());
        assertEquals("换班成功", response.message());
        assertEquals(new RecordingAuditStore.Entry(
                501L, Actions.ACADEMIC_ENROLLMENT_SWITCH_SECTION, "SUCCESS", null), audit.last());
    }

    @Test
    void routesCurriculumCreateToAcademicService() throws Exception {
        ConnectionFactory connections = connections();
        AcademicCurriculumServiceTest.createSchema(connections);
        CurriculumRepository curricula = new CurriculumRepository(connections);
        SessionManager sessions = new SessionManager();
        String token = sessions.create(account(1, false, UserRole.ACADEMIC_ADMIN)).token();
        ScheduleRevisionRepositoryTest.NoopNotifications notifications =
                new ScheduleRevisionRepositoryTest.NoopNotifications();
        AcademicService academic = new AcademicService(
                new AcademicRepository(connections, notifications), curricula,
                new ScheduleRevisionRepository(connections, notifications), sessions);
        RequestRouter router = new RequestRouter(
                null, null, academic, null, null, null, null, null, sessions);

        ResponseMessage response = router.route(RequestMessage.create(
                Actions.ACADEMIC_CURRICULUM_CREATE,
                Map.of("sessionToken", token, "majorId", "10", "planName", "2026版",
                        "versionNo", "1", "yearFrom", "2026", "yearTo", "2029")),
                "127.0.0.1");

        assertTrue(response.success());
        assertEquals("2026版", curricula.get(Long.parseLong(
                response.data().get("planId"))).planName());
    }

    @Test
    void forcedPasswordSessionCannotReachCurriculumActions() {
        ConnectionFactory connections = connections();
        SessionManager sessions = new SessionManager();
        String token = sessions.create(account(1, true, UserRole.ACADEMIC_ADMIN)).token();
        ScheduleRevisionRepositoryTest.NoopNotifications notifications =
                new ScheduleRevisionRepositoryTest.NoopNotifications();
        RequestRouter router = new RequestRouter(
                null, null, new AcademicService(
                        new AcademicRepository(connections, notifications),
                        new CurriculumRepository(connections),
                        new ScheduleRevisionRepository(connections, notifications), sessions),
                null, null, null, null, null, sessions);

        ResponseMessage response = router.route(RequestMessage.create(
                Actions.ACADEMIC_CURRICULUM_SEARCH,
                Map.of("sessionToken", token)), "127.0.0.1");

        assertFalse(response.success());
        assertEquals("请先修改初始密码", response.message());
    }

    private ConnectionFactory connections() {
        return new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:academic-router-" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
    }

    private UserAccount account(long id, boolean forced, UserRole role) {
        Set<UserRole> roles = role == UserRole.ACADEMIC_ADMIN
                ? Set.of(UserRole.TEACHER, role) : Set.of(role);
        return new UserAccount(id, "admin", "hash", "salt", "管理员",
                true, forced, roles);
    }
}
