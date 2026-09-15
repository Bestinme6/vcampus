package com.vcampus.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShopReviewVocabularyTest {
    @Test
    void reviewStatusesMatchDatabaseVocabulary() {
        assertEquals(List.of("NORMAL", "HIDDEN", "DELETED"),
                Arrays.stream(ShopReviewStatus.values()).map(Enum::name).toList());
    }
}
