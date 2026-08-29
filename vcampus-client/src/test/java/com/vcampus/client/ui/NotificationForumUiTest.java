package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.client.ui.NotificationViewData.NotificationDetail;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationForumUiTest {
    @Test
    void forumDestinationPreservesPostId() {
        var destination = new NotificationDestination(
                NotificationTarget.FORUM_POST, 41L);

        assertTrue(destination.navigable());
        assertEquals(41L, destination.relatedEntityId());
    }

    @Test
    void forumDestinationWithoutPostIdIsNotNavigable() {
        assertFalse(new NotificationDestination(
                NotificationTarget.FORUM_POST, null).navigable());
    }

    @Test
    void messageCenterOffersForumSourceFilter() {
        NotificationPanel panel = new NotificationPanel(
                new VCampusClient("localhost", 1), "token",
                destination -> { }, () -> { });

        assertTrue(buttonLabels(panel).contains("论坛通知"));
    }

    @Test
    void forumNotificationOffersPostNavigation() {
        NotificationDetail detail = new NotificationDetail(
                1L, NotificationType.FORUM_POST_COMMENTED, NotificationSource.FORUM,
                "您的帖子收到一条新评论", "李老师评论了您的帖子",
                NotificationTarget.FORUM_POST, 41L, false, null,
                Instant.parse("2026-08-29T00:00:00Z"));
        NotificationDetailDialog dialog = new NotificationDetailDialog(
                null, detail, destination -> { });
        try {
            assertTrue(buttonLabels(dialog.getContentPane()).contains("查看帖子"));
        } finally {
            dialog.dispose();
        }
    }

    private List<String> buttonLabels(Container container) {
        List<String> labels = new ArrayList<>();
        for (Component component : container.getComponents()) {
            if (component instanceof JButton button) {
                labels.add(button.getText());
            }
            if (component instanceof Container child) {
                labels.addAll(buttonLabels(child));
            }
        }
        return labels;
    }
}
