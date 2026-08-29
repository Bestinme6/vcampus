package com.vcampus.client.ui;

import com.vcampus.common.model.RoleCompositionPolicy;
import com.vcampus.common.model.UserRole;

import java.util.Objects;
import java.util.Set;

enum ProfileModuleKind {
    STUDENT,
    TEACHER;

    static ProfileModuleKind forRoles(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        RoleCompositionPolicy.requireValid(roles);
        if (roles.contains(UserRole.STUDENT)) {
            return STUDENT;
        }
        if (roles.contains(UserRole.TEACHER)) {
            return TEACHER;
        }
        throw new IllegalArgumentException("当前身份没有个人档案页面");
    }
}
