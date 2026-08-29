package com.vcampus.common.protocol;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForumActionsTest {
    @Test
    void forumActionsHaveRequiredPrefixAndAreUnique() throws Exception {
        Set<Field> fields = Arrays.stream(Actions.class.getFields())
                .filter(field -> field.getName().startsWith("FORUM_"))
                .collect(Collectors.toSet());
        Set<String> values = fields.stream()
                .map(this::read)
                .collect(Collectors.toSet());

        assertEquals(14, fields.size());
        assertEquals(14, values.size());
        assertTrue(values.stream().allMatch(value -> value.startsWith("forum.")));
        assertEquals("forum.admin.post.moderate", Actions.FORUM_ADMIN_POST_MODERATE);
    }

    private String read(Field field) {
        try {
            assertTrue(Modifier.isStatic(field.getModifiers()));
            return (String) field.get(null);
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        }
    }
}
