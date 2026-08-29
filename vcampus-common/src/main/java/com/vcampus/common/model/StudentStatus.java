package com.vcampus.common.model;

public enum StudentStatus {
    ENROLLED("在读"),
    SUSPENDED("休学"),
    WITHDRAWN("退学"),
    GRADUATED("毕业");

    private final String displayName;

    StudentStatus(String displayName) {
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
