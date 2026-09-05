package com.vcampus.common.model;

public final class CurriculumPlanPolicy {
    private CurriculumPlanPolicy() {
    }

    public static void validateYears(int from, int to) {
        if (from < 2000 || to > 2100 || from > to) {
            throw new IllegalArgumentException("适用入学年份必须在 2000—2100 之间且起始年份不晚于结束年份");
        }
    }

    public static void requireEditable(CurriculumPlanStatus status) {
        if (status != CurriculumPlanStatus.DRAFT) {
            throw new IllegalStateException("只有草稿培养方案可以修改");
        }
    }

    public static boolean canTransition(CurriculumPlanStatus from, CurriculumPlanStatus to) {
        return (from == CurriculumPlanStatus.DRAFT && to == CurriculumPlanStatus.PUBLISHED)
                || (from == CurriculumPlanStatus.PUBLISHED && to == CurriculumPlanStatus.ARCHIVED);
    }
}
