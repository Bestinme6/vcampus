package com.vcampus.client.fx.academic;

import com.vcampus.common.model.CourseRequirementType;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AcademicDataTest {
    @Test
    void groupsTeachingSectionsUnderTheirCourse() throws Exception {
        AcademicData.EnrollmentCatalog catalog = AcademicData.enrollmentCatalog(
                success(catalogData("20", "12")));

        AcademicData.AvailableCourse course = catalog.courses().getFirst();
        assertEquals(CourseRequirementType.REQUIRED, course.requirementType());
        assertEquals(2, course.sections().size());
        assertEquals("CS-01", course.sections().getFirst().sectionCode());
        assertThrows(UnsupportedOperationException.class,
                () -> course.sections().add(course.sections().getFirst()));
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.courses().clear());
    }

    @Test
    void rejectsUnknownSchemaAndMissingRows() {
        assertThrows(IllegalArgumentException.class, () -> AcademicData.enrollmentCatalog(
                success(Map.of("catalogSchemaVersion", "99", "course.count", "0"))));

        Map<String, String> missing = new LinkedHashMap<>();
        missing.put("catalogSchemaVersion", "2");
        missing.put("course.count", "1");
        assertThrows(IllegalArgumentException.class,
                () -> AcademicData.enrollmentCatalog(success(missing)));
    }

    @Test
    void rejectsNegativeCapacityAndEnrollmentAboveCapacity() {
        assertThrows(IllegalArgumentException.class,
                () -> AcademicData.enrollmentCatalog(success(catalogData("-1", "0"))));
        assertThrows(IllegalArgumentException.class,
                () -> AcademicData.enrollmentCatalog(success(catalogData("10", "11"))));
    }

    @Test
    void rejectsInvalidEnumValues() {
        Map<String, String> data = catalogData("20", "12");
        data.put("course.0", RowCodec.encode("7", "C000007", "数据结构", "4.0",
                "OPTIONAL", "3"));
        assertThrows(IllegalArgumentException.class,
                () -> AcademicData.enrollmentCatalog(success(data)));
    }

    @Test
    void decodesExactlySixFieldScheduleSlots() throws Exception {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("schemaVersion", "2");
        data.put("scheduleRevisionId", "31");
        data.put("sectionId", "9");
        data.put("revisionNo", "2");
        data.put("status", "DRAFT");
        data.put("slot.count", "1");
        data.put("slot.0", RowCodec.encode("2", "3", "4", "1", "16", "教一-101"));

        AcademicData.ScheduleDraft draft = AcademicData.scheduleDraft(success(data));

        assertEquals("教一-101", draft.slots().getFirst().classroom());
        assertThrows(UnsupportedOperationException.class, () -> draft.slots().clear());

        data.put("slot.0", RowCodec.encode("2", "3", "4", "1", "16"));
        assertThrows(IllegalArgumentException.class,
                () -> AcademicData.scheduleDraft(success(data)));
    }

    @Test
    void failedResponseUsesServerMessage() {
        IOException failure = assertThrows(IOException.class,
                () -> AcademicData.coursePage(ResponseMessage.failure("request", "无权访问")));
        assertEquals("无权访问", failure.getMessage());
    }

    @Test
    void decodesMajorReferencesWithoutBreakingLegacyReferencePayloads() throws Exception {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("term.count", "0");
        data.put("course.count", "0");
        data.put("teacher.count", "0");
        data.put("major.count", "1");
        data.put("major.0", RowCodec.encode("8", "3", "080901", "计算机科学与技术"));

        AcademicData.ReferenceData references = AcademicData.referenceData(success(data));

        assertEquals(1, references.majors().size());
        assertEquals(8L, references.majors().getFirst().id());
        assertEquals("计算机科学与技术", references.majors().getFirst().name());
        assertThrows(UnsupportedOperationException.class, () -> references.majors().clear());

        data.remove("major.count");
        data.remove("major.0");
        assertEquals(List.of(), AcademicData.referenceData(success(data)).majors());
    }

    @Test
    void decodesStructuredSchedulePublicationConflicts() throws Exception {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("conflict.count", "1");
        data.put("conflict.0", RowCodec.encode("CLASSROOM", "19", "教一-101",
                "2", "3", "4", "1", "16"));

        AcademicData.SchedulePublishResult result = AcademicData.schedulePublishResult(
                new ResponseMessage("request", false, "课表存在时间冲突", data));

        assertEquals(false, result.success());
        assertEquals(AcademicData.ScheduleConflictKind.CLASSROOM,
                result.conflicts().getFirst().kind());
        assertEquals("教一-101", result.conflicts().getFirst().displayName());
    }

    private static Map<String, String> catalogData(String capacity, String enrolled) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("catalogSchemaVersion", "2");
        data.put("course.count", "1");
        data.put("course.0", RowCodec.encode("7", "C000007", "数据结构", "4.0",
                "REQUIRED", "3"));
        data.put("course.0.section.count", "2");
        data.put("course.0.section.0", sectionRow("9", "CS-01", capacity, enrolled));
        data.put("course.0.section.1", sectionRow("10", "CS-02", "30", "7"));
        return data;
    }

    private static String sectionRow(String id, String code, String capacity, String enrolled) {
        return RowCodec.encode(id, "2", "2026 秋", code, "41", "张老师",
                capacity, enrolled, "OPEN", "false", "周二 3-4节", "教一-101",
                "", "", "false", "false");
    }

    private static ResponseMessage success(Map<String, String> data) {
        return ResponseMessage.success("request", "ok", data);
    }
}
