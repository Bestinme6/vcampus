package com.vcampus.client.fx.academic;

import com.vcampus.common.model.ScheduleSlot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleGridTest {
    @Test
    void dragCreatesOneContiguousSlotAndStopsBeforeBlockedCell() {
        ScheduleGrid.Selection result = ScheduleGrid.select(
                new ScheduleGrid.Cell(1, 1), new ScheduleGrid.Cell(1, 4),
                Set.of(new ScheduleGrid.Cell(1, 3)), 1, 16, "教一-101");

        assertEquals(List.of(new ScheduleSlot(1, 1, 2, 1, 16, "教一-101")), result.slots());
        assertTrue(result.hitBlockedCell());
    }

    @Test
    void dragRemainsOnTheStartingWeekday() {
        ScheduleGrid.Selection result = ScheduleGrid.select(
                new ScheduleGrid.Cell(2, 5), new ScheduleGrid.Cell(4, 7),
                Set.of(), 3, 12, "实验楼-204");

        assertEquals(List.of(new ScheduleSlot(2, 5, 7, 3, 12, "实验楼-204")), result.slots());
    }
}
