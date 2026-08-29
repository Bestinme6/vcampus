package com.vcampus.common.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountAccessPolicyTest {
    @Test
    void onlyStandaloneSuperAdministratorManagesAccounts() {
        assertTrue(AccountAccessPolicy.canManageAccounts(Set.of(UserRole.SUPER_ADMIN)));
        assertFalse(AccountAccessPolicy.canManageAccounts(Set.of(
                UserRole.TEACHER, UserRole.ACADEMIC_ADMIN)));
        assertFalse(AccountAccessPolicy.canManageAccounts(Set.of(UserRole.STUDENT)));
    }
}
