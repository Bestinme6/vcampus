package com.vcampus.common.model;

public enum CourseRequirementType {
    REQUIRED("必修"),
    ELECTIVE("选修");

    private final String displayName;

    CourseRequirementType(String displayName) {
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
