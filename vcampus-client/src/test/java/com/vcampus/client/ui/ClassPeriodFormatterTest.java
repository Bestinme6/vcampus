package com.vcampus.client.ui;

import com.vcampus.common.model.ClassPeriodTime;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClassPeriodFormatterTest {
    @Test
    void formatsConfiguredRangeAndFallsBackWhenEndpointMissing() {
        List<ClassPeriodTime> periods = List.of(
                new ClassPeriodTime(1, LocalTime.parse("08:00"), LocalTime.parse("08:45")),
                new ClassPeriodTime(2, LocalTime.parse("08:50"), LocalTime.parse("09:35")));
        assertEquals("第 1—2 节 08:00—09:35", ClassPeriodFormatter.format(1, 2, periods));
        assertEquals("第 1—3 节", ClassPeriodFormatter.format(1, 3, periods));
        assertEquals("第 2 节 08:50—09:35", ClassPeriodFormatter.format(2, 2, periods));
    }
}
