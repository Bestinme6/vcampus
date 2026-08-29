package com.vcampus.client.ui;

import com.vcampus.common.model.ForumModerationAction;

final class ForumModerationReasonPolicy {
    private ForumModerationReasonPolicy() {
    }

    static boolean requiresInput(ForumModerationAction action) {
        return action == ForumModerationAction.HIDE
                || action == ForumModerationAction.RESTORE;
    }
}
