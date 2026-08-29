package com.vcampus.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleSlotTest {
    @Test
    void detectsPeriodAndWeekOverlapOnSameDay() {
        ScheduleSlot base = new ScheduleSlot(1, 2, 4, 1, 8, "教一-101");

        assertTrue(base.overlaps(new ScheduleSlot(1, 4, 5, 8, 12, "教二-202")));
        assertFalse(base.overlaps(new ScheduleSlot(1, 4, 5, 9, 12, "教二-202")));
        assertFalse(base.overlaps(new ScheduleSlot(2, 2, 4, 1, 8, "教二-202")));
    }

    @Test
    void limitsTimetableToTwelvePeriods() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScheduleSlot(1, 1, 13, 1, 16, "教一-101"));
    }
}
