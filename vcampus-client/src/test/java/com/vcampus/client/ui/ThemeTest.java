package com.vcampus.client.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import java.awt.Color;
import java.awt.Font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeTest {
    @Test
    void commandButtonUsesDarkBoldTextAndStablePaintedBackground() {
        JButton button = new JButton("刷新");

        Theme.styleCommandButton(button);

        assertEquals(Theme.TEXT, button.getForeground());
        assertEquals(Theme.SECONDARY, button.getBackground());
        assertTrue(button.getFont().isBold());
        assertTrue(button.isOpaque());
        assertTrue(button.isContentAreaFilled());
    }

    @Test
    void darkTextPrimaryButtonUsesBlackTextWithoutChangingItsAccentBackground() {
        JButton button = new JButton("发布帖子");

        Theme.styleDarkTextPrimaryButton(button);

        assertEquals(Color.BLACK, button.getForeground());
        assertEquals(Theme.ACCENT, button.getBackground());
    }
}
