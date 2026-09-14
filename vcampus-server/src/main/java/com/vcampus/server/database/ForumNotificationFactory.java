package com.vcampus.server.database;

import com.vcampus.common.model.ForumModerationAction;
import com.vcampus.common.model.ForumReportReason;
import com.vcampus.common.model.ForumReportResolution;
import com.vcampus.common.model.ForumTargetType;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import com.vcampus.server.database.NotificationWriter.NotificationDraft;

import java.util.Optional;

final class ForumNotificationFactory {
    private static final int TITLE_LIMIT = 160;
    private static final int CONTENT_LIMIT = 1_000;

    java.util.List<NotificationDraft> commentNotifications(long postAuthorId, long commenterId,
            String commenterName, long postId, String postTitle, String comment, Long replyAuthorId) {
        java.util.Map<Long, NotificationDraft> drafts = new java.util.LinkedHashMap<>();
        if (replyAuthorId != null && replyAuthorId != commenterId) {
            drafts.put(replyAuthorId, new NotificationDraft(replyAuthorId, commenterId,
                    NotificationType.FORUM_COMMENT_REPLIED, NotificationSource.FORUM,
                    "您的评论收到一条回复", bounded(commenterName + "回复了您在《" + postTitle + "》中的评论：" + comment, CONTENT_LIMIT),
                    NotificationTarget.FORUM_POST, postId));
        }
        commentCreated(postAuthorId, commenterId, commenterName, postId, postTitle, comment)
                .ifPresent(draft -> drafts.putIfAbsent(postAuthorId, draft));
        return java.util.List.copyOf(drafts.values());
    }

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
        if (action == ForumModerationAction.DELETE
                || action == ForumModerationAction.HIDE
                || action == ForumModerationAction.RESTORE
                || action == ForumModerationAction.ANNOUNCE || action == ForumModerationAction.UNANNOUNCE) {
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

    NotificationDraft announcement(
            long recipientUserId,
            long operatorId,
            String operatorName,
            long postId,
            String postTitle,
            String postContent) {
        String summary = postContent.strip().replaceAll("\\s+", " ");
        if (summary.length() > 200) summary = summary.substring(0, 199) + "…";
        return new NotificationDraft(
                recipientUserId,
                operatorId,
                NotificationType.FORUM_ANNOUNCEMENT,
                NotificationSource.FORUM,
                bounded("校园公告：" + postTitle, TITLE_LIMIT),
                bounded(operatorName + "发布了校园公告《" + postTitle + "》：" + summary,
                        CONTENT_LIMIT),
                NotificationTarget.FORUM_POST,
                postId);
    }

    NotificationDraft contentReported(
            long administratorId,
            long reporterId,
            String reporterName,
            ForumTargetType targetType,
            long postId,
            String postTitle,
            ForumReportReason reason,
            String detail) {
        String object = targetType == ForumTargetType.POST ? "帖子" : "评论";
        String content = reporterName + "举报了" + object + "《" + postTitle + "》"
                + "，举报原因：" + reportReasonLabel(reason);
        if (detail != null && !detail.isBlank()) content += "；补充说明：" + detail.strip();
        return new NotificationDraft(
                administratorId,
                reporterId,
                NotificationType.FORUM_CONTENT_REPORTED,
                NotificationSource.FORUM,
                bounded("收到一条新的论坛举报", TITLE_LIMIT),
                bounded(content, CONTENT_LIMIT),
                NotificationTarget.FORUM_POST,
                postId);
    }

    NotificationDraft reportResolved(
            long reporterId,
            long operatorId,
            String operatorName,
            ForumTargetType targetType,
            long postId,
            String postTitle,
            ForumReportResolution resolution,
            String note) {
        String object = targetType == ForumTargetType.POST ? "帖子" : "评论";
        boolean dismissed = resolution == ForumReportResolution.DISMISS;
        String title = dismissed ? "您的论坛举报未通过" : "您的论坛举报已核实";
        String result = switch (resolution) {
            case DISMISS -> "未发现违规";
            case HIDE -> "相关" + object + "已隐藏";
            case DELETE -> "相关" + object + "已删除";
        };
        String content = operatorName + "已处理您对" + object + "《" + postTitle
                + "》的举报。处理结果：" + result + "。处理说明：" + note;
        boolean parentPostAvailable = dismissed || targetType == ForumTargetType.COMMENT;
        return new NotificationDraft(
                reporterId,
                operatorId,
                NotificationType.FORUM_REPORT_RESOLVED,
                NotificationSource.FORUM,
                bounded(title, TITLE_LIMIT),
                bounded(content, CONTENT_LIMIT),
                parentPostAvailable ? NotificationTarget.FORUM_POST : NotificationTarget.NONE,
                parentPostAvailable ? postId : null);
    }

    private String reportReasonLabel(ForumReportReason reason) {
        return switch (reason) {
            case SPAM -> "垃圾广告";
            case HARASSMENT -> "骚扰或辱骂";
            case MISLEADING -> "虚假或误导信息";
            case PRIVACY -> "泄露隐私";
            case INAPPROPRIATE -> "不当内容";
            case OTHER -> "其他";
        };
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
                && action != ForumModerationAction.RESTORE
                && action != ForumModerationAction.DELETE) {
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
            case DELETE -> "删除";
            case LOCK -> "锁定";
            case UNLOCK -> "解锁";
            case PIN -> "置顶";
            case UNPIN -> "取消置顶";
            case FEATURE -> "设为精华";
            case UNFEATURE -> "取消精华";
            case ANNOUNCE -> "设为管理员公告";
            case UNANNOUNCE -> "取消公告";
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
