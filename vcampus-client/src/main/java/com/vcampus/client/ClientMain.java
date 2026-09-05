package com.vcampus.client;

import com.vcampus.client.ui.LoginFrame;
import com.vcampus.client.ui.Theme;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class ClientMain {
    private ClientMain() {
    }

    public static void main(String[] args) {
        if (!java.util.Arrays.asList(args).contains("--swing")) {
            javafx.application.Application.launch(com.vcampus.client.fx.CampusApplication.class, args);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // Swing falls back to the cross-platform look and feel.
            }
            Theme.install();
            new LoginFrame().setVisible(true);
        });
    }
}
