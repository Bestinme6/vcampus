package com.vcampus.client.ui;

import com.vcampus.common.model.ForumContentStatus;

public final class ForumCommentActionPolicy {
    private ForumCommentActionPolicy() { }

    public static String deletionWarning(ForumViewData.CommentRow comment) {
        if (comment == null) return "请先选择一条评论";
        if (comment.status() != ForumContentStatus.NORMAL) return "这条评论的状态已变化，请刷新后重试";
        if (!comment.canDelete()) return "只能删除自己发布的评论；管理他人评论请到内容管理中选择隐藏";
        return "";
    }
}
