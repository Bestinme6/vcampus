package com.vcampus.server.service;

import com.vcampus.common.model.Gender;
import com.vcampus.common.model.StudentAccessPolicy;
import com.vcampus.common.model.StudentStatus;
import com.vcampus.common.model.StudentStatusPolicy;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.server.database.StudentRepository;
import com.vcampus.server.database.StudentRepository.UpdateStudent;
import com.vcampus.server.database.StudentRepository.StudentPage;
import com.vcampus.server.database.StudentRepository.AcademicReferences;
import com.vcampus.server.database.StudentRepository.ReferenceItem;
import com.vcampus.server.model.StudentProfile;
import com.vcampus.server.model.StudentStatusRecord;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class StudentService {
    private static final int PAGE_SIZE = 8;
    private final StudentRepository students;
    private final SessionManager sessions;

    public StudentService(
            StudentRepository students,
            SessionManager sessions) {
        this.students = students;
        this.sessions = sessions;
    }

    public ResponseMessage getSelf(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        if (!StudentAccessPolicy.canUseSelfService(session.get().roles())) {
            return forbidden(request);
        }
        try {
            return students.findByUserId(session.get().userId())
                    .map(profile -> profileResponse(request, profile))
                    .orElseGet(() -> ResponseMessage.failure(request.requestId(), "当前账号没有关联学生档案"));
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage search(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        if (!isStudentAdmin(session.get())) {
            return forbidden(request);
        }
        try {
            int page = parseInteger(request.parameters().getOrDefault("page", "1"), "页码", 1, 100_000);
            StudentStatus status = parseOptionalStatus(request.parameters().get("status"));
            StudentPage result = students.search(
                    request.parameters().getOrDefault("keyword", ""), status, page, PAGE_SIZE);
            Map<String, String> data = new LinkedHashMap<>();
            data.put("page", Integer.toString(result.page()));
            data.put("pageSize", Integer.toString(result.pageSize()));
            data.put("total", Integer.toString(result.total()));
            data.put("count", Integer.toString(result.rows().size()));
            for (int index = 0; index < result.rows().size(); index++) {
                putListProfile(data, "row." + index + ".", result.rows().get(index));
            }
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage get(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        if (!isStudentAdmin(session.get())) {
            return forbidden(request);
        }
        try {
            long studentId = parseLong(request.parameters().get("studentId"), "学生ID");
            return students.findById(studentId)
                    .map(profile -> profileResponse(request, profile))
                    .orElseGet(() -> ResponseMessage.failure(request.requestId(), "学生档案不存在"));
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage updateContact(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        UserSession user = session.get();
        boolean admin = isStudentAdmin(user);
        if (!admin && !user.roles().contains(UserRole.STUDENT)) {
            return forbidden(request);
        }
        try {
            long studentId;
            if (admin && hasText(request.parameters().get("studentId"))) {
                studentId = parseLong(request.parameters().get("studentId"), "学生ID");
            } else {
                Optional<StudentProfile> ownProfile = students.findByUserId(user.userId());
                if (ownProfile.isEmpty()) {
                    return ResponseMessage.failure(request.requestId(), "当前账号没有关联学生档案");
                }
                studentId = ownProfile.get().id();
            }
            String phone = trimToNull(request.parameters().get("phone"));
            String email = trimToNull(request.parameters().get("email"));
            String address = trimToNull(request.parameters().get("address"));
            validateContact(phone, email, address);
            if (!students.updateContact(studentId, phone, email, address)) {
                return ResponseMessage.failure(request.requestId(), "学生档案不存在");
            }
            return ResponseMessage.success(request.requestId(), "联系方式已更新", Map.of());
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage update(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        if (!isStudentAdmin(session.get())) {
            return forbidden(request);
        }
        try {
            UpdateStudent command = parseUpdate(request.parameters());
            if (!students.updateStudent(command)) {
                return ResponseMessage.failure(request.requestId(), "学生档案不存在");
            }
            return ResponseMessage.success(request.requestId(), "学生档案已更新", Map.of());
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage changeStatus(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        if (!isStudentAdmin(session.get())) {
            return forbidden(request);
        }
        try {
            long studentId = parseLong(request.parameters().get("studentId"), "学生ID");
            StudentStatus newStatus = StudentStatus.valueOf(required(request.parameters(), "newStatus", "新学籍状态"));
            String reason = required(request.parameters(), "reason", "变更原因");
            if (reason.length() < 2 || reason.length() > 255) {
                throw new IllegalArgumentException("变更原因长度必须为 2—255 位");
            }
            StudentProfile profile = students.findById(studentId)
                    .orElseThrow(() -> new IllegalArgumentException("学生档案不存在"));
            if (!StudentStatusPolicy.canTransition(profile.status(), newStatus)) {
                throw new IllegalArgumentException(
                        "不能从“" + profile.status().displayName() + "”变更为“" + newStatus.displayName() + "”");
            }
            StudentStatus oldStatus = students.changeStatus(
                    studentId, newStatus, reason,
                    session.get().userId(), session.get().displayName());
            return ResponseMessage.success(
                    request.requestId(),
                    oldStatus == newStatus ? "学籍状态没有变化" : "学籍状态变更成功",
                    Map.of("oldStatus", oldStatus.name(), "newStatus", newStatus.name()));
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage statusHistory(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        UserSession user = session.get();
        try {
            long studentId;
            if (isStudentAdmin(user) && hasText(request.parameters().get("studentId"))) {
                studentId = parseLong(request.parameters().get("studentId"), "学生ID");
            } else if (user.roles().contains(UserRole.STUDENT)) {
                Optional<StudentProfile> ownProfile = students.findByUserId(user.userId());
                if (ownProfile.isEmpty()) {
                    return ResponseMessage.failure(request.requestId(), "当前账号没有关联学生档案");
                }
                studentId = ownProfile.get().id();
            } else {
                return forbidden(request);
            }
            List<StudentStatusRecord> history = students.statusHistory(studentId);
            Map<String, String> data = new LinkedHashMap<>();
            data.put("count", Integer.toString(history.size()));
            for (int index = 0; index < history.size(); index++) {
                StudentStatusRecord record = history.get(index);
                String prefix = "row." + index + ".";
                data.put(prefix + "oldStatus", record.oldStatus() == null ? "" : record.oldStatus().name());
                data.put(prefix + "newStatus", record.newStatus().name());
                data.put(prefix + "reason", record.reason());
                data.put(prefix + "operator", record.operatorName());
                data.put(prefix + "changedAt", record.changedAt().toString());
            }
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage referenceData(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        if (!isStudentAdmin(session.get())) {
            return forbidden(request);
        }
        try {
            AcademicReferences references = students.academicReferences();
            Map<String, String> data = new LinkedHashMap<>();
            putReferences(data, "department", references.departments());
            putReferences(data, "major", references.majors());
            putReferences(data, "class", references.classes());
            return ResponseMessage.success(request.requestId(), "基础数据加载成功", data);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    private Optional<UserSession> session(RequestMessage request) {
        return sessions.find(request.parameters().get("sessionToken"));
    }

    private boolean isStudentAdmin(UserSession session) {
        return StudentAccessPolicy.canManageStudents(session.roles());
    }

    private ResponseMessage profileResponse(RequestMessage request, StudentProfile profile) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("id", Long.toString(profile.id()));
        data.put("studentNumber", profile.studentNumber());
        data.put("fullName", profile.fullName());
        data.put("gender", profile.gender().name());
        data.put("birthDate", profile.birthDate() == null ? "" : profile.birthDate().toString());
        data.put("departmentId", Long.toString(profile.departmentId()));
        data.put("departmentName", profile.departmentName());
        data.put("majorId", Long.toString(profile.majorId()));
        data.put("majorName", profile.majorName());
        data.put("classId", Long.toString(profile.classId()));
        data.put("className", profile.className());
        data.put("enrollmentYear", Integer.toString(profile.enrollmentYear()));
        data.put("status", profile.status().name());
        data.put("phone", nullToEmpty(profile.phone()));
        data.put("email", nullToEmpty(profile.email()));
        data.put("address", nullToEmpty(profile.address()));
        return ResponseMessage.success(request.requestId(), "查询成功", data);
    }

    private void putListProfile(Map<String, String> data, String prefix, StudentProfile profile) {
        data.put(prefix + "id", Long.toString(profile.id()));
        data.put(prefix + "studentNumber", profile.studentNumber());
        data.put(prefix + "fullName", profile.fullName());
        data.put(prefix + "gender", profile.gender().name());
        data.put(prefix + "departmentName", profile.departmentName());
        data.put(prefix + "majorName", profile.majorName());
        data.put(prefix + "className", profile.className());
        data.put(prefix + "enrollmentYear", Integer.toString(profile.enrollmentYear()));
        data.put(prefix + "status", profile.status().name());
        data.put(prefix + "phone", nullToEmpty(profile.phone()));
        data.put(prefix + "email", nullToEmpty(profile.email()));
    }

    private void putReferences(Map<String, String> data, String type, List<ReferenceItem> items) {
        data.put(type + ".count", Integer.toString(items.size()));
        for (int index = 0; index < items.size(); index++) {
            ReferenceItem item = items.get(index);
            String prefix = type + "." + index + ".";
            data.put(prefix + "id", Long.toString(item.id()));
            data.put(prefix + "parentId", Long.toString(item.parentId()));
            data.put(prefix + "code", item.code());
            data.put(prefix + "name", item.name());
            data.put(prefix + "year", Integer.toString(item.year()));
        }
    }

    private UpdateStudent parseUpdate(Map<String, String> values) {
        String fullName = required(values, "fullName", "姓名");
        if (fullName.length() > 100) {
            throw new IllegalArgumentException("姓名不能超过 100 位");
        }
        Gender gender = Gender.valueOf(values.getOrDefault("gender", Gender.UNSPECIFIED.name()));
        LocalDate birthDate = parseOptionalDate(values.get("birthDate"));
        int enrollmentYear = parseInteger(values.get("enrollmentYear"), "入学年份", 2000, 2100);
        String phone = trimToNull(values.get("phone"));
        String email = trimToNull(values.get("email"));
        String address = trimToNull(values.get("address"));
        validateContact(phone, email, address);
        return new UpdateStudent(
                parseLong(values.get("studentId"), "学生ID"),
                fullName,
                gender,
                birthDate,
                parseLong(values.get("departmentId"), "学院ID"),
                parseLong(values.get("majorId"), "专业ID"),
                parseLong(values.get("classId"), "班级ID"),
                enrollmentYear,
                phone,
                email,
                address);
    }

    private StudentStatus parseOptionalStatus(String value) {
        return hasText(value) ? StudentStatus.valueOf(value) : null;
    }

    private LocalDate parseOptionalDate(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("出生日期格式必须为 yyyy-MM-dd");
        }
    }

    private int parseInteger(String value, String label, int minimum, int maximum) {
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

    private long parseLong(String value, String label) {
        try {
            long parsed = Long.parseLong(value == null ? "" : value.trim());
            if (parsed < 1) {
                throw new IllegalArgumentException(label + "必须大于 0");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + "必须是有效数字");
        }
    }

    private String required(Map<String, String> values, String key, String label) {
        String value = values.get(key);
        if (!hasText(value)) {
            throw new IllegalArgumentException("请填写" + label);
        }
        return value.trim();
    }

    private void validateContact(String phone, String email, String address) {
        if (phone != null && (phone.length() > 32 || !phone.matches("[0-9+() -]+"))) {
            throw new IllegalArgumentException("手机号格式不正确");
        }
        if (email != null && (email.length() > 128 || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        if (address != null && address.length() > 255) {
            throw new IllegalArgumentException("地址不能超过 255 位");
        }
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private ResponseMessage expired(RequestMessage request) {
        return ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录");
    }

    private ResponseMessage forbidden(RequestMessage request) {
        return ResponseMessage.failure(request.requestId(), "没有执行该操作的权限");
    }

    private ResponseMessage databaseFailure(RequestMessage request, SQLException exception) {
        System.err.println("Student database error: " + exception.getMessage());
        return ResponseMessage.failure(request.requestId(), "学籍数据暂时不可用，请稍后重试");
    }
}
