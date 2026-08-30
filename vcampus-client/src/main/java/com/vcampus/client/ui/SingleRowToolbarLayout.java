package com.vcampus.client.ui;

import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.util.Arrays;
import java.util.List;

final class SingleRowToolbarLayout implements LayoutManager {
    private static final int MINIMUM_FIELD_WIDTH = 120;

    private final int horizontalGap;
    private final int verticalGap;

    SingleRowToolbarLayout(int horizontalGap, int verticalGap) {
        this.horizontalGap = horizontalGap;
        this.verticalGap = verticalGap;
    }

    @Override
    public void addLayoutComponent(String name, Component component) {
    }

    @Override
    public void removeLayoutComponent(Component component) {
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        return layoutSize(parent, false);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        return layoutSize(parent, true);
    }

    @Override
    public void layoutContainer(Container parent) {
        synchronized (parent.getTreeLock()) {
            List<Component> components = visibleComponents(parent);
            Insets insets = parent.getInsets();
            int[] widths = components.stream()
                    .map(Component::getPreferredSize)
                    .mapToInt(size -> size.width)
                    .toArray();
            int availableWidth = parent.getWidth() - insets.left - insets.right
                    - 2 * horizontalGap - Math.max(0, components.size() - 1) * horizontalGap;
            int deficit = Math.max(0, Arrays.stream(widths).sum() - availableWidth);
            for (int index = 0; index < components.size() && deficit > 0; index++) {
                if (!(components.get(index) instanceof JTextField)) continue;
                int shrinkable = Math.max(0, widths[index] - MINIMUM_FIELD_WIDTH);
                int shrink = Math.min(deficit, shrinkable);
                widths[index] -= shrink;
                deficit -= shrink;
            }

            int x = insets.left + horizontalGap;
            int contentHeight = parent.getHeight() - insets.top - insets.bottom - 2 * verticalGap;
            for (int index = 0; index < components.size(); index++) {
                Component component = components.get(index);
                int height = component.getPreferredSize().height;
                int y = insets.top + verticalGap + Math.max(0, (contentHeight - height) / 2);
                component.setBounds(x, y, widths[index], height);
                x += widths[index] + horizontalGap;
            }
        }
    }

    private Dimension layoutSize(Container parent, boolean minimum) {
        synchronized (parent.getTreeLock()) {
            List<Component> components = visibleComponents(parent);
            Insets insets = parent.getInsets();
            int width = components.stream()
                    .mapToInt(component -> componentWidth(component, minimum))
                    .sum();
            int height = components.stream()
                    .map(Component::getPreferredSize)
                    .mapToInt(size -> size.height)
                    .max()
                    .orElse(0);
            width += insets.left + insets.right + 2 * horizontalGap
                    + Math.max(0, components.size() - 1) * horizontalGap;
            height += insets.top + insets.bottom + 2 * verticalGap;
            return new Dimension(width, height);
        }
    }

    private List<Component> visibleComponents(Container parent) {
        return Arrays.stream(parent.getComponents())
                .filter(Component::isVisible)
                .toList();
    }

    private int componentWidth(Component component, boolean minimum) {
        int preferredWidth = component.getPreferredSize().width;
        return minimum && component instanceof JTextField
                ? Math.min(preferredWidth, Math.max(component.getMinimumSize().width,
                MINIMUM_FIELD_WIDTH))
                : preferredWidth;
    }
}
