package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.UserRole;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyModalLogoutTest {
    @Test void accountValidationCannotReopenItsFormAfterLogout() throws Exception {
        checkValidationLogout("创建账号", (frame, bridge) -> assertTrue(AccountCreateDialog.showDialog(bridge.content(),
                new AccountViewData.ReferenceData(List.of(), List.of(), List.of())).isEmpty()));
    }

    @Test void sectionValidationCannotReopenItsFormAfterLogout() throws Exception {
        checkValidationLogout("新增教学班 · 图形化排课", (frame, bridge) -> assertNull(SectionCreateDialog.show(
                bridge.content(), new VCampusClient("127.0.0.1", 1), "test-token",
                new AcademicViewData.ReferenceData(List.of(), List.of(), List.of()), null)));
    }

    private void checkValidationLogout(String formTitle, ModalAction action) throws Exception {
        AtomicReference<JFrame> host = new AtomicReference<>();
        AtomicReference<LegacyModuleBridge> bridge = new AtomicReference<>();
        AtomicInteger openedForms = new AtomicInteger();
        AtomicBoolean sawValidation = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            bridge.set(new LegacyModuleBridge(new VCampusClient("127.0.0.1", 1), "test-token",
                    Set.of(UserRole.SUPER_ADMIN), () -> {}, () -> {}, () -> {}));
            JFrame frame = new JFrame(); frame.setContentPane(bridge.get().content()); frame.pack(); host.set(frame);
        });
        AWTEventListener observer = event -> {
            if (!(event instanceof WindowEvent windowEvent) || windowEvent.getID() != WindowEvent.WINDOW_OPENED
                    || !(windowEvent.getWindow() instanceof JDialog dialog) || dialog.getOwner() != host.get()) return;
            SwingUtilities.invokeLater(() -> {
                try {
                    if (dialog.getTitle().equals(formTitle)) {
                        if (openedForms.incrementAndGet() == 1) findOptionPane(dialog).setValue(JOptionPane.OK_OPTION);
                        else dialog.dispose(); // Ensure the regression fails without leaving a modal open.
                    } else {
                        sawValidation.set(true);
                        bridge.get().close();
                    }
                } catch (Throwable error) { failure.set(error); dialog.dispose(); }
            });
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(observer, AWTEvent.WINDOW_EVENT_MASK);
        try {
            SwingUtilities.invokeLater(() -> {
                try { action.show(host.get(), bridge.get()); }
                catch (Throwable error) { failure.set(error); }
                finally { finished.countDown(); }
            });
            assertTrue(finished.await(5, TimeUnit.SECONDS), "modal must return when its session closes");
            assertNull(failure.get());
            assertTrue(sawValidation.get(), "exercise logout while the validation warning is open");
            assertEquals(1, openedForms.get(), "the previous session's form must not reopen");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                bridge.get().close();
                for (Window dialog : host.get().getOwnedWindows()) dialog.dispose();
                host.get().dispose();
            });
            Toolkit.getDefaultToolkit().removeAWTEventListener(observer);
        }
    }

    private static JOptionPane findOptionPane(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JOptionPane pane) return pane;
            if (child instanceof Container nested) {
                JOptionPane result = findOptionPane(nested);
                if (result != null) return result;
            }
        }
        return null;
    }

    @FunctionalInterface private interface ModalAction { void show(JFrame frame, LegacyModuleBridge bridge); }
}
