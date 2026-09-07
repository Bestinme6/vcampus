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
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        AcademicService service = new AcademicService(
                new AcademicRepository(connections, new ScheduleRevisionRepositoryTest.NoopNotifications()),
                new CurriculumRepository(connections),
                new ScheduleRevisionRepository(connections, new ScheduleRevisionRepositoryTest.NoopNotifications()),
                sessions);

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
    }
}
