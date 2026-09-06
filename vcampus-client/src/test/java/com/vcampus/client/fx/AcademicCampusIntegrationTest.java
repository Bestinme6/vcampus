package com.vcampus.client.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcademicCampusIntegrationTest {
    @Test
    void mapsCampusAcademicRoutesToNativeWorkspaceSubpages() {
        assertTrue(CampusApplication.isAcademicRoute("academic"));
        assertTrue(CampusApplication.isAcademicRoute("academic-enrollment"));
        assertEquals("overview", CampusApplication.academicSubroute("academic"));
        assertEquals("enrollment", CampusApplication.academicSubroute("academic-enrollment"));
        assertEquals("student-schedule", CampusApplication.academicSubroute("academic-student-schedule"));
        assertEquals("teacher-schedule", CampusApplication.academicSubroute("academic-teacher-schedule"));
        assertEquals("grades", CampusApplication.academicSubroute("academic-grades"));
    }

    @Test
    void sectionDeepLinkRequiresPositiveNumericId() {
        assertEquals(91L, CampusApplication.academicSectionId("academic-section/91"));
        assertNull(CampusApplication.academicSectionId("academic-section/0"));
        assertNull(CampusApplication.academicSectionId("academic-section/not-a-number"));
    }
}
