package com.vcampus.client.fx;

import com.vcampus.common.model.UserRole;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityCampusIntegrationTest {
    @Test void resolvesAllNativeIdentityRoutesByRole(){
        assertEquals("student-profile",CampusApplication.identityPage("personal-profile",Set.of(UserRole.STUDENT)));
        assertEquals("teacher-profile",CampusApplication.identityPage("personal-profile",Set.of(UserRole.TEACHER)));
        assertEquals("student-admin",CampusApplication.identityPage("student-status",Set.of(UserRole.STUDENT_ADMIN,UserRole.TEACHER)));
        assertEquals("accounts",CampusApplication.identityPage("accounts",Set.of(UserRole.SUPER_ADMIN)));
        assertEquals("unauthorized",CampusApplication.identityPage("accounts",Set.of(UserRole.STUDENT)));
        assertTrue(CampusApplication.isIdentityRoute("personal-profile"));
        assertFalse(CampusApplication.isIdentityRoute("library"));
    }
}
