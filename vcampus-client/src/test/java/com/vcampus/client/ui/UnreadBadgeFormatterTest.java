package com.vcampus.client.ui;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UnreadBadgeFormatterTest {
    @ParameterizedTest
    @CsvSource({"0,''", "1,1", "99,99", "100,99+", "999,99+"})
    void formatsUnreadCountWithoutOverflowingTheBadge(int count, String expected) {
        assertEquals(expected, UnreadBadgeFormatter.format(count));
    }

    @ParameterizedTest
    @CsvSource({"-1", "-100"})
    void rejectsNegativeUnreadCounts(int count) {
        assertThrows(IllegalArgumentException.class,
                () -> UnreadBadgeFormatter.format(count));
    }
}
