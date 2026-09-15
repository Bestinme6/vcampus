package com.vcampus.common.model;

import org.junit.jupiter.api.Test;

import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForumPolicyTest {
    @Test
    void studentsTeachersAndSuperAdministratorsCanUseForum() {
        assertTrue(ForumAccessPolicy.canUse(Set.of(UserRole.STUDENT)));
        assertTrue(ForumAccessPolicy.canUse(Set.of(UserRole.TEACHER)));
        assertTrue(ForumAccessPolicy.canUse(Set.of(UserRole.SUPER_ADMIN)));
    }

    @Test
    void onlyForumAndSuperAdministratorsCanModerate() {
        assertFalse(ForumAccessPolicy.canManage(Set.of(UserRole.STUDENT)));
        assertTrue(ForumAccessPolicy.canManage(
                Set.of(UserRole.STUDENT, UserRole.FORUM_ADMIN)));
        assertTrue(ForumAccessPolicy.canManage(Set.of(UserRole.SUPER_ADMIN)));
    }

    @Test
    void onlyForumAndSuperAdministratorsAreRejectedFromBoosting() {
        assertTrue(ForumAccessPolicy.canBoost(Set.of(UserRole.STUDENT)));
        assertTrue(ForumAccessPolicy.canBoost(Set.of(UserRole.TEACHER)));
        assertTrue(ForumAccessPolicy.canBoost(Set.of(UserRole.STUDENT, UserRole.STUDENT_ADMIN)));
        assertTrue(ForumAccessPolicy.canBoost(Set.of(UserRole.TEACHER, UserRole.ACADEMIC_ADMIN)));
        assertTrue(ForumAccessPolicy.canBoost(Set.of(UserRole.STUDENT, UserRole.LIBRARY_ADMIN)));
        assertTrue(ForumAccessPolicy.canBoost(Set.of(UserRole.TEACHER, UserRole.SHOP_ADMIN)));
        assertTrue(ForumAccessPolicy.canBoost(Set.of(UserRole.STUDENT, UserRole.BANK_ADMIN)));
        assertFalse(ForumAccessPolicy.canBoost(Set.of(UserRole.STUDENT, UserRole.FORUM_ADMIN)));
        assertFalse(ForumAccessPolicy.canBoost(Set.of(UserRole.SUPER_ADMIN)));
    }

    @Test
    void rejectsMissingRoleSet() {
        assertThrows(NullPointerException.class, () -> ForumAccessPolicy.canUse(null));
        assertThrows(NullPointerException.class, () -> ForumAccessPolicy.canManage(null));
        assertThrows(NullPointerException.class, () -> ForumAccessPolicy.canBoost(null));
    }
}
