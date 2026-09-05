package com.vcampus.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurriculumPlanPolicyTest {
    @Test
    void exposesChineseDisplayNamesForCurriculumVocabulary() {
        assertEquals("必修", CourseRequirementType.REQUIRED.displayName());
        assertEquals("选修", CourseRequirementType.ELECTIVE.displayName());
        assertEquals("草稿", CurriculumPlanStatus.DRAFT.displayName());
        assertEquals("已发布", CurriculumPlanStatus.PUBLISHED.displayName());
        assertEquals("已归档", CurriculumPlanStatus.ARCHIVED.displayName());
        assertEquals("草稿", ScheduleRevisionStatus.DRAFT.displayName());
        assertEquals("已发布", ScheduleRevisionStatus.PUBLISHED.displayName());
        assertEquals("已替代", ScheduleRevisionStatus.SUPERSEDED.displayName());
    }

    @Test
    void validatesInclusiveAcademicYearBounds() {
        CurriculumPlanPolicy.validateYears(2000, 2100);

        assertThrows(IllegalArgumentException.class,
                () -> CurriculumPlanPolicy.validateYears(1999, 2100));
        assertThrows(IllegalArgumentException.class,
                () -> CurriculumPlanPolicy.validateYears(2000, 2101));
        assertThrows(IllegalArgumentException.class,
                () -> CurriculumPlanPolicy.validateYears(2026, 2025));
    }

    @Test
    void publishedCurriculumIsImmutableButCanBeArchived() {
        assertThrows(IllegalStateException.class,
                () -> CurriculumPlanPolicy.requireEditable(CurriculumPlanStatus.PUBLISHED));
        assertTrue(CurriculumPlanPolicy.canTransition(
                CurriculumPlanStatus.DRAFT, CurriculumPlanStatus.PUBLISHED));
        assertTrue(CurriculumPlanPolicy.canTransition(
                CurriculumPlanStatus.PUBLISHED, CurriculumPlanStatus.ARCHIVED));
        assertFalse(CurriculumPlanPolicy.canTransition(
                CurriculumPlanStatus.ARCHIVED, CurriculumPlanStatus.PUBLISHED));
    }
}
