package com.vcampus.server.service;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.server.database.AuditStore;
import com.vcampus.server.database.UserAccountStore;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.PasswordHasher;
import com.vcampus.server.security.PasswordHasher.PasswordHash;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {
    private static final String TEMPORARY_PASSWORD = "temporary-123";
    private static final String NEW_PASSWORD = "new-password-456";

    private PasswordHasher hasher;
    private FakeUserStore users;
    private FakeAuditStore audit;
    private SessionManager sessions;
    private AuthService service;
    private SessionManager.UserSession session;

    @BeforeEach
    void setUp() {
        hasher = new PasswordHasher();
        PasswordHash initial = hasher.hash(TEMPORARY_PASSWORD.toCharArray());
        users = new FakeUserStore(new UserAccount(
                31L, "T0000031", initial.hash(), initial.salt(), "测试教师",
                true, true, Set.of(UserRole.TEACHER)));
        audit = new FakeAuditStore();
        sessions = new SessionManager();
        session = sessions.create(users.account);
        service = new AuthService(users, audit, hasher, sessions);
    }

    @Test
    void changesPasswordAndCompletesCurrentForcedSession() {
        ResponseMessage response = service.changePassword(
                request(session.token(), TEMPORARY_PASSWORD, NEW_PASSWORD), "127.0.0.1");

        assertTrue(response.success());
        assertFalse(sessions.requiresPasswordChange(session.token()));
        assertFalse(hasher.verify(TEMPORARY_PASSWORD.toCharArray(),
                users.account.passwordHash(), users.account.passwordSalt()));
        assertTrue(hasher.verify(NEW_PASSWORD.toCharArray(),
                users.account.passwordHash(), users.account.passwordSalt()));
        assertEquals("SUCCESS", audit.result);
        assertEquals(Actions.AUTH_CHANGE_PASSWORD, audit.action);
    }

    @Test
    void rejectsWrongCurrentPasswordWithoutChangingStoredPassword() {
        ResponseMessage response = service.changePassword(
                request(session.token(), "incorrect-password", NEW_PASSWORD), "127.0.0.1");

        assertFalse(response.success());
        assertEquals("当前密码错误", response.message());
        assertTrue(hasher.verify(TEMPORARY_PASSWORD.toCharArray(),
                users.account.passwordHash(), users.account.passwordSalt()));
        assertTrue(sessions.requiresPasswordChange(session.token()));
    }

    @Test
    void rejectsNewPasswordThatEqualsCurrentPassword() {
        ResponseMessage response = service.changePassword(
                request(session.token(), TEMPORARY_PASSWORD, TEMPORARY_PASSWORD), "127.0.0.1");

        assertFalse(response.success());
        assertEquals("新密码不能与当前密码相同", response.message());
        assertTrue(sessions.requiresPasswordChange(session.token()));
    }

    @Test
    void rejectsExpiredSessionAndInvalidNewPasswordLength() {
        ResponseMessage expired = service.changePassword(
                request("missing-token", TEMPORARY_PASSWORD, NEW_PASSWORD), "127.0.0.1");
        ResponseMessage shortPassword = service.changePassword(
                request(session.token(), TEMPORARY_PASSWORD, "short"), "127.0.0.1");

        assertFalse(expired.success());
        assertEquals("登录已过期，请重新登录", expired.message());
        assertFalse(shortPassword.success());
        assertEquals("新密码长度必须为 8—128 位", shortPassword.message());
    }

    private RequestMessage request(String token, String currentPassword, String newPassword) {
        return RequestMessage.create(Actions.AUTH_CHANGE_PASSWORD, Map.of(
                "sessionToken", token,
                "currentPassword", currentPassword,
                "newPassword", newPassword));
    }

    private static final class FakeUserStore implements UserAccountStore {
        private UserAccount account;

        private FakeUserStore(UserAccount account) {
            this.account = account;
        }

        @Override
        public Optional<UserAccount> findByUsername(String username) {
            return account.username().equals(username) ? Optional.of(account) : Optional.empty();
        }

        @Override
        public void updateLastLogin(long userId) {
        }

        @Override
        public boolean updatePassword(long userId, PasswordHash password, boolean forcePasswordChange)
                throws SQLException {
            if (userId != account.id()) {
                return false;
            }
            account = new UserAccount(
                    account.id(), account.username(), password.hash(), password.salt(),
                    account.displayName(), account.enabled(), forcePasswordChange, account.roles());
            return true;
        }
    }

    private static final class FakeAuditStore implements AuditStore {
        private String action;
        private String result;

        @Override
        public void record(Long userId, String action, String result, String clientAddress) {
            this.action = action;
            this.result = result;
        }
    }
}
