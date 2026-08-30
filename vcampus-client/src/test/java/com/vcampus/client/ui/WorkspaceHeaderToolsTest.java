package com.vcampus.client.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkspaceHeaderToolsTest {
    @Test
    void headerToolsContainsOnlyTheUserAvatar() throws Exception {
        onEdt(() -> {
            JPanel tools = WorkspaceHeaderTools.create("彼", "STUDENT");

            assertEquals(1, tools.getComponentCount());
            assertFalse(Arrays.stream(tools.getComponents()).anyMatch(JTextField.class::isInstance));
            JLabel avatar = (JLabel) tools.getComponent(0);
            assertEquals("彼", avatar.getText());
            assertEquals("当前角色：STUDENT", avatar.getToolTipText());
            assertEquals(SwingConstants.CENTER, avatar.getHorizontalAlignment());
            assertEquals(Theme.SECONDARY, avatar.getBackground());
            assertEquals(Theme.ACCENT, avatar.getForeground());
        });
    }

    @Test
    void avatarStaysCircularInsideATallerHeader() throws Exception {
        onEdt(() -> {
            JPanel header = new JPanel(new BorderLayout());
            JPanel titles = new JPanel();
            titles.setPreferredSize(new Dimension(180, 64));
            JPanel tools = WorkspaceHeaderTools.create("彼", "STUDENT");
            header.add(titles, BorderLayout.WEST);
            header.add(tools, BorderLayout.EAST);
            header.setSize(320, 64);
            header.doLayout();
            tools.doLayout();

            JLabel avatar = (JLabel) tools.getComponent(0);
            assertEquals(42, avatar.getWidth());
            assertEquals(42, avatar.getHeight());
            BufferedImage image = new BufferedImage(
                    avatar.getWidth(), avatar.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                avatar.paint(graphics);
            } finally {
                graphics.dispose();
            }

            assertEquals(0, alpha(image.getRGB(0, 0)), "圆形头像左上角应保持透明");
            assertEquals(0, alpha(image.getRGB(41, 0)), "圆形头像右上角应保持透明");
            assertEquals(0, alpha(image.getRGB(0, 41)), "圆形头像左下角应保持透明");
            assertEquals(0, alpha(image.getRGB(41, 41)), "圆形头像右下角应保持透明");
            assertEquals(255, alpha(image.getRGB(21, 21)), "圆形头像中心应绘制背景");
        });
    }

    private int alpha(int argb) {
        return argb >>> 24;
    }

    private void onEdt(Runnable assertions) throws Exception {
        AtomicReference<AssertionError> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                assertions.run();
            } catch (AssertionError error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) throw failure.get();
    }
}
