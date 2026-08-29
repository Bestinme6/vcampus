package com.vcampus.common.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleCompositionPolicyTest {
    @Test
    void everyAccountMustHaveExactlyOneBaseIdentity() {
        assertTrue(RoleCompositionPolicy.violation(Set.of(UserRole.STUDENT)).isEmpty());
        assertTrue(RoleCompositionPolicy.violation(Set.of(UserRole.TEACHER)).isEmpty());
        assertTrue(RoleCompositionPolicy.violation(Set.of(UserRole.SUPER_ADMIN)).isEmpty());
        assertEquals("账号必须且只能拥有一个基础身份",
                RoleCompositionPolicy.violation(Set.of()).orElseThrow());
        assertEquals("账号必须且只能拥有一个基础身份",
                RoleCompositionPolicy.violation(Set.of(UserRole.STUDENT, UserRole.TEACHER)).orElseThrow());
    }

    @Test
    void studentMayOnlyCombineLibraryAndForumAdministration() {
        assertTrue(RoleCompositionPolicy.violation(Set.of(
                UserRole.STUDENT, UserRole.LIBRARY_ADMIN, UserRole.FORUM_ADMIN)).isEmpty());
        assertEquals("学生只能兼任图书管理员或论坛管理员",
                RoleCompositionPolicy.violation(Set.of(
                        UserRole.STUDENT, UserRole.ACADEMIC_ADMIN)).orElseThrow());
    }

    @Test
    void teacherMayCombineEveryBusinessAdministrationRole() {
        assertTrue(RoleCompositionPolicy.violation(Set.of(
                UserRole.TEACHER,
                UserRole.STUDENT_ADMIN,
                UserRole.ACADEMIC_ADMIN,
                UserRole.LIBRARY_ADMIN,
                UserRole.SHOP_ADMIN,
                UserRole.BANK_ADMIN,
                UserRole.FORUM_ADMIN)).isEmpty());
    }

    @Test
    void superAdministratorMustRemainStandalone() {
        assertEquals("超级管理员不能附加其他角色",
                RoleCompositionPolicy.violation(Set.of(
                        UserRole.SUPER_ADMIN, UserRole.FORUM_ADMIN)).orElseThrow());
    }

    @Test
    void requireValidUsesTheSameViolationMessage() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> RoleCompositionPolicy.requireValid(Set.of(UserRole.STUDENT, UserRole.SHOP_ADMIN)));
        assertEquals("学生只能兼任图书管理员或论坛管理员", exception.getMessage());
    }

    @Test
    void exposesBaseIdentityAndAdministrativeRoleSets() {
        assertEquals(UserRole.STUDENT,
                RoleCompositionPolicy.baseIdentity(Set.of(UserRole.STUDENT, UserRole.FORUM_ADMIN)));
        assertEquals(Set.of(UserRole.LIBRARY_ADMIN, UserRole.FORUM_ADMIN),
                RoleCompositionPolicy.allowedAdministrativeRoles(UserRole.STUDENT));
        assertEquals(Set.of(
                        UserRole.STUDENT_ADMIN,
                        UserRole.ACADEMIC_ADMIN,
                        UserRole.LIBRARY_ADMIN,
                        UserRole.SHOP_ADMIN,
                        UserRole.BANK_ADMIN,
                        UserRole.FORUM_ADMIN),
                RoleCompositionPolicy.allowedAdministrativeRoles(UserRole.TEACHER));
        assertEquals(Set.of(UserRole.FORUM_ADMIN),
                RoleCompositionPolicy.administrativeRoles(Set.of(
                        UserRole.TEACHER, UserRole.FORUM_ADMIN)));
    }

    @Test
    void helperMethodsRejectInvalidRoleCompositions() {
        assertThrows(IllegalArgumentException.class,
                () -> RoleCompositionPolicy.baseIdentity(Set.of(
                        UserRole.STUDENT, UserRole.TEACHER)));
        assertThrows(IllegalArgumentException.class,
                () -> RoleCompositionPolicy.administrativeRoles(Set.of(
                        UserRole.STUDENT, UserRole.ACADEMIC_ADMIN)));
    }
}
