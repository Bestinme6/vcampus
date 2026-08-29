package com.vcampus.server.model;

import com.vcampus.common.model.UserRole;

import java.util.Objects;
import java.util.Set;

public record UserAccount(
        long id,
        String username,
        String passwordHash,
        String passwordSalt,
        String displayName,
        boolean enabled,
        boolean forcePasswordChange,
        Set<UserRole> roles) {

    public UserAccount {
        username = Objects.requireNonNull(username, "username");
        passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        passwordSalt = Objects.requireNonNull(passwordSalt, "passwordSalt");
        displayName = Objects.requireNonNull(displayName, "displayName");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
    }
}
