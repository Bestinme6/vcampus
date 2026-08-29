package com.vcampus.common.model;

import java.util.Objects;
import java.util.Set;

public final class ShopAccessPolicy {
    private ShopAccessPolicy() {
    }

    public static boolean canManage(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        return roles.contains(UserRole.SHOP_ADMIN) || roles.contains(UserRole.SUPER_ADMIN);
    }
}
