package com.vcampus.client.fx.forum;

final class ForumCommentDeletionPolicy {
    private ForumCommentDeletionPolicy() {}

    static boolean requiresReason(boolean administrator, long viewerUserId, long authorUserId) {
        return administrator && viewerUserId != authorUserId;
    }
}
