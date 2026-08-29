package com.vcampus.common.model;

import java.util.Objects;
import java.util.Set;

public final class AccountAccessPolicy {
    private AccountAccessPolicy() {
    }

    public static boolean canManageAccounts(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        return Set.of(UserRole.SUPER_ADMIN).equals(Set.copyOf(roles));
    }
}
