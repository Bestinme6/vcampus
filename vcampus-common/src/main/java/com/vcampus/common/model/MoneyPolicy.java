package com.vcampus.common.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

public final class MoneyPolicy {
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+(?:\\.[0-9]{1,2})?");
    private static final BigDecimal MAX = new BigDecimal("9999999999999.99");

    private MoneyPolicy() {
    }

    public static BigDecimal parsePositive(String value) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (!DECIMAL.matcher(normalized).matches()) {
            throw new IllegalArgumentException("金额格式无效");
        }
        BigDecimal amount = new BigDecimal(normalized).setScale(2, RoundingMode.UNNECESSARY);
        if (amount.signum() <= 0 || amount.compareTo(MAX) > 0) {
            throw new IllegalArgumentException("金额超出范围");
        }
        return amount;
    }

    public static String format(BigDecimal value) {
        Objects.requireNonNull(value, "value");
        return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
