package com.vcampus.client.fx.academic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CourseFormPolicyTest {
    @Test
    void courseCodeIsRequiredOnlyForCreation() {
        assertEquals(List.of("课程号必须为字母 C 加 6 位数字"),
                CourseForm.validateCreate("JAVA", "Java程序设计", "3.0", "48", ""));
        assertTrue(CourseForm.validateUpdate("Java程序设计", "3.0", "48", "").isEmpty());
    }

    @Test
    void validatesCreditsHoursAndDescriptionBounds() {
        assertEquals(List.of("课程名称不能为空", "学分必须大于 0 且不超过 20",
                        "总学时必须为 1—400 的整数", "课程说明不能超过 500 位"),
                CourseForm.validateUpdate(" ", "0", "401", "x".repeat(501)));
    }
}
