package com.vcampus.client.ui;

import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

final class CircularAvatarLabel extends JLabel {
    CircularAvatarLabel(String text) {
        super(text, SwingConstants.CENTER);
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D circle = (Graphics2D) graphics.create();
        try {
            circle.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            circle.setColor(getBackground());
            circle.fillOval(0, 0, getWidth(), getHeight());
        } finally {
            circle.dispose();
        }
        super.paintComponent(graphics);
    }
}
