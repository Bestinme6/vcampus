package com.vcampus.server.service;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.server.database.AcademicRepository;
import com.vcampus.server.database.AcademicSectionTargetTest;
import com.vcampus.server.database.CurriculumRepository;
import com.vcampus.server.database.ScheduleRevisionRepository;
import com.vcampus.server.database.ScheduleRevisionRepositoryTest;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.support.RecordingAuditStore;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AcademicAuditCoverageTest {
    @Test
    void everyCriticalAcademicActionIsAuditedOnceWithAuthenticatedActor() throws Exception {
        var connections = AcademicSectionTargetTest.newDatabase();
        var notifications = new ScheduleRevisionRepositoryTest.NoopNotifications();
        SessionManager sessions = new SessionManager();
        String studentToken = sessions.create(new UserAccount(
                501L, "student", "hash", "salt", "学生", true, false,
                Set.of(UserRole.STUDENT))).token();
        RecordingAuditStore audit = new RecordingAuditStore();
        AcademicService service = new AcademicService(
                new AcademicRepository(connections, notifications),
                new CurriculumRepository(connections),
                new ScheduleRevisionRepository(connections, notifications),
                sessions, audit);

        List<Call> calls = List.of(
                new Call(Actions.ACADEMIC_CURRICULUM_CREATE, service::createCurriculum),
                new Call(Actions.ACADEMIC_CURRICULUM_UPDATE, service::updateCurriculum),
                new Call(Actions.ACADEMIC_CURRICULUM_COPY, service::copyCurriculum),
                new Call(Actions.ACADEMIC_CURRICULUM_PUBLISH, service::publishCurriculum),
                new Call(Actions.ACADEMIC_CURRICULUM_ARCHIVE, service::archiveCurriculum),
                new Call(Actions.ACADEMIC_CURRICULUM_COURSE_ADD, service::addCurriculumCourse),
                new Call(Actions.ACADEMIC_CURRICULUM_COURSE_UPDATE, service::updateCurriculumCourse),
                new Call(Actions.ACADEMIC_CURRICULUM_COURSE_REMOVE, service::removeCurriculumCourse),
                new Call(Actions.ACADEMIC_COURSE_CREATE, service::createCourse),
                new Call(Actions.ACADEMIC_COURSE_UPDATE, service::updateCourse),
                new Call(Actions.ACADEMIC_SECTION_CREATE, service::createSection),
                new Call(Actions.ACADEMIC_SECTION_SET_STATUS, service::setSectionStatus),
                new Call(Actions.ACADEMIC_SECTION_DELETE, service::deleteSection),
                new Call(Actions.ACADEMIC_SECTION_TARGETS_SAVE, service::saveSectionTargets),
                new Call(Actions.ACADEMIC_SCHEDULE_DRAFT_SAVE, service::saveScheduleDraft),
                new Call(Actions.ACADEMIC_SCHEDULE_DRAFT_RESET, service::resetScheduleDraft),
                new Call(Actions.ACADEMIC_SCHEDULE_PUBLISH, service::publishSchedule),
                new Call(Actions.ACADEMIC_GRADE_SAVE, service::saveGrade),
                new Call(Actions.ACADEMIC_GRADE_PUBLISH, service::publishGrades),
                new Call(Actions.ACADEMIC_ENROLLMENT_ENROLL, service::enroll),
                new Call(Actions.ACADEMIC_ENROLLMENT_DROP, service::drop),
                new Call(Actions.ACADEMIC_ENROLLMENT_SWITCH_SECTION, service::switchSection));

        for (Call call : calls) {
            assertFalse(call.invoke().apply(request(call.action(), studentToken)).success(), call.action());
        }

        assertEquals(calls.stream().map(Call::action).toList(),
                audit.entries().stream().map(RecordingAuditStore.Entry::action).toList());
        assertEquals(Set.of(501L), audit.entries().stream()
                .map(RecordingAuditStore.Entry::userId).collect(java.util.stream.Collectors.toSet()));
    }

    private RequestMessage request(String action, String token) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("sessionToken", token);
        return RequestMessage.create(action, parameters);
    }

    private record Call(String action, Function<RequestMessage,
            com.vcampus.common.protocol.ResponseMessage> invoke) { }
}
