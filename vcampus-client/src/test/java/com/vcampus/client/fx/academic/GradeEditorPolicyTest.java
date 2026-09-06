package com.vcampus.client.fx.academic;

import com.vcampus.common.model.EnrollmentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradeEditorPolicyTest {
    @Test
    void publishedSectionMakesEveryGradeCellReadOnly() {
        GradeEditorPolicy.State state = GradeEditorPolicy.forSection(true,
                new AcademicData.Roster(List.of(row(1, new BigDecimal("90")))));

        assertFalse(state.editable());
        assertFalse(state.canPublish());
    }

    @Test
    void publishIsDisabledUntilEveryEnrolledStudentHasScore() {
        assertFalse(GradeEditorPolicy.forSection(false,
                new AcademicData.Roster(List.of(row(1, null), row(2, new BigDecimal("88")))))
                .canPublish());
        assertTrue(GradeEditorPolicy.forSection(false,
                new AcademicData.Roster(List.of(row(1, new BigDecimal("91")),
                        row(2, new BigDecimal("88"))))).canPublish());
    }

    private static AcademicData.RosterRow row(long id, BigDecimal score) {
        return new AcademicData.RosterRow(id, id + 100, "202600" + id, "学生" + id,
                EnrollmentStatus.ENROLLED, score, score == null ? null : new BigDecimal("4.0"), "");
    }
}
