package com.vcampus.client.ui;

import com.vcampus.common.model.UserRole;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProfileModuleKindTest {
    @Test
    void studentIdentityUsesStudentProfile() {
        assertEquals(ProfileModuleKind.STUDENT,
                ProfileModuleKind.forRoles(Set.of(UserRole.STUDENT, UserRole.LIBRARY_ADMIN)));
    }

    @Test
    void teacherIdentityUsesTeacherProfileEvenWithStudentAdministrationRole() {
        assertEquals(ProfileModuleKind.TEACHER,
                ProfileModuleKind.forRoles(Set.of(UserRole.TEACHER, UserRole.STUDENT_ADMIN)));
    }
}
