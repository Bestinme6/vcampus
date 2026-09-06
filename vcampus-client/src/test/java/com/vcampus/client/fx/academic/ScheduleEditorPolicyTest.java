package com.vcampus.client.fx.academic;

import com.vcampus.common.model.ScheduleSlot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleEditorPolicyTest {
    @Test
    void staleDraftKeepsLocalEditsAndRequiresReload() {
        List<ScheduleSlot> local = List.of(new ScheduleSlot(1, 1, 2, 1, 16, "教一-101"));

        ScheduleEditorPolicy.FailureState state = ScheduleEditorPolicy.afterFailure(
                local, "排课草稿已被其他管理员更新");

        assertEquals(local, state.unsavedDraft());
        assertTrue(state.reloadRequired());
    }

    @Test
    void ordinaryValidationFailureKeepsDraftWithoutForcingReload() {
        List<ScheduleSlot> local = List.of(new ScheduleSlot(3, 3, 4, 1, 8, "实验楼-204"));

        ScheduleEditorPolicy.FailureState state = ScheduleEditorPolicy.afterFailure(
                local, "课表存在时间冲突");

        assertEquals(local, state.unsavedDraft());
        assertFalse(state.reloadRequired());
    }
}
