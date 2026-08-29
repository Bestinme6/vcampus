package com.vcampus.common.model;

import java.util.Objects;
import java.util.Set;

public final class BankAccessPolicy {
    private BankAccessPolicy() {
    }

    public static boolean canManage(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        return roles.contains(UserRole.BANK_ADMIN) || roles.contains(UserRole.SUPER_ADMIN);
    }
}
