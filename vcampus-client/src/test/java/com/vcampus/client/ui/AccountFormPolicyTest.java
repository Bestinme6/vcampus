package com.vcampus.client.ui;

import com.vcampus.common.model.UserRole;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountFormPolicyTest {
    @Test
    void studentUsesStudentNumberFieldsAndTwoAdministrativeRoles() {
        assertEquals("2026000001", AccountFormPolicy.username(
                UserRole.STUDENT, "2026000001"));
        assertEquals(Set.of(UserRole.LIBRARY_ADMIN, UserRole.FORUM_ADMIN),
                AccountFormPolicy.allowedRoles(UserRole.STUDENT));
        assertTrue(AccountFormPolicy.showsAcademicClassFields(UserRole.STUDENT));
    }

    @Test
    void teacherUsesTeacherNumberFieldsAndSixAdministrativeRoles() {
        assertEquals("T0000001", AccountFormPolicy.username(
                UserRole.TEACHER, "T0000001"));
        assertEquals(6, AccountFormPolicy.allowedRoles(UserRole.TEACHER).size());
        assertFalse(AccountFormPolicy.showsAcademicClassFields(UserRole.TEACHER));
    }

    @Test
    void rejectsSuperAdministratorAndMalformedNumbers() {
        assertThrows(IllegalArgumentException.class,
                () -> AccountFormPolicy.allowedRoles(UserRole.SUPER_ADMIN));
        assertThrows(IllegalArgumentException.class,
                () -> AccountFormPolicy.username(UserRole.STUDENT, "2026"));
        assertThrows(IllegalArgumentException.class,
                () -> AccountFormPolicy.username(UserRole.TEACHER, "t0000001"));
    }
}
