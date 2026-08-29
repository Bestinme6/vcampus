package com.vcampus.common.model;

public enum EnrollmentStatus {
    ENROLLED("已选"),
    DROPPED("已退");

    private final String displayName;

    EnrollmentStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
