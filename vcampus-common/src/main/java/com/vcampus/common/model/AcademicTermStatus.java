package com.vcampus.common.model;

public enum AcademicTermStatus {
    PLANNED("未开始"),
    SELECTION("选课中"),
    IN_PROGRESS("进行中"),
    FINISHED("已结束");

    private final String displayName;

    AcademicTermStatus(String displayName) {
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
