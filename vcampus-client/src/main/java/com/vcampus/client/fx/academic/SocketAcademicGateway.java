package com.vcampus.client.fx.academic;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.CurriculumPlanStatus;
import com.vcampus.common.model.ScheduleSlot;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Socket-backed synchronous adapter with no JavaFX or threading dependency. */
public final class SocketAcademicGateway implements AcademicGateway {
    private final VCampusClient client;
    private final String token;

    public SocketAcademicGateway(VCampusClient client, String token) {
        this.client = Objects.requireNonNull(client, "client");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("token");
        this.token = token;
    }

    @Override
    public AcademicData.ReferenceData references() throws IOException {
        return AcademicData.referenceData(client.academicReferenceData(token));
    }

    @Override
    public AcademicData.CoursePage courses(String keyword, int page) throws IOException {
        return AcademicData.coursePage(client.searchCourses(token, safe(keyword), page));
    }

    @Override
    public long createCourse(AcademicCommands.CourseDraft draft) throws IOException {
        return AcademicData.responseId(client.createCourse(token, courseValues(draft)), "courseId");
    }

    @Override
    public void updateCourse(long courseId, AcademicCommands.CourseDraft draft, boolean enabled)
            throws IOException {
        Map<String, String> values = courseValues(draft);
        values.put("courseId", Long.toString(positive(courseId, "courseId")));
        values.put("enabled", Boolean.toString(enabled));
        requireSuccess(client.updateCourse(token, values));
    }

    @Override
    public AcademicData.CurriculumPage curricula(Long majorId, CurriculumPlanStatus status, int page)
            throws IOException {
        return AcademicData.curriculumPage(client.searchCurricula(token, majorId,
                status == null ? null : status.name(), page));
    }

    @Override
    public AcademicData.CurriculumDetail curriculum(long planId) throws IOException {
        return AcademicData.curriculumDetail(client.getCurriculum(token, positive(planId, "planId")));
    }

    @Override
    public long createCurriculum(AcademicCommands.CurriculumDraft draft) throws IOException {
        return AcademicData.responseId(client.createCurriculum(token, curriculumValues(draft, true)),
                "planId");
    }

    @Override
    public void updateCurriculum(long planId, AcademicCommands.CurriculumDraft draft)
            throws IOException {
        Map<String, String> values = curriculumValues(draft, false);
        values.put("planId", Long.toString(positive(planId, "planId")));
        requireSuccess(client.updateCurriculum(token, values));
    }

    @Override
    public long copyCurriculum(long planId, String name, int yearFrom, int yearTo)
            throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("planId", Long.toString(positive(planId, "planId")));
        values.put("planName", requiredText(name, "planName"));
        values.put("yearFrom", Integer.toString(yearFrom));
        values.put("yearTo", Integer.toString(yearTo));
        return AcademicData.responseId(client.copyCurriculum(token, values), "planId");
    }

    @Override
    public void saveCurriculumCourse(long planId, AcademicCommands.CurriculumCourseDraft draft)
            throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("planId", Long.toString(positive(planId, "planId")));
        values.put("courseId", Long.toString(draft.courseId()));
        values.put("requirementType", draft.requirementType().name());
        values.put("recommendedTermNumber", Integer.toString(draft.recommendedTermNumber()));
        requireSuccess(draft.alreadyInPlan()
                ? client.updateCurriculumCourse(token, values)
                : client.addCurriculumCourse(token, values));
    }

    @Override
    public void removeCurriculumCourse(long planId, long courseId) throws IOException {
        requireSuccess(client.removeCurriculumCourse(token, positive(planId, "planId"),
                positive(courseId, "courseId")));
    }

    @Override
    public void publishCurriculum(long planId) throws IOException {
        requireSuccess(client.publishCurriculum(token, positive(planId, "planId")));
    }

    @Override
    public void archiveCurriculum(long planId) throws IOException {
        requireSuccess(client.archiveCurriculum(token, positive(planId, "planId")));
    }

    @Override
    public AcademicData.SectionPage sections(long termId, String keyword, int page) throws IOException {
        return AcademicData.sectionPage(client.searchCourseSections(token, positive(termId, "termId"),
                safe(keyword), page));
    }

    @Override
    public long createSection(AcademicCommands.SectionDraft draft) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("termId", Long.toString(draft.termId()));
        values.put("courseId", Long.toString(draft.courseId()));
        values.put("sectionCode", draft.sectionCode());
        values.put("teacherUserId", Long.toString(draft.teacherUserId()));
        values.put("capacity", Integer.toString(draft.capacity()));
        values.put("status", draft.status().name());
        values.put("publishSchedule", Boolean.toString(draft.publishSchedule()));
        putSlots(values, "schedule", draft.slots());
        return AcademicData.responseId(client.createCourseSection(token, values), "sectionId");
    }

    @Override
    public void setSectionStatus(long sectionId, com.vcampus.common.model.CourseSectionStatus status)
            throws IOException {
        requireSuccess(client.setCourseSectionStatus(token, positive(sectionId, "sectionId"),
                Objects.requireNonNull(status, "status").name()));
    }

    @Override
    public List<AcademicData.SectionTarget> sectionTargets(long sectionId) throws IOException {
        return AcademicData.sectionTargets(client.getCourseSectionTargets(
                token, positive(sectionId, "sectionId")));
    }

    @Override
    public void saveSectionTargets(long sectionId, List<AcademicData.SectionTarget> targets)
            throws IOException {
        Objects.requireNonNull(targets, "targets");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("sectionId", Long.toString(positive(sectionId, "sectionId")));
        values.put("target.count", Integer.toString(targets.size()));
        for (int index = 0; index < targets.size(); index++) {
            AcademicData.SectionTarget target = Objects.requireNonNull(targets.get(index), "target");
            values.put("target." + index, RowCodec.encode(Long.toString(target.majorId()),
                    Integer.toString(target.yearFrom()), Integer.toString(target.yearTo())));
        }
        requireSuccess(client.saveCourseSectionTargets(token, values));
    }

    @Override
    public AcademicData.ScheduleDraft scheduleDraft(long sectionId) throws IOException {
        return AcademicData.scheduleDraft(client.getScheduleDraft(token,
                positive(sectionId, "sectionId")));
    }

    @Override
    public AcademicData.ScheduleDraft saveSchedule(AcademicCommands.ScheduleDraftCommand command)
            throws IOException {
        Map<String, String> values = revisionValues(command.sectionId(), command.scheduleRevisionId(),
                command.expectedRevisionNo());
        putSlots(values, "slot", command.slots());
        return AcademicData.scheduleDraft(client.saveScheduleDraft(token, values));
    }

    @Override
    public AcademicData.SchedulePublishResult publishSchedule(
            AcademicCommands.SchedulePublishCommand command) throws IOException {
        return AcademicData.schedulePublishResult(client.publishSchedule(token,
                revisionValues(command.sectionId(), command.scheduleRevisionId(),
                        command.expectedRevisionNo())));
    }

    @Override
    public AcademicData.EnrollmentCatalog enrollmentCatalog(long termId) throws IOException {
        return AcademicData.enrollmentCatalog(client.availableCourseSections(
                token, positive(termId, "termId")));
    }

    @Override
    public void enroll(long sectionId) throws IOException {
        requireSuccess(client.enrollCourse(token, positive(sectionId, "sectionId")));
    }

    @Override
    public void drop(long sectionId) throws IOException {
        requireSuccess(client.dropCourse(token, positive(sectionId, "sectionId")));
    }

    @Override
    public void switchSection(long fromSectionId, long toSectionId) throws IOException {
        requireSuccess(client.switchCourseSection(token, positive(fromSectionId, "fromSectionId"),
                positive(toSectionId, "toSectionId")));
    }

    @Override
    public List<AcademicData.ScheduleEntry> studentSchedule(long termId) throws IOException {
        return AcademicData.scheduleEntries(client.mySchedule(token, positive(termId, "termId")));
    }

    @Override
    public List<AcademicData.ScheduleEntry> teacherSchedule(long termId, Long teacherUserId)
            throws IOException {
        if (teacherUserId != null) positive(teacherUserId, "teacherUserId");
        return AcademicData.scheduleEntries(client.teacherSchedule(token,
                positive(termId, "termId"), teacherUserId));
    }

    @Override
    public List<AcademicData.TeachingSection> teacherSections(long termId) throws IOException {
        return AcademicData.teachingSections(client.teacherSections(token,
                positive(termId, "termId")));
    }

    @Override
    public AcademicData.Roster roster(long sectionId) throws IOException {
        return AcademicData.roster(client.sectionRoster(token, positive(sectionId, "sectionId")));
    }

    @Override
    public void saveGrade(long sectionId, long enrollmentId, AcademicCommands.GradeDraft draft)
            throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("sectionId", Long.toString(positive(sectionId, "sectionId")));
        values.put("enrollmentId", Long.toString(positive(enrollmentId, "enrollmentId")));
        values.put("score", draft.score().toPlainString());
        values.put("comment", draft.comment());
        values.put("reason", draft.reason());
        requireSuccess(client.saveGrade(token, values));
    }

    @Override
    public void publishGrades(long sectionId) throws IOException {
        requireSuccess(client.publishGrades(token, positive(sectionId, "sectionId")));
    }

    @Override
    public List<AcademicData.GradeRow> myGrades() throws IOException {
        return AcademicData.gradeRows(client.myGrades(token));
    }

    private ResponseMessage requireSuccess(ResponseMessage response) throws IOException {
        if (response == null) throw new IOException("服务器未返回数据");
        if (!response.success()) throw new IOException(response.message());
        return response;
    }

    private static Map<String, String> courseValues(AcademicCommands.CourseDraft draft) {
        Objects.requireNonNull(draft, "draft");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("courseCode", draft.courseCode());
        values.put("courseName", draft.courseName());
        values.put("credits", draft.credits().toPlainString());
        values.put("totalHours", Integer.toString(draft.totalHours()));
        values.put("description", draft.description());
        return values;
    }

    private static Map<String, String> curriculumValues(
            AcademicCommands.CurriculumDraft draft, boolean includeVersion) {
        Objects.requireNonNull(draft, "draft");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("majorId", Long.toString(draft.majorId()));
        values.put("planName", draft.planName());
        if (includeVersion) values.put("versionNo", Integer.toString(draft.versionNo()));
        values.put("yearFrom", Integer.toString(draft.yearFrom()));
        values.put("yearTo", Integer.toString(draft.yearTo()));
        return values;
    }

    private static Map<String, String> revisionValues(long sectionId, long revisionId,
                                                       int revisionNo) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("sectionId", Long.toString(positive(sectionId, "sectionId")));
        values.put("scheduleRevisionId", Long.toString(positive(revisionId, "scheduleRevisionId")));
        if (revisionNo < 1) throw new IllegalArgumentException("expectedRevisionNo");
        values.put("expectedRevisionNo", Integer.toString(revisionNo));
        return values;
    }

    private static void putSlots(Map<String, String> values, String prefix, List<ScheduleSlot> slots) {
        values.put(prefix + ".count", Integer.toString(slots.size()));
        for (int index = 0; index < slots.size(); index++) {
            ScheduleSlot slot = Objects.requireNonNull(slots.get(index), "slot");
            values.put(prefix + "." + index, RowCodec.encode(Integer.toString(slot.dayOfWeek()),
                    Integer.toString(slot.startPeriod()), Integer.toString(slot.endPeriod()),
                    Integer.toString(slot.startWeek()), Integer.toString(slot.endWeek()),
                    slot.classroom()));
        }
    }

    private static long positive(long value, String name) {
        if (value < 1) throw new IllegalArgumentException(name);
        return value;
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name);
        return value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
