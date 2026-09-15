package com.vcampus.client.fx;

import com.vcampus.common.model.AuditLogEntry;
import com.vcampus.common.model.RoleCompositionPolicy;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.ResponseMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class AccountSecurityData {
    private static final int MAX_ACTIVITY = 5;
    private static final Set<String> SECURITY_ACTIONS = Set.of(
            Actions.AUTH_LOGIN, Actions.AUTH_LOGOUT, Actions.AUTH_CHANGE_PASSWORD);

    private AccountSecurityData() { }

    static Summary from(ResponseMessage response) {
        try {
            if (response == null || !response.success()) throw new IllegalArgumentException();
            Map<String, String> data = response.data();
            long userId = Long.parseLong(required(data, "userId"));
            if (userId <= 0) throw new IllegalArgumentException();
            String username = required(data, "username");
            String displayName = required(data, "displayName");
            Set<UserRole> roles = Arrays.stream(required(data, "roles").split(","))
                    .map(UserRole::valueOf).collect(Collectors.toUnmodifiableSet());
            RoleCompositionPolicy.requireValid(roles);
            Instant expiresAt = Instant.parse(required(data, "expiresAt"));
            int count = Integer.parseInt(required(data, "row.count"));
            if (count < 0 || count > MAX_ACTIVITY) throw new IllegalArgumentException();
            List<AuditLogEntry> activities = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                AuditLogEntry row = AuditLogEntry.decode(required(data, "row." + index));
                if (row.userId() == null || row.userId() != userId
                        || !SECURITY_ACTIONS.contains(row.actionCode())) {
                    throw new IllegalArgumentException();
                }
                activities.add(row);
            }
            return new Summary(userId, username, displayName, roles, expiresAt, activities);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("服务器返回的账号安全信息格式不正确", exception);
        }
    }

    private static String required(Map<String, String> data, String key) {
        String value = data.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value.trim();
    }

    record Summary(long userId, String username, String displayName, Set<UserRole> roles,
                   Instant expiresAt, List<AuditLogEntry> activities) {
        Summary {
            if (userId <= 0 || username == null || username.isBlank()
                    || displayName == null || displayName.isBlank() || expiresAt == null) {
                throw new IllegalArgumentException("账号安全信息无效");
            }
            roles = Set.copyOf(roles);
            RoleCompositionPolicy.requireValid(roles);
            activities = List.copyOf(activities);
        }
    }
}
