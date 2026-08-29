package com.vcampus.client.ui;

import com.vcampus.common.model.ForumSort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ForumNavigationTest {
    @Test
    void openingPostAndReturningPreservesHomeQuery() {
        ForumNavigation navigation = new ForumNavigation();
        navigation.rememberHome(new ForumNavigation.HomeQuery(
                2L, "食堂", ForumSort.LATEST_REPLY, 3));

        navigation.openPost(18L);
        assertEquals(18L, navigation.currentPostId());

        ForumNavigation.HomeQuery restored = navigation.backHome();
        assertEquals(3, restored.page());
        assertEquals("食堂", restored.keyword());
        assertNull(navigation.currentPostId());
    }
}
