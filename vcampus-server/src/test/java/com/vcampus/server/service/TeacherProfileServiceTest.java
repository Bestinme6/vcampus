package com.vcampus.server.service;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.server.database.TeacherProfileStore;
import com.vcampus.server.model.TeacherProfile;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeacherProfileServiceTest {
    private SessionManager sessions;
    private FakeTeacherProfileStore store;
    private TeacherProfileService service;
    private UserSession teacherSession;
    private UserSession studentSession;

    @BeforeEach
    void setUp() {
        sessions = new SessionManager();
        store = new FakeTeacherProfileStore();
        service = new TeacherProfileService(store, sessions);
        teacherSession = sessions.create(account(11L, "T20260001", UserRole.TEACHER));
        studentSession = sessions.create(account(12L, "2026000001", UserRole.STUDENT));
    }

    @Test
    void teacherReadsOwnProfile() {
        store.profile = new TeacherProfile(
                1L, 11L, "T20260001", "李老师", "计算机科学与工程学院",
                "讲师", "13900000000", "teacher@vcampus.edu");

        ResponseMessage response = service.getSelf(request(
                Actions.TEACHER_PROFILE_GET_SELF, teacherSession.token(), Map.of()));

        assertTrue(response.success());
        assertEquals("T20260001", response.data().get("teacherNumber"));
        assertEquals("李老师", response.data().get("fullName"));
        assertEquals("计算机科学与工程学院", response.data().get("departmentName"));
        assertEquals("讲师", response.data().get("professionalTitle"));
        assertEquals(11L, store.lastLookupUserId);
    }

    @Test
    void studentCannotReadTeacherProfile() {
        ResponseMessage response = service.getSelf(request(
                Actions.TEACHER_PROFILE_GET_SELF, studentSession.token(), Map.of()));

        assertFalse(response.success());
        assertEquals("无权执行此操作", response.message());
    }

    @Test
    void teacherUpdatesOnlyOwnPhoneAndEmail() {
        ResponseMessage response = service.updateContact(request(
                Actions.TEACHER_PROFILE_UPDATE_CONTACT,
                teacherSession.token(),
                Map.of("phone", " 13800000000 ", "email", " teacher@vcampus.edu ")));

        assertTrue(response.success());
        assertEquals(11L, store.lastUpdatedUserId);
        assertEquals("13800000000", store.lastPhone);
        assertEquals("teacher@vcampus.edu", store.lastEmail);
    }

    @Test
    void invalidEmailIsRejectedBeforeRepositoryUpdate() {
        ResponseMessage response = service.updateContact(request(
                Actions.TEACHER_PROFILE_UPDATE_CONTACT,
                teacherSession.token(),
                Map.of("phone", "13800000000", "email", "invalid-email")));

        assertFalse(response.success());
        assertEquals("邮箱格式不正确", response.message());
        assertEquals(0L, store.lastUpdatedUserId);
    }

    @Test
    void missingTeacherProfileGetsClearMessage() {
        ResponseMessage response = service.getSelf(request(
                Actions.TEACHER_PROFILE_GET_SELF, teacherSession.token(), Map.of()));

        assertFalse(response.success());
        assertEquals("未找到教师档案，请联系管理员", response.message());
    }

    private RequestMessage request(String action, String token, Map<String, String> values) {
        java.util.LinkedHashMap<String, String> parameters = new java.util.LinkedHashMap<>(values);
        parameters.put("sessionToken", token);
        return RequestMessage.create(action, parameters);
    }

    private UserAccount account(long id, String username, UserRole role) {
        return new UserAccount(id, username, "hash", "salt", username, true, false, Set.of(role));
    }

    private static final class FakeTeacherProfileStore implements TeacherProfileStore {
        private TeacherProfile profile;
        private long lastLookupUserId;
        private long lastUpdatedUserId;
        private String lastPhone;
        private String lastEmail;

        @Override
        public Optional<TeacherProfile> findByUserId(long userId) throws SQLException {
            lastLookupUserId = userId;
            return Optional.ofNullable(profile);
        }

        @Override
        public boolean updateContact(long userId, String phone, String email) throws SQLException {
            lastUpdatedUserId = userId;
            lastPhone = phone;
            lastEmail = email;
            return true;
        }
    }
}
