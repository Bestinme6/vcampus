package com.vcampus.common.model;

import java.util.Objects;
import java.util.Set;

public final class ForumAccessPolicy {
    private ForumAccessPolicy() {
    }

    public static boolean canUse(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        return roles.contains(UserRole.STUDENT)
                || roles.contains(UserRole.TEACHER)
                || roles.contains(UserRole.SUPER_ADMIN);
    }

    public static boolean canManage(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        return roles.contains(UserRole.FORUM_ADMIN)
                || roles.contains(UserRole.SUPER_ADMIN);
    }
}
