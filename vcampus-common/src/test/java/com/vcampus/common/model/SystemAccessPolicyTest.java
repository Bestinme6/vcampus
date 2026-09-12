package com.vcampus.common.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemAccessPolicyTest {
    @Test
    void onlyExactSuperAdministratorSetCanManage() {
        assertTrue(SystemAccessPolicy.canManage(Set.of(UserRole.SUPER_ADMIN)));
        assertFalse(SystemAccessPolicy.canManage(Set.of(UserRole.TEACHER)));
        assertFalse(SystemAccessPolicy.canManage(Set.of(UserRole.SUPER_ADMIN, UserRole.FORUM_ADMIN)));
    }
}
