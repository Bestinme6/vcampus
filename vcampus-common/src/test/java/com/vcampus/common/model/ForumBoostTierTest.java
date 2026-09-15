package com.vcampus.common.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ForumBoostTierTest {
    @Test
    void tiersOwnAllServerTrustedPaymentValues() {
        assertAll(
                () -> assertEquals(new BigDecimal("5.00"), ForumBoostTier.FIVE.amount()),
                () -> assertEquals(50, ForumBoostTier.FIVE.initialHeat()),
                () -> assertEquals(new BigDecimal("10.00"), ForumBoostTier.TEN.amount()),
                () -> assertEquals(100, ForumBoostTier.TEN.initialHeat()),
                () -> assertEquals(new BigDecimal("15.00"), ForumBoostTier.FIFTEEN.amount()),
                () -> assertEquals(150, ForumBoostTier.FIFTEEN.initialHeat()));
    }

    @Test
    void unknownTierIsRejectedByTheEnumContract() {
        assertThrows(IllegalArgumentException.class, () -> ForumBoostTier.valueOf("TWENTY"));
    }
}
