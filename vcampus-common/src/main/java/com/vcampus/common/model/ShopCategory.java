package com.vcampus.common.model;

public enum ShopCategory {
    LEARNING_STATIONERY("学习文具"),
    DAILY_SUPPLIES("生活用品"),
    DIGITAL_ACCESSORIES("数码配件"),
    CAMPUS_MERCH("校园周边"),
    OTHER("其他");

    private final String displayName;

    ShopCategory(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static ShopCategory parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("category must not be null");
        }
        try {
            return valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown shop category: " + value, exception);
        }
    }
}
