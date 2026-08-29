package com.vcampus.client.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MainContentHostTest {
    @Test
    void lazyPageIsCreatedOnceAndReused() {
        MainContentHost host = new MainContentHost();
        AtomicInteger creations = new AtomicInteger();

        JPanel first = host.showLazy("library", () -> {
            creations.incrementAndGet();
            return new JPanel();
        });
        JPanel second = host.showLazy("library", () -> {
            creations.incrementAndGet();
            return new JPanel();
        });

        assertSame(first, second);
        assertEquals(1, creations.get());
        assertEquals("library", host.currentName());
        assertEquals(1, host.registeredCount());
    }

    @Test
    void registeredPageCanBeShownButUnknownPageIsRejected() {
        MainContentHost host = new MainContentHost();
        host.register("workspace", new JLabel("工作台"));

        host.show("workspace");

        assertEquals("workspace", host.currentName());
        assertThrows(IllegalArgumentException.class, () -> host.show("missing"));
    }
}
