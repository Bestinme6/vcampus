package com.vcampus.client.fx.academic;

import com.vcampus.common.model.EnrollmentStatus;

import java.util.Objects;

/** Pure edit/publish policy for one teaching section's gradebook. */
final class GradeEditorPolicy {
    private GradeEditorPolicy() { }

    record State(boolean editable, boolean canPublish) { }

    static State forSection(boolean gradesPublished, AcademicData.Roster roster) {
        Objects.requireNonNull(roster, "roster");
        boolean complete = roster.rows().stream()
                .filter(row -> row.status() == EnrollmentStatus.ENROLLED)
                .allMatch(row -> row.score() != null);
        boolean hasEnrolled = roster.rows().stream()
                .anyMatch(row -> row.status() == EnrollmentStatus.ENROLLED);
        return new State(!gradesPublished, !gradesPublished && hasEnrolled && complete);
    }
}
