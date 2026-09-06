package com.vcampus.client.fx.academic;

import com.vcampus.common.model.CurriculumPlanStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurriculumPlanViewTest {
    @Test
    void publishedPlanDisablesEveryContentMutation() {
        CurriculumPlanView.ActionState state =
                CurriculumPlanView.actionsFor(CurriculumPlanStatus.PUBLISHED, true);

        assertFalse(state.canEdit());
        assertFalse(state.canRemoveCourse());
        assertTrue(state.canCopy());
        assertTrue(state.canArchive());
    }

    @Test
    void draftCanBeEditedButCannotBeArchived() {
        CurriculumPlanView.ActionState state =
                CurriculumPlanView.actionsFor(CurriculumPlanStatus.DRAFT, true);

        assertTrue(state.canEdit());
        assertTrue(state.canRemoveCourse());
        assertTrue(state.canCopy());
        assertFalse(state.canArchive());
    }
}
