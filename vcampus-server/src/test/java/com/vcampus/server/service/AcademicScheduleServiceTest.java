package com.vcampus.server.service;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.AcademicRepository;
import com.vcampus.server.database.CurriculumRepository;
import com.vcampus.server.database.ScheduleRevisionRepository;
import com.vcampus.server.database.ScheduleRevisionRepositoryTest;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.support.RecordingAuditStore;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcademicScheduleServiceTest {
    @Test
    void publishedSchedulePayloadIncludesCourseCredits() throws Exception {
        var connections = ScheduleRevisionRepositoryTest.database();
        SessionManager sessions = new SessionManager();
        String token = sessions.create(new UserAccount(
                501, "student", "hash", "salt", "学生", true, false,
                Set.of(UserRole.STUDENT))).token();
        AcademicService service = new AcademicService(
                new AcademicRepository(connections, new ScheduleRevisionRepositoryTest.NoopNotifications()),
                new CurriculumRepository(connections),
                new ScheduleRevisionRepository(connections, new ScheduleRevisionRepositoryTest.NoopNotifications()),
                sessions);

        var response = service.mySchedule(RequestMessage.create(
                Actions.ACADEMIC_SCHEDULE_MY,
                Map.of("sessionToken", token, "termId", "1")));
        var row = RowCodec.decode(response.data().get("row.0"));

        assertTrue(response.success());
        assertEquals(14, row.size());
        assertEquals("3.0", row.get(5));
        assertEquals("教一-101", row.get(13));
    }

    @Test
    void managerCanLoadSaveAndPublishScheduleDraft() throws Exception {
        var connections = ScheduleRevisionRepositoryTest.database();
        SessionManager sessions = new SessionManager();
        String token = sessions.create(new UserAccount(
                1, "manager", "hash", "salt", "管理员", true, false,
                Set.of(UserRole.TEACHER, UserRole.ACADEMIC_ADMIN))).token();
        RecordingAuditStore audit = new RecordingAuditStore();
        AcademicService service = new AcademicService(
                new AcademicRepository(connections, new ScheduleRevisionRepositoryTest.NoopNotifications()),
                new CurriculumRepository(connections),
                new ScheduleRevisionRepository(connections, new ScheduleRevisionRepositoryTest.NoopNotifications()),
                sessions, audit);

        var loaded = service.getScheduleDraft(RequestMessage.create(
                Actions.ACADEMIC_SCHEDULE_DRAFT_GET,
                Map.of("sessionToken", token, "sectionId", "700")));
        var saved = service.saveScheduleDraft(RequestMessage.create(
                Actions.ACADEMIC_SCHEDULE_DRAFT_SAVE,
                Map.of("sessionToken", token, "sectionId", "700",
                        "scheduleRevisionId", loaded.data().get("scheduleRevisionId"),
                        "expectedRevisionNo", loaded.data().get("revisionNo"),
                        "slot.count", "1",
                        "slot.0", RowCodec.encode("3", "5", "6", "1", "16", "教一-201"))));
        var published = service.publishSchedule(RequestMessage.create(
                Actions.ACADEMIC_SCHEDULE_PUBLISH,
                Map.of("sessionToken", token, "sectionId", "700",
                        "scheduleRevisionId", saved.data().get("scheduleRevisionId"),
                        "expectedRevisionNo", saved.data().get("revisionNo"))));

        assertTrue(loaded.success());
        assertTrue(saved.success());
        assertTrue(published.success());
        assertEquals("PUBLISHED", published.data().get("status"));
        assertEquals(List.of(Actions.ACADEMIC_SCHEDULE_DRAFT_SAVE, Actions.ACADEMIC_SCHEDULE_PUBLISH),
                audit.entries().stream().map(RecordingAuditStore.Entry::action).toList());
        assertTrue(audit.entries().stream().allMatch(entry -> "SUCCESS".equals(entry.result())));
    }

    @Test
    void onlyManagersCanResetDraftAndResetRestoresPublishedSlots() throws Exception {
        var connections = ScheduleRevisionRepositoryTest.database();
        SessionManager sessions = new SessionManager();
        String manager = sessions.create(new UserAccount(
                1, "manager", "hash", "salt", "管理员", true, false,
                Set.of(UserRole.TEACHER, UserRole.ACADEMIC_ADMIN))).token();
        String student = sessions.create(new UserAccount(
                501, "student", "hash", "salt", "学生", true, false,
                Set.of(UserRole.STUDENT))).token();
        AcademicService service = new AcademicService(
                new AcademicRepository(connections, new ScheduleRevisionRepositoryTest.NoopNotifications()),
                new CurriculumRepository(connections),
                new ScheduleRevisionRepository(connections, new ScheduleRevisionRepositoryTest.NoopNotifications()),
                sessions);
        var loaded = service.getScheduleDraft(RequestMessage.create(
                Actions.ACADEMIC_SCHEDULE_DRAFT_GET,
                Map.of("sessionToken", manager, "sectionId", "700")));
        service.saveScheduleDraft(RequestMessage.create(Actions.ACADEMIC_SCHEDULE_DRAFT_SAVE,
                Map.of("sessionToken", manager, "sectionId", "700",
                        "scheduleRevisionId", loaded.data().get("scheduleRevisionId"),
                        "expectedRevisionNo", loaded.data().get("revisionNo"), "slot.count", "0")));

        var denied = service.resetScheduleDraft(RequestMessage.create(
                Actions.ACADEMIC_SCHEDULE_DRAFT_RESET,
                Map.of("sessionToken", student, "sectionId", "700")));
        var reset = service.resetScheduleDraft(RequestMessage.create(
                Actions.ACADEMIC_SCHEDULE_DRAFT_RESET,
                Map.of("sessionToken", manager, "sectionId", "700")));
        var restored = service.getScheduleDraft(RequestMessage.create(
                Actions.ACADEMIC_SCHEDULE_DRAFT_GET,
                Map.of("sessionToken", manager, "sectionId", "700")));

        assertFalse(denied.success());
        assertTrue(reset.success());
        assertEquals("1", restored.data().get("slot.count"));
        assertEquals("教一-101", RowCodec.decode(restored.data().get("slot.0")).get(5));
    }
}
