package com.vcampus.server.database;

import com.vcampus.common.model.ForumModerationAction;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import com.vcampus.server.database.NotificationWriter.NotificationDraft;

import java.util.Optional;

final class ForumNotificationFactory {
    private static final int TITLE_LIMIT = 160;
    private static final int CONTENT_LIMIT = 1_000;

    Optional<NotificationDraft> commentCreated(
            long postAuthorId,
            long commenterId,
            String commenterName,
            long postId,
            String postTitle,
            String comment) {
        if (postAuthorId == commenterId) {
            return Optional.empty();
        }
        return Optional.of(new NotificationDraft(
                postAuthorId,
                commenterId,
                NotificationType.FORUM_POST_COMMENTED,
                NotificationSource.FORUM,
                bounded("您的帖子收到一条新评论", TITLE_LIMIT),
                bounded(commenterName + "评论了您的帖子《" + postTitle + "》：" + comment,
                        CONTENT_LIMIT),
                NotificationTarget.FORUM_POST,
                postId));
    }

    Optional<NotificationDraft> postModerated(
            long postAuthorId,
            long operatorId,
            String operatorName,
            long postId,
            String postTitle,
            ForumModerationAction action,
            String reason) {
        if (postAuthorId == operatorId) {
            return Optional.empty();
        }
        String content = operatorName + "已将您的帖子《" + postTitle + "》"
                + actionLabel(action) + "。";
        if (action == ForumModerationAction.HIDE
                || action == ForumModerationAction.RESTORE) {
            content += "管理原因：" + reason;
        }
        return Optional.of(new NotificationDraft(
                postAuthorId,
                operatorId,
                NotificationType.FORUM_POST_MODERATED,
                NotificationSource.FORUM,
                bounded("您的帖子状态已更新", TITLE_LIMIT),
                bounded(content, CONTENT_LIMIT),
                NotificationTarget.FORUM_POST,
                postId));
    }

    Optional<NotificationDraft> commentModerated(
            long commentAuthorId,
            long operatorId,
            String operatorName,
            long postId,
            String postTitle,
            ForumModerationAction action,
            String reason) {
        if (commentAuthorId == operatorId) {
            return Optional.empty();
        }
        if (action != ForumModerationAction.HIDE
                && action != ForumModerationAction.RESTORE) {
            throw new IllegalArgumentException("评论审核动作无效");
        }
        String content = operatorName + "已" + actionLabel(action)
                + "您在帖子《" + postTitle + "》中的评论。管理原因：" + reason;
        return Optional.of(new NotificationDraft(
                commentAuthorId,
                operatorId,
                NotificationType.FORUM_COMMENT_MODERATED,
                NotificationSource.FORUM,
                bounded("您的评论状态已更新", TITLE_LIMIT),
                bounded(content, CONTENT_LIMIT),
                NotificationTarget.FORUM_POST,
                postId));
    }

    private String actionLabel(ForumModerationAction action) {
        return switch (action) {
            case HIDE -> "隐藏";
            case RESTORE -> "恢复";
            case LOCK -> "锁定";
            case UNLOCK -> "解锁";
            case PIN -> "置顶";
            case UNPIN -> "取消置顶";
            case FEATURE -> "设为精华";
            case UNFEATURE -> "取消精华";
            default -> throw new IllegalArgumentException("不支持的论坛通知动作");
        };
    }

    private String bounded(String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit - 1) + "…";
    }
}
