package com.vcampus.common.model;

import java.math.BigDecimal;
import java.util.Objects;

public record LibraryFeeAssessment(
        int overdueDays,
        int billableDays,
        BigDecimal replacementPrice,
        BigDecimal overdueAmount,
        BigDecimal damageAmount,
        BigDecimal lostAmount,
        BigDecimal totalAmount) {
    public LibraryFeeAssessment {
        if (overdueDays < 0 || billableDays < 0 || billableDays > overdueDays) {
            throw new IllegalArgumentException("逾期天数无效");
        }
        overdueAmount = amount(overdueAmount);
        damageAmount = amount(damageAmount);
        lostAmount = amount(lostAmount);
        totalAmount = amount(totalAmount);
        if (replacementPrice != null) replacementPrice = amount(replacementPrice);
        if (totalAmount.compareTo(overdueAmount.add(damageAmount).add(lostAmount)) != 0) {
            throw new IllegalArgumentException("费用合计无效");
        }
    }

    private static BigDecimal amount(BigDecimal value) {
        BigDecimal normalized = Objects.requireNonNull(value, "amount").setScale(2);
        if (normalized.signum() < 0) throw new IllegalArgumentException("费用不能为负数");
        return normalized;
    }
}
