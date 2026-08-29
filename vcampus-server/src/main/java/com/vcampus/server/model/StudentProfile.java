package com.vcampus.server.model;

import com.vcampus.common.model.Gender;
import com.vcampus.common.model.StudentStatus;

import java.time.LocalDate;

public record StudentProfile(
        long id,
        long userId,
        String studentNumber,
        String fullName,
        Gender gender,
        LocalDate birthDate,
        long departmentId,
        String departmentName,
        long majorId,
        String majorName,
        long classId,
        String className,
        int enrollmentYear,
        StudentStatus status,
        String phone,
        String email,
        String address) {
}
