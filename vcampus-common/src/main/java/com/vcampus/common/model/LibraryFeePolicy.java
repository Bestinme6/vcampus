package com.vcampus.common.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public record LibraryFeePolicy(
        int graceDays,
        BigDecimal dailyOverdueFee,
        BigDecimal overdueCap,
        BigDecimal minorDamageRate,
        BigDecimal majorDamageRate,
        BigDecimal lostRate) {
    public LibraryFeePolicy {
        if (graceDays < 0) throw new IllegalArgumentException("宽限天数不能为负数");
        dailyOverdueFee = positiveAmount(dailyOverdueFee, "每日逾期费用");
        overdueCap = positiveAmount(overdueCap, "逾期费用上限");
        minorDamageRate = rate(minorDamageRate, "轻微破损比例");
        majorDamageRate = rate(majorDamageRate, "严重破损比例");
        lostRate = rate(lostRate, "遗失赔偿比例");
        if (majorDamageRate.compareTo(minorDamageRate) < 0) {
            throw new IllegalArgumentException("严重破损比例不能低于轻微破损比例");
        }
    }

    public LibraryFeeAssessment assess(LocalDate dueDate, LocalDate closedDate,
                                        LibraryReturnCondition condition,
                                        LibraryDamageLevel damageLevel,
                                        BigDecimal replacementPrice) {
        Objects.requireNonNull(dueDate, "dueDate");
        Objects.requireNonNull(closedDate, "closedDate");
        Objects.requireNonNull(condition, "condition");
        long days = Math.max(0, ChronoUnit.DAYS.between(dueDate, closedDate));
        int overdueDays = Math.toIntExact(days);
        int billableDays = Math.max(0, overdueDays - graceDays);
        BigDecimal overdue = dailyOverdueFee.multiply(BigDecimal.valueOf(billableDays))
                .min(overdueCap).setScale(2, RoundingMode.HALF_UP);
        BigDecimal price = replacementPrice == null ? null
                : positiveAmount(replacementPrice, "赔偿参考价");
        BigDecimal damage = BigDecimal.ZERO.setScale(2);
        BigDecimal lost = BigDecimal.ZERO.setScale(2);
        switch (condition) {
            case NORMAL -> {
                if (damageLevel != null) throw new IllegalArgumentException("正常归还不能设置破损程度");
            }
            case DAMAGED -> {
                if (damageLevel == null) throw new IllegalArgumentException("请选择破损程度");
                if (price == null) throw new IllegalArgumentException("该书目尚未设置赔偿参考价");
                BigDecimal selected = damageLevel == LibraryDamageLevel.MINOR
                        ? minorDamageRate : majorDamageRate;
                damage = price.multiply(selected).setScale(2, RoundingMode.HALF_UP);
            }
            case LOST -> {
                if (damageLevel != null) throw new IllegalArgumentException("遗失不能设置破损程度");
                if (price == null) throw new IllegalArgumentException("该书目尚未设置赔偿参考价");
                lost = price.multiply(lostRate).setScale(2, RoundingMode.HALF_UP);
            }
        }
        return new LibraryFeeAssessment(overdueDays, billableDays, price, overdue, damage, lost,
                overdue.add(damage).add(lost).setScale(2));
    }

    private static BigDecimal positiveAmount(BigDecimal value, String label) {
        try {
            BigDecimal normalized = Objects.requireNonNull(value, label).setScale(2, RoundingMode.UNNECESSARY);
            if (normalized.signum() <= 0) throw new IllegalArgumentException(label + "必须大于零");
            return normalized;
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(label + "最多保留两位小数", error);
        }
    }

    private static BigDecimal rate(BigDecimal value, String label) {
        try {
            BigDecimal normalized = Objects.requireNonNull(value, label).setScale(4, RoundingMode.UNNECESSARY);
            if (normalized.signum() <= 0 || normalized.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException(label + "应在 0 到 1 之间");
            }
            return normalized;
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(label + "最多保留四位小数", error);
        }
    }
}
