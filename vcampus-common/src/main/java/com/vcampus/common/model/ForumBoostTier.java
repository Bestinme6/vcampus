package com.vcampus.common.model;

import java.math.BigDecimal;

public enum ForumBoostTier {
    FIVE("5.00", 50),
    TEN("10.00", 100),
    FIFTEEN("15.00", 150);

    private final BigDecimal amount;
    private final int initialHeat;

    ForumBoostTier(String amount, int initialHeat) {
        this.amount = new BigDecimal(amount);
        this.initialHeat = initialHeat;
    }

    public BigDecimal amount() {
        return amount;
    }

    public int initialHeat() {
        return initialHeat;
    }
}
