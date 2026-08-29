package com.vcampus.client.ui;

import com.vcampus.common.model.ModuleCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainModuleRouteTest {
    @Test
    void libraryUsesTheEmbeddedContentRoute() {
        assertEquals("library", MainModuleRoute.route(ModuleCode.LIBRARY).orElseThrow());
    }

    @Test
    void profileAndStudentStatusUseEmbeddedContentRoutes() {
        assertEquals("personal-profile",
                MainModuleRoute.route(ModuleCode.PERSONAL_PROFILE).orElseThrow());
        assertEquals("student-status",
                MainModuleRoute.route(ModuleCode.STUDENT_STATUS).orElseThrow());
    }

    @Test
    void academicUsesTheEmbeddedContentRoute() {
        assertEquals("academic", MainModuleRoute.route(ModuleCode.ACADEMIC).orElseThrow());
    }

    @Test
    void futureModulesDoNotPretendToHaveEmbeddedPages() {
        assertTrue(MainModuleRoute.route(ModuleCode.SHOP).isEmpty());
        assertTrue(MainModuleRoute.route(ModuleCode.BANK).isEmpty());
    }

    @Test
    void forumUsesTheEmbeddedContentRoute() {
        assertEquals("forum", MainModuleRoute.route(ModuleCode.FORUM).orElseThrow());
    }
}
