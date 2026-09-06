package com.vcampus.server.service;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.AcademicRepository;
import com.vcampus.server.database.ConnectionFactory;
import com.vcampus.server.database.CurriculumRepository;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestRouterAcademicTest {
    @Test
    void routesCurriculumCreateToAcademicService() throws Exception {
        ConnectionFactory connections = connections();
        AcademicCurriculumServiceTest.createSchema(connections);
        CurriculumRepository curricula = new CurriculumRepository(connections);
        SessionManager sessions = new SessionManager();
        String token = sessions.create(account(1, false, UserRole.ACADEMIC_ADMIN)).token();
        AcademicService academic = new AcademicService(
                new AcademicRepository(connections, null), curricula, sessions);
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
        SessionManager sessions = new SessionManager();
        String token = sessions.create(account(1, true, UserRole.ACADEMIC_ADMIN)).token();
        RequestRouter router = new RequestRouter(
                null, null, new AcademicService(null, sessions),
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
