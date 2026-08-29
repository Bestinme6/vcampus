package com.vcampus.client.ui;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

class RoundedPanel extends JPanel {
    private final int arc;
    private final Color fill;

    RoundedPanel(Color fill, int arc) {
        this.fill = fill;
        this.arc = arc;
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D copy = (Graphics2D) graphics.create();
        copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        copy.setColor(fill);
        copy.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
        copy.dispose();
        super.paintComponent(graphics);
    }
}
