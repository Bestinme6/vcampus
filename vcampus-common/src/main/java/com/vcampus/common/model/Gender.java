package com.vcampus.common.model;

public enum Gender {
    MALE("男"),
    FEMALE("女"),
    OTHER("其他"),
    UNSPECIFIED("未填写");

    private final String displayName;

    Gender(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
