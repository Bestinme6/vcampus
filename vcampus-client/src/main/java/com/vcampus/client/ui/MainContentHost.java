package com.vcampus.client.ui;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.CardLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

final class MainContentHost extends JPanel {
    private final CardLayout cards = new CardLayout();
    private final Map<String, JComponent> contents = new LinkedHashMap<>();
    private String currentName;

    MainContentHost() {
        setLayout(cards);
        setBackground(Theme.BACKGROUND);
    }

    void register(String name, JComponent content) {
        String normalizedName = requireName(name);
        Objects.requireNonNull(content, "content");
        if (contents.containsKey(normalizedName)) {
            throw new IllegalArgumentException("内容页面已经注册: " + normalizedName);
        }
        contents.put(normalizedName, content);
        add(content, normalizedName);
    }

    void show(String name) {
        String normalizedName = requireName(name);
        if (!contents.containsKey(normalizedName)) {
            throw new IllegalArgumentException("内容页面尚未注册: " + normalizedName);
        }
        cards.show(this, normalizedName);
        currentName = normalizedName;
    }

    @SuppressWarnings("unchecked")
    <T extends JComponent> T showLazy(String name, Supplier<T> factory) {
        String normalizedName = requireName(name);
        Objects.requireNonNull(factory, "factory");
        JComponent content = contents.get(normalizedName);
        if (content == null) {
            content = Objects.requireNonNull(factory.get(), "factory result");
            register(normalizedName, content);
        }
        show(normalizedName);
        return (T) content;
    }

    String currentName() {
        return currentName;
    }

    int registeredCount() {
        return contents.size();
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("内容页面名称不能为空");
        }
        return name;
    }
}
