package com.vcampus.client;

import com.vcampus.client.ui.LoginFrame;
import com.vcampus.client.ui.Theme;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * VCampus 桌面客户端统一启动入口。
 *
 * <p>默认启动 JavaFX 主客户端；命令行包含 {@code --swing} 时，在 Swing 事件分派线程中启动
 * 兼容客户端。两种界面都通过 Socket 访问应用服务器。</p>
 *
 * @since 1.0
 */
public final class ClientMain {
    private ClientMain() {
    }

    /**
     * 启动桌面客户端。
     *
     * @param args 可选 {@code --swing} 兼容模式参数
     */
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
