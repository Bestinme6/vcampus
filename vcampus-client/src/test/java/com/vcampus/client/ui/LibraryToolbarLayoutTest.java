package com.vcampus.client.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryToolbarLayoutTest {
    @Test
    void keepsCatalogActionsOnOneRowAtWorkspaceWidth() throws Exception {
        AtomicReference<AssertionError> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            TestLibraryPanel owner = new TestLibraryPanel();
            LibraryCatalogToolbar catalog = owner.catalogToolbar();
            JPanel toolbar = catalog.panel();
            toolbar.setSize(844, 84);
            toolbar.doLayout();

            try {
                assertEquals(1L, Arrays.stream(toolbar.getComponents())
                        .filter(JButton.class::isInstance)
                        .map(component -> component.getY())
                        .distinct()
                        .count(), "书目操作按钮不应自动换到第二行");
                int rightEdge = Arrays.stream(toolbar.getComponents())
                        .filter(JButton.class::isInstance)
                        .mapToInt(component -> component.getX() + component.getWidth())
                        .max()
                        .orElseThrow();
                assertTrue(rightEdge <= toolbar.getWidth() - toolbar.getInsets().right,
                        "所有操作按钮都应完整显示在工具栏内");
                assertTrue(catalog.keyword().getWidth() >= 120, "搜索框应保持可用宽度");
                assertTrue(catalog.keyword().getWidth() < catalog.keyword().getPreferredSize().width,
                        "空间不足时应优先压缩搜索框");
                assertEquals("新增馆藏", catalog.addCopy().getText());
                assertEquals("为选中书目新增实体馆藏", catalog.addCopy().getToolTipText());
                assertEquals("启用/停用", catalog.toggle().getText());
                int minimumWidth = toolbar.getMinimumSize().width;
                toolbar.setSize(minimumWidth, 84);
                toolbar.doLayout();
                int minimumRightEdge = Arrays.stream(toolbar.getComponents())
                        .mapToInt(component -> component.getX() + component.getWidth())
                        .max()
                        .orElseThrow();
                assertTrue(minimumRightEdge <= minimumWidth - toolbar.getInsets().right,
                        "按报告的最小宽度布局时控件不应越界");
                assertTrue(catalog.keyword().getWidth() >= 120,
                        "最小宽度布局仍应保留可用搜索框");
            } catch (AssertionError error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) throw failure.get();
    }

    private static final class TestLibraryPanel extends LibraryPanel {
        private TestLibraryPanel() {
            super(null, "test-session");
        }

        private LibraryCatalogToolbar catalogToolbar() {
            return LibraryCatalogToolbar.create(this, field(14));
        }
    }
}
