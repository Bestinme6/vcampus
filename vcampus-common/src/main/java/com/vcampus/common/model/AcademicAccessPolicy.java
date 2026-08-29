package com.vcampus.common.model;

import java.util.Objects;
import java.util.Set;

public final class AcademicAccessPolicy {
    private AcademicAccessPolicy() {
    }

    public static boolean canStudy(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        return roles.contains(UserRole.STUDENT);
    }

    public static boolean canTeach(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        return roles.contains(UserRole.TEACHER) || roles.contains(UserRole.SUPER_ADMIN);
    }

    public static boolean canManage(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        return roles.contains(UserRole.ACADEMIC_ADMIN) || roles.contains(UserRole.SUPER_ADMIN);
    }

    public static boolean canPublishGrades(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        return canTeach(roles) || canManage(roles);
    }
}
