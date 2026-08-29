package com.vcampus.server.service;

import com.vcampus.common.model.Gender;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.AccountStore;
import com.vcampus.server.database.AccountStore.AccountPage;
import com.vcampus.server.database.AccountStore.MutationResult;
import com.vcampus.server.database.AuditStore;
import com.vcampus.server.model.AccountSummary;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.PasswordHasher;
import com.vcampus.server.security.PasswordHasher.PasswordHash;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountServiceTest {
    private FakeAccountStore store;
    private FakeAuditStore audit;
    private PasswordHasher hasher;
    private SessionManager sessions;
    private AccountService service;
    private SessionManager.UserSession adminSession;
    private SessionManager.UserSession teacherSession;

    @BeforeEach
    void setUp() {
        store = new FakeAccountStore();
        audit = new FakeAuditStore();
        hasher = new PasswordHasher();
        sessions = new SessionManager();
        service = new AccountService(store, audit, hasher, sessions);
        adminSession = sessions.create(account(1L, "admin", Set.of(UserRole.SUPER_ADMIN)));
        teacherSession = sessions.create(account(2L, "T0000002", Set.of(UserRole.TEACHER)));
    }

    @Test
    void rejectsExpiredAndNonSuperAdministratorSessions() {
        ResponseMessage expired = service.search(request(Actions.ACCOUNT_SEARCH, "missing", Map.of()));
        ResponseMessage forbidden = service.search(request(
                Actions.ACCOUNT_SEARCH, teacherSession.token(), Map.of()));

        assertFalse(expired.success());
        assertEquals("登录已过期，请重新登录", expired.message());
        assertFalse(forbidden.success());
        assertEquals("无权执行此操作", forbidden.message());
        assertEquals(0, store.searchCalls);
    }

    @Test
    void createsStudentWithAllowedAdministrativeRoles() {
        ResponseMessage response = service.create(request(
                Actions.ACCOUNT_CREATE, adminSession.token(), studentValues("LIBRARY_ADMIN,FORUM_ADMIN")));

        assertTrue(response.success());
        assertNotNull(store.studentCommand);
        assertEquals("2026000003", store.studentCommand.studentNumber());
        assertEquals(Set.of(UserRole.STUDENT, UserRole.LIBRARY_ADMIN, UserRole.FORUM_ADMIN),
                store.studentCommand.roles());
        assertTrue(hasher.verify("temporary-123".toCharArray(),
                store.password.hash(), store.password.salt()));
        assertEquals("SUCCESS", audit.result);
    }

    @Test
    void rejectsStudentWithAcademicAdministratorRole() {
        ResponseMessage response = service.create(request(
                Actions.ACCOUNT_CREATE, adminSession.token(), studentValues("ACADEMIC_ADMIN")));

        assertFalse(response.success());
        assertEquals("学生只能兼任图书管理员或论坛管理员", response.message());
        assertEquals(null, store.studentCommand);
    }

    @Test
    void createsTeacherWithAllSixAdministrativeRoles() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("identity", "TEACHER");
        values.put("number", "T0000003");
        values.put("fullName", "赵老师");
        values.put("departmentId", "10");
        values.put("professionalTitle", "讲师");
        values.put("phone", "13900000003");
        values.put("email", "teacher3@vcampus.edu");
        values.put("roles", "STUDENT_ADMIN,ACADEMIC_ADMIN,LIBRARY_ADMIN,SHOP_ADMIN,BANK_ADMIN,FORUM_ADMIN");
        values.put("initialPassword", "temporary-123");

        ResponseMessage response = service.create(request(
                Actions.ACCOUNT_CREATE, adminSession.token(), values));

        assertTrue(response.success());
        assertEquals(7, store.teacherCommand.roles().size());
        assertTrue(store.teacherCommand.roles().contains(UserRole.TEACHER));
    }

    @Test
    void roleStateAndPasswordChangesInvalidateTargetSessions() {
        SessionManager.UserSession target = sessions.create(account(
                50L, "T0000050", Set.of(UserRole.TEACHER)));
        store.summary = new AccountSummary(
                50L, "T0000050", "目标教师", UserRole.TEACHER, Set.of(),
                true, false, null);

        ResponseMessage roles = service.updateRoles(request(
                Actions.ACCOUNT_UPDATE_ROLES, adminSession.token(),
                Map.of("userId", "50", "roles", "BANK_ADMIN")));
        assertTrue(roles.success());
        assertTrue(sessions.find(target.token()).isEmpty());

        SessionManager.UserSession targetAgain = sessions.create(account(
                50L, "T0000050", Set.of(UserRole.TEACHER)));
        ResponseMessage disabled = service.setEnabled(request(
                Actions.ACCOUNT_SET_ENABLED, adminSession.token(),
                Map.of("userId", "50", "enabled", "false")));
        assertTrue(disabled.success());
        assertTrue(sessions.find(targetAgain.token()).isEmpty());

        SessionManager.UserSession targetThird = sessions.create(account(
                50L, "T0000050", Set.of(UserRole.TEACHER)));
        ResponseMessage reset = service.resetPassword(request(
                Actions.ACCOUNT_RESET_PASSWORD, adminSession.token(),
                Map.of("userId", "50", "temporaryPassword", "new-temporary-456")));
        assertTrue(reset.success());
        assertTrue(sessions.find(targetThird.token()).isEmpty());
        assertTrue(hasher.verify("new-temporary-456".toCharArray(),
                store.password.hash(), store.password.salt()));
    }

    @Test
    void unchangedRoleOrEnabledStateDoesNotInvalidateTargetSession() {
        SessionManager.UserSession target = sessions.create(account(
                50L, "T0000050", Set.of(UserRole.TEACHER)));
        store.summary = new AccountSummary(
                50L, "T0000050", "目标教师", UserRole.TEACHER, Set.of(),
                true, false, null);
        store.mutationResult = MutationResult.UNCHANGED;

        ResponseMessage roles = service.updateRoles(request(
                Actions.ACCOUNT_UPDATE_ROLES, adminSession.token(),
                Map.of("userId", "50", "roles", "")));
        ResponseMessage enabled = service.setEnabled(request(
                Actions.ACCOUNT_SET_ENABLED, adminSession.token(),
                Map.of("userId", "50", "enabled", "true")));

        assertTrue(roles.success());
        assertTrue(enabled.success());
        assertTrue(sessions.find(target.token()).isPresent());
    }

    @Test
    void searchEncodesOnlyNonSensitiveAccountFields() {
        store.page = new AccountPage(List.of(new AccountSummary(
                60L, "T0000060", "搜索教师", UserRole.TEACHER,
                Set.of(UserRole.FORUM_ADMIN), true, true,
                Instant.parse("2026-08-26T08:00:00Z"))), 1, 8, 1);

        ResponseMessage response = service.search(request(
                Actions.ACCOUNT_SEARCH, adminSession.token(), Map.of("page", "1")));

        assertTrue(response.success());
        List<String> row = RowCodec.decode(response.data().get("row.0"));
        assertEquals(List.of(
                "60", "T0000060", "搜索教师", "TEACHER", "FORUM_ADMIN",
                "true", "true", "2026-08-26T08:00:00Z"), row);
        assertFalse(response.data().toString().contains("password"));
    }

    private Map<String, String> studentValues(String roles) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("identity", "STUDENT");
        values.put("number", "2026000003");
        values.put("fullName", "赵同学");
        values.put("gender", "FEMALE");
        values.put("birthDate", "2007-03-04");
        values.put("departmentId", "10");
        values.put("majorId", "20");
        values.put("classId", "100");
        values.put("enrollmentYear", "2026");
        values.put("phone", "13800000003");
        values.put("email", "student3@vcampus.edu");
        values.put("address", "东南路3号");
        values.put("roles", roles);
        values.put("initialPassword", "temporary-123");
        return values;
    }

    private RequestMessage request(String action, String token, Map<String, String> values) {
        Map<String, String> parameters = new LinkedHashMap<>(values);
        parameters.put("sessionToken", token);
        return RequestMessage.create(action, parameters);
    }

    private UserAccount account(long id, String username, Set<UserRole> roles) {
        return new UserAccount(id, username, "hash", "salt", username, true, false, roles);
    }

    private static final class FakeAccountStore implements AccountStore {
        private int searchCalls;
        private AccountPage page = new AccountPage(List.of(), 1, 8, 0);
        private CreateStudentAccount studentCommand;
        private CreateTeacherAccount teacherCommand;
        private PasswordHash password;
        private AccountSummary summary;
        private MutationResult mutationResult = MutationResult.CHANGED;

        @Override
        public AccountPage search(String keyword, UserRole identity, Boolean enabled, int page, int pageSize) {
            searchCalls++;
            return this.page;
        }

        @Override
        public AccountReferences referenceData() {
            return new AccountReferences(List.of(), List.of(), List.of());
        }

        @Override
        public long createStudent(CreateStudentAccount command, PasswordHash password, long operatorUserId) {
            studentCommand = command;
            this.password = password;
            return 101L;
        }

        @Override
        public long createTeacher(CreateTeacherAccount command, PasswordHash password) {
            teacherCommand = command;
            this.password = password;
            return 102L;
        }

        @Override
        public Optional<AccountSummary> findManageableById(long userId) {
            return Optional.ofNullable(summary);
        }

        @Override
        public MutationResult replaceAdministrativeRoles(
                long userId, UserRole baseIdentity, Set<UserRole> roles,
                long operatorUserId, String operatorDisplayName) {
            return summary != null && summary.userId() == userId
                    ? mutationResult : MutationResult.NOT_FOUND;
        }

        @Override
        public MutationResult setEnabled(
                long userId, boolean enabled,
                long operatorUserId, String operatorDisplayName) {
            return summary != null && summary.userId() == userId
                    ? mutationResult : MutationResult.NOT_FOUND;
        }

        @Override
        public MutationResult resetPassword(
                long userId, PasswordHash password,
                long operatorUserId, String operatorDisplayName) {
            this.password = password;
            return summary != null && summary.userId() == userId
                    ? MutationResult.CHANGED : MutationResult.NOT_FOUND;
        }
    }

    private static final class FakeAuditStore implements AuditStore {
        private String result;

        @Override
        public void record(Long userId, String action, String result, String clientAddress) {
            this.result = result;
        }
    }
}
