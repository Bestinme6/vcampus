package com.vcampus.client.ui;

import java.util.List;
import java.util.Objects;

final class AcademicModuleNavigation {
    private static final String TEACHER_SCHEDULE = "教师课表";
    private static final String STUDENT_GRADES = "我的成绩";

    private AcademicModuleNavigation() {
    }

    static int teacherScheduleIndex(List<String> tabTitles) {
        return indexOf(tabTitles, TEACHER_SCHEDULE);
    }

    static int studentGradesIndex(List<String> tabTitles) {
        return indexOf(tabTitles, STUDENT_GRADES);
    }

    private static int indexOf(List<String> tabTitles, String target) {
        Objects.requireNonNull(tabTitles, "tabTitles");
        return tabTitles.indexOf(target);
    }
}
