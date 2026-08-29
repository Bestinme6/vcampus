package com.vcampus.common.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudentPolicyTest {
    @Test
    void studentAndAdministratorPermissionsStaySeparated() {
        assertTrue(StudentAccessPolicy.canUseSelfService(Set.of(UserRole.STUDENT)));
        assertFalse(StudentAccessPolicy.canManageStudents(Set.of(UserRole.STUDENT)));
        assertTrue(StudentAccessPolicy.canManageStudents(Set.of(UserRole.STUDENT_ADMIN)));
        assertFalse(StudentAccessPolicy.canUseSelfService(Set.of(UserRole.STUDENT_ADMIN)));
        assertTrue(StudentAccessPolicy.canManageStudents(Set.of(UserRole.SUPER_ADMIN)));
        assertFalse(StudentAccessPolicy.canUseSelfService(Set.of(UserRole.SUPER_ADMIN)));
    }

    @Test
    void statusTransitionsFollowAcademicRules() {
        assertTrue(StudentStatusPolicy.canTransition(StudentStatus.ENROLLED, StudentStatus.SUSPENDED));
        assertTrue(StudentStatusPolicy.canTransition(StudentStatus.SUSPENDED, StudentStatus.ENROLLED));
        assertTrue(StudentStatusPolicy.canTransition(StudentStatus.ENROLLED, StudentStatus.GRADUATED));
        assertFalse(StudentStatusPolicy.canTransition(StudentStatus.GRADUATED, StudentStatus.ENROLLED));
        assertFalse(StudentStatusPolicy.canTransition(StudentStatus.WITHDRAWN, StudentStatus.ENROLLED));
    }
}
