package com.vcampus.client.ui;

import com.vcampus.common.model.UserRole;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForumAdminTabPolicyTest {
    @Test
    void adminTabIsVisibleOnlyToForumOrSuperAdministrator() {
        assertFalse(ForumAdminTabPolicy.visible(Set.of(UserRole.STUDENT)));
        assertTrue(ForumAdminTabPolicy.visible(
                Set.of(UserRole.TEACHER, UserRole.FORUM_ADMIN)));
        assertTrue(ForumAdminTabPolicy.visible(Set.of(UserRole.SUPER_ADMIN)));
    }
}
