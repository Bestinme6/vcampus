package com.vcampus.client.ui;

import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;

final class LegacyUiLifecycle {
    private static final String CLOSED_KEY = "vcampus.legacy.closed";

    private LegacyUiLifecycle() {
    }

    static void markClosed(Component component) {
        if (component instanceof JComponent jComponent) {
            jComponent.putClientProperty(CLOSED_KEY, Boolean.TRUE);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                markClosed(child);
            }
        }
    }

    static boolean active(Component component) {
        for (Component current = component; current != null; current = current.getParent()) {
            if (current instanceof JComponent jComponent
                    && Boolean.TRUE.equals(jComponent.getClientProperty(CLOSED_KEY))) {
                return false;
            }
        }
        return true;
    }
}
