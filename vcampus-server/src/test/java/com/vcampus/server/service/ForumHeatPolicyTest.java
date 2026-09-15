package com.vcampus.server.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForumHeatPolicyTest {
    private static final Instant CREATED = Instant.parse("2026-09-15T00:00:00Z");

    @ParameterizedTest
    @CsvSource({"0,100", "12,50", "24,25", "48,0"})
    void boostHeatHalvesEveryTwelveHoursAndExpiresAtFortyEight(int hours, int expected) {
        assertEquals(expected, ForumHeatPolicy.currentBoostHeat(
                100, CREATED, CREATED.plus(48, ChronoUnit.HOURS),
                CREATED.plus(hours, ChronoUnit.HOURS)));
    }

    @Test
    void scoreCapsViewsAndAgeAndIncludesAllApprovedSignals() {
        double score = ForumHeatPolicy.score(2, 3, 5000, 40, 999);
        assertEquals(2 * 4 + 3 * 8 + 1000 * 0.2 + 40 - 168 * 2, score, 0.0001);
        assertTrue(ForumHeatPolicy.currentBoostHeat(
                100, CREATED, CREATED.plus(48, ChronoUnit.HOURS),
                CREATED.plus(47, ChronoUnit.HOURS)) > 0);
    }
}
