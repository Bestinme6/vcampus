package com.vcampus.server.database;

import com.vcampus.common.model.ForumModerationAction;
import com.vcampus.common.model.ForumReportReason;
import com.vcampus.common.model.ForumTargetType;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForumNotificationFactoryTest {
    private final ForumNotificationFactory factory = new ForumNotificationFactory();

    @Test
    void commentDraftTargetsPostAuthorAndPost() {
        var draft = factory.commentCreated(
                1L, 2L, "李老师", 41L, "校园活动建议", "我支持这个建议").orElseThrow();

        assertEquals(1L, draft.recipientUserId());
        assertEquals(2L, draft.senderUserId());
        assertEquals(NotificationType.FORUM_POST_COMMENTED, draft.type());
        assertEquals(NotificationSource.FORUM, draft.source());
        assertEquals(NotificationTarget.FORUM_POST, draft.target());
        assertEquals(41L, draft.relatedEntityId());
        assertEquals("您的帖子收到一条新评论", draft.title());
        assertEquals("李老师评论了您的帖子《校园活动建议》：我支持这个建议", draft.content());
    }

    @Test
    void selfActionsDoNotCreateNotifications() {
        assertTrue(factory.commentCreated(
                1L, 1L, "张同学", 41L, "标题", "评论").isEmpty());
        assertTrue(factory.postModerated(
                3L, 3L, "管理员", 41L, "标题",
                ForumModerationAction.LOCK, "管理员调整内容状态").isEmpty());
        assertTrue(factory.commentModerated(
                3L, 3L, "管理员", 41L, "标题",
                ForumModerationAction.HIDE, "审核原因").isEmpty());
    }

    @Test
    void generatedFieldsNeverExceedNotificationColumns() {
        String longText = "长".repeat(2_000);
        var draft = factory.commentCreated(
                1L, 2L, longText, 41L, longText, longText).orElseThrow();

        assertTrue(draft.title().length() <= 160);
        assertTrue(draft.content().length() <= 1_000);
    }

    @Test
    void everyPostModerationActionUsesItsChineseLabel() {
        Map<ForumModerationAction, String> labels = Map.of(
                ForumModerationAction.HIDE, "隐藏",
                ForumModerationAction.RESTORE, "恢复",
                ForumModerationAction.LOCK, "锁定",
                ForumModerationAction.UNLOCK, "解锁",
                ForumModerationAction.PIN, "置顶",
                ForumModerationAction.UNPIN, "取消置顶",
                ForumModerationAction.FEATURE, "设为精华",
                ForumModerationAction.UNFEATURE, "取消精华");

        labels.forEach((action, label) -> assertTrue(factory.postModerated(
                1L, 3L, "论坛管理员", 41L, "标题", action, "审核原因")
                .orElseThrow().content().contains(label)));
    }

    @Test
    void commentModerationIncludesReasonAndTargetsParentPost() {
        for (ForumModerationAction action : List.of(
                ForumModerationAction.HIDE, ForumModerationAction.RESTORE)) {
            var draft = factory.commentModerated(
                    2L, 3L, "论坛管理员", 41L, "标题", action, "审核原因")
                    .orElseThrow();
            assertEquals(NotificationType.FORUM_COMMENT_MODERATED, draft.type());
            assertEquals(NotificationTarget.FORUM_POST, draft.target());
            assertEquals(41L, draft.relatedEntityId());
            assertTrue(draft.content().contains("审核原因"));
        }
    }

    @Test
    void reportedContentNotificationIdentifiesObjectReasonAndParentPost() {
        var draft = factory.contentReported(
                4L, 3L, "举报人", ForumTargetType.COMMENT,
                10L, "帖子标题", ForumReportReason.HARASSMENT, "持续辱骂");

        assertEquals(NotificationType.FORUM_CONTENT_REPORTED, draft.type());
        assertEquals(NotificationSource.FORUM, draft.source());
        assertEquals(NotificationTarget.FORUM_POST, draft.target());
        assertEquals(10L, draft.relatedEntityId());
        assertTrue(draft.content().contains("评论"));
        assertTrue(draft.content().contains("骚扰或辱骂"));
        assertTrue(draft.content().contains("持续辱骂"));
    }
}
