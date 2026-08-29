package com.vcampus.common.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RowCodecTest {
    @Test
    void roundTripSupportsEmptyChineseAndDelimiterLikeText() {
        String encoded = RowCodec.encode("", "数据库系统", "含:冒号|竖线", "2026-2027");

        assertEquals(List.of("", "数据库系统", "含:冒号|竖线", "2026-2027"), RowCodec.decode(encoded));
    }

    @Test
    void malformedLengthIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RowCodec.decode("10:short"));
    }
}
