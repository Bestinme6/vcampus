package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationType;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.MessageCodec;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.JDialog;
import javax.swing.JFrame;
import java.awt.Dialog;
import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.WindowEvent;
import java.awt.event.AWTEventListener;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyModuleBridgeTest {
    @Test
    void bankNotificationUsesNativeLedgerWithoutOpeningSwingBank() throws Exception {
        AtomicInteger opened=new AtomicInteger();
        var bridge=onEdt(()->new LegacyModuleBridge(new VCampusClient("127.0.0.1",1),"token",
                Set.of(UserRole.STUDENT),()->{},()->{},()->{},null,null,null,null,opened::incrementAndGet));
        onEdt(()->{navigateFromNotification(bridge,new NotificationDestination(NotificationTarget.BANK_LEDGER,21L));return null;});
        assertEquals(1,opened.get());
        assertEquals(0,onEdt(()->bridge.content().getComponentCount()));
        onEdt(()->{bridge.close();navigateFromNotification(bridge,new NotificationDestination(NotificationTarget.BANK_LEDGER,21L));return null;});
        assertEquals(1,opened.get());
    }
    @Test
    void libraryNotificationsUseExternalJavaFxRoutes() throws Exception {
        AtomicInteger loans = new AtomicInteger();
        AtomicReference<Long> book = new AtomicReference<>();
        var bridge = onEdt(() -> new LegacyModuleBridge(new VCampusClient("127.0.0.1",1),"token",
                Set.of(UserRole.STUDENT),()->{},()->{},()->{},null,loans::incrementAndGet,book::set));
        onEdt(() -> {
            navigateFromNotification(bridge,new NotificationDestination(NotificationTarget.LIBRARY_LOANS,7L));
            navigateFromNotification(bridge,new NotificationDestination(NotificationTarget.LIBRARY_CATALOG,88L));
            return null;
        });
        assertEquals(1,loans.get());
        assertEquals(88L,book.get());
        assertEquals(0,onEdt(()->bridge.content().getComponentCount()));
        onEdt(() -> {bridge.close();return null;});
    }

    @Test
    void forumNotificationUsesExternalRouteAndDoesNotOpenLegacyForum() throws Exception {
        AtomicReference<NotificationDestination> opened = new AtomicReference<>();
        var bridge = onEdt(() -> new LegacyModuleBridge(new VCampusClient("127.0.0.1",1),"token",
                Set.of(UserRole.STUDENT),()->{},()->{},()->{},id->opened.set(new NotificationDestination(NotificationTarget.FORUM_POST,id))));
        Method navigate = LegacyModuleBridge.class.getDeclaredMethod("navigateFromNotification",NotificationDestination.class);
        navigate.setAccessible(true);
        var destination = new NotificationDestination(NotificationTarget.FORUM_POST,42L);
        onEdt(() -> {navigate.invoke(bridge,destination);return null;});
        assertEquals(destination,opened.get());
        assertEquals(0,onEdt(()->bridge.content().getComponentCount()));
        onEdt(() -> {bridge.close();opened.set(null);navigate.invoke(bridge,destination);return null;});
        assertEquals(null,opened.get());
    }
    @Test
    void allPublicEntryPointsRejectCallsOutsideTheEventDispatchThread() throws Exception {
        VCampusClient client = new VCampusClient("127.0.0.1", 1);

        assertThrows(IllegalStateException.class,
                () -> new LegacyModuleBridge(client, "token", Set.of(UserRole.STUDENT),
                        () -> { }, () -> { }, () -> { }));

        LegacyModuleBridge bridge = onEdt(() -> new LegacyModuleBridge(client, "token",
                Set.of(UserRole.STUDENT), () -> { }, () -> { }, () -> { }));
        assertThrows(IllegalStateException.class, bridge::content);
        assertThrows(IllegalStateException.class, () -> bridge.open("library"));
        assertThrows(IllegalStateException.class, bridge::close);
        onEdt(() -> {
            bridge.close();
            return null;
        });
    }

    @Test
    void unauthorizedRouteShowsAnExplanationWithoutCreatingItsPanel() throws Exception {
        LegacyModuleBridge bridge = onEdt(() -> new LegacyModuleBridge(
                new VCampusClient("127.0.0.1", 1), "token", Set.of(UserRole.STUDENT),
                () -> { }, () -> { }, () -> { }));

        JPanel host = onEdt(bridge::content);
        onEdt(() -> {
            bridge.open("accounts");
            return null;
        });

        assertEquals(1, onEdt(host::getComponentCount));
        assertTrue(onEdt(() -> componentText(host)).contains("无权访问"));
    }

    @Test
    void closedBridgeIgnoresLaterNavigationWithoutCreatingModules() throws Exception {
        LegacyModuleBridge bridge = onEdt(() -> new LegacyModuleBridge(
                new VCampusClient("127.0.0.1", 1), "token", Set.of(UserRole.STUDENT),
                () -> { }, () -> { }, () -> { }));
        JPanel host = onEdt(bridge::content);

        onEdt(() -> {
            bridge.close();
            assertDoesNotThrow(() -> bridge.open("library"));
            return null;
        });

        assertEquals(0, onEdt(host::getComponentCount));
    }

    @Test
    void studentProfileNotificationIsUnavailableForTeachers() throws Exception {
        LegacyModuleBridge bridge = onEdt(() -> new LegacyModuleBridge(
                new VCampusClient("127.0.0.1", 1), "token", Set.of(UserRole.TEACHER),
                () -> { }, () -> { }, () -> { }));
        JPanel host = onEdt(bridge::content);

        onEdt(() -> {
            navigateFromNotification(bridge,
                    new NotificationDestination(NotificationTarget.STUDENT_PROFILE, null));
            return null;
        });

        assertEquals(1, onEdt(host::getComponentCount));
        assertTrue(onEdt(() -> componentText(host)).contains("无权访问"));
    }

    @Test
    void closeDisposesOnlyDialogsOwnedByTheBridgeHost() throws Exception {
        onEdt(() -> {
            LegacyModuleBridge bridge = new LegacyModuleBridge(
                    new VCampusClient("127.0.0.1", 1), "token", Set.of(UserRole.STUDENT),
                    () -> { }, () -> { }, () -> { });
            JFrame bridgeHost = new JFrame();
            bridgeHost.setContentPane(bridge.content());
            bridgeHost.pack();
            JDialog ownedDialog = new JDialog(bridgeHost, "owned", Dialog.ModalityType.MODELESS);
            ownedDialog.pack();

            JFrame unrelatedHost = new JFrame();
            unrelatedHost.pack();
            JDialog unrelatedDialog = new JDialog(
                    unrelatedHost, "unrelated", Dialog.ModalityType.MODELESS);
            unrelatedDialog.pack();
            try {
                bridge.close();

                assertTrue(!ownedDialog.isDisplayable());
                assertTrue(bridgeHost.isDisplayable());
                assertTrue(unrelatedDialog.isDisplayable());
                assertTrue(unrelatedHost.isDisplayable());
            } finally {
                ownedDialog.dispose();
                bridgeHost.dispose();
                unrelatedDialog.dispose();
                unrelatedHost.dispose();
            }
            return null;
        });
    }

    @Test
    void closingBridgeSuppressesDelayedNotificationDetailAndMarkRead() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch detailRequested = new CountDownLatch(1);
            CountDownLatch releaseDetail = new CountDownLatch(1);
            CountDownLatch serverFinished = new CountDownLatch(1);
            AtomicReference<Throwable> serverFailure = new AtomicReference<>();
            AtomicInteger markReadRequests = new AtomicInteger();
            Thread responder = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    RequestMessage request = readRequest(socket);
                    if (!Actions.NOTIFICATION_GET.equals(request.action())) {
                        throw new AssertionError("Expected notification detail request");
                    }
                    detailRequested.countDown();
                    if (!releaseDetail.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("Detail response was not released");
                    }
                    writeResponse(socket, ResponseMessage.success(request.requestId(), "ok", Map.of(
                            "id", "7", "type", NotificationType.LIBRARY_DUE_SOON.name(),
                            "source", NotificationSource.LIBRARY.name(), "title", "due",
                            "content", "return", "target", NotificationTarget.LIBRARY_LOANS.name(),
                            "relatedEntityId", "", "isRead", "false", "readAt", "",
                            "createdAt", Instant.parse("2026-08-31T00:00:00Z").toString())));
                    server.setSoTimeout(500);
                    try (Socket followUp = server.accept()) {
                        RequestMessage followUpRequest = readRequest(followUp);
                        if (Actions.NOTIFICATION_MARK_READ.equals(followUpRequest.action())) {
                            markReadRequests.incrementAndGet();
                        }
                    } catch (java.net.SocketTimeoutException ignored) {
                        // A closed bridge must not issue the follow-up mutation.
                    }
                } catch (Throwable error) {
                    serverFailure.set(error);
                } finally {
                    serverFinished.countDown();
                }
            }, "notification-detail-loopback");
            responder.setDaemon(true);
            responder.start();

            AtomicBoolean detailDialogOpened = new AtomicBoolean();
            Set<java.awt.Window> existingWindows = onEdt(() -> Set.of(java.awt.Window.getWindows()));
            AWTEventListener listener = event -> {
                if (event instanceof WindowEvent windowEvent
                        && windowEvent.getID() == WindowEvent.WINDOW_OPENED
                        && windowEvent.getWindow() instanceof NotificationDetailDialog dialog) {
                    detailDialogOpened.set(true);
                    SwingUtilities.invokeLater(dialog::dispose);
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK);
            try {
                LegacyModuleBridge bridge = onEdt(() -> new LegacyModuleBridge(
                        new VCampusClient("127.0.0.1", server.getLocalPort()), "token",
                        Set.of(UserRole.STUDENT), () -> { }, () -> { }, () -> { }));
                NotificationPanel panel = onEdt(() -> new NotificationPanel(
                        new VCampusClient("127.0.0.1", server.getLocalPort()), "token",
                        destination -> { }, () -> { }));
                onEdt(() -> {
                    bridge.content().add(panel, "notification-test");
                    openDetail(panel);
                    return null;
                });
                assertTrue(detailRequested.await(2, TimeUnit.SECONDS));
                onEdt(() -> {
                    bridge.close();
                    return null;
                });
                releaseDetail.countDown();
                assertTrue(serverFinished.await(3, TimeUnit.SECONDS));
                assertTrue(java.util.concurrent.ForkJoinPool.commonPool().awaitQuiescence(3, TimeUnit.SECONDS),
                        "wait for response parsing and its EDT completion to be queued");
                onEdt(() -> null);

                if (serverFailure.get() != null) {
                    throw new AssertionError(serverFailure.get());
                }
                assertTrue(!detailDialogOpened.get());
                assertTrue(onEdt(() -> java.util.Arrays.stream(java.awt.Window.getWindows())
                        .noneMatch(window -> window instanceof NotificationDetailDialog && !existingWindows.contains(window))),
                        "a late response must not even construct a private detail dialog");
                assertEquals(0, markReadRequests.get());
            } finally {
                Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
            }
        }
    }

    private static String componentText(java.awt.Container container) {
        StringBuilder text = new StringBuilder();
        for (java.awt.Component component : container.getComponents()) {
            if (component instanceof javax.swing.JLabel label) {
                text.append(label.getText());
            }
            if (component instanceof java.awt.Container child) {
                text.append(componentText(child));
            }
        }
        return text.toString();
    }

    private static <T> T onEdt(EdtOperation<T> operation) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result.set(operation.run());
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
        } catch (InvocationTargetException exception) {
            throw new AssertionError(exception.getCause());
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return result.get();
    }

    private static void navigateFromNotification(
            LegacyModuleBridge bridge, NotificationDestination destination) throws Exception {
        Method method = LegacyModuleBridge.class.getDeclaredMethod(
                "navigateFromNotification", NotificationDestination.class);
        method.setAccessible(true);
        try {
            method.invoke(bridge, destination);
        } catch (InvocationTargetException exception) {
            throw new AssertionError(exception.getCause());
        }
    }

    private static void openDetail(NotificationPanel panel) throws Exception {
        Method method = NotificationPanel.class.getDeclaredMethod(
                "openDetail", NotificationViewData.NotificationRow.class);
        method.setAccessible(true);
        method.invoke(panel, new NotificationViewData.NotificationRow(
                7L, NotificationType.LIBRARY_DUE_SOON, NotificationSource.LIBRARY,
                "due", "return", NotificationTarget.LIBRARY_LOANS, null, false,
                Instant.parse("2026-08-31T00:00:00Z")));
    }

    private static RequestMessage readRequest(Socket socket) throws Exception {
        return MessageCodec.readRequest(new DataInputStream(
                new BufferedInputStream(socket.getInputStream())));
    }

    private static void writeResponse(Socket socket, ResponseMessage response) throws Exception {
        MessageCodec.writeResponse(new DataOutputStream(
                new BufferedOutputStream(socket.getOutputStream())), response);
    }

    @FunctionalInterface
    private interface EdtOperation<T> {
        T run() throws Exception;
    }
}
