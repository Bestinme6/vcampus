package com.vcampus.server.service;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.server.database.TeacherProfileStore;
import com.vcampus.server.model.TeacherProfile;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class TeacherProfileService {
    private final TeacherProfileStore teachers;
    private final SessionManager sessions;

    public TeacherProfileService(TeacherProfileStore teachers, SessionManager sessions) {
        this.teachers = teachers;
        this.sessions = sessions;
    }

    public ResponseMessage getSelf(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        if (!session.get().roles().contains(UserRole.TEACHER)) {
            return forbidden(request);
        }
        try {
            return teachers.findByUserId(session.get().userId())
                    .map(profile -> profileResponse(request, profile))
                    .orElseGet(() -> ResponseMessage.failure(
                            request.requestId(), "未找到教师档案，请联系管理员"));
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage updateContact(RequestMessage request) {
        Optional<UserSession> session = session(request);
        if (session.isEmpty()) {
            return expired(request);
        }
        if (!session.get().roles().contains(UserRole.TEACHER)) {
            return forbidden(request);
        }
        try {
            String phone = trimToNull(request.parameters().get("phone"));
            String email = trimToNull(request.parameters().get("email"));
            validateContact(phone, email);
            if (!teachers.updateContact(session.get().userId(), phone, email)) {
                return ResponseMessage.failure(request.requestId(), "未找到教师档案，请联系管理员");
            }
            return ResponseMessage.success(request.requestId(), "联系方式已更新", Map.of());
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    private ResponseMessage profileResponse(RequestMessage request, TeacherProfile profile) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("teacherNumber", profile.teacherNumber());
        data.put("fullName", profile.fullName());
        data.put("departmentName", profile.departmentName());
        data.put("professionalTitle", profile.professionalTitle());
        data.put("phone", profile.phone() == null ? "" : profile.phone());
        data.put("email", profile.email() == null ? "" : profile.email());
        return ResponseMessage.success(request.requestId(), "查询成功", data);
    }

    private void validateContact(String phone, String email) {
        if (phone != null && phone.length() > 32) {
            throw new IllegalArgumentException("电话号码长度不能超过32位");
        }
        if (email != null && (email.length() > 128
                || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private Optional<UserSession> session(RequestMessage request) {
        return sessions.find(request.parameters().get("sessionToken"));
    }

    private ResponseMessage expired(RequestMessage request) {
        return ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录");
    }

    private ResponseMessage forbidden(RequestMessage request) {
        return ResponseMessage.failure(request.requestId(), "无权执行此操作");
    }

    private ResponseMessage databaseFailure(RequestMessage request, SQLException exception) {
        System.err.println("Teacher profile database error: " + exception.getMessage());
        return ResponseMessage.failure(request.requestId(), "数据库暂时不可用，请稍后重试");
    }
}
