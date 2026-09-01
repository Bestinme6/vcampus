package com.vcampus.client.fx;

import com.vcampus.common.model.RoleCompositionPolicy;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.ResponseMessage;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

record ClientSession(String token, String displayName, Set<UserRole> roles, boolean requiresPasswordChange) {
    ClientSession {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("服务器未返回有效会话");
        roles = Set.copyOf(roles);
        RoleCompositionPolicy.requireValid(roles);
        displayName = displayName == null || displayName.isBlank() ? "校园用户" : displayName;
    }
    static ClientSession from(ResponseMessage response) {
        if (!response.success()) throw new IllegalArgumentException(response.message());
        var data = response.data();
        Set<UserRole> roles = Arrays.stream(data.getOrDefault("roles", "").split(","))
                .map(UserRole::valueOf).collect(Collectors.toUnmodifiableSet());
        String force = data.get("forcePasswordChange");
        if (!"true".equals(force) && !"false".equals(force))
            throw new IllegalArgumentException("服务器未返回改密状态");
        return new ClientSession(data.get("sessionToken"), data.get("displayName"), roles, Boolean.parseBoolean(force));
    }
    UserRole identity() { return RoleCompositionPolicy.baseIdentity(roles); }
    ClientSession passwordChanged() { return new ClientSession(token, displayName, roles, false); }
    @Override public String toString() { return "ClientSession[identity=" + identity() + "]"; }
}
