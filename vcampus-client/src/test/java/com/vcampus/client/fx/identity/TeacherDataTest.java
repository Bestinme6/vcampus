package com.vcampus.client.fx.identity;

import com.vcampus.common.protocol.ResponseMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TeacherDataTest {
    @Test void mapsTeacherAndDefaultsOptionalContact() {
        TeacherData teacher = TeacherData.from(ResponseMessage.success("r","ok", Map.of(
                "teacherNumber","T20260001", "fullName","李老师", "departmentName","计算机学院",
                "professionalTitle","讲师")));
        assertEquals("T20260001", teacher.teacherNumber());
        assertEquals("", teacher.phone());
        assertEquals("", teacher.email());
    }
}
