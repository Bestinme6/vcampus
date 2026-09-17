package com.vcampus.common.model;

public enum SecondhandCategory {
    BOOK("教材书籍"),
    DIGITAL("数码产品"),
    DAILY("生活用品"),
    CLOTHING("服饰箱包"),
    SPORTS("体育娱乐"),
    OTHER("其他");

    private final String displayName;

    SecondhandCategory(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static SecondhandCategory parse(String value) {
        if (value == null) throw new IllegalArgumentException("category must not be null");
        String normalized = value.trim();
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown secondhand category: " + value, error);
        }
    }

    public static SecondhandCategory fromStored(String value) {
        try {
            return parse(value);
        } catch (RuntimeException ignored) {
            return OTHER;
        }
    }
}
