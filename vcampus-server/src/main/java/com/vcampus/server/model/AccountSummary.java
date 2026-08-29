package com.vcampus.server.model;

import com.vcampus.common.model.UserRole;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record AccountSummary(
        long userId,
        String username,
        String displayName,
        UserRole baseIdentity,
        Set<UserRole> administrativeRoles,
        boolean enabled,
        boolean forcePasswordChange,
        Instant lastLoginAt) {

    public AccountSummary {
        username = Objects.requireNonNull(username, "username");
        displayName = Objects.requireNonNull(displayName, "displayName");
        baseIdentity = Objects.requireNonNull(baseIdentity, "baseIdentity");
        administrativeRoles = Set.copyOf(
                Objects.requireNonNull(administrativeRoles, "administrativeRoles"));
    }
}
