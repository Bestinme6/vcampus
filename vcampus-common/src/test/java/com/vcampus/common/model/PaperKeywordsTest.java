package com.vcampus.common.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PaperKeywordsTest {
    @Test void normalizesMultipleChineseAndEnglishSeparators() {
        assertEquals("虚拟校园；信息检索；Java", PaperKeywords.normalize(" 虚拟校园, 信息检索\nJava；虚拟校园 "));
        assertEquals(List.of("虚拟校园","信息检索","Java"), PaperKeywords.parse("虚拟校园；信息检索；Java"));
    }
    @Test void rejectsTooManyOrOversizedKeywords() {
        assertThrows(IllegalArgumentException.class,()->PaperKeywords.normalize(java.util.stream.IntStream.rangeClosed(1,13).mapToObj(i->"词"+i).collect(java.util.stream.Collectors.joining(","))));
        assertThrows(IllegalArgumentException.class,()->PaperKeywords.normalize("很".repeat(41)));
    }
}
