package com.vcampus.client.ui;

import com.vcampus.common.model.ModuleCode;

import java.util.Objects;
import java.util.Optional;

final class MainModuleRoute {
    private MainModuleRoute() {
    }

    static Optional<String> route(ModuleCode module) {
        Objects.requireNonNull(module, "module");
        return switch (module) {
            case PERSONAL_PROFILE -> Optional.of("personal-profile");
            case STUDENT_STATUS -> Optional.of("student-status");
            case ACADEMIC -> Optional.of("academic");
            case LIBRARY -> Optional.of("library");
            case FORUM -> Optional.of("forum");
            default -> Optional.empty();
        };
    }
}
