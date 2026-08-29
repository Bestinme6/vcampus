package com.vcampus.server.service;

import com.vcampus.common.model.Gender;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.server.database.AccountStore;
import com.vcampus.server.database.AuditStore;
import com.vcampus.server.database.NotificationStore;
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

    private static final class EmptyNotificationStore implements NotificationStore {
        private int searchCalls;
        private long lastRecipientUserId;

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
        public int markAllRead(long recipientUserId) {
            return 0;
        }
    }
}
