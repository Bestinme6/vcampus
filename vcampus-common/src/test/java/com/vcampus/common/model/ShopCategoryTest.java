package com.vcampus.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShopCategoryTest {
    @Test
    void exposesStableWireNamesAndChineseDisplayLabels() {
        assertEquals("学习文具", ShopCategory.LEARNING_STATIONERY.displayName());
        assertEquals("生活用品", ShopCategory.DAILY_SUPPLIES.displayName());
        assertEquals("数码配件", ShopCategory.DIGITAL_ACCESSORIES.displayName());
        assertEquals("校园周边", ShopCategory.CAMPUS_MERCH.displayName());
        assertEquals("其他", ShopCategory.OTHER.displayName());
    }

    @Test
    void parsesOnlyTrimmedStableEnumWireNames() {
        assertEquals(ShopCategory.LEARNING_STATIONERY,
                ShopCategory.parse("  LEARNING_STATIONERY  "));
        assertEquals(ShopCategory.OTHER, ShopCategory.parse("OTHER"));

        assertThrows(Exception.class, () -> ShopCategory.parse("学习文具"));
        assertThrows(Exception.class, () -> ShopCategory.parse(null));
        assertThrows(Exception.class, () -> ShopCategory.parse("unknown"));
    }
}
