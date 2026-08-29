package com.vcampus.server.security;

import com.vcampus.common.model.UserRole;
import com.vcampus.server.model.UserAccount;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerTest {
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-08-25T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void sessionCarriesAllRolesAndCanBeInvalidated() {
        SessionManager sessions = new SessionManager(new SecureRandom(), NOW, Duration.ofHours(1));
        UserAccount account = account();

        SessionManager.UserSession session = sessions.create(account);

        assertTrue(sessions.find(session.token()).isPresent());
        assertTrue(session.roles().containsAll(account.roles()));
        assertTrue(sessions.invalidate(session.token()));
        assertFalse(sessions.find(session.token()).isPresent());
    }

    @Test
    void expiredSessionIsRejected() {
        SessionManager sessions = new SessionManager(new SecureRandom(), NOW, Duration.ZERO);
        SessionManager.UserSession session = sessions.create(account());

        assertFalse(sessions.find(session.token()).isPresent());
    }

    @Test
    void invalidRoleCombinationCannotCreateSession() {
        SessionManager sessions = new SessionManager(new SecureRandom(), NOW, Duration.ofHours(1));
        UserAccount invalid = new UserAccount(
                2L,
                "invalid",
                "hash",
                "salt",
                "错误账号",
                true,
                false,
                Set.of(UserRole.STUDENT, UserRole.TEACHER));

        assertThrows(IllegalArgumentException.class, () -> sessions.create(invalid));
    }

    @Test
    void invalidatesEverySessionOwnedByOneUser() {
        SessionManager sessions = new SessionManager(new SecureRandom(), NOW, Duration.ofHours(1));
        SessionManager.UserSession first = sessions.create(account(21L, false));
        SessionManager.UserSession second = sessions.create(account(21L, false));
        SessionManager.UserSession anotherUser = sessions.create(account(22L, false));

        sessions.invalidateUser(21L);

        assertFalse(sessions.find(first.token()).isPresent());
        assertFalse(sessions.find(second.token()).isPresent());
        assertTrue(sessions.find(anotherUser.token()).isPresent());
    }

    @Test
    void completesPasswordChangeOnlyForCurrentSession() {
        SessionManager sessions = new SessionManager(new SecureRandom(), NOW, Duration.ofHours(1));
        SessionManager.UserSession current = sessions.create(account(21L, true));
        SessionManager.UserSession other = sessions.create(account(21L, true));

        assertTrue(sessions.requiresPasswordChange(current.token()));
        assertTrue(sessions.completePasswordChange(current.token()));

        assertFalse(sessions.requiresPasswordChange(current.token()));
        assertTrue(sessions.requiresPasswordChange(other.token()));
        assertFalse(sessions.completePasswordChange("missing-token"));
    }

    private UserAccount account() {
        return new UserAccount(
                1L,
                "admin",
                "hash",
                "salt",
                "系统管理员",
                true,
                false,
                Set.of(UserRole.SUPER_ADMIN));
    }

    private UserAccount account(long id, boolean forcePasswordChange) {
        return new UserAccount(
                id,
                "teacher-" + id,
                "hash",
                "salt",
                "教师" + id,
                true,
                forcePasswordChange,
                Set.of(UserRole.TEACHER));
    }
}
