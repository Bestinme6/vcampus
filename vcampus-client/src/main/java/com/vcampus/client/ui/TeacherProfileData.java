package com.vcampus.client.ui;

import java.util.Map;
import java.util.Objects;

record TeacherProfileData(
        String teacherNumber,
        String fullName,
        String departmentName,
        String professionalTitle,
        String phone,
        String email) {

    static TeacherProfileData from(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        return new TeacherProfileData(
                values.getOrDefault("teacherNumber", ""),
                values.getOrDefault("fullName", ""),
                values.getOrDefault("departmentName", ""),
                values.getOrDefault("professionalTitle", ""),
                values.getOrDefault("phone", ""),
                values.getOrDefault("email", ""));
    }
}
