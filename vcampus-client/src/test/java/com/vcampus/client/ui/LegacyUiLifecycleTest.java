package com.vcampus.client.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyUiLifecycleTest {
    @Test
    void closedAncestorMakesNestedComponentInactive() {
        JPanel host = new JPanel();
        JPanel module = new JPanel();
        JLabel value = new JLabel("private data");
        module.add(value);
        host.add(module);

        assertTrue(LegacyUiLifecycle.active(value));
        LegacyUiLifecycle.markClosed(host);

        assertFalse(LegacyUiLifecycle.active(host));
        assertFalse(LegacyUiLifecycle.active(module));
        assertFalse(LegacyUiLifecycle.active(value));
    }
}
