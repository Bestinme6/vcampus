package com.vcampus.client.ui;

final class AcademicTabRefreshPolicy {
    static final String SECTION_MANAGEMENT_TITLE = "教学班与发布";

    private AcademicTabRefreshPolicy() {
    }

    static boolean refreshesReferenceData(String tabTitle) {
        return SECTION_MANAGEMENT_TITLE.equals(tabTitle);
    }
}
