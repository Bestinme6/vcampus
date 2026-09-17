package com.vcampus.client.fx.shop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SecondhandStyleTest {
    @Test
    void usesLightBlueWhitePaletteWithoutLegacyBeigeGreen() throws Exception {
        String css;
        try (var stream = getClass().getResourceAsStream("secondhand.css")) {
            css = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertTrue(css.contains("#f3f8ff"));
        assertTrue(css.contains("#2878d0"));
        assertTrue(css.contains("#d8e8fb"));
        assertFalse(css.contains("#f7f4ed"));
        assertFalse(css.contains("#2f6b5c"));
    }
}
