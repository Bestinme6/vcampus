package com.vcampus.client.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AcademicModuleNavigationTest {
    @Test
    void teacherScheduleDeepLinkFindsItsTab() {
        assertEquals(1, AcademicModuleNavigation.teacherScheduleIndex(
                List.of("课程管理", "教师课表", "授课与成绩")));
    }

    @Test
    void studentGradesDeepLinkFindsItsTab() {
        assertEquals(2, AcademicModuleNavigation.studentGradesIndex(
                List.of("选课中心", "我的课表", "我的成绩")));
    }

    @Test
    void unavailableDeepLinkDoesNotSelectAnotherTab() {
        assertEquals(-1, AcademicModuleNavigation.teacherScheduleIndex(
                List.of("选课中心", "我的成绩")));
        assertEquals(-1, AcademicModuleNavigation.studentGradesIndex(
                List.of("教师课表", "授课与成绩")));
    }
}
