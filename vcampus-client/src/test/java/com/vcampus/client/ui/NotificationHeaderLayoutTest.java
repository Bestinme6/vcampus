package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GridBagLayout;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationHeaderLayoutTest {
    @Test
    void markAllReadSharesTheQueryRowInsteadOfTheSourceFilterRow() throws Exception {
        runOnEdt(() -> {
            NotificationPanel panel = new NotificationPanel(
                    new VCampusClient("localhost", 1),
                    "test-session",
                    destination -> { },
                    () -> { });

            JButton query = findButton(panel, "查询");
            JButton markAllRead = findButton(panel, "一键已读");
            JButton shopFilter = findButton(panel, "商店通知");

            assertNotNull(query);
            assertNotNull(markAllRead);
            assertNotNull(shopFilter);
            assertSame(query.getParent(), markAllRead.getParent(),
                    "一键已读应放在查询按钮右侧并与查询控件保持同一行");
            assertNotSame(shopFilter.getParent(), markAllRead.getParent(),
                    "一键已读不应继续占用通知来源筛选行");

            Container queryRow = query.getParent();
            JTextField keyword = findComponent(queryRow, JTextField.class);
            assertNotNull(keyword);
            assertInstanceOf(GridBagLayout.class, queryRow.getLayout(),
                    "查询行应让关键词输入框吸收剩余宽度");

            queryRow.setSize(700, 40);
            queryRow.doLayout();
            assertTrue(markAllRead.getX() >= query.getX() + query.getWidth(),
                    "一键已读必须完整位于查询按钮右侧，不能重叠或换到其左侧");
            assertTrue(keyword.getWidth() > keyword.getPreferredSize().width,
                    "查询行变宽时应由关键词输入框伸展，固定操作按钮不应被拉伸");
        });
    }

    private static JButton findButton(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (component instanceof Container child) {
                JButton match = findButton(child, text);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static <T extends Component> T findComponent(Container root, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                T match = findComponent(child, type);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static void runOnEdt(Runnable action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                action.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        if (failure.get() instanceof AssertionError assertionError) {
            throw assertionError;
        }
        if (failure.get() != null) {
            throw new RuntimeException(failure.get());
        }
    }
}
