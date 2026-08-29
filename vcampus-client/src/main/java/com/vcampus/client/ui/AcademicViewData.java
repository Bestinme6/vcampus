package com.vcampus.client.ui;

import com.vcampus.common.model.AcademicTermStatus;
import com.vcampus.common.model.CourseSectionStatus;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class AcademicViewData {
    private AcademicViewData() {
    }

    static ReferenceData references(ResponseMessage response) {
        Map<String, String> data = response.data();
        List<TermOption> terms = new ArrayList<>();
        for (int index = 0; index < integer(data, "term.count"); index++) {
            List<String> row = RowCodec.decode(data.get("term." + index));
            terms.add(new TermOption(
                    Long.parseLong(row.get(0)), row.get(1), AcademicTermStatus.valueOf(row.get(2))));
        }
        List<CourseOption> courses = new ArrayList<>();
        for (int index = 0; index < integer(data, "course.count"); index++) {
            List<String> row = RowCodec.decode(data.get("course." + index));
            courses.add(new CourseOption(
                    Long.parseLong(row.get(0)), row.get(1), row.get(2), new BigDecimal(row.get(3))));
        }
        List<TeacherOption> teachers = new ArrayList<>();
        for (int index = 0; index < integer(data, "teacher.count"); index++) {
            List<String> row = RowCodec.decode(data.get("teacher." + index));
            teachers.add(new TeacherOption(Long.parseLong(row.get(0)), row.get(1), row.get(2)));
        }
        return new ReferenceData(List.copyOf(terms), List.copyOf(courses), List.copyOf(teachers));
    }

    static List<SectionView> sections(ResponseMessage response) {
        int count = integer(response.data(), "count");
        List<SectionView> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<String> row = RowCodec.decode(response.data().get("row." + index));
            rows.add(new SectionView(
                    Long.parseLong(row.get(0)), Long.parseLong(row.get(1)), row.get(2),
                    Long.parseLong(row.get(3)), row.get(4), row.get(5), new BigDecimal(row.get(6)),
                    row.get(7), Long.parseLong(row.get(8)), row.get(9),
                    Integer.parseInt(row.get(10)), Integer.parseInt(row.get(11)),
                    CourseSectionStatus.valueOf(row.get(12)), Boolean.parseBoolean(row.get(13)),
                    row.get(14), row.get(15),
                    row.get(16).isBlank() ? null : Long.parseLong(row.get(16)), row.get(17)));
        }
        return List.copyOf(rows);
    }

    static List<ScheduleEntryView> schedules(ResponseMessage response) {
        int count = integer(response.data(), "count");
        List<ScheduleEntryView> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<String> row = RowCodec.decode(response.data().get("row." + index));
            rows.add(new ScheduleEntryView(
                    Long.parseLong(row.get(0)), Long.parseLong(row.get(1)), row.get(2),
                    row.get(3), row.get(4), row.get(5), row.get(6),
                    Integer.parseInt(row.get(7)), Integer.parseInt(row.get(8)),
                    Integer.parseInt(row.get(9)), Integer.parseInt(row.get(10)),
                    Integer.parseInt(row.get(11)), row.get(12)));
        }
        return List.copyOf(rows);
    }

    static List<RosterView> roster(ResponseMessage response) {
        int count = integer(response.data(), "count");
        List<RosterView> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<String> row = RowCodec.decode(response.data().get("row." + index));
            rows.add(new RosterView(
                    Long.parseLong(row.get(0)), Long.parseLong(row.get(1)), row.get(2), row.get(3), row.get(4),
                    row.get(5).isBlank() ? null : new BigDecimal(row.get(5)),
                    row.get(6).isBlank() ? null : new BigDecimal(row.get(6)), row.get(7)));
        }
        return List.copyOf(rows);
    }

    static List<GradeView> grades(ResponseMessage response) {
        int count = integer(response.data(), "count");
        List<GradeView> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<String> row = RowCodec.decode(response.data().get("row." + index));
            rows.add(new GradeView(
                    row.get(0), row.get(1), row.get(2), new BigDecimal(row.get(3)),
                    row.get(4), new BigDecimal(row.get(5)), new BigDecimal(row.get(6))));
        }
        return List.copyOf(rows);
    }

    private static int integer(Map<String, String> data, String key) {
        return Integer.parseInt(data.getOrDefault(key, "0"));
    }

    record ReferenceData(List<TermOption> terms, List<CourseOption> courses, List<TeacherOption> teachers) {
    }

    record TermOption(long id, String name, AcademicTermStatus status) {
        @Override
        public String toString() {
            return name + " · " + status.displayName();
        }
    }

    record CourseOption(long id, String code, String name, BigDecimal credits) {
        @Override
        public String toString() {
            return code + " · " + name;
        }
    }

    record TeacherOption(long userId, String username, String displayName) {
        @Override
        public String toString() {
            return username + " · " + displayName;
        }
    }

    record SectionView(
            long id, long termId, String termName, long courseId, String courseCode,
            String courseName, BigDecimal credits, String sectionCode, long teacherUserId,
            String teacherName, int capacity, int enrolledCount, CourseSectionStatus status,
            boolean gradesPublished, String scheduleSummary, String classroomSummary,
            Long ownEnrollmentId,
            String ownEnrollmentStatus) {

        String schedule() {
            return scheduleSummary;
        }
    }

    record ScheduleEntryView(
            long sectionId, long termId, String termName, String courseCode,
            String courseName, String sectionCode, String teacherName,
            int dayOfWeek, int startPeriod, int endPeriod,
            int startWeek, int endWeek, String classroom) {

        boolean overlapsWeeks(int fromWeek, int toWeek) {
            return startWeek <= toWeek && endWeek >= fromWeek;
        }
    }

    record RosterView(
            long enrollmentId, long studentId, String studentNumber, String fullName,
            String status, BigDecimal score, BigDecimal gradePoint, String comment) {
    }

    record GradeView(
            String termName, String courseCode, String courseName, BigDecimal credits,
            String teacherName, BigDecimal score, BigDecimal gradePoint) {
    }
}
