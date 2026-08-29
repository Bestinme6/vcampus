package com.vcampus.common.model;

public enum CourseSectionStatus {
    PLANNED("未开放"),
    OPEN("开放"),
    CLOSED("已关闭"),
    COMPLETED("已完成");

    private final String displayName;

    CourseSectionStatus(String displayName) {
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
