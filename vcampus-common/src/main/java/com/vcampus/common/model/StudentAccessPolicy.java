package com.vcampus.common.model;

import java.util.Objects;
import java.util.Set;

public final class StudentAccessPolicy {
    private StudentAccessPolicy() {
    }

    public static boolean canUseSelfService(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        return roles.contains(UserRole.STUDENT);
    }

    public static boolean canManageStudents(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        return roles.contains(UserRole.STUDENT_ADMIN) || roles.contains(UserRole.SUPER_ADMIN);
    }
}
