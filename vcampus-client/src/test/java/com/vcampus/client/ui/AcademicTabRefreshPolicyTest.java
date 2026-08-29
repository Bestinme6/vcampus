package com.vcampus.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcademicTabRefreshPolicyTest {
    @Test
    void refreshesReferenceDataWheneverSectionManagementTabIsActivated() {
        assertTrue(AcademicTabRefreshPolicy.refreshesReferenceData("教学班与发布"));
        assertFalse(AcademicTabRefreshPolicy.refreshesReferenceData("课程管理"));
        assertFalse(AcademicTabRefreshPolicy.refreshesReferenceData("我的课表"));
    }
}
