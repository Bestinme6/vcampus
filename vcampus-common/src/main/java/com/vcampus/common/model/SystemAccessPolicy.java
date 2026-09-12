package com.vcampus.common.model;

import java.util.Objects;
import java.util.Set;

public final class SystemAccessPolicy {
    private SystemAccessPolicy() {
    }

    public static boolean canManage(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        return Set.of(UserRole.SUPER_ADMIN).equals(Set.copyOf(roles));
    }
}
