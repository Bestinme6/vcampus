package com.vcampus.client.ui;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TeacherProfileDataTest {
    @Test
    void mapsServerFieldsForTheTeacherWindow() {
        TeacherProfileData profile = TeacherProfileData.from(Map.of(
                "teacherNumber", "T20260001",
                "fullName", "李老师",
                "departmentName", "计算机科学与工程学院",
                "professionalTitle", "讲师",
                "phone", "13900000000",
                "email", "teacher@vcampus.edu"));

        assertEquals("T20260001", profile.teacherNumber());
        assertEquals("李老师", profile.fullName());
        assertEquals("计算机科学与工程学院", profile.departmentName());
        assertEquals("讲师", profile.professionalTitle());
        assertEquals("13900000000", profile.phone());
        assertEquals("teacher@vcampus.edu", profile.email());
    }

    @Test
    void missingOptionalContactFieldsBecomeEmptyText() {
        TeacherProfileData profile = TeacherProfileData.from(Map.of(
                "teacherNumber", "T20260001",
                "fullName", "李老师",
                "departmentName", "计算机科学与工程学院",
                "professionalTitle", "讲师"));

        assertEquals("", profile.phone());
        assertEquals("", profile.email());
    }
}
