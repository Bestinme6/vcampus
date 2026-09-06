package com.vcampus.client.fx.academic;

import com.vcampus.common.model.CourseRequirementType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurriculumPlanFormTest {
    @Test
    void batchRequirementChangePreservesSuggestedTerms() {
        List<AcademicData.CurriculumCourse> rows = List.of(
                course(1, CourseRequirementType.REQUIRED, 3),
                course(2, CourseRequirementType.REQUIRED, 5));

        List<AcademicCommands.CurriculumCourseDraft> changed = CurriculumPlanForm.setRequirement(
                rows, Set.of(1L), CourseRequirementType.ELECTIVE);

        assertEquals(1, changed.size());
        assertEquals(rows.getFirst().recommendedTermNumber(),
                changed.getFirst().recommendedTermNumber());
        assertEquals(CourseRequirementType.ELECTIVE, changed.getFirst().requirementType());
        assertTrue(changed.getFirst().alreadyInPlan());
    }

    @Test
    void batchSuggestedTermPreservesRequirementType() {
        List<AcademicData.CurriculumCourse> rows = List.of(
                course(1, CourseRequirementType.REQUIRED, 3));

        AcademicCommands.CurriculumCourseDraft changed = CurriculumPlanForm.setRecommendedTerm(
                rows, Set.of(1L), 6).getFirst();

        assertEquals(CourseRequirementType.REQUIRED, changed.requirementType());
        assertEquals(6, changed.recommendedTermNumber());
    }

    private static AcademicData.CurriculumCourse course(
            long id, CourseRequirementType type, int term) {
        return new AcademicData.CurriculumCourse(id, "C00000" + id, "课程" + id,
                new BigDecimal("3.0"), type, term);
    }
}
