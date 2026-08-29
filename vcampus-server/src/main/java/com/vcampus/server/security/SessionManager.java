package com.vcampus.server.security;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.model.RoleCompositionPolicy;
import com.vcampus.server.model.UserAccount;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class SessionManager {
    private static final Duration DEFAULT_TTL = Duration.ofHours(8);
    private static final int TOKEN_BYTES = 32;

    private final ConcurrentMap<String, UserSession> sessions = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom;
    private final Clock clock;
    private final Duration ttl;

    public SessionManager() {
        this(new SecureRandom(), Clock.systemUTC(), DEFAULT_TTL);
    }

    SessionManager(SecureRandom secureRandom, Clock clock, Duration ttl) {
        this.secureRandom = secureRandom;
        this.clock = clock;
        this.ttl = ttl;
    }

    public UserSession create(UserAccount account) {
        RoleCompositionPolicy.requireValid(account.roles());
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        UserSession session = new UserSession(
                token,
                account.id(),
                account.username(),
                account.displayName(),
                account.forcePasswordChange(),
                account.roles(),
                clock.instant().plus(ttl));
        sessions.put(token, session);
        return session;
    }

    public Optional<UserSession> find(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        UserSession session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (!session.expiresAt().isAfter(clock.instant())) {
            sessions.remove(token, session);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public boolean invalidate(String token) {
        return token != null && sessions.remove(token) != null;
    }

    public void invalidateUser(long userId) {
        sessions.entrySet().removeIf(entry -> entry.getValue().userId() == userId);
    }

    public boolean requiresPasswordChange(String token) {
        return find(token).map(UserSession::forcePasswordChange).orElse(false);
    }

    public boolean completePasswordChange(String token) {
        Optional<UserSession> found = find(token);
        if (found.isEmpty()) {
            return false;
        }
        UserSession current = found.get();
        UserSession updated = new UserSession(
                current.token(),
                current.userId(),
                current.username(),
                current.displayName(),
                false,
                current.roles(),
                current.expiresAt());
        return sessions.replace(token, current, updated);
    }

    public record UserSession(
            String token,
            long userId,
            String username,
            String displayName,
            boolean forcePasswordChange,
            Set<UserRole> roles,
            Instant expiresAt) {

        public UserSession {
            roles = Set.copyOf(roles);
        }
    }
}
