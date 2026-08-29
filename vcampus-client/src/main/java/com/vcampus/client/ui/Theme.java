package com.vcampus.client.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;

public final class Theme {
    static final Color BACKGROUND = Color.WHITE;
    static final Color SURFACE = Color.WHITE;
    static final Color SURFACE_HOVER = new Color(244, 247, 252);
    static final Color HEADER = new Color(235, 242, 252);
    static final Color BORDER = new Color(210, 219, 232);
    static final Color TEXT = new Color(31, 41, 55);
    static final Color MUTED = new Color(100, 116, 139);
    static final Color ACCENT = new Color(47, 95, 203);
    static final Color SECONDARY = new Color(220, 232, 255);
    static final Color SUCCESS = new Color(36, 122, 77);
    static final Color DANGER = new Color(190, 55, 55);
    static final Color PRIMARY_TEXT = Color.WHITE;

    private Theme() {
    }

    public static void install() {
        Font font = new Font("Microsoft YaHei UI", Font.PLAIN, 14);
        UIManager.put("Label.font", font);
        UIManager.put("Button.font", font);
        UIManager.put("TextField.font", font);
        UIManager.put("PasswordField.font", font);
        UIManager.put("OptionPane.messageFont", font);
        UIManager.put("OptionPane.buttonFont", font);
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Button.background", SURFACE_HOVER);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("TextField.background", BACKGROUND);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", TEXT);
        UIManager.put("PasswordField.background", BACKGROUND);
        UIManager.put("PasswordField.foreground", TEXT);
        UIManager.put("PasswordField.caretForeground", TEXT);
        UIManager.put("TextArea.background", BACKGROUND);
        UIManager.put("TextArea.foreground", TEXT);
        UIManager.put("ComboBox.background", BACKGROUND);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("Spinner.background", BACKGROUND);
        UIManager.put("Spinner.foreground", TEXT);
        UIManager.put("FormattedTextField.background", BACKGROUND);
        UIManager.put("FormattedTextField.foreground", TEXT);
        UIManager.put("Table.background", BACKGROUND);
        UIManager.put("Table.foreground", TEXT);
        UIManager.put("Table.gridColor", BORDER);
        UIManager.put("Table.selectionBackground", SECONDARY);
        UIManager.put("Table.selectionForeground", TEXT);
        UIManager.put("TableHeader.background", HEADER);
        UIManager.put("TableHeader.foreground", TEXT);
        UIManager.put("ScrollPane.background", BACKGROUND);
        UIManager.put("Viewport.background", BACKGROUND);
        UIManager.put("TabbedPane.background", BACKGROUND);
        UIManager.put("TabbedPane.foreground", TEXT);
        UIManager.put("TabbedPane.selected", SURFACE_HOVER);
        UIManager.put("SplitPane.background", BACKGROUND);
        UIManager.put("OptionPane.background", BACKGROUND);
        UIManager.put("OptionPane.messageArea.background", BACKGROUND);
        UIManager.put("OptionPane.foreground", TEXT);
        UIManager.put("TitledBorder.titleColor", TEXT);
    }

    static void styleField(JComponent field) {
        field.setForeground(TEXT);
        field.setBackground(SURFACE_HOVER);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
    }

    static void stylePrimaryButton(JButton button) {
        button.setForeground(PRIMARY_TEXT);
        button.setBackground(ACCENT);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(11, 22, 11, 22));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    static void styleDarkTextPrimaryButton(JButton button) {
        stylePrimaryButton(button);
        button.setForeground(Color.BLACK);
    }

    static void styleCommandButton(JButton button) {
        button.setForeground(TEXT);
        button.setBackground(SECONDARY);
        button.setFont(button.getFont().deriveFont(Font.BOLD));
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT),
                BorderFactory.createEmptyBorder(10, 20, 10, 20)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    static void styleQuietButton(JButton button) {
        button.setForeground(TEXT);
        button.setBackground(SURFACE_HOVER);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(10, 18, 10, 18)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }
}
