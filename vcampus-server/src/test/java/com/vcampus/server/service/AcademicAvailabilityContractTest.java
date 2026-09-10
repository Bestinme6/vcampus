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
import com.vcampus.server.database.ScheduleRevisionRepository;
import com.vcampus.server.database.ScheduleRevisionRepositoryTest;
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
    private ConnectionFactory connections;

    @BeforeEach
    void setUp() throws Exception {
        connections = AcademicSectionTargetTest.newDatabase();
        SessionManager sessions = new SessionManager();
        studentToken = sessions.create(account(
                501, "student", false, Set.of(UserRole.STUDENT))).token();
        managerToken = sessions.create(account(
                1, "manager", false,
                Set.of(UserRole.TEACHER, UserRole.ACADEMIC_ADMIN))).token();
        ScheduleRevisionRepositoryTest.NoopNotifications notifications =
                new ScheduleRevisionRepositoryTest.NoopNotifications();
        service = new AcademicService(new AcademicRepository(connections, notifications),
                new CurriculumRepository(connections),
                new ScheduleRevisionRepository(connections, notifications), sessions);
    }

    @Test
    void occupancyPaginatesUnderBinaryProtocolLimitAndIgnoresDraftRevisions() throws Exception {
        try (var connection = connections.openConnection(); var statement = connection.createStatement()) {
            for (int i = 0; i < 130; i++) statement.executeUpdate("INSERT INTO class_schedules(section_id, revision_id, day_of_week, start_period, end_period, start_week, end_week, classroom) VALUES (700, 900, 2, 1, 2, 1, 16, 'A101')");
            statement.executeUpdate("INSERT INTO course_section_schedule_revisions VALUES (901, 700, 2, 'DRAFT', 1, CURRENT_TIMESTAMP, NULL, NULL)");
            statement.executeUpdate("INSERT INTO class_schedules(section_id, revision_id, day_of_week, start_period, end_period, start_week, end_week, classroom) VALUES (700, 901, 3, 1, 2, 1, 16, 'DRAFT-ROOM')");
        }
        var first = service.scheduleOccupancy(request(Actions.ACADEMIC_SCHEDULE_OCCUPANCY, managerToken,
                Map.of("termId", "1", "page", "1")));
        var second = service.scheduleOccupancy(request(Actions.ACADEMIC_SCHEDULE_OCCUPANCY, managerToken,
                Map.of("termId", "1", "page", "2")));
        assertEquals("100", first.data().get("count"));
        assertEquals("true", first.data().get("hasMore"));
        assertEquals("31", second.data().get("count"));
        assertEquals("false", second.data().get("hasMore"));
        var bytes = new java.io.ByteArrayOutputStream();
        com.vcampus.common.protocol.MessageCodec.writeResponse(new java.io.DataOutputStream(bytes), first);
        assertEquals(first, com.vcampus.common.protocol.MessageCodec.readResponse(
                new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))));
    }

    @Test
    void schedulingOccupancyIsManagerOnlyAndReturnsPublishedSlots() {
        ResponseMessage denied = service.scheduleOccupancy(request("academic.schedule.occupancy", studentToken,
                Map.of("termId", "1")));
        assertFalse(denied.success());
        ResponseMessage result = service.scheduleOccupancy(request("academic.schedule.occupancy", managerToken,
                Map.of("termId", "1")));
        assertTrue(result.success());
        assertEquals("1", result.data().get("count"));
        var row = RowCodec.decode(result.data().get("row.0"));
        assertEquals(9, row.size());
        assertEquals("700", row.get(0));
        assertEquals("20", row.get(1));
        assertEquals("教一-101", row.get(8));
        assertEquals("0", service.scheduleOccupancy(request("academic.schedule.occupancy", managerToken,
                Map.of("termId", "2"))).data().get("count"));
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

    @Test
    void onlyManagersCanDeleteAnUnopenedSectionAndItsDraftData() throws Exception {
        try (var connection = connections.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO course_sections VALUES (702, 1, 100, 'CS-03', 20, 30, 0, 'PLANNED', FALSE)");
            statement.executeUpdate("INSERT INTO course_section_targets(section_id, major_id, enrollment_year_start, enrollment_year_end) VALUES (702, 10, 2025, 2026)");
            statement.executeUpdate("INSERT INTO course_section_schedule_revisions VALUES (902, 702, 1, 'DRAFT', 1, CURRENT_TIMESTAMP, NULL, NULL)");
            statement.executeUpdate("INSERT INTO class_schedules(section_id, revision_id, day_of_week, start_period, end_period, start_week, end_week, classroom) VALUES (702, 902, 3, 3, 4, 1, 16, '草稿教室')");
        }

        ResponseMessage denied = service.deleteSection(request(
                Actions.ACADEMIC_SECTION_DELETE, studentToken, Map.of("sectionId", "702")));
        ResponseMessage deleted = service.deleteSection(request(
                Actions.ACADEMIC_SECTION_DELETE, managerToken, Map.of("sectionId", "702")));

        assertFalse(denied.success());
        assertTrue(deleted.success());
        try (var connection = connections.openConnection(); var statement = connection.createStatement()) {
            for (String table : java.util.List.of("class_schedules", "course_section_schedule_revisions",
                    "course_section_targets", "course_sections")) {
                try (var result = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE "
                        + (table.equals("course_sections") ? "id" : "section_id") + " = 702")) {
                    assertTrue(result.next());
                    assertEquals(0, result.getInt(1), table);
                }
            }
        }
    }

    @Test
    void deletionRejectsSectionsWithPublishedHistory() {
        ResponseMessage rejected = service.deleteSection(request(
                Actions.ACADEMIC_SECTION_DELETE, managerToken, Map.of("sectionId", "700")));

        assertFalse(rejected.success());
        assertTrue(rejected.message().contains("发布"));
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
