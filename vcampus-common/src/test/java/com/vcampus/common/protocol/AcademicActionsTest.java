package com.vcampus.common.protocol;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcademicActionsTest {
    @Test
    void newAcademicActionsUseTheRequiredPrefixAndValues() throws Exception {
        Set<Field> fields = Arrays.stream(Actions.class.getFields())
                .filter(field -> field.getName().startsWith("ACADEMIC_CURRICULUM_")
                        || field.getName().startsWith("ACADEMIC_SECTION_TARGETS_")
                        || field.getName().startsWith("ACADEMIC_SCHEDULE_DRAFT_")
                        || field.getName().equals("ACADEMIC_SCHEDULE_PUBLISH")
                        || field.getName().equals("ACADEMIC_ENROLLMENT_SWITCH_SECTION"))
                .collect(Collectors.toSet());

        assertEquals(16, fields.size());
        assertTrue(fields.stream().map(this::read).allMatch(value -> value.startsWith("academic.")));
        assertEquals("academic.enrollment.switchSection",
                Actions.ACADEMIC_ENROLLMENT_SWITCH_SECTION);
        assertEquals("academic.schedule.draft.save", Actions.ACADEMIC_SCHEDULE_DRAFT_SAVE);
    }

    private String read(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        }
    }
}
