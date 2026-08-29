package com.vcampus.common.model;

import java.math.BigDecimal;

public final class GradePolicy {
    private GradePolicy() {
    }

    public static BigDecimal gradePoint(BigDecimal score) {
        if (score == null || score.compareTo(BigDecimal.ZERO) < 0
                || score.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Score must be between 0 and 100");
        }
        if (score.compareTo(new BigDecimal("90")) >= 0) {
            return new BigDecimal("4.0");
        }
        if (score.compareTo(new BigDecimal("85")) >= 0) {
            return new BigDecimal("3.7");
        }
        if (score.compareTo(new BigDecimal("82")) >= 0) {
            return new BigDecimal("3.3");
        }
        if (score.compareTo(new BigDecimal("78")) >= 0) {
            return new BigDecimal("3.0");
        }
        if (score.compareTo(new BigDecimal("75")) >= 0) {
            return new BigDecimal("2.7");
        }
        if (score.compareTo(new BigDecimal("72")) >= 0) {
            return new BigDecimal("2.3");
        }
        if (score.compareTo(new BigDecimal("68")) >= 0) {
            return new BigDecimal("2.0");
        }
        if (score.compareTo(new BigDecimal("64")) >= 0) {
            return new BigDecimal("1.5");
        }
        if (score.compareTo(new BigDecimal("60")) >= 0) {
            return BigDecimal.ONE.setScale(1);
        }
        return BigDecimal.ZERO.setScale(1);
    }
}
