package com.vcampus.common.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessPolicyTest {
    @Test
    void studentCanUseStudentFacingModulesButNotAdminOnlyStudentRecords() {
        Set<UserRole> roles = Set.of(UserRole.STUDENT);

        assertTrue(AccessPolicy.canAccess(ModuleCode.PERSONAL_PROFILE, roles));
        assertFalse(AccessPolicy.canAccess(ModuleCode.STUDENT_STATUS, roles));
        assertTrue(AccessPolicy.canAccess(ModuleCode.ACADEMIC, roles));
        assertTrue(AccessPolicy.canAccess(ModuleCode.LIBRARY, roles));
    }

    @Test
    void teacherKeepsTeacherFacingModulesWhenAddingLibraryAdministration() {
        Set<UserRole> roles = Set.of(UserRole.TEACHER, UserRole.LIBRARY_ADMIN);

        assertTrue(AccessPolicy.canAccess(ModuleCode.PERSONAL_PROFILE, roles));
        assertTrue(AccessPolicy.canAccess(ModuleCode.LIBRARY, roles));
        assertTrue(AccessPolicy.canAccess(ModuleCode.BANK, roles));
        assertTrue(AccessPolicy.canAccess(ModuleCode.ACADEMIC, roles));
    }

    @Test
    void multipleRolesCombinePermissionsAndSuperAdministratorReceivesAll() {
        Set<UserRole> combined = Set.of(UserRole.TEACHER, UserRole.STUDENT_ADMIN, UserRole.FORUM_ADMIN);
        assertTrue(AccessPolicy.canAccess(ModuleCode.STUDENT_STATUS, combined));
        assertTrue(AccessPolicy.canAccess(ModuleCode.FORUM, combined));
        assertTrue(AccessPolicy.canAccess(ModuleCode.SHOP, combined));

        for (ModuleCode module : ModuleCode.values()) {
            if (module == ModuleCode.PERSONAL_PROFILE) {
                assertFalse(AccessPolicy.canAccess(module, Set.of(UserRole.SUPER_ADMIN)));
            } else {
                assertTrue(AccessPolicy.canAccess(module, Set.of(UserRole.SUPER_ADMIN)));
            }
        }
    }
}
