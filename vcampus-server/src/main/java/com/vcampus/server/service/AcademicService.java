package com.vcampus.server.service;

import com.vcampus.common.model.AcademicAccessPolicy;
import com.vcampus.common.model.CourseSectionStatus;
import com.vcampus.common.model.ScheduleSlot;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.AcademicRepository;
import com.vcampus.server.database.AcademicRepository.AcademicReferences;
import com.vcampus.server.database.AcademicRepository.AcademicRuleException;
import com.vcampus.server.database.AcademicRepository.CoursePage;
import com.vcampus.server.database.AcademicRepository.CourseRecord;
import com.vcampus.server.database.AcademicRepository.CreateCourse;
import com.vcampus.server.database.AcademicRepository.CreateSection;
import com.vcampus.server.database.AcademicRepository.UpdateCourse;
import com.vcampus.server.database.AcademicRepository.GradeRecord;
import com.vcampus.server.database.AcademicRepository.RosterRecord;
import com.vcampus.server.database.AcademicRepository.SectionPage;
import com.vcampus.server.database.AcademicRepository.SectionRecord;
import com.vcampus.server.database.AcademicRepository.ScheduleRecord;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;

import java.math.BigDecimal;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class AcademicService {
    private static final int PAGE_SIZE = 8;

    private final AcademicRepository academic;
    private final SessionManager sessions;

    public AcademicService(AcademicRepository academic, SessionManager sessions) {
        this.academic = academic;
        this.sessions = sessions;
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
                schedules);
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
}
