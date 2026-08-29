package com.vcampus.client.ui;

import com.vcampus.common.model.NotificationTarget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NotificationNavigationPolicyTest {
    @Test
    void forumNotificationRequiresPositivePostId() {
        assertEquals(41L, NotificationNavigationPolicy.forumPostId(
                new NotificationDestination(NotificationTarget.FORUM_POST, 41L)));
        assertThrows(IllegalArgumentException.class,
                () -> NotificationNavigationPolicy.forumPostId(
                        new NotificationDestination(NotificationTarget.FORUM_POST, null)));
        assertThrows(IllegalArgumentException.class,
                () -> NotificationNavigationPolicy.forumPostId(
                        new NotificationDestination(NotificationTarget.FORUM_POST, 0L)));
        assertThrows(IllegalArgumentException.class,
                () -> NotificationNavigationPolicy.forumPostId(
                        new NotificationDestination(NotificationTarget.LIBRARY_LOANS, 41L)));
    }
}
