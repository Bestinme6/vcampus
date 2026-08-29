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
    void rejectsMissingRoleSet() {
        assertThrows(NullPointerException.class, () -> ForumAccessPolicy.canUse(null));
        assertThrows(NullPointerException.class, () -> ForumAccessPolicy.canManage(null));
    }
}
