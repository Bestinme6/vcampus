package com.vcampus.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SecondhandCategoryTest {
    @Test
    void exposesStableCodesAndChineseLabels() {
        assertEquals("教材书籍", SecondhandCategory.BOOK.displayName());
        assertEquals("数码产品", SecondhandCategory.DIGITAL.displayName());
        assertEquals("生活用品", SecondhandCategory.DAILY.displayName());
        assertEquals("服饰箱包", SecondhandCategory.CLOTHING.displayName());
        assertEquals("体育娱乐", SecondhandCategory.SPORTS.displayName());
        assertEquals("其他", SecondhandCategory.OTHER.displayName());
    }

    @Test
    void parsesOnlyStableCodesAndFallsBackForLegacyReads() {
        assertEquals(SecondhandCategory.BOOK, SecondhandCategory.parse("  BOOK  "));
        assertEquals(SecondhandCategory.OTHER, SecondhandCategory.fromStored("legacy-category"));
        assertEquals(SecondhandCategory.OTHER, SecondhandCategory.fromStored(null));

        assertThrows(IllegalArgumentException.class, () -> SecondhandCategory.parse("教材书籍"));
        assertThrows(IllegalArgumentException.class, () -> SecondhandCategory.parse("unknown"));
        assertThrows(IllegalArgumentException.class, () -> SecondhandCategory.parse(null));
    }
}
