package com.vcampus.common.model;

import java.util.Objects;
import java.util.Set;

public final class LibraryAccessPolicy {
    private LibraryAccessPolicy() {
    }

    public static boolean canBorrow(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        return roles.contains(UserRole.STUDENT) || roles.contains(UserRole.TEACHER);
    }

    public static boolean canManage(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        return roles.contains(UserRole.LIBRARY_ADMIN) || roles.contains(UserRole.SUPER_ADMIN);
    }
}
