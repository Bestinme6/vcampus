package com.vcampus.common.model;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class RoleCompositionPolicy {
    private static final Set<UserRole> BASE_IDENTITIES = Set.of(
            UserRole.STUDENT,
            UserRole.TEACHER,
            UserRole.SUPER_ADMIN);
    private static final Set<UserRole> STUDENT_ALLOWED_ROLES = Set.of(
            UserRole.STUDENT,
            UserRole.LIBRARY_ADMIN,
            UserRole.FORUM_ADMIN);
    private static final Set<UserRole> STUDENT_ADMINISTRATIVE_ROLES = Set.of(
            UserRole.LIBRARY_ADMIN,
            UserRole.FORUM_ADMIN);
    private static final Set<UserRole> TEACHER_ADMINISTRATIVE_ROLES = Set.of(
            UserRole.STUDENT_ADMIN,
            UserRole.ACADEMIC_ADMIN,
            UserRole.LIBRARY_ADMIN,
            UserRole.SHOP_ADMIN,
            UserRole.BANK_ADMIN,
            UserRole.FORUM_ADMIN);

    private RoleCompositionPolicy() {
    }

    public static Optional<String> violation(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        long baseIdentityCount = roles.stream().filter(BASE_IDENTITIES::contains).count();
        if (baseIdentityCount != 1) {
            return Optional.of("账号必须且只能拥有一个基础身份");
        }
        if (roles.contains(UserRole.SUPER_ADMIN) && roles.size() != 1) {
            return Optional.of("超级管理员不能附加其他角色");
        }
        if (roles.contains(UserRole.STUDENT) && !STUDENT_ALLOWED_ROLES.containsAll(roles)) {
            return Optional.of("学生只能兼任图书管理员或论坛管理员");
        }
        return Optional.empty();
    }

    public static void requireValid(Set<UserRole> roles) {
        violation(roles).ifPresent(message -> {
            throw new IllegalArgumentException(message);
        });
    }

    public static UserRole baseIdentity(Set<UserRole> roles) {
        requireValid(roles);
        return roles.stream()
                .filter(BASE_IDENTITIES::contains)
                .findFirst()
                .orElseThrow();
    }

    public static Set<UserRole> allowedAdministrativeRoles(UserRole baseIdentity) {
        Objects.requireNonNull(baseIdentity, "baseIdentity");
        return switch (baseIdentity) {
            case STUDENT -> STUDENT_ADMINISTRATIVE_ROLES;
            case TEACHER -> TEACHER_ADMINISTRATIVE_ROLES;
            case SUPER_ADMIN -> Set.of();
            default -> throw new IllegalArgumentException("必须指定学生、教师或超级管理员基础身份");
        };
    }

    public static Set<UserRole> administrativeRoles(Set<UserRole> roles) {
        UserRole baseIdentity = baseIdentity(roles);
        return roles.stream()
                .filter(role -> role != baseIdentity)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
