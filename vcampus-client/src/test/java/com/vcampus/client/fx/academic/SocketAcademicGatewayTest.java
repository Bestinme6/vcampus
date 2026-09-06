package com.vcampus.client.fx.academic;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.CourseSectionStatus;
import com.vcampus.common.model.CourseRequirementType;
import com.vcampus.common.model.ScheduleSlot;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SocketAcademicGatewayTest {
    @Test
    void mapsSectionDraftAndSessionTokenToSocketRequest() throws Exception {
        CapturingClient client = new CapturingClient(ResponseMessage.success(
                "request", "ok", Map.of("sectionId", "91")));
        AcademicGateway gateway = new SocketAcademicGateway(client, "session-1");

        long sectionId = gateway.createSection(new AcademicCommands.SectionDraft(
                2, 7, "CS-01", 41, 50, CourseSectionStatus.PLANNED,
                List.of(new ScheduleSlot(2, 3, 4, 1, 16, "教一-101")), false));

        assertEquals(91L, sectionId);
        assertEquals(Actions.ACADEMIC_SECTION_CREATE, client.request.action());
        assertEquals("session-1", client.request.parameters().get("sessionToken"));
        assertEquals("false", client.request.parameters().get("publishSchedule"));
        assertEquals("1", client.request.parameters().get("schedule.count"));
        assertEquals(List.of("2", "3", "4", "1", "16", "教一-101"),
                RowCodec.decode(client.request.parameters().get("schedule.0")));
    }

    @Test
    void mapsGradeAndAtomicSwitchCommands() throws Exception {
        CapturingClient client = new CapturingClient(
                ResponseMessage.success("request", "ok", Map.of()));
        AcademicGateway gateway = new SocketAcademicGateway(client, "session-1");

        gateway.saveGrade(9, 22,
                new AcademicCommands.GradeDraft(new BigDecimal("89.5"), "稳定", "期末成绩"));
        assertEquals(Actions.ACADEMIC_GRADE_SAVE, client.request.action());
        assertEquals("89.5", client.request.parameters().get("score"));

        gateway.switchSection(9, 10);
        assertEquals(Actions.ACADEMIC_ENROLLMENT_SWITCH_SECTION, client.request.action());
        assertEquals("9", client.request.parameters().get("fromSectionId"));
        assertEquals("10", client.request.parameters().get("toSectionId"));
    }

    @Test
    void mapsNewAndExistingCurriculumCoursesToDistinctActions() throws Exception {
        CapturingClient client = new CapturingClient(
                ResponseMessage.success("request", "ok", Map.of()));
        AcademicGateway gateway = new SocketAcademicGateway(client, "session-1");

        gateway.saveCurriculumCourse(5, new AcademicCommands.CurriculumCourseDraft(
                7, CourseRequirementType.REQUIRED, 3));
        assertEquals(Actions.ACADEMIC_CURRICULUM_COURSE_ADD, client.request.action());

        gateway.saveCurriculumCourse(5, new AcademicCommands.CurriculumCourseDraft(
                7, CourseRequirementType.ELECTIVE, 4, true));
        assertEquals(Actions.ACADEMIC_CURRICULUM_COURSE_UPDATE, client.request.action());
        assertEquals("ELECTIVE", client.request.parameters().get("requirementType"));
    }

    @Test
    void nullAndFailedResponsesBecomeIoFailures() {
        AcademicGateway nullGateway = new SocketAcademicGateway(new CapturingClient(null), "token");
        assertEquals("服务器未返回数据", assertThrows(IOException.class,
                () -> nullGateway.enroll(1)).getMessage());

        AcademicGateway failedGateway = new SocketAcademicGateway(new CapturingClient(
                ResponseMessage.failure("request", "选课人数已满")), "token");
        assertEquals("选课人数已满", assertThrows(IOException.class,
                () -> failedGateway.enroll(1)).getMessage());
    }

    private static final class CapturingClient extends VCampusClient {
        private final ResponseMessage response;
        private RequestMessage request;

        private CapturingClient(ResponseMessage response) {
            super("localhost", 1);
            this.response = response;
        }

        @Override
        public ResponseMessage send(RequestMessage request) {
            this.request = request;
            return response;
        }
    }
}
