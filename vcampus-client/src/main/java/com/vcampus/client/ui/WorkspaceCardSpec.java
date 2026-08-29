package com.vcampus.client.ui;

import com.vcampus.common.model.ModuleCode;

import java.util.Objects;

public record WorkspaceCardSpec(
        ModuleCode module,
        String iconText,
        String title,
        String description) {

    public WorkspaceCardSpec {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(iconText, "iconText");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
    }
}
