package com.vcampus.client.ui;

import com.vcampus.common.model.ForumModerationAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForumModerationReasonPolicyTest {
    @Test
    void restoreRequiresAdministratorToEnterAnAuditReason() {
        assertTrue(ForumModerationReasonPolicy.requiresInput(
                ForumModerationAction.RESTORE));
    }

    @Test
    void onlyVisibilityChangesRequireAnExplicitReason() {
        assertTrue(ForumModerationReasonPolicy.requiresInput(
                ForumModerationAction.HIDE));
        assertFalse(ForumModerationReasonPolicy.requiresInput(
                ForumModerationAction.LOCK));
    }
}
