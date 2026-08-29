package com.vcampus.server.service;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.model.RoleCompositionPolicy;
import com.vcampus.common.model.UsernamePolicy;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.server.database.AuditStore;
import com.vcampus.server.database.UserAccountStore;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.PasswordHasher;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class AuthService {
    private final UserAccountStore users;
    private final AuditStore audit;
    private final PasswordHasher passwordHasher;
    private final SessionManager sessions;

    public AuthService(
            UserAccountStore users,
            AuditStore audit,
            PasswordHasher passwordHasher,
            SessionManager sessions) {
        this.users = users;
        this.audit = audit;
        this.passwordHasher = passwordHasher;
        this.sessions = sessions;
    }

    public ResponseMessage login(RequestMessage request, String clientAddress) {
        String username = request.parameters().getOrDefault("username", "").trim();
        String passwordValue = request.parameters().getOrDefault("password", "");
        if (username.isEmpty() || username.length() > 64 || passwordValue.isEmpty()) {
            return ResponseMessage.failure(request.requestId(), "请输入有效的账号和密码");
        }

        char[] password = passwordValue.toCharArray();
        try {
            Optional<UserAccount> found = users.findByUsername(username);
            if (found.isEmpty()) {
                audit.record(null, Actions.AUTH_LOGIN, "DENIED", clientAddress);
                return invalidCredentials(request);
            }
            UserAccount account = found.get();
            if (!UsernamePolicy.matchesExactly(username, account.username())) {
                audit.record(account.id(), Actions.AUTH_LOGIN, "DENIED", clientAddress);
                return invalidCredentials(request);
            }
            if (!passwordHasher.verify(password, account.passwordHash(), account.passwordSalt())) {
                audit.record(account.id(), Actions.AUTH_LOGIN, "DENIED", clientAddress);
                return invalidCredentials(request);
            }
            if (!account.enabled()) {
                audit.record(account.id(), Actions.AUTH_LOGIN, "DISABLED", clientAddress);
                return ResponseMessage.failure(request.requestId(), "账号已停用，请联系管理员");
            }
            if (account.roles().isEmpty()) {
                audit.record(account.id(), Actions.AUTH_LOGIN, "NO_ROLE", clientAddress);
                return ResponseMessage.failure(request.requestId(), "账号尚未分配角色，请联系管理员");
            }
            Optional<String> roleViolation = RoleCompositionPolicy.violation(account.roles());
            if (roleViolation.isPresent()) {
                audit.record(account.id(), Actions.AUTH_LOGIN, "INVALID_ROLES", clientAddress);
                return ResponseMessage.failure(request.requestId(), "账号角色配置异常，请联系管理员");
            }

            users.updateLastLogin(account.id());
            UserSession session = sessions.create(account);
            audit.record(account.id(), Actions.AUTH_LOGIN, "SUCCESS", clientAddress);
            return ResponseMessage.success(request.requestId(), "登录成功", sessionData(session));
        } catch (SQLException exception) {
            System.err.println("Login database error: " + exception.getMessage());
            return ResponseMessage.failure(request.requestId(), "数据库暂时不可用，请联系管理员");
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public ResponseMessage logout(RequestMessage request, String clientAddress) {
        String token = request.parameters().get("sessionToken");
        Optional<UserSession> session = sessions.find(token);
        boolean removed = sessions.invalidate(token);
        session.ifPresent(value -> audit.record(
                value.userId(), Actions.AUTH_LOGOUT, removed ? "SUCCESS" : "NOT_FOUND", clientAddress));
        return ResponseMessage.success(request.requestId(), "已安全退出", Map.of());
    }

    public ResponseMessage currentSession(RequestMessage request) {
        return sessions.find(request.parameters().get("sessionToken"))
                .map(session -> ResponseMessage.success(
                        request.requestId(), "会话有效", sessionData(session)))
                .orElseGet(() -> ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录"));
    }

    public ResponseMessage changePassword(RequestMessage request, String clientAddress) {
        String token = request.parameters().get("sessionToken");
        Optional<UserSession> foundSession = sessions.find(token);
        if (foundSession.isEmpty()) {
            return ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录");
        }

        char[] currentPassword = request.parameters()
                .getOrDefault("currentPassword", "").toCharArray();
        char[] newPassword = request.parameters()
                .getOrDefault("newPassword", "").toCharArray();
        UserSession session = foundSession.get();
        try {
            if (currentPassword.length == 0) {
                return ResponseMessage.failure(request.requestId(), "请输入当前密码");
            }
            if (newPassword.length < 8 || newPassword.length > 128) {
                return ResponseMessage.failure(request.requestId(), "新密码长度必须为 8—128 位");
            }
            Optional<UserAccount> foundAccount = users.findByUsername(session.username());
            if (foundAccount.isEmpty()) {
                return ResponseMessage.failure(request.requestId(), "账号不存在，请联系管理员");
            }
            UserAccount account = foundAccount.get();
            if (!passwordHasher.verify(
                    currentPassword, account.passwordHash(), account.passwordSalt())) {
                audit.record(account.id(), Actions.AUTH_CHANGE_PASSWORD, "DENIED", clientAddress);
                return ResponseMessage.failure(request.requestId(), "当前密码错误");
            }
            if (Arrays.equals(currentPassword, newPassword)) {
                return ResponseMessage.failure(request.requestId(), "新密码不能与当前密码相同");
            }
            PasswordHasher.PasswordHash passwordHash = passwordHasher.hash(newPassword);
            if (!users.updatePassword(account.id(), passwordHash, false)) {
                return ResponseMessage.failure(request.requestId(), "账号不存在，请联系管理员");
            }
            if (!sessions.completePasswordChange(token)) {
                return ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录");
            }
            audit.record(account.id(), Actions.AUTH_CHANGE_PASSWORD, "SUCCESS", clientAddress);
            return ResponseMessage.success(request.requestId(), "密码修改成功", Map.of());
        } catch (SQLException exception) {
            System.err.println("Password change database error: " + exception.getMessage());
            audit.record(session.userId(), Actions.AUTH_CHANGE_PASSWORD, "DATABASE_ERROR", clientAddress);
            return ResponseMessage.failure(request.requestId(), "数据库暂时不可用，请稍后重试");
        } finally {
            Arrays.fill(currentPassword, '\0');
            Arrays.fill(newPassword, '\0');
        }
    }

    private ResponseMessage invalidCredentials(RequestMessage request) {
        return ResponseMessage.failure(request.requestId(), "账号或密码错误");
    }

    private Map<String, String> sessionData(UserSession session) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("sessionToken", session.token());
        data.put("userId", Long.toString(session.userId()));
        data.put("username", session.username());
        data.put("displayName", session.displayName());
        data.put("roles", session.roles().stream()
                .map(UserRole::name)
                .sorted()
                .collect(Collectors.joining(",")));
        data.put("forcePasswordChange", Boolean.toString(session.forcePasswordChange()));
        data.put("expiresAt", session.expiresAt().toString());
        return data;
    }
}
