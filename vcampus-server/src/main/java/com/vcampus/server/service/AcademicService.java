package com.vcampus.server.service;

import com.vcampus.common.model.AcademicAccessPolicy;
import com.vcampus.common.model.CourseSectionStatus;
import com.vcampus.common.model.CourseRequirementType;
import com.vcampus.common.model.CurriculumPlanStatus;
import com.vcampus.common.model.ScheduleSlot;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.AcademicRepository;
import com.vcampus.server.database.AcademicRepository.AcademicReferences;
import com.vcampus.server.database.AcademicRepository.AcademicRuleException;
import com.vcampus.server.database.AcademicRepository.AvailableCourse;
import com.vcampus.server.database.AcademicRepository.AvailableSection;
import com.vcampus.server.database.AcademicRepository.CoursePage;
import com.vcampus.server.database.AcademicRepository.CourseRecord;
import com.vcampus.server.database.AcademicRepository.CreateCourse;
import com.vcampus.server.database.AcademicRepository.CreateSection;
import com.vcampus.server.database.AcademicRepository.UpdateCourse;
import com.vcampus.server.database.AcademicRepository.GradeRecord;
import com.vcampus.server.database.AcademicRepository.RosterRecord;
import com.vcampus.server.database.AcademicRepository.SectionPage;
import com.vcampus.server.database.AcademicRepository.SectionRecord;
import com.vcampus.server.database.AcademicRepository.SectionTarget;
import com.vcampus.server.database.AcademicRepository.ScheduleRecord;
import com.vcampus.server.database.CurriculumRepository;
import com.vcampus.server.database.CurriculumRepository.CreateCurriculum;
import com.vcampus.server.database.CurriculumRepository.CurriculumCourse;
import com.vcampus.server.database.CurriculumRepository.CurriculumDetail;
import com.vcampus.server.database.CurriculumRepository.CurriculumPage;
import com.vcampus.server.database.CurriculumRepository.CurriculumQuery;
import com.vcampus.server.database.CurriculumRepository.CurriculumSummary;
import com.vcampus.server.database.CurriculumRepository.UpdateCurriculum;
import com.vcampus.server.database.ScheduleRevisionRepository;
import com.vcampus.server.database.ScheduleRevisionRepository.PublishSchedule;
import com.vcampus.server.database.ScheduleRevisionRepository.SaveScheduleDraft;
import com.vcampus.server.database.ScheduleRevisionRepository.ScheduleConflictException;
import com.vcampus.server.database.ScheduleRevisionRepository.ScheduleDraft;
import com.vcampus.server.database.ScheduleRevisionRepository.ScheduleRevision;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;

import java.math.BigDecimal;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

public final class AcademicService {
    private static final int PAGE_SIZE = 8;

    private final AcademicRepository academic;
    private final CurriculumRepository curricula;
    private final ScheduleRevisionRepository schedules;
    private final SessionManager sessions;

    public AcademicService(AcademicRepository academic, SessionManager sessions) {
        this(academic, null, null, sessions);
    }

    public AcademicService(AcademicRepository academic, CurriculumRepository curricula,
                           SessionManager sessions) {
        this(academic, curricula, null, sessions);
    }

    public AcademicService(AcademicRepository academic, CurriculumRepository curricula,
                           ScheduleRevisionRepository schedules, SessionManager sessions) {
        this.academic = academic;
        this.curricula = curricula;
        this.schedules = schedules;
        this.sessions = Objects.requireNonNull(sessions);
    }

    public ResponseMessage searchCurricula(RequestMessage request) {
        Optional<UserSession> manager = managerSession(request);
        if (manager.isEmpty()) return expiredOrForbidden(request);
        try {
            long majorId = optionalLong(request.parameters().get("majorId"));
            String rawStatus = request.parameters().getOrDefault("status", "").trim();
            CurriculumPlanStatus status = rawStatus.isEmpty()
                    ? null : curriculumStatus(rawStatus);
            int page = integer(request.parameters().getOrDefault("page", "1"),
                    "页码", 1, 100_000);
            CurriculumPage result = curriculumRepository().search(new CurriculumQuery(
                    majorId == 0 ? null : majorId, status,
                    request.parameters().getOrDefault("keyword", ""), page, PAGE_SIZE));
            Map<String, String> data = pageData(
                    result.page(), result.pageSize(), result.total(), result.rows().size());
            data.put("schemaVersion", "2");
            for (int index = 0; index < result.rows().size(); index++) {
                CurriculumSummary row = result.rows().get(index);
                data.put("row." + index, RowCodec.encode(
                        Long.toString(row.id()), Long.toString(row.majorId()), row.majorName(),
                        row.planName(), Integer.toString(row.versionNo()),
                        Integer.toString(row.enrollmentYearStart()),
                        Integer.toString(row.enrollmentYearEnd()), row.status().name(),
                        Integer.toString(row.courseCount())));
            }
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (AcademicRuleException | IllegalStateException | IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage getCurriculum(RequestMessage request) {
        Optional<UserSession> manager = managerSession(request);
        if (manager.isEmpty()) return expiredOrForbidden(request);
        try {
            CurriculumDetail plan = curriculumRepository().get(
                    positiveLong(request.parameters().get("planId"), "培养方案ID"));
            Map<String, String> data = new LinkedHashMap<>();
            data.put("schemaVersion", "2");
            data.put("plan", RowCodec.encode(
                    Long.toString(plan.id()), Long.toString(plan.majorId()), plan.majorName(),
                    plan.planName(), Integer.toString(plan.versionNo()),
                    Integer.toString(plan.enrollmentYearStart()),
                    Integer.toString(plan.enrollmentYearEnd()), plan.status().name(),
                    plan.createdAt().toString(), nullToEmpty(plan.publishedBy()),
                    plan.publishedAt() == null ? "" : plan.publishedAt().toString()));
            data.put("course.count", Integer.toString(plan.courses().size()));
            for (int index = 0; index < plan.courses().size(); index++) {
                CurriculumCourse course = plan.courses().get(index);
                data.put("course." + index, RowCodec.encode(
                        Long.toString(course.courseId()), course.courseCode(), course.courseName(),
                        course.credits().toPlainString(), course.requirementType().name(),
                        Integer.toString(course.recommendedTermNumber())));
            }
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (AcademicRuleException | IllegalStateException | IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage createCurriculum(RequestMessage request) {
        Optional<UserSession> manager = managerSession(request);
        if (manager.isEmpty()) return expiredOrForbidden(request);
        try {
            CreateCurriculum command = parseCurriculum(request.parameters());
            long planId = curriculumRepository().create(command, manager.get().userId());
            return ResponseMessage.success(request.requestId(), "培养方案创建成功",
                    Map.of("planId", Long.toString(planId)));
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            return ResponseMessage.failure(request.requestId(), "该专业的培养方案版本号已经存在");
        } catch (AcademicRuleException | IllegalStateException | IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage updateCurriculum(RequestMessage request) {
        Optional<UserSession> manager = managerSession(request);
        if (manager.isEmpty()) return expiredOrForbidden(request);
        try {
            Map<String, String> values = request.parameters();
            curriculumRepository().update(new UpdateCurriculum(
                    positiveLong(values.get("planId"), "培养方案ID"),
                    requiredName(values, "planName", "培养方案名称"),
                    integer(values.get("yearFrom"), "适用起始年份", 1900, 2200),
                    integer(values.get("yearTo"), "适用结束年份", 1900, 2200)),
                    manager.get().userId());
            return ResponseMessage.success(request.requestId(), "培养方案已更新", Map.of());
        } catch (AcademicRuleException | IllegalStateException | IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage copyCurriculum(RequestMessage request) {
        Optional<UserSession> manager = managerSession(request);
        if (manager.isEmpty()) return expiredOrForbidden(request);
        try {
            Map<String, String> values = request.parameters();
            long sourceId = positiveLong(values.get("planId"), "培养方案ID");
            CurriculumDetail source = curriculumRepository().get(sourceId);
            CreateCurriculum target = new CreateCurriculum(
                    source.majorId(), requiredName(values, "planName", "培养方案名称"), 0,
                    integer(values.get("yearFrom"), "适用起始年份", 1900, 2200),
                    integer(values.get("yearTo"), "适用结束年份", 1900, 2200));
            long planId = curriculumRepository().copy(sourceId, target, manager.get().userId());
            return ResponseMessage.success(request.requestId(), "培养方案复制成功",
                    Map.of("planId", Long.toString(planId)));
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            return ResponseMessage.failure(request.requestId(), "培养方案复制冲突，请重试");
        } catch (AcademicRuleException | IllegalStateException | IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage publishCurriculum(RequestMessage request) {
        return mutateCurriculum(request, "培养方案已发布", (repository, planId, userId) ->
                repository.publish(planId, userId));
    }

    public ResponseMessage archiveCurriculum(RequestMessage request) {
        return mutateCurriculum(request, "培养方案已归档", (repository, planId, userId) ->
                repository.archive(planId, userId));
    }

    public ResponseMessage addCurriculumCourse(RequestMessage request) {
        return mutateCurriculumCourse(request, "课程已加入培养方案", false);
    }

    public ResponseMessage updateCurriculumCourse(RequestMessage request) {
        return mutateCurriculumCourse(request, "培养方案课程已更新", true);
    }

    public ResponseMessage removeCurriculumCourse(RequestMessage request) {
        Optional<UserSession> manager = managerSession(request);
        if (manager.isEmpty()) return expiredOrForbidden(request);
        try {
            curriculumRepository().removeCourse(
                    positiveLong(request.parameters().get("planId"), "培养方案ID"),
                    positiveLong(request.parameters().get("courseId"), "课程ID"),
                    manager.get().userId());
            return ResponseMessage.success(request.requestId(), "课程已从培养方案移除", Map.of());
        } catch (AcademicRuleException | IllegalStateException | IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage referenceData(RequestMessage request) {
        Optional<UserSession> session = authorizedSession(request);
        if (session.isEmpty()) {
            return expiredOrForbidden(request);
        }
        try {
            AcademicReferences references = academic.references();
            Map<String, String> data = new LinkedHashMap<>();
            data.put("term.count", Integer.toString(references.terms().size()));
            for (int index = 0; index < references.terms().size(); index++) {
                var term = references.terms().get(index);
                data.put("term." + index, RowCodec.encode(
                        Long.toString(term.id()), term.name(), term.status().name()));
                if (term.startDate() != null && term.endDate() != null) {
                    data.put("term." + index + ".startDate", term.startDate().toString());
                    data.put("term." + index + ".endDate", term.endDate().toString());
                }
            }
            data.put("course.count", Integer.toString(references.courses().size()));
            for (int index = 0; index < references.courses().size(); index++) {
                var course = references.courses().get(index);
                data.put("course." + index, RowCodec.encode(
                        Long.toString(course.id()), course.code(), course.name(), course.credits().toPlainString()));
            }
            data.put("teacher.count", Integer.toString(references.teachers().size()));
            for (int index = 0; index < references.teachers().size(); index++) {
                var teacher = references.teachers().get(index);
                data.put("teacher." + index, RowCodec.encode(
                        Long.toString(teacher.userId()), teacher.username(), teacher.displayName()));
            }
            return ResponseMessage.success(request.requestId(), "教务基础数据加载成功", data);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage searchCourses(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        if (!AcademicAccessPolicy.canManage(session.get().roles())) {
            return forbidden(request);
        }
        try {
            int page = integer(request.parameters().getOrDefault("page", "1"), "页码", 1, 100_000);
            CoursePage result = academic.searchCourses(
                    request.parameters().getOrDefault("keyword", ""), page, PAGE_SIZE);
            Map<String, String> data = pageData(result.page(), result.pageSize(), result.total(), result.rows().size());
            for (int index = 0; index < result.rows().size(); index++) {
                CourseRecord course = result.rows().get(index);
                data.put("row." + index, RowCodec.encode(
                        Long.toString(course.id()), course.code(), course.name(),
                        course.credits().toPlainString(), Integer.toString(course.totalHours()),
                        nullToEmpty(course.description()), Boolean.toString(course.enabled())));
            }
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage createCourse(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        if (!AcademicAccessPolicy.canManage(session.get().roles())) {
            return forbidden(request);
        }
        try {
            CreateCourse command = parseCourse(request.parameters());
            long courseId = academic.createCourse(command);
            return ResponseMessage.success(
                    request.requestId(), "课程创建成功", Map.of("courseId", Long.toString(courseId)));
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            return ResponseMessage.failure(request.requestId(), "课程号已经存在");
        } catch (IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage updateCourse(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        if (!AcademicAccessPolicy.canManage(session.get().roles())) {
            return forbidden(request);
        }
        try {
            CreateCourse values = parseCourse(request.parameters());
            UpdateCourse command = new UpdateCourse(
                    positiveLong(request.parameters().get("courseId"), "课程ID"),
                    values.courseName(), values.credits(), values.totalHours(),
                    values.description(), Boolean.parseBoolean(request.parameters().getOrDefault("enabled", "true")));
            if (!academic.updateCourse(command)) {
                return ResponseMessage.failure(request.requestId(), "课程不存在");
            }
            return ResponseMessage.success(request.requestId(), "课程信息已更新", Map.of());
        } catch (IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage searchSections(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        if (!AcademicAccessPolicy.canManage(session.get().roles())) {
            return forbidden(request);
        }
        try {
            long termId = optionalLong(request.parameters().get("termId"));
            int page = integer(request.parameters().getOrDefault("page", "1"), "页码", 1, 100_000);
            SectionPage result = academic.searchSections(
                    termId, request.parameters().getOrDefault("keyword", ""), page, PAGE_SIZE);
            Map<String, String> data = pageData(result.page(), result.pageSize(), result.total(), result.rows().size());
            putSections(data, result.rows());
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage createSection(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        if (!AcademicAccessPolicy.canManage(session.get().roles())) {
            return forbidden(request);
        }
        try {
            CreateSection command = parseSection(request.parameters());
            long sectionId = academic.createSection(
                    command, session.get().userId(), session.get().displayName());
            return ResponseMessage.success(
                    request.requestId(), "教学班创建成功", Map.of("sectionId", Long.toString(sectionId)));
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            return ResponseMessage.failure(request.requestId(), "教学班编号已经存在");
        } catch (AcademicRuleException | IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage setSectionStatus(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        if (!AcademicAccessPolicy.canManage(session.get().roles())) {
            return forbidden(request);
        }
        try {
            long sectionId = positiveLong(request.parameters().get("sectionId"), "教学班ID");
            CourseSectionStatus status = CourseSectionStatus.valueOf(
                    required(request.parameters(), "status", "教学班状态"));
            if (!academic.setSectionStatus(sectionId, status)) {
                return ResponseMessage.failure(request.requestId(), "教学班不存在");
            }
            return ResponseMessage.success(request.requestId(), "教学班状态已更新", Map.of());
        } catch (IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage getSectionTargets(RequestMessage request) {
        Optional<UserSession> manager = managerSession(request);
        if (manager.isEmpty()) return expiredOrForbidden(request);
        try {
            List<SectionTarget> targets = academic.getSectionTargets(
                    positiveLong(request.parameters().get("sectionId"), "教学班ID"));
            Map<String, String> data = new LinkedHashMap<>();
            data.put("schemaVersion", "2");
            data.put("target.count", Integer.toString(targets.size()));
            for (int index = 0; index < targets.size(); index++) {
                SectionTarget target = targets.get(index);
                data.put("target." + index, RowCodec.encode(
                        Long.toString(target.majorId()),
                        Integer.toString(target.enrollmentYearStart()),
                        Integer.toString(target.enrollmentYearEnd())));
            }
            return ResponseMessage.success(request.requestId(), "教学班目标范围加载成功", data);
        } catch (AcademicRuleException | IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage saveSectionTargets(RequestMessage request) {
        Optional<UserSession> manager = managerSession(request);
        if (manager.isEmpty()) return expiredOrForbidden(request);
        try {
            academic.saveSectionTargets(
                    positiveLong(request.parameters().get("sectionId"), "教学班ID"),
                    parseSectionTargets(request.parameters()));
            return ResponseMessage.success(request.requestId(), "教学班目标范围已保存", Map.of());
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            return ResponseMessage.failure(request.requestId(), "教学班目标范围存在重复");
        } catch (AcademicRuleException | IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage getScheduleDraft(RequestMessage request) {
        Optional<UserSession> manager = managerSession(request);
        if (manager.isEmpty()) return expiredOrForbidden(request);
        try {
            ScheduleDraft draft = scheduleRepository().getOrCreateDraft(
                    positiveLong(request.parameters().get("sectionId"), "教学班ID"),
                    manager.get().userId());
            return scheduleDraftResponse(request, "课表草稿加载成功", draft);
        } catch (AcademicRuleException | IllegalStateException | IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage saveScheduleDraft(RequestMessage request) {
        Optional<UserSession> manager = managerSession(request);
        if (manager.isEmpty()) return expiredOrForbidden(request);
        try {
            Map<String, String> values = request.parameters();
            ScheduleDraft draft = scheduleRepository().saveDraft(new SaveScheduleDraft(
                    positiveLong(values.get("sectionId"), "教学班ID"),
                    positiveLong(values.get("scheduleRevisionId"), "课表修订ID"),
                    integer(values.get("expectedRevisionNo"), "预期修订号", 1, 1_000_000),
                    parseRevisionSlots(values)), manager.get().userId());
            return scheduleDraftResponse(request, "课表草稿已保存", draft);
        } catch (AcademicRuleException | IllegalStateException | IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage publishSchedule(RequestMessage request) {
        Optional<UserSession> manager = managerSession(request);
        if (manager.isEmpty()) return expiredOrForbidden(request);
        try {
            Map<String, String> values = request.parameters();
            ScheduleRevision revision = scheduleRepository().publish(new PublishSchedule(
                    positiveLong(values.get("sectionId"), "教学班ID"),
                    positiveLong(values.get("scheduleRevisionId"), "课表修订ID"),
                    integer(values.get("expectedRevisionNo"), "预期修订号", 1, 1_000_000)),
                    manager.get().userId(), manager.get().displayName());
            Map<String, String> data = new LinkedHashMap<>();
            data.put("scheduleRevisionId", Long.toString(revision.id()));
            data.put("revisionNo", Integer.toString(revision.revisionNo()));
            data.put("status", revision.status().name());
            return ResponseMessage.success(request.requestId(), "课表已发布", data);
        } catch (ScheduleConflictException conflict) {
            Map<String, String> data = new LinkedHashMap<>();
            data.put("conflict.count", Integer.toString(conflict.conflicts().size()));
            for (int index = 0; index < conflict.conflicts().size(); index++) {
                var row = conflict.conflicts().get(index);
                data.put("conflict." + index, RowCodec.encode(
                        row.kind().name(), Long.toString(row.relatedEntityId()),
                        row.displayName(), Integer.toString(row.dayOfWeek()),
                        Integer.toString(row.startPeriod()), Integer.toString(row.endPeriod()),
                        Integer.toString(row.startWeek()), Integer.toString(row.endWeek())));
            }
            return new ResponseMessage(request.requestId(), false, conflict.getMessage(), data);
        } catch (AcademicRuleException | IllegalStateException | IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage availableSections(RequestMessage request) {
        Optional<UserSession> session = studentSession(request);
        if (session.isEmpty()) {
            return expiredOrForbidden(request);
        }
        try {
            long termId = positiveLong(request.parameters().get("termId"), "学期ID");
            List<SectionRecord> rows = academic.availableSections(session.get().userId(), termId);
            Map<String, String> data = new LinkedHashMap<>();
            data.put("count", Integer.toString(rows.size()));
            putSections(data, rows);
            List<AvailableCourse> courses = academic.availableCourseGroups(
                    session.get().userId(), termId);
            data.put("catalogSchemaVersion", "2");
            data.put("course.count", Integer.toString(courses.size()));
            putAvailableCourses(data, courses);
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (AcademicRuleException | IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage enroll(RequestMessage request) {
        Optional<UserSession> session = studentSession(request);
        if (session.isEmpty()) {
            return expiredOrForbidden(request);
        }
        try {
            academic.enroll(session.get().userId(), positiveLong(request.parameters().get("sectionId"), "教学班ID"));
            return ResponseMessage.success(request.requestId(), "选课成功", Map.of());
        } catch (AcademicRuleException | IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage drop(RequestMessage request) {
        Optional<UserSession> session = studentSession(request);
        if (session.isEmpty()) {
            return expiredOrForbidden(request);
        }
        try {
            academic.drop(session.get().userId(), positiveLong(request.parameters().get("sectionId"), "教学班ID"));
            return ResponseMessage.success(request.requestId(), "退课成功", Map.of());
        } catch (AcademicRuleException | IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage mySchedule(RequestMessage request) {
        Optional<UserSession> session = studentSession(request);
        if (session.isEmpty()) {
            return expiredOrForbidden(request);
        }
        try {
            long termId = positiveLong(request.parameters().get("termId"), "学期ID");
            List<ScheduleRecord> rows = academic.mySchedule(session.get().userId(), termId);
            Map<String, String> data = new LinkedHashMap<>();
            data.put("count", Integer.toString(rows.size()));
            putSchedules(data, rows);
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (AcademicRuleException | IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage teacherSections(RequestMessage request) {
        Optional<UserSession> session = teachingSession(request);
        if (session.isEmpty()) {
            return expiredOrForbidden(request);
        }
        try {
            long termId = optionalLong(request.parameters().get("termId"));
            List<SectionRecord> rows = academic.teacherSections(session.get().userId(), termId);
            Map<String, String> data = new LinkedHashMap<>();
            data.put("count", Integer.toString(rows.size()));
            putSections(data, rows);
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage teacherSchedule(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        boolean manager = AcademicAccessPolicy.canManage(session.get().roles());
        boolean teacher = AcademicAccessPolicy.canTeach(session.get().roles());
        if (!manager && !teacher) {
            return forbidden(request);
        }
        try {
            long termId = positiveLong(request.parameters().get("termId"), "学期ID");
            long requestedTeacherId = optionalLong(request.parameters().get("teacherUserId"));
            long teacherUserId = requestedTeacherId == 0 ? session.get().userId() : requestedTeacherId;
            if (!manager && teacherUserId != session.get().userId()) {
                return forbidden(request);
            }
            List<ScheduleRecord> rows = academic.teacherSchedule(teacherUserId, termId);
            Map<String, String> data = new LinkedHashMap<>();
            data.put("count", Integer.toString(rows.size()));
            putSchedules(data, rows);
            return ResponseMessage.success(request.requestId(), "课表查询成功", data);
        } catch (IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage roster(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        boolean manager = AcademicAccessPolicy.canManage(session.get().roles());
        boolean teacher = AcademicAccessPolicy.canTeach(session.get().roles());
        if (!manager && !teacher) {
            return forbidden(request);
        }
        try {
            long sectionId = positiveLong(request.parameters().get("sectionId"), "教学班ID");
            if (!manager && !academic.isSectionTeacher(sectionId, session.get().userId())) {
                return forbidden(request);
            }
            List<RosterRecord> rows = academic.roster(sectionId);
            Map<String, String> data = new LinkedHashMap<>();
            data.put("count", Integer.toString(rows.size()));
            for (int index = 0; index < rows.size(); index++) {
                RosterRecord row = rows.get(index);
                data.put("row." + index, RowCodec.encode(
                        Long.toString(row.enrollmentId()), Long.toString(row.studentId()),
                        row.studentNumber(), row.fullName(), row.status().name(),
                        decimal(row.score()), decimal(row.gradePoint()), nullToEmpty(row.comment())));
            }
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage saveGrade(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        boolean manager = AcademicAccessPolicy.canManage(session.get().roles());
        boolean teacher = AcademicAccessPolicy.canTeach(session.get().roles());
        if (!manager && !teacher) {
            return forbidden(request);
        }
        try {
            long sectionId = positiveLong(request.parameters().get("sectionId"), "教学班ID");
            long enrollmentId = positiveLong(request.parameters().get("enrollmentId"), "选课记录ID");
            BigDecimal score = decimalRequired(request.parameters().get("score"), "成绩");
            if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException("成绩必须在 0—100 之间");
            }
            String reason = required(request.parameters(), "reason", "录入或修改原因");
            if (reason.length() > 255) {
                throw new IllegalArgumentException("原因不能超过 255 位");
            }
            academic.saveGrade(
                    sectionId, enrollmentId, score,
                    request.parameters().get("comment"), reason,
                    session.get().userId(), manager);
            return ResponseMessage.success(
                    request.requestId(),
                    "成绩保存成功。请点击“发布最终成绩”，发布后学生才能查看。",
                    Map.of());
        } catch (AcademicRuleException | IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage publishGrades(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        if (!AcademicAccessPolicy.canPublishGrades(session.get().roles())) {
            return forbidden(request);
        }
        try {
            boolean manager = AcademicAccessPolicy.canManage(session.get().roles());
            academic.publishGrades(
                    positiveLong(request.parameters().get("sectionId"), "教学班ID"),
                    session.get().userId(), manager);
            return ResponseMessage.success(request.requestId(), "成绩发布成功", Map.of());
        } catch (AcademicRuleException | IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage myGrades(RequestMessage request) {
        Optional<UserSession> session = studentSession(request);
        if (session.isEmpty()) {
            return expiredOrForbidden(request);
        }
        try {
            List<GradeRecord> rows = academic.myGrades(session.get().userId());
            Map<String, String> data = new LinkedHashMap<>();
            data.put("count", Integer.toString(rows.size()));
            for (int index = 0; index < rows.size(); index++) {
                GradeRecord row = rows.get(index);
                data.put("row." + index, RowCodec.encode(
                        row.termName(), row.courseCode(), row.courseName(),
                        row.credits().toPlainString(), row.teacherName(),
                        row.score().toPlainString(), row.gradePoint().toPlainString()));
            }
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (AcademicRuleException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    private CreateCurriculum parseCurriculum(Map<String, String> values) {
        return new CreateCurriculum(
                positiveLong(values.get("majorId"), "专业ID"),
                requiredName(values, "planName", "培养方案名称"),
                integer(values.get("versionNo"), "版本号", 1, 10_000),
                integer(values.get("yearFrom"), "适用起始年份", 1900, 2200),
                integer(values.get("yearTo"), "适用结束年份", 1900, 2200));
    }

    private ResponseMessage mutateCurriculum(RequestMessage request, String message,
                                              CurriculumMutation mutation) {
        Optional<UserSession> manager = managerSession(request);
        if (manager.isEmpty()) return expiredOrForbidden(request);
        try {
            mutation.apply(curriculumRepository(),
                    positiveLong(request.parameters().get("planId"), "培养方案ID"),
                    manager.get().userId());
            return ResponseMessage.success(request.requestId(), message, Map.of());
        } catch (AcademicRuleException | IllegalStateException | IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    private ResponseMessage mutateCurriculumCourse(RequestMessage request, String message,
                                                    boolean update) {
        Optional<UserSession> manager = managerSession(request);
        if (manager.isEmpty()) return expiredOrForbidden(request);
        try {
            Map<String, String> values = request.parameters();
            long planId = positiveLong(values.get("planId"), "培养方案ID");
            long courseId = positiveLong(values.get("courseId"), "课程ID");
            CourseRequirementType type = requirementType(
                    required(values, "requirementType", "课程属性"));
            int term = integer(values.get("recommendedTermNumber"), "建议学期", 1, 12);
            if (update) {
                curriculumRepository().updateCourse(
                        planId, courseId, type, term, manager.get().userId());
            } else {
                curriculumRepository().addCourse(
                        planId, courseId, type, term, manager.get().userId());
            }
            return ResponseMessage.success(request.requestId(), message, Map.of());
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            return ResponseMessage.failure(request.requestId(), "该课程已在培养方案中");
        } catch (AcademicRuleException | IllegalStateException | IllegalArgumentException exception) {
            return invalid(request, exception);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    private CurriculumPlanStatus curriculumStatus(String value) {
        try {
            return CurriculumPlanStatus.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("培养方案状态无效");
        }
    }

    private CourseRequirementType requirementType(String value) {
        try {
            return CourseRequirementType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("课程属性必须为 REQUIRED 或 ELECTIVE");
        }
    }

    private String requiredName(Map<String, String> values, String key, String label) {
        String name = required(values, key, label);
        if (name.length() > 120) {
            throw new IllegalArgumentException(label + "不能超过 120 位");
        }
        return name;
    }

    private CreateCourse parseCourse(Map<String, String> values) {
        String code = required(values, "courseCode", "课程号").toUpperCase();
        if (!code.matches("C[0-9]{6}")) {
            throw new IllegalArgumentException("课程号必须为字母 C 加 6 位数字");
        }
        String name = required(values, "courseName", "课程名称");
        if (name.length() > 120) {
            throw new IllegalArgumentException("课程名称不能超过 120 位");
        }
        BigDecimal credits = decimalRequired(values.get("credits"), "学分");
        if (credits.compareTo(BigDecimal.ZERO) <= 0 || credits.compareTo(new BigDecimal("20")) > 0) {
            throw new IllegalArgumentException("学分必须大于 0 且不超过 20");
        }
        int hours = integer(values.get("totalHours"), "总学时", 1, 400);
        String description = values.getOrDefault("description", "").trim();
        if (description.length() > 500) {
            throw new IllegalArgumentException("课程说明不能超过 500 位");
        }
        return new CreateCourse(code, name, credits, hours, description);
    }

    private CreateSection parseSection(Map<String, String> values) {
        String code = required(values, "sectionCode", "教学班编号");
        if (!code.matches("[A-Za-z0-9._-]{3,24}")) {
            throw new IllegalArgumentException("教学班编号只能包含字母、数字、点、下划线和连字符");
        }
        List<ScheduleSlot> schedules = parseSchedules(values);
        return new CreateSection(
                positiveLong(values.get("termId"), "学期ID"),
                positiveLong(values.get("courseId"), "课程ID"),
                code,
                positiveLong(values.get("teacherUserId"), "教师ID"),
                integer(values.get("capacity"), "容量", 1, 500),
                CourseSectionStatus.valueOf(values.getOrDefault("status", CourseSectionStatus.OPEN.name())),
                schedules,
                Boolean.parseBoolean(values.getOrDefault("publishSchedule", "true")));
    }

    private List<ScheduleSlot> parseSchedules(Map<String, String> values) {
        List<ScheduleSlot> schedules = new java.util.ArrayList<>();
        String encodedCount = values.get("schedule.count");
        if (encodedCount == null || encodedCount.isBlank()) {
            schedules.add(new ScheduleSlot(
                    integer(values.get("dayOfWeek"), "星期", 1, 7),
                    integer(values.get("startPeriod"), "开始节次", 1, 12),
                    integer(values.get("endPeriod"), "结束节次", 1, 12),
                    integer(values.get("startWeek"), "开始周", 1, 30),
                    integer(values.get("endWeek"), "结束周", 1, 30),
                    required(values, "classroom", "教室")));
        } else {
            int count = integer(encodedCount, "上课时段数量", 1, 30);
            for (int index = 0; index < count; index++) {
                List<String> row = RowCodec.decode(required(
                        values, "schedule." + index, "第 " + (index + 1) + " 个上课时段"));
                if (row.size() != 6) {
                    throw new IllegalArgumentException("上课时段数据格式不正确");
                }
                schedules.add(new ScheduleSlot(
                        integer(row.get(0), "星期", 1, 7),
                        integer(row.get(1), "开始节次", 1, 12),
                        integer(row.get(2), "结束节次", 1, 12),
                        integer(row.get(3), "开始周", 1, 30),
                        integer(row.get(4), "结束周", 1, 30),
                        row.get(5)));
            }
        }
        for (int left = 0; left < schedules.size(); left++) {
            for (int right = left + 1; right < schedules.size(); right++) {
                if (schedules.get(left).overlaps(schedules.get(right))) {
                    throw new IllegalArgumentException("同一教学班的上课时段不能互相重叠");
                }
            }
        }
        return List.copyOf(schedules);
    }

    private List<ScheduleSlot> parseRevisionSlots(Map<String, String> values) {
        int count = integer(values.getOrDefault("slot.count", "0"),
                "上课时段数量", 0, 30);
        List<ScheduleSlot> slots = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<String> row = RowCodec.decode(required(
                    values, "slot." + index, "第 " + (index + 1) + " 个上课时段"));
            if (row.size() != 6) {
                throw new IllegalArgumentException("上课时段数据格式不正确");
            }
            slots.add(new ScheduleSlot(
                    integer(row.get(0), "星期", 1, 7),
                    integer(row.get(1), "开始节次", 1, 12),
                    integer(row.get(2), "结束节次", 1, 12),
                    integer(row.get(3), "开始周", 1, 30),
                    integer(row.get(4), "结束周", 1, 30), row.get(5)));
        }
        return List.copyOf(slots);
    }

    private ResponseMessage scheduleDraftResponse(RequestMessage request, String message,
                                                  ScheduleDraft draft) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("schemaVersion", "2");
        data.put("scheduleRevisionId", Long.toString(draft.revisionId()));
        data.put("sectionId", Long.toString(draft.sectionId()));
        data.put("revisionNo", Integer.toString(draft.revisionNo()));
        data.put("status", "DRAFT");
        data.put("slot.count", Integer.toString(draft.slots().size()));
        for (int index = 0; index < draft.slots().size(); index++) {
            ScheduleSlot slot = draft.slots().get(index);
            data.put("slot." + index, RowCodec.encode(
                    Integer.toString(slot.dayOfWeek()),
                    Integer.toString(slot.startPeriod()),
                    Integer.toString(slot.endPeriod()),
                    Integer.toString(slot.startWeek()),
                    Integer.toString(slot.endWeek()), slot.classroom()));
        }
        return ResponseMessage.success(request.requestId(), message, data);
    }

    private List<SectionTarget> parseSectionTargets(Map<String, String> values) {
        int count = integer(values.getOrDefault("target.count", "0"),
                "目标范围数量", 0, 100);
        List<SectionTarget> targets = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<String> row = RowCodec.decode(required(
                    values, "target." + index, "第 " + (index + 1) + " 个目标范围"));
            if (row.size() != 3) {
                throw new IllegalArgumentException("教学班目标范围数据格式不正确");
            }
            targets.add(new SectionTarget(
                    positiveLong(row.get(0), "专业ID"),
                    integer(row.get(1), "适用起始年份", 1900, 2200),
                    integer(row.get(2), "适用结束年份", 1900, 2200)));
        }
        return List.copyOf(targets);
    }

    private void putAvailableCourses(Map<String, String> data,
                                     List<AvailableCourse> courses) {
        for (int courseIndex = 0; courseIndex < courses.size(); courseIndex++) {
            AvailableCourse course = courses.get(courseIndex);
            String prefix = "course." + courseIndex;
            data.put(prefix, RowCodec.encode(
                    Long.toString(course.courseId()), course.courseCode(), course.courseName(),
                    course.credits().toPlainString(), course.requirementType().name(),
                    Integer.toString(course.recommendedTermNumber())));
            data.put(prefix + ".section.count", Integer.toString(course.sections().size()));
            for (int sectionIndex = 0; sectionIndex < course.sections().size(); sectionIndex++) {
                AvailableSection section = course.sections().get(sectionIndex);
                data.put(prefix + ".section." + sectionIndex, RowCodec.encode(
                        Long.toString(section.sectionId()), Long.toString(section.termId()),
                        section.termName(), section.sectionCode(),
                        Long.toString(section.teacherUserId()), section.teacherName(),
                        Integer.toString(section.capacity()),
                        Integer.toString(section.enrolledCount()), section.status().name(),
                        Boolean.toString(section.gradesPublished()),
                        nullToEmpty(section.scheduleSummary()),
                        nullToEmpty(section.classroomSummary()),
                        section.ownEnrollmentId() == null
                                ? "" : Long.toString(section.ownEnrollmentId()),
                        nullToEmpty(section.ownEnrollmentStatus()),
                        Boolean.toString(section.full()),
                        Boolean.toString(section.scheduleConflict())));
            }
        }
    }

    private void putSections(Map<String, String> data, List<SectionRecord> rows) {
        for (int index = 0; index < rows.size(); index++) {
            SectionRecord row = rows.get(index);
            data.put("row." + index, RowCodec.encode(
                    Long.toString(row.id()), Long.toString(row.termId()), row.termName(),
                    Long.toString(row.courseId()), row.courseCode(), row.courseName(),
                    row.credits().toPlainString(), row.sectionCode(),
                    Long.toString(row.teacherUserId()), row.teacherName(),
                    Integer.toString(row.capacity()), Integer.toString(row.enrolledCount()),
                    row.status().name(), Boolean.toString(row.gradesPublished()),
                    nullToEmpty(row.scheduleSummary()), nullToEmpty(row.classroomSummary()),
                    row.ownEnrollmentId() == null ? "" : Long.toString(row.ownEnrollmentId()),
                    nullToEmpty(row.ownEnrollmentStatus())));
        }
    }

    private void putSchedules(Map<String, String> data, List<ScheduleRecord> rows) {
        for (int index = 0; index < rows.size(); index++) {
            ScheduleRecord row = rows.get(index);
            data.put("row." + index, RowCodec.encode(
                    Long.toString(row.sectionId()), Long.toString(row.termId()), row.termName(),
                    row.courseCode(), row.courseName(), row.sectionCode(), row.teacherName(),
                    Integer.toString(row.dayOfWeek()), Integer.toString(row.startPeriod()),
                    Integer.toString(row.endPeriod()), Integer.toString(row.startWeek()),
                    Integer.toString(row.endWeek()), row.classroom()));
        }
    }

    private Optional<UserSession> authorizedSession(RequestMessage request) {
        return session(request).filter(user -> AcademicAccessPolicy.canStudy(user.roles())
                || AcademicAccessPolicy.canTeach(user.roles())
                || AcademicAccessPolicy.canManage(user.roles()));
    }

    private Optional<UserSession> managerSession(RequestMessage request) {
        return session(request).filter(user -> AcademicAccessPolicy.canManage(user.roles()));
    }

    private Optional<UserSession> studentSession(RequestMessage request) {
        return session(request).filter(user -> AcademicAccessPolicy.canStudy(user.roles()));
    }

    private Optional<UserSession> teachingSession(RequestMessage request) {
        return session(request).filter(user -> AcademicAccessPolicy.canTeach(user.roles()));
    }

    private Optional<UserSession> session(RequestMessage request) {
        return sessions.find(request.parameters().get("sessionToken"));
    }

    private Map<String, String> pageData(int page, int pageSize, int total, int count) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("page", Integer.toString(page));
        data.put("pageSize", Integer.toString(pageSize));
        data.put("total", Integer.toString(total));
        data.put("count", Integer.toString(count));
        return data;
    }

    private String required(Map<String, String> values, String key, String label) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("请填写" + label);
        }
        return value.trim();
    }

    private long positiveLong(String value, String label) {
        long parsed = optionalLong(value);
        if (parsed < 1) {
            throw new IllegalArgumentException(label + "必须大于 0");
        }
        return parsed;
    }

    private long optionalLong(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("ID必须是有效数字");
        }
    }

    private int integer(String value, String label, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(label + "必须在 " + minimum + "—" + maximum + " 之间");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + "必须是数字");
        }
    }

    private BigDecimal decimalRequired(String value, String label) {
        try {
            return new BigDecimal(value == null ? "" : value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + "必须是有效数字");
        }
    }

    private String decimal(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private CurriculumRepository curriculumRepository() {
        if (curricula == null) {
            throw new IllegalStateException("培养方案功能尚未装配");
        }
        return curricula;
    }

    private ScheduleRevisionRepository scheduleRepository() {
        if (schedules == null) {
            throw new IllegalStateException("课表修订功能尚未装配");
        }
        return schedules;
    }

    private ResponseMessage expiredOrForbidden(RequestMessage request) {
        return session(request).isEmpty() ? expired(request) : forbidden(request);
    }

    private ResponseMessage expired(RequestMessage request) {
        return ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录");
    }

    private ResponseMessage forbidden(RequestMessage request) {
        return ResponseMessage.failure(request.requestId(), "没有执行该操作的权限");
    }

    private ResponseMessage invalid(RequestMessage request, RuntimeException exception) {
        return ResponseMessage.failure(request.requestId(), exception.getMessage());
    }

    private ResponseMessage databaseFailure(RequestMessage request, SQLException exception) {
        System.err.println("Academic database error: " + exception.getMessage());
        return ResponseMessage.failure(request.requestId(), "教务数据暂时不可用，请稍后重试");
    }

    @FunctionalInterface
    private interface CurriculumMutation {
        void apply(CurriculumRepository repository, long planId, long operatorId)
                throws SQLException;
    }
}
