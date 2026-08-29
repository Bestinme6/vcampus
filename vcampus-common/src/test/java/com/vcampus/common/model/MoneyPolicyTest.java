package com.vcampus.common.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyPolicyTest {
    @Test
    void parsesAndFormatsCanonicalPositiveMoney() {
        assertEquals(new BigDecimal("12.30"), MoneyPolicy.parsePositive("12.30"));
        assertEquals(new BigDecimal("12.30"), MoneyPolicy.parsePositive(" 12.3 "));
        assertEquals("12.30", MoneyPolicy.format(new BigDecimal("12.3")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "0.00", "-1", "1.001", "1e2", "NaN",
            "9999999999999.991", "10000000000000.00"})
    void rejectsNonPositiveOrUnsafeMoney(String value) {
        assertThrows(IllegalArgumentException.class, () -> MoneyPolicy.parsePositive(value));
    }

    @Test
    void rejectsNullMoney() {
        assertThrows(IllegalArgumentException.class, () -> MoneyPolicy.parsePositive(null));
    }
}
