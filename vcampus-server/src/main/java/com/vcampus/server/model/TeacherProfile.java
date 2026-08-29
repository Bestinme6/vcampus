package com.vcampus.server.model;

import java.util.Objects;

public record TeacherProfile(
        long id,
        long userId,
        String teacherNumber,
        String fullName,
        String departmentName,
        String professionalTitle,
        String phone,
        String email) {

    public TeacherProfile {
        Objects.requireNonNull(teacherNumber, "teacherNumber");
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(departmentName, "departmentName");
        Objects.requireNonNull(professionalTitle, "professionalTitle");
    }
}
