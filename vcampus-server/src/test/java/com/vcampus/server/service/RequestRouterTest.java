package com.vcampus.server.service;

import com.vcampus.common.model.Gender;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.server.database.AccountStore;
import com.vcampus.server.database.AuditStore;
import com.vcampus.server.database.NotificationStore;
import com.vcampus.server.database.AuditQueryStore;
import com.vcampus.server.database.SystemManagementStore;
import com.vcampus.server.model.AccountSummary;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.PasswordHasher;
import com.vcampus.server.security.PasswordHasher.PasswordHash;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestRouterTest {
    @Test
    void routesSystemConfigurationAndHandlesUnavailableService() {
        SessionManager sessions = new SessionManager();
        SessionManager.UserSession admin = sessions.create(new UserAccount(
                1L, "admin", "hash", "salt", "系统管理员",
                true, false, Set.of(UserRole.SUPER_ADMIN)));
        CountingSystemStore store = new CountingSystemStore();
        SystemManagementService system = new SystemManagementService(
                store, query -> new AuditQueryStore.AuditPage(List.of(), 1, 20, 0),
                new NoopAuditStore(), sessions);
        RequestRouter router = new RequestRouter(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, sessions, null, system);

        ResponseMessage response = router.route(
                authorized(Actions.SYSTEM_CONFIG_GET, admin.token()), "127.0.0.1");

        assertTrue(response.success());
        assertEquals(1, store.configurationCalls);

        RequestRouter unavailable = new RequestRouter(
                null, null, null, null, null, null, null, null, sessions);
        ResponseMessage failure = unavailable.route(
                authorized(Actions.SYSTEM_CONFIG_GET, admin.token()), "127.0.0.1");
        assertEquals("系统管理暂不可用", failure.message());
    }
    @Test
    void forcedPasswordSessionCannotReachBusinessServices() {
        SessionManager sessions = new SessionManager();
        SessionManager.UserSession forced = sessions.create(new UserAccount(
                71L, "T0000071", "hash", "salt", "待改密教师",
                true, true, Set.of(UserRole.TEACHER)));
        AccountService accounts = new AccountService(
                new EmptyAccountStore(), new NoopAuditStore(), new PasswordHasher(), sessions);
        EmptyNotificationStore notificationStore = new EmptyNotificationStore();
        NotificationService notifications = new NotificationService(notificationStore, sessions);
        RequestRouter router = new RequestRouter(
                null, null, null, null, accounts, notifications, null, null, sessions);

        ResponseMessage response = router.route(
                authorized(Actions.NOTIFICATION_SEARCH, forced.token()), "127.0.0.1");

        assertFalse(response.success());
        assertEquals("请先修改初始密码", response.message());
        assertEquals(0, notificationStore.searchCalls);
    }

    @Test
    void routesAccountSearchToAccountService() {
        SessionManager sessions = new SessionManager();
        SessionManager.UserSession admin = sessions.create(new UserAccount(
                1L, "admin", "hash", "salt", "系统管理员",
                true, false, Set.of(UserRole.SUPER_ADMIN)));
        EmptyAccountStore store = new EmptyAccountStore();
        AccountService accounts = new AccountService(
                store, new NoopAuditStore(), new PasswordHasher(), sessions);
        NotificationService notifications = new NotificationService(
                new EmptyNotificationStore(), sessions);
        RequestRouter router = new RequestRouter(
                null, null, null, null, accounts, notifications, null, null, sessions);

        ResponseMessage response = router.route(
                authorized(Actions.ACCOUNT_SEARCH, admin.token()), "127.0.0.1");

        assertTrue(response.success());
        assertEquals(1, store.searchCalls);
    }

    @Test
    void routesNotificationSearchToNotificationService() {
        SessionManager sessions = new SessionManager();
        SessionManager.UserSession student = sessions.create(new UserAccount(
                2L, "2026000002", "hash", "salt", "学生",
                true, false, Set.of(UserRole.STUDENT)));
        EmptyNotificationStore store = new EmptyNotificationStore();
        NotificationService notifications = new NotificationService(store, sessions);
        RequestRouter router = new RequestRouter(
                null, null, null, null, null, notifications, null, null, sessions);

        ResponseMessage response = router.route(
                authorized(Actions.NOTIFICATION_SEARCH, student.token()), "127.0.0.1");

        assertTrue(response.success());
        assertEquals(1, store.searchCalls);
        assertEquals(2L, store.lastRecipientUserId);
    }

    @Test
    void routesNotificationDeleteToNotificationService() {
        SessionManager sessions = new SessionManager();
        SessionManager.UserSession student = sessions.create(new UserAccount(
                2L, "2026000002", "hash", "salt", "学生",
                true, false, Set.of(UserRole.STUDENT)));
        EmptyNotificationStore store = new EmptyNotificationStore();
        store.deleteResult = true;
        NotificationService notifications = new NotificationService(store, sessions);
        RequestRouter router = new RequestRouter(
                null, null, null, null, null, notifications, null, null, sessions);
        Map<String,String> values = new LinkedHashMap<>();
        values.put("sessionToken", student.token());
        values.put("notificationId", "17");

        ResponseMessage response = router.route(
                RequestMessage.create(Actions.NOTIFICATION_DELETE, values), "127.0.0.1");

        assertTrue(response.success());
        assertEquals(1, store.deleteCalls);
        assertEquals(2L, store.lastRecipientUserId);
    }

    @Test
    void routesNotificationMarkUnreadToNotificationService() {
        SessionManager sessions = new SessionManager();
        SessionManager.UserSession student = sessions.create(new UserAccount(
                2L, "2026000002", "hash", "salt", "学生",
                true, false, Set.of(UserRole.STUDENT)));
        EmptyNotificationStore store = new EmptyNotificationStore();
        store.markUnreadResult = true;
        NotificationService notifications = new NotificationService(store, sessions);
        RequestRouter router = new RequestRouter(
                null, null, null, null, null, notifications, null, null, sessions);
        Map<String,String> values = new LinkedHashMap<>();
        values.put("sessionToken", student.token());
        values.put("notificationId", "17");

        ResponseMessage response = router.route(
                RequestMessage.create(Actions.NOTIFICATION_MARK_UNREAD, values), "127.0.0.1");

        assertTrue(response.success());
        assertEquals(1, store.markUnreadCalls);
        assertEquals(2L, store.lastRecipientUserId);
    }

    @Test
    void routesSecurityOverviewToTheAuthenticatedService() {
        SessionManager sessions = new SessionManager();
        SessionManager.UserSession student = sessions.create(new UserAccount(
                2L, "2026000002", "hash", "salt", "演示学生",
                true, false, Set.of(UserRole.STUDENT)));
        AuthService auth = new AuthService(null, new NoopAuditStore(), null, sessions,
                (userId, limit) -> List.of());
        RequestRouter router = new RequestRouter(
                auth, null, null, null, null, null, null, null, sessions);

        ResponseMessage response = router.route(
                authorized(Actions.AUTH_SECURITY_OVERVIEW, student.token()), "127.0.0.1");

        assertTrue(response.success());
        assertEquals("2026000002", response.data().get("username"));
    }

    private RequestMessage authorized(String action, String token) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("sessionToken", token);
        return RequestMessage.create(action, parameters);
    }

    private static final class EmptyAccountStore implements AccountStore {
        private int searchCalls;

        @Override
        public AccountPage search(String keyword, UserRole identity, Boolean enabled, int page, int pageSize) {
            searchCalls++;
            return new AccountPage(List.of(), page, pageSize, 0);
        }

        @Override public AccountReferences referenceData() { return new AccountReferences(List.of(), List.of(), List.of()); }
        @Override public long createStudent(CreateStudentAccount command, PasswordHash password, long operatorUserId) { return 0; }
        @Override public long createTeacher(CreateTeacherAccount command, PasswordHash password) { return 0; }
        @Override public Optional<AccountSummary> findManageableById(long userId) { return Optional.empty(); }
        @Override public MutationResult replaceAdministrativeRoles(long userId, UserRole baseIdentity, Set<UserRole> roles, long operatorUserId, String operatorDisplayName) { return MutationResult.NOT_FOUND; }
        @Override public MutationResult setEnabled(long userId, boolean enabled, long operatorUserId, String operatorDisplayName) { return MutationResult.NOT_FOUND; }
        @Override public MutationResult resetPassword(long userId, PasswordHash password, long operatorUserId, String operatorDisplayName) { return MutationResult.NOT_FOUND; }
    }

    private static final class NoopAuditStore implements AuditStore {
        @Override
        public void record(Long userId, String action, String result, String clientAddress) {
        }
    }

    private static final class CountingSystemStore implements SystemManagementStore {
        private int configurationCalls;

        @Override
        public Snapshot configuration() {
            configurationCalls++;
            return new Snapshot(List.of(), List.of());
        }

        @Override public boolean updateTerm(com.vcampus.common.model.SystemTermConfig term, long operatorUserId) { return false; }
        @Override public void updatePeriods(List<com.vcampus.common.model.ClassPeriodTime> periods, long operatorUserId) { }
    }

    private static final class EmptyNotificationStore implements NotificationStore {
        private int searchCalls;
        private long lastRecipientUserId;
        private int deleteCalls;
        private boolean deleteResult;
        private int markUnreadCalls;
        private boolean markUnreadResult;

        @Override
        public NotificationPage search(long recipientUserId, NotificationQuery query) {
            searchCalls++;
            lastRecipientUserId = recipientUserId;
            return new NotificationPage(List.of(), query.page(), query.pageSize(), 0);
        }

        @Override
        public Optional<com.vcampus.server.model.NotificationRecord> findOwned(
                long recipientUserId, long notificationId) {
            return Optional.empty();
        }

        @Override
        public int unreadCount(long recipientUserId) {
            return 0;
        }

        @Override
        public boolean markRead(long recipientUserId, long notificationId) {
            return false;
        }

        @Override
        public boolean markUnread(long recipientUserId, long notificationId) {
            markUnreadCalls++;
            lastRecipientUserId = recipientUserId;
            return markUnreadResult;
        }

        @Override
        public int markAllRead(long recipientUserId) {
            return 0;
        }

        @Override
        public boolean delete(long recipientUserId, long notificationId) {
            deleteCalls++;
            lastRecipientUserId = recipientUserId;
            return deleteResult;
        }
    }
}
