package com.vcampus.client.fx.academic;

import com.vcampus.common.model.CourseSectionStatus;
import com.vcampus.common.model.EnrollmentStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrollmentCenterPolicyTest {
    @Test
    void selectedCourseOffersSwitchOnlyToAnotherEligibleSection() {
        AcademicData.AvailableSection selected = section(1, true, false, false);
        AcademicData.AvailableSection candidate = section(2, false, false, false);

        EnrollmentCenterView.ActionState actions = EnrollmentCenterView.actionsFor(selected, candidate);

        assertFalse(actions.canEnroll());
        assertTrue(actions.canSwitch());
        assertFalse(actions.canDropCandidate());
    }

    @Test
    void fullOrConflictingSectionCannotBeSubmitted() {
        assertFalse(EnrollmentCenterView.actionsFor(null,
                section(2, false, true, false)).canEnroll());
        assertFalse(EnrollmentCenterView.actionsFor(null,
                section(3, false, false, true)).canEnroll());
    }

    private static AcademicData.AvailableSection section(long id, boolean enrolled,
                                                          boolean full, boolean conflict) {
        return new AcademicData.AvailableSection(id, 7, "2026 秋", "CS-0" + id,
                41, "张老师", 40, full ? 40 : 20, CourseSectionStatus.OPEN,
                false, "周一 1-2节", "教一-101", enrolled ? id + 100 : null,
                enrolled ? EnrollmentStatus.ENROLLED : null, full, conflict);
    }
}
