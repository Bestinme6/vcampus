package com.vcampus.client.fx.academic;

import com.vcampus.common.model.AcademicTermStatus;
import com.vcampus.common.model.CourseRequirementType;
import com.vcampus.common.model.CourseSectionStatus;
import com.vcampus.common.model.CurriculumPlanStatus;
import com.vcampus.common.model.EnrollmentStatus;
import com.vcampus.common.model.ScheduleRevisionStatus;
import com.vcampus.common.model.ScheduleSlot;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable academic response models and strict protocol decoders. */
public final class AcademicData {
    private static final int SCHEMA_VERSION = 2;
    private static final int MAX_ROWS = 10_000;

    private AcademicData() {
    }

    public record Term(long id, String name, AcademicTermStatus status,
                       LocalDate startDate, LocalDate endDate) {
        public Term {
            positiveId(id, "学期数据无效");
            name = text(name, "学期数据无效");
            status = Objects.requireNonNull(status, "status");
            if ((startDate == null) != (endDate == null)
                    || startDate != null && endDate.isBefore(startDate)) {
                throw new IllegalArgumentException("学期日期数据无效");
            }
        }
    }

    public record CourseReference(long id, String code, String name, BigDecimal credits) {
        public CourseReference {
            positiveId(id, "课程引用数据无效");
            code = text(code, "课程引用数据无效");
            name = text(name, "课程引用数据无效");
            credits = positiveDecimal(credits, "课程引用数据无效");
        }
    }

    public record TeacherReference(long userId, String username, String displayName) {
        public TeacherReference {
            positiveId(userId, "教师引用数据无效");
            username = text(username, "教师引用数据无效");
            displayName = text(displayName, "教师引用数据无效");
        }
    }

    public record MajorReference(long id, long departmentId, String code, String name) {
        public MajorReference {
            positiveId(id, "专业引用数据无效");
            positiveId(departmentId, "专业引用数据无效");
            code = text(code, "专业引用数据无效");
            name = text(name, "专业引用数据无效");
        }
    }

    public record ReferenceData(List<Term> terms, List<CourseReference> courses,
                                List<TeacherReference> teachers, List<MajorReference> majors) {
        public ReferenceData {
            terms = List.copyOf(terms);
            courses = List.copyOf(courses);
            teachers = List.copyOf(teachers);
            majors = List.copyOf(majors);
        }

        public ReferenceData(List<Term> terms, List<CourseReference> courses,
                             List<TeacherReference> teachers) {
            this(terms, courses, teachers, List.of());
        }
    }

    public record Course(long id, String code, String name, BigDecimal credits,
                         int totalHours, String description, boolean enabled) {
        public Course {
            positiveId(id, "课程数据无效");
            code = text(code, "课程数据无效");
            name = text(name, "课程数据无效");
            credits = positiveDecimal(credits, "课程数据无效");
            if (totalHours < 1) throw new IllegalArgumentException("课程数据无效");
            description = Objects.requireNonNullElse(description, "");
        }
    }

    public record CoursePage(List<Course> rows, int page, int pageSize, int total) {
        public CoursePage {
            rows = List.copyOf(rows);
            AcademicData.page(page, pageSize, total, rows.size());
        }
    }

    public record CurriculumSummary(long id, long majorId, String majorName, String planName,
                                    int versionNo, int yearFrom, int yearTo,
                                    CurriculumPlanStatus status, int courseCount) {
        public CurriculumSummary {
            positiveId(id, "培养方案数据无效");
            positiveId(majorId, "培养方案数据无效");
            majorName = text(majorName, "培养方案数据无效");
            planName = text(planName, "培养方案数据无效");
            if (versionNo < 1 || yearFrom < 1900 || yearTo < yearFrom || courseCount < 0) {
                throw new IllegalArgumentException("培养方案数据无效");
            }
            status = Objects.requireNonNull(status, "status");
        }
    }

    public record CurriculumCourse(long courseId, String courseCode, String courseName,
                                   BigDecimal credits, CourseRequirementType requirementType,
                                   int recommendedTermNumber) {
        public CurriculumCourse {
            positiveId(courseId, "培养方案课程数据无效");
            courseCode = text(courseCode, "培养方案课程数据无效");
            courseName = text(courseName, "培养方案课程数据无效");
            credits = positiveDecimal(credits, "培养方案课程数据无效");
            requirementType = Objects.requireNonNull(requirementType, "requirementType");
            if (recommendedTermNumber < 1 || recommendedTermNumber > 12) {
                throw new IllegalArgumentException("培养方案课程数据无效");
            }
        }
    }

    public record CurriculumPage(List<CurriculumSummary> rows, int page, int pageSize, int total) {
        public CurriculumPage {
            rows = List.copyOf(rows);
            AcademicData.page(page, pageSize, total, rows.size());
        }
    }

    public record CurriculumDetail(long id, long majorId, String majorName, String planName,
                                   int versionNo, int yearFrom, int yearTo,
                                   CurriculumPlanStatus status, Instant createdAt,
                                   String publishedBy, Instant publishedAt,
                                   List<CurriculumCourse> courses) {
        public CurriculumDetail {
            CurriculumSummary summary = new CurriculumSummary(id, majorId, majorName, planName,
                    versionNo, yearFrom, yearTo, status, courses.size());
            id = summary.id();
            majorName = summary.majorName();
            planName = summary.planName();
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            publishedBy = Objects.requireNonNullElse(publishedBy, "");
            courses = List.copyOf(courses);
            if (status == CurriculumPlanStatus.PUBLISHED && publishedAt == null) {
                throw new IllegalArgumentException("培养方案发布时间无效");
            }
        }
    }

    public record TeachingSection(long id, long termId, String termName, long courseId,
                                  String courseCode, String courseName, BigDecimal credits,
                                  String sectionCode, long teacherUserId, String teacherName,
                                  int capacity, int enrolledCount, CourseSectionStatus status,
                                  boolean gradesPublished, String scheduleSummary,
                                  String classroomSummary, Long ownEnrollmentId,
                                  EnrollmentStatus ownEnrollmentStatus) {
        public TeachingSection {
            positiveId(id, "教学班数据无效");
            positiveId(termId, "教学班数据无效");
            positiveId(courseId, "教学班数据无效");
            positiveId(teacherUserId, "教学班数据无效");
            termName = text(termName, "教学班数据无效");
            courseCode = text(courseCode, "教学班数据无效");
            courseName = text(courseName, "教学班数据无效");
            sectionCode = text(sectionCode, "教学班数据无效");
            teacherName = text(teacherName, "教学班数据无效");
            credits = positiveDecimal(credits, "教学班数据无效");
            AcademicData.capacity(capacity, enrolledCount);
            status = Objects.requireNonNull(status, "status");
            scheduleSummary = Objects.requireNonNullElse(scheduleSummary, "");
            classroomSummary = Objects.requireNonNullElse(classroomSummary, "");
            if ((ownEnrollmentId == null) != (ownEnrollmentStatus == null)
                    || ownEnrollmentId != null && ownEnrollmentId < 1) {
                throw new IllegalArgumentException("选课状态数据无效");
            }
        }
    }

    public record SectionPage(List<TeachingSection> rows, int page, int pageSize, int total) {
        public SectionPage {
            rows = List.copyOf(rows);
            AcademicData.page(page, pageSize, total, rows.size());
        }
    }

    public record SectionTarget(long majorId, int yearFrom, int yearTo) {
        public SectionTarget {
            positiveId(majorId, "教学班目标范围无效");
            if (yearFrom < 1900 || yearTo < yearFrom || yearTo > 2200) {
                throw new IllegalArgumentException("教学班目标范围无效");
            }
        }
    }

    public record AvailableSection(long sectionId, long termId, String termName,
                                   String sectionCode, long teacherUserId, String teacherName,
                                   int capacity, int enrolledCount, CourseSectionStatus status,
                                   boolean gradesPublished, String scheduleSummary,
                                   String classroomSummary, Long ownEnrollmentId,
                                   EnrollmentStatus ownEnrollmentStatus, boolean full,
                                   boolean scheduleConflict) {
        public AvailableSection {
            positiveId(sectionId, "可选教学班数据无效");
            positiveId(termId, "可选教学班数据无效");
            positiveId(teacherUserId, "可选教学班数据无效");
            termName = text(termName, "可选教学班数据无效");
            sectionCode = text(sectionCode, "可选教学班数据无效");
            teacherName = text(teacherName, "可选教学班数据无效");
            AcademicData.capacity(capacity, enrolledCount);
            status = Objects.requireNonNull(status, "status");
            scheduleSummary = Objects.requireNonNullElse(scheduleSummary, "");
            classroomSummary = Objects.requireNonNullElse(classroomSummary, "");
            if (full != (enrolledCount >= capacity)
                    || (ownEnrollmentId == null) != (ownEnrollmentStatus == null)
                    || ownEnrollmentId != null && ownEnrollmentId < 1) {
                throw new IllegalArgumentException("可选教学班数据无效");
            }
        }
    }

    public record AvailableCourse(long courseId, String courseCode, String courseName,
                                  BigDecimal credits, CourseRequirementType requirementType,
                                  int recommendedTermNumber, List<AvailableSection> sections) {
        public AvailableCourse {
            positiveId(courseId, "可选课程数据无效");
            courseCode = text(courseCode, "可选课程数据无效");
            courseName = text(courseName, "可选课程数据无效");
            credits = positiveDecimal(credits, "可选课程数据无效");
            requirementType = Objects.requireNonNull(requirementType, "requirementType");
            if (recommendedTermNumber < 1 || recommendedTermNumber > 12) {
                throw new IllegalArgumentException("可选课程数据无效");
            }
            sections = List.copyOf(sections);
        }
    }

    public record EnrollmentCatalog(List<AvailableCourse> courses) {
        public EnrollmentCatalog {
            courses = List.copyOf(courses);
        }
    }

    public record ScheduleDraft(long scheduleRevisionId, long sectionId, int revisionNo,
                                ScheduleRevisionStatus status, List<ScheduleSlot> slots) {
        public ScheduleDraft {
            positiveId(scheduleRevisionId, "课表草稿数据无效");
            positiveId(sectionId, "课表草稿数据无效");
            if (revisionNo < 1 || status != ScheduleRevisionStatus.DRAFT) {
                throw new IllegalArgumentException("课表草稿数据无效");
            }
            slots = List.copyOf(slots);
        }
    }

    public enum ScheduleConflictKind {
        TEACHER("教师"), CLASSROOM("教室");

        private final String displayName;

        ScheduleConflictKind(String displayName) { this.displayName = displayName; }

        public String displayName() { return displayName; }
    }

    public record ScheduleConflict(ScheduleConflictKind kind, long relatedSectionId,
                                   String displayName, int dayOfWeek, int startPeriod,
                                   int endPeriod, int startWeek, int endWeek) {
        public ScheduleConflict {
            kind = Objects.requireNonNull(kind, "kind");
            positiveId(relatedSectionId, "排课冲突数据无效");
            displayName = text(displayName, "排课冲突数据无效");
            new ScheduleSlot(dayOfWeek, startPeriod, endPeriod, startWeek, endWeek, "冲突占用");
        }
    }

    public record SchedulePublishResult(boolean success, String message,
                                        List<ScheduleConflict> conflicts) {
        public SchedulePublishResult {
            message = Objects.requireNonNullElse(message, success ? "课表已发布" : "课表发布失败");
            conflicts = List.copyOf(conflicts);
            if (success && !conflicts.isEmpty()) throw new IllegalArgumentException("排课发布结果无效");
        }
    }

    public record ScheduleEntry(long sectionId, long termId, String termName,
                                String courseCode, String courseName, BigDecimal credits,
                                String sectionCode,
                                String teacherName, ScheduleSlot slot) {
        public ScheduleEntry {
            positiveId(sectionId, "课表数据无效");
            positiveId(termId, "课表数据无效");
            termName = text(termName, "课表数据无效");
            courseCode = text(courseCode, "课表数据无效");
            courseName = text(courseName, "课表数据无效");
            credits = positiveDecimal(credits, "课表数据无效");
            sectionCode = text(sectionCode, "课表数据无效");
            teacherName = text(teacherName, "课表数据无效");
            slot = Objects.requireNonNull(slot, "slot");
        }
    }

    public record RosterRow(long enrollmentId, long studentId, String studentNumber,
                            String fullName, EnrollmentStatus status, BigDecimal score,
                            BigDecimal gradePoint, String comment) {
        public RosterRow {
            positiveId(enrollmentId, "名单数据无效");
            positiveId(studentId, "名单数据无效");
            studentNumber = text(studentNumber, "名单数据无效");
            fullName = text(fullName, "名单数据无效");
            status = Objects.requireNonNull(status, "status");
            comment = Objects.requireNonNullElse(comment, "");
        }
    }

    public record Roster(List<RosterRow> rows) {
        public Roster {
            rows = List.copyOf(rows);
        }
    }

    public record GradeRow(String termName, String courseCode, String courseName,
                           BigDecimal credits, String teacherName, BigDecimal score,
                           BigDecimal gradePoint) {
        public GradeRow {
            termName = text(termName, "成绩数据无效");
            courseCode = text(courseCode, "成绩数据无效");
            courseName = text(courseName, "成绩数据无效");
            credits = positiveDecimal(credits, "成绩数据无效");
            teacherName = text(teacherName, "成绩数据无效");
            score = nonNegativeDecimal(score, "成绩数据无效");
            gradePoint = nonNegativeDecimal(gradePoint, "成绩数据无效");
        }
    }

    public static ReferenceData referenceData(ResponseMessage response) throws IOException {
        Map<String, String> data = data(response);
        List<Term> terms = new ArrayList<>();
        for (int index = 0, count = count(data, "term.count"); index < count; index++) {
            String key = "term." + index;
            List<String> row = row(data, key, 3, "学期数据无效");
            String start = data.get(key + ".startDate");
            String end = data.get(key + ".endDate");
            terms.add(new Term(id(row.get(0)), row.get(1), enumeration(row.get(2), AcademicTermStatus.class),
                    optionalDate(start), optionalDate(end)));
        }
        List<CourseReference> courses = new ArrayList<>();
        for (int index = 0, count = count(data, "course.count"); index < count; index++) {
            List<String> row = row(data, "course." + index, 4, "课程引用数据无效");
            courses.add(new CourseReference(id(row.get(0)), row.get(1), row.get(2), decimal(row.get(3))));
        }
        List<TeacherReference> teachers = new ArrayList<>();
        for (int index = 0, count = count(data, "teacher.count"); index < count; index++) {
            List<String> row = row(data, "teacher." + index, 3, "教师引用数据无效");
            teachers.add(new TeacherReference(id(row.get(0)), row.get(1), row.get(2)));
        }
        List<MajorReference> majors = new ArrayList<>();
        for (int index = 0, count = optionalCount(data, "major.count"); index < count; index++) {
            List<String> row = row(data, "major." + index, 4, "专业引用数据无效");
            majors.add(new MajorReference(id(row.get(0)), id(row.get(1)), row.get(2), row.get(3)));
        }
        return new ReferenceData(terms, courses, teachers, majors);
    }

    public static CoursePage coursePage(ResponseMessage response) throws IOException {
        Map<String, String> data = data(response);
        int count = count(data, "count");
        List<Course> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<String> row = row(data, "row." + index, 7, "课程数据无效");
            rows.add(new Course(id(row.get(0)), row.get(1), row.get(2), decimal(row.get(3)),
                    positiveInt(row.get(4)), row.get(5), bool(row.get(6))));
        }
        return new CoursePage(rows, positive(data, "page"), positive(data, "pageSize"),
                nonNegative(data, "total"));
    }

    public static CurriculumPage curriculumPage(ResponseMessage response) throws IOException {
        Map<String, String> data = versioned(response, "schemaVersion");
        int count = count(data, "count");
        List<CurriculumSummary> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<String> row = row(data, "row." + index, 9, "培养方案数据无效");
            rows.add(curriculumSummary(row));
        }
        return new CurriculumPage(rows, positive(data, "page"), positive(data, "pageSize"),
                nonNegative(data, "total"));
    }

    public static CurriculumDetail curriculumDetail(ResponseMessage response) throws IOException {
        Map<String, String> data = versioned(response, "schemaVersion");
        List<String> plan = row(data, "plan", 11, "培养方案数据无效");
        int count = count(data, "course.count");
        List<CurriculumCourse> courses = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<String> row = row(data, "course." + index, 6, "培养方案课程数据无效");
            courses.add(curriculumCourse(row));
        }
        return new CurriculumDetail(id(plan.get(0)), id(plan.get(1)), plan.get(2), plan.get(3),
                positiveInt(plan.get(4)), integer(plan.get(5)), integer(plan.get(6)),
                enumeration(plan.get(7), CurriculumPlanStatus.class), instant(plan.get(8)),
                plan.get(9), optionalInstant(plan.get(10)), courses);
    }

    public static SectionPage sectionPage(ResponseMessage response) throws IOException {
        Map<String, String> data = data(response);
        int count = count(data, "count");
        List<TeachingSection> rows = teachingSections(data, count);
        return new SectionPage(rows, positive(data, "page"), positive(data, "pageSize"),
                nonNegative(data, "total"));
    }

    public static List<SectionTarget> sectionTargets(ResponseMessage response) throws IOException {
        Map<String, String> data = versioned(response, "schemaVersion");
        int count = count(data, "target.count");
        List<SectionTarget> targets = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<String> row = row(data, "target." + index, 3, "教学班目标范围无效");
            targets.add(new SectionTarget(id(row.get(0)), integer(row.get(1)), integer(row.get(2))));
        }
        return List.copyOf(targets);
    }

    public static ScheduleDraft scheduleDraft(ResponseMessage response) throws IOException {
        Map<String, String> data = versioned(response, "schemaVersion");
        int count = count(data, "slot.count");
        List<ScheduleSlot> slots = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            slots.add(slot(row(data, "slot." + index, 6, "课表时段数据无效")));
        }
        return new ScheduleDraft(id(required(data, "scheduleRevisionId")),
                id(required(data, "sectionId")), positive(data, "revisionNo"),
                enumeration(required(data, "status"), ScheduleRevisionStatus.class), slots);
    }

    public static SchedulePublishResult schedulePublishResult(ResponseMessage response) throws IOException {
        if (response == null) throw new IOException("服务器未返回数据");
        Map<String, String> data = response.data();
        List<ScheduleConflict> conflicts = new ArrayList<>();
        for (int index = 0, count = optionalCount(data, "conflict.count"); index < count; index++) {
            List<String> row = row(data, "conflict." + index, 8, "排课冲突数据无效");
            conflicts.add(new ScheduleConflict(enumeration(row.get(0), ScheduleConflictKind.class),
                    id(row.get(1)), row.get(2), integer(row.get(3)), integer(row.get(4)),
                    integer(row.get(5)), integer(row.get(6)), integer(row.get(7))));
        }
        return new SchedulePublishResult(response.success(), response.message(), conflicts);
    }

    public static EnrollmentCatalog enrollmentCatalog(ResponseMessage response) throws IOException {
        Map<String, String> data = versioned(response, "catalogSchemaVersion");
        int courseCount = count(data, "course.count");
        List<AvailableCourse> courses = new ArrayList<>();
        for (int courseIndex = 0; courseIndex < courseCount; courseIndex++) {
            String prefix = "course." + courseIndex;
            List<String> course = row(data, prefix, 6, "可选课程数据无效");
            int sectionCount = count(data, prefix + ".section.count");
            List<AvailableSection> sections = new ArrayList<>();
            for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                List<String> section = row(data, prefix + ".section." + sectionIndex,
                        16, "可选教学班数据无效");
                int capacity = integer(section.get(6));
                int enrolled = integer(section.get(7));
                sections.add(new AvailableSection(id(section.get(0)), id(section.get(1)),
                        section.get(2), section.get(3), id(section.get(4)), section.get(5),
                        capacity, enrolled, enumeration(section.get(8), CourseSectionStatus.class),
                        bool(section.get(9)), section.get(10), section.get(11),
                        optionalId(section.get(12)), optionalEnum(section.get(13), EnrollmentStatus.class),
                        bool(section.get(14)), bool(section.get(15))));
            }
            courses.add(new AvailableCourse(id(course.get(0)), course.get(1), course.get(2),
                    decimal(course.get(3)), enumeration(course.get(4), CourseRequirementType.class),
                    positiveInt(course.get(5)), sections));
        }
        return new EnrollmentCatalog(courses);
    }

    public static List<ScheduleEntry> scheduleEntries(ResponseMessage response) throws IOException {
        Map<String, String> data = data(response);
        int count = count(data, "count");
        List<ScheduleEntry> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<String> row = row(data, "row." + index, 14, "课表数据无效");
            rows.add(new ScheduleEntry(id(row.get(0)), id(row.get(1)), row.get(2), row.get(3),
                    row.get(4), decimal(row.get(5)), row.get(6), row.get(7),
                    slot(row.subList(8, 14))));
        }
        return List.copyOf(rows);
    }

    public static List<TeachingSection> teachingSections(ResponseMessage response) throws IOException {
        Map<String, String> data = data(response);
        return List.copyOf(teachingSections(data, count(data, "count")));
    }

    public static Roster roster(ResponseMessage response) throws IOException {
        Map<String, String> data = data(response);
        int count = count(data, "count");
        List<RosterRow> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<String> row = row(data, "row." + index, 8, "名单数据无效");
            rows.add(new RosterRow(id(row.get(0)), id(row.get(1)), row.get(2), row.get(3),
                    enumeration(row.get(4), EnrollmentStatus.class), optionalDecimal(row.get(5)),
                    optionalDecimal(row.get(6)), row.get(7)));
        }
        return new Roster(rows);
    }

    public static List<GradeRow> gradeRows(ResponseMessage response) throws IOException {
        Map<String, String> data = data(response);
        int count = count(data, "count");
        List<GradeRow> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<String> row = row(data, "row." + index, 7, "成绩数据无效");
            rows.add(new GradeRow(row.get(0), row.get(1), row.get(2), decimal(row.get(3)),
                    row.get(4), decimal(row.get(5)), decimal(row.get(6))));
        }
        return List.copyOf(rows);
    }

    public static void requireSuccess(ResponseMessage response) throws IOException {
        data(response);
    }

    static long responseId(ResponseMessage response, String key) throws IOException {
        return id(required(data(response), key));
    }

    private static List<TeachingSection> teachingSections(Map<String, String> data, int count) {
        List<TeachingSection> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<String> row = row(data, "row." + index, 18, "教学班数据无效");
            rows.add(new TeachingSection(id(row.get(0)), id(row.get(1)), row.get(2), id(row.get(3)),
                    row.get(4), row.get(5), decimal(row.get(6)), row.get(7), id(row.get(8)),
                    row.get(9), integer(row.get(10)), integer(row.get(11)),
                    enumeration(row.get(12), CourseSectionStatus.class), bool(row.get(13)),
                    row.get(14), row.get(15), optionalId(row.get(16)),
                    optionalEnum(row.get(17), EnrollmentStatus.class)));
        }
        return rows;
    }

    private static CurriculumSummary curriculumSummary(List<String> row) {
        return new CurriculumSummary(id(row.get(0)), id(row.get(1)), row.get(2), row.get(3),
                positiveInt(row.get(4)), integer(row.get(5)), integer(row.get(6)),
                enumeration(row.get(7), CurriculumPlanStatus.class), nonNegative(row.get(8)));
    }

    private static CurriculumCourse curriculumCourse(List<String> row) {
        return new CurriculumCourse(id(row.get(0)), row.get(1), row.get(2), decimal(row.get(3)),
                enumeration(row.get(4), CourseRequirementType.class), positiveInt(row.get(5)));
    }

    private static ScheduleSlot slot(List<String> row) {
        try {
            return new ScheduleSlot(integer(row.get(0)), integer(row.get(1)), integer(row.get(2)),
                    integer(row.get(3)), integer(row.get(4)), row.get(5));
        } catch (RuntimeException exception) {
            throw malformed("课表时段数据无效", exception);
        }
    }

    private static Map<String, String> versioned(ResponseMessage response, String key) throws IOException {
        Map<String, String> data = data(response);
        if (integer(required(data, key)) != SCHEMA_VERSION) {
            throw new IllegalArgumentException("不支持的教务数据版本");
        }
        return data;
    }

    private static Map<String, String> data(ResponseMessage response) throws IOException {
        if (response == null) throw new IOException("服务器未返回数据");
        if (!response.success()) throw new IOException(response.message());
        return response.data();
    }

    private static List<String> row(Map<String, String> data, String key, int size, String message) {
        try {
            List<String> fields = RowCodec.decode(required(data, key));
            if (fields.size() != size) throw new IllegalArgumentException(message);
            return fields;
        } catch (RuntimeException exception) {
            throw malformed(message, exception);
        }
    }

    private static int count(Map<String, String> data, String key) {
        int value = nonNegative(required(data, key));
        if (value > MAX_ROWS) throw new IllegalArgumentException("响应数据数量过大");
        return value;
    }

    private static int optionalCount(Map<String, String> data, String key) {
        return data.containsKey(key) ? count(data, key) : 0;
    }

    private static String required(Map<String, String> data, String key) {
        String value = data.get(key);
        if (value == null) throw new IllegalArgumentException("响应缺少字段：" + key);
        return value;
    }

    private static long id(String value) {
        try {
            long parsed = Long.parseLong(value);
            positiveId(parsed, "ID 数据无效");
            return parsed;
        } catch (RuntimeException exception) {
            throw malformed("ID 数据无效", exception);
        }
    }

    private static Long optionalId(String value) {
        return value == null || value.isEmpty() ? null : id(value);
    }

    private static int positive(Map<String, String> data, String key) {
        return positiveInt(required(data, key));
    }

    private static int positiveInt(String value) {
        int parsed = integer(value);
        if (parsed < 1) throw new IllegalArgumentException("正整数数据无效");
        return parsed;
    }

    private static int nonNegative(Map<String, String> data, String key) {
        return nonNegative(required(data, key));
    }

    private static int nonNegative(String value) {
        int parsed = integer(value);
        if (parsed < 0) throw new IllegalArgumentException("非负整数数据无效");
        return parsed;
    }

    private static int integer(String value) {
        try {
            return Integer.parseInt(value);
        } catch (RuntimeException exception) {
            throw malformed("整数数据无效", exception);
        }
    }

    private static BigDecimal decimal(String value) {
        try {
            return new BigDecimal(value);
        } catch (RuntimeException exception) {
            throw malformed("小数数据无效", exception);
        }
    }

    private static BigDecimal optionalDecimal(String value) {
        return value == null || value.isEmpty() ? null : decimal(value);
    }

    private static boolean bool(String value) {
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("布尔数据无效");
        }
        return Boolean.parseBoolean(value);
    }

    private static <E extends Enum<E>> E enumeration(String value, Class<E> type) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException exception) {
            throw malformed("枚举数据无效", exception);
        }
    }

    private static <E extends Enum<E>> E optionalEnum(String value, Class<E> type) {
        return value == null || value.isEmpty() ? null : enumeration(value, type);
    }

    private static LocalDate optionalDate(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            throw malformed("日期数据无效", exception);
        }
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw malformed("时间数据无效", exception);
        }
    }

    private static Instant optionalInstant(String value) {
        return value == null || value.isEmpty() ? null : instant(value);
    }

    private static String text(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value;
    }

    private static BigDecimal positiveDecimal(BigDecimal value, String message) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException(message);
        return value;
    }

    private static BigDecimal nonNegativeDecimal(BigDecimal value, String message) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException(message);
        return value;
    }

    private static void positiveId(long value, String message) {
        if (value < 1) throw new IllegalArgumentException(message);
    }

    private static void capacity(int capacity, int enrolledCount) {
        if (capacity < 1 || enrolledCount < 0 || enrolledCount > capacity) {
            throw new IllegalArgumentException("教学班容量数据无效");
        }
    }

    private static void page(int page, int pageSize, int total, int rowCount) {
        if (page < 1 || pageSize < 1 || total < 0 || rowCount > pageSize || rowCount > total) {
            throw new IllegalArgumentException("分页数据无效");
        }
    }

    private static IllegalArgumentException malformed(String message, RuntimeException cause) {
        return cause instanceof IllegalArgumentException && message.equals(cause.getMessage())
                ? (IllegalArgumentException) cause : new IllegalArgumentException(message, cause);
    }
}
