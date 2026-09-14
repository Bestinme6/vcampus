package com.vcampus.common.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class LibraryFeePolicyTest {
    private static final LibraryFeePolicy POLICY = new LibraryFeePolicy(
            1, new BigDecimal("0.50"), new BigDecimal("20.00"),
            new BigDecimal("0.2000"), new BigDecimal("0.5000"), new BigDecimal("1.0000"));

    @Test void keepsFirstOverdueDayFreeAndChargesFromSecondDay() {
        assertEquals(new BigDecimal("0.00"), assess(LocalDate.of(2026, 9, 2)).overdueAmount());
        assertEquals(new BigDecimal("0.50"), assess(LocalDate.of(2026, 9, 3)).overdueAmount());
    }

    @Test void capsOverdueAndCombinesMajorDamage() {
        LibraryFeeAssessment value = POLICY.assess(LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 9, 1), LibraryReturnCondition.DAMAGED,
                LibraryDamageLevel.MAJOR, new BigDecimal("89.00"));
        assertEquals(new BigDecimal("20.00"), value.overdueAmount());
        assertEquals(new BigDecimal("44.50"), value.damageAmount());
        assertEquals(new BigDecimal("64.50"), value.totalAmount());
    }

    @Test void calculatesMinorAndLostAmounts() {
        var minor = POLICY.assess(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1),
                LibraryReturnCondition.DAMAGED, LibraryDamageLevel.MINOR, new BigDecimal("89.00"));
        var lost = POLICY.assess(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1),
                LibraryReturnCondition.LOST, null, new BigDecimal("89.00"));
        assertEquals(new BigDecimal("17.80"), minor.damageAmount());
        assertEquals(new BigDecimal("89.00"), lost.lostAmount());
        assertEquals(2, lost.totalAmount().scale());
    }

    @Test void validatesConditionPriceAndDamageLevel() {
        assertDoesNotThrow(() -> POLICY.assess(LocalDate.now(), LocalDate.now(),
                LibraryReturnCondition.NORMAL, null, null));
        assertThrows(IllegalArgumentException.class, () -> POLICY.assess(LocalDate.now(), LocalDate.now(),
                LibraryReturnCondition.DAMAGED, null, new BigDecimal("20.00")));
        assertThrows(IllegalArgumentException.class, () -> POLICY.assess(LocalDate.now(), LocalDate.now(),
                LibraryReturnCondition.DAMAGED, LibraryDamageLevel.MINOR, null));
        assertThrows(IllegalArgumentException.class, () -> POLICY.assess(LocalDate.now(), LocalDate.now(),
                LibraryReturnCondition.LOST, LibraryDamageLevel.MINOR, new BigDecimal("20.00")));
    }

    @Test void rejectsInvalidPolicy() {
        assertThrows(IllegalArgumentException.class, () -> new LibraryFeePolicy(-1,
                new BigDecimal("0.50"), new BigDecimal("20.00"), new BigDecimal("0.2000"),
                new BigDecimal("0.5000"), new BigDecimal("1.0000")));
        assertThrows(IllegalArgumentException.class, () -> new LibraryFeePolicy(1,
                new BigDecimal("0.50"), new BigDecimal("20.00"), new BigDecimal("0.6000"),
                new BigDecimal("0.5000"), new BigDecimal("1.0000")));
    }

    private LibraryFeeAssessment assess(LocalDate closed) {
        return POLICY.assess(LocalDate.of(2026, 9, 1), closed,
                LibraryReturnCondition.NORMAL, null, null);
    }
}
