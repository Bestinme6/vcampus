package com.vcampus.client.fx.academic;

import com.vcampus.common.model.ScheduleSlot;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Pure state rules for recoverable schedule-editor failures. */
final class ScheduleEditorPolicy {
    private ScheduleEditorPolicy() {
    }

    record FailureState(List<ScheduleSlot> unsavedDraft, boolean reloadRequired, String message) {
        FailureState {
            unsavedDraft = List.copyOf(unsavedDraft);
            message = Objects.requireNonNullElse(message, "请求失败");
        }
    }

    static FailureState afterFailure(List<ScheduleSlot> localDraft, String message) {
        String text = Objects.requireNonNullElse(message, "请求失败");
        String normalized = text.toLowerCase(Locale.ROOT);
        boolean stale = normalized.contains("其他管理员") || normalized.contains("预期修订")
                || normalized.contains("stale") || normalized.contains("revision");
        return new FailureState(Objects.requireNonNull(localDraft, "localDraft"), stale, text);
    }
}
