package com.vcampus.client.ui;

import com.vcampus.common.model.ForumContentStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForumCommentActionPolicyTest {
    @Test void distinguishesSelectionOwnershipAndRemovedState() {
        assertTrue(ForumCommentActionPolicy.deletionWarning(null).contains("选择"));
        assertTrue(ForumCommentActionPolicy.deletionWarning(row(ForumContentStatus.NORMAL, false)).contains("隐藏"));
        assertTrue(ForumCommentActionPolicy.deletionWarning(row(ForumContentStatus.DELETED, false)).contains("状态"));
        assertEquals("", ForumCommentActionPolicy.deletionWarning(row(ForumContentStatus.NORMAL, true)));
    }
    private ForumViewData.CommentRow row(ForumContentStatus status, boolean deletable) {
        return new ForumViewData.CommentRow(1, 1, 2, "作者", "正文", status, Instant.EPOCH, deletable);
    }
}
