package com.vcampus.common.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcademicPolicyTest {
    @Test
    void academicRolesRemainSeparatedAndCanBeCombined() {
        assertTrue(AcademicAccessPolicy.canStudy(Set.of(UserRole.STUDENT)));
        assertFalse(AcademicAccessPolicy.canTeach(Set.of(UserRole.STUDENT)));
        assertTrue(AcademicAccessPolicy.canTeach(Set.of(UserRole.TEACHER)));
        assertFalse(AcademicAccessPolicy.canManage(Set.of(UserRole.TEACHER)));
        assertTrue(AcademicAccessPolicy.canManage(Set.of(UserRole.ACADEMIC_ADMIN)));
        assertTrue(AcademicAccessPolicy.canTeach(Set.of(UserRole.SUPER_ADMIN)));
        assertTrue(AcademicAccessPolicy.canManage(Set.of(UserRole.SUPER_ADMIN)));
    }

    @Test
    void teachersAndAcademicManagersCanPublishGradesButStudentsCannot() {
        assertTrue(AcademicAccessPolicy.canPublishGrades(Set.of(UserRole.TEACHER)));
        assertTrue(AcademicAccessPolicy.canPublishGrades(Set.of(
                UserRole.TEACHER, UserRole.ACADEMIC_ADMIN)));
        assertTrue(AcademicAccessPolicy.canPublishGrades(Set.of(UserRole.SUPER_ADMIN)));
        assertFalse(AcademicAccessPolicy.canPublishGrades(Set.of(UserRole.STUDENT)));
    }

    @Test
    void gradePointBoundariesAreStable() {
        assertEquals(new BigDecimal("4.0"), GradePolicy.gradePoint(new BigDecimal("90")));
        assertEquals(new BigDecimal("3.7"), GradePolicy.gradePoint(new BigDecimal("85")));
        assertEquals(new BigDecimal("1.0"), GradePolicy.gradePoint(new BigDecimal("60")));
        assertEquals(new BigDecimal("0.0"), GradePolicy.gradePoint(new BigDecimal("59.99")));
    }
}
