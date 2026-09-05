package com.vcampus.common.model;

public enum ScheduleRevisionStatus {
    DRAFT("草稿"),
    PUBLISHED("已发布"),
    SUPERSEDED("已替代");

    private final String displayName;

    ScheduleRevisionStatus(String displayName) {
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
