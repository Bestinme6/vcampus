package com.vcampus.common.model;

import java.util.Objects;
import java.util.Set;

public final class AccessPolicy {
    private AccessPolicy() {
    }

    public static boolean canAccess(ModuleCode module, Set<UserRole> roles) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(roles, "roles");
        if (module == ModuleCode.AUTH) {
            return true;
        }
        if (module == ModuleCode.PERSONAL_PROFILE) {
            return containsAny(roles, UserRole.STUDENT, UserRole.TEACHER);
        }
        if (roles.contains(UserRole.SUPER_ADMIN)) {
            return true;
        }
        return switch (module) {
            case PERSONAL_PROFILE -> false;
            case STUDENT_STATUS -> containsAny(roles, UserRole.STUDENT_ADMIN);
            case ACADEMIC -> containsAny(roles, UserRole.STUDENT, UserRole.TEACHER, UserRole.ACADEMIC_ADMIN);
            case LIBRARY -> containsAny(roles, UserRole.STUDENT, UserRole.TEACHER, UserRole.LIBRARY_ADMIN);
            case SHOP -> containsAny(roles, UserRole.STUDENT, UserRole.TEACHER, UserRole.SHOP_ADMIN);
            case BANK -> containsAny(roles, UserRole.STUDENT, UserRole.TEACHER, UserRole.BANK_ADMIN);
            case FORUM -> containsAny(roles, UserRole.STUDENT, UserRole.TEACHER, UserRole.FORUM_ADMIN);
            case AUTH -> true;
        };
    }

    private static boolean containsAny(Set<UserRole> roles, UserRole... expected) {
        for (UserRole role : expected) {
            if (roles.contains(role)) {
                return true;
            }
        }
        return false;
    }
}
