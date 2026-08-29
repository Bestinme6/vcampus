package com.vcampus.client.ui;

import com.vcampus.common.model.NotificationSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationFilterPolicyTest {
    @Test
    void resetsToFirstPageWhenSearchOrFiltersChange() {
        NotificationFilterPolicy policy = new NotificationFilterPolicy();
        policy.applyTotal(95, 10);
        policy.goToPage(4);

        policy.changeKeyword("  成绩  ");
        assertEquals(1, policy.page());
        assertEquals("成绩", policy.keyword());

        policy.goToPage(3);
        policy.changeSource(NotificationSource.ACADEMIC);
        assertEquals(1, policy.page());

        policy.goToPage(2);
        policy.changeRead(Boolean.FALSE);
        assertEquals(1, policy.page());
    }

    @Test
    void clampsNavigationToAvailablePages() {
        NotificationFilterPolicy policy = new NotificationFilterPolicy();

        policy.applyTotal(0, 10);
        assertEquals(1, policy.page());
        assertEquals(1, policy.totalPages());
        assertFalse(policy.canGoPrevious());
        assertFalse(policy.canGoNext());

        policy.applyTotal(21, 10);
        assertTrue(policy.canGoNext());
        policy.goToPage(99);
        assertEquals(3, policy.page());
        assertTrue(policy.canGoPrevious());
        assertFalse(policy.canGoNext());

        policy.applyTotal(5, 10);
        assertEquals(1, policy.page());
    }

    @Test
    void ignoresInvalidPageRequestsAndUsesServerPageSize() {
        NotificationFilterPolicy policy = new NotificationFilterPolicy();
        policy.applyTotal(25, 5);

        policy.goToPage(0);
        assertEquals(1, policy.page());
        policy.goToPage(4);
        assertEquals(4, policy.page());
        assertEquals(5, policy.totalPages());
    }
}
