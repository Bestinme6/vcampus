package com.vcampus.client.fx.forum;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForumCommentDeletionPolicyTest {
    @Test void onlyAdministratorsDeletingAnotherUsersCommentNeedAReason() {
        assertFalse(ForumCommentDeletionPolicy.requiresReason(false, 7L, 8L));
        assertFalse(ForumCommentDeletionPolicy.requiresReason(true, 7L, 7L));
        assertTrue(ForumCommentDeletionPolicy.requiresReason(true, 7L, 8L));
    }
}
