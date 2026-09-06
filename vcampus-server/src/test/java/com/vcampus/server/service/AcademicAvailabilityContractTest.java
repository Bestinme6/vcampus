package com.vcampus.server.service;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.AcademicRepository;
import com.vcampus.server.database.AcademicSectionTargetTest;
import com.vcampus.server.database.ConnectionFactory;
import com.vcampus.server.database.CurriculumRepository;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcademicAvailabilityContractTest {
    private AcademicService service;
    private String studentToken;
    private String managerToken;

    @BeforeEach
    void setUp() throws Exception {
        ConnectionFactory connections = AcademicSectionTargetTest.newDatabase();
        SessionManager sessions = new SessionManager();
        studentToken = sessions.create(account(
                501, "student", false, Set.of(UserRole.STUDENT))).token();
        managerToken = sessions.create(account(
                1, "manager", false,
                Set.of(UserRole.TEACHER, UserRole.ACADEMIC_ADMIN))).token();
        service = new AcademicService(new AcademicRepository(connections, null),
                new CurriculumRepository(connections), sessions);
    }

    @Test
    void responseKeepsLegacyRowsAndAddsCourseGroups() {
        ResponseMessage response = service.availableSections(request(
                Actions.ACADEMIC_ENROLLMENT_AVAILABLE, studentToken,
                Map.of("termId", "1")));

        assertTrue(response.success());
        assertNotNull(response.data().get("row.0"));
        assertEquals("2", response.data().get("catalogSchemaVersion"));
        assertEquals(6, RowCodec.decode(response.data().get("course.0")).size());
        assertNotNull(response.data().get("course.0.section.0"));
    }

    @Test
    void onlyAcademicManagersCanReplaceSectionTargets() {
        ResponseMessage rejected = service.saveSectionTargets(request(
                Actions.ACADEMIC_SECTION_TARGETS_SAVE, studentToken,
                Map.of("sectionId", "700", "target.count", "0")));
        ResponseMessage saved = service.saveSectionTargets(request(
                Actions.ACADEMIC_SECTION_TARGETS_SAVE, managerToken,
                Map.of("sectionId", "700", "target.count", "1",
                        "target.0", RowCodec.encode("10", "2025", "2026"))));
        ResponseMessage loaded = service.getSectionTargets(request(
                Actions.ACADEMIC_SECTION_TARGETS_GET, managerToken,
                Map.of("sectionId", "700")));

        assertFalse(rejected.success());
        assertEquals("没有执行该操作的权限", rejected.message());
        assertTrue(saved.success());
        assertEquals("1", loaded.data().get("target.count"));
        assertEquals(3, RowCodec.decode(loaded.data().get("target.0")).size());
    }

    private RequestMessage request(String action, String token, Map<String, String> values) {
        Map<String, String> parameters = new LinkedHashMap<>(values);
        parameters.put("sessionToken", token);
        return RequestMessage.create(action, parameters);
    }

    private UserAccount account(long id, String username, boolean forced, Set<UserRole> roles) {
        return new UserAccount(id, username, "hash", "salt", username,
                true, forced, roles);
    }
}
