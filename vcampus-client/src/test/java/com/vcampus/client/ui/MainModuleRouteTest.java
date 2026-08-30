package com.vcampus.client.ui;

import com.vcampus.common.model.ModuleCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void shopUsesTheEmbeddedContentRoute() {
        assertEquals("shop", MainModuleRoute.route(ModuleCode.SHOP).orElseThrow());
    }

    @Test
    void bankUsesTheEmbeddedContentRoute() {
        assertEquals("bank", MainModuleRoute.route(ModuleCode.BANK).orElseThrow());
    }

    @Test
    void forumUsesTheEmbeddedContentRoute() {
        assertEquals("forum", MainModuleRoute.route(ModuleCode.FORUM).orElseThrow());
    }
}
