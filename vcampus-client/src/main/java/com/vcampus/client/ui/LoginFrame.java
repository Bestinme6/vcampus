package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.ResponseMessage;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class LoginFrame extends JFrame {
    private final JTextField hostField = new JTextField("127.0.0.1");
    private final JTextField portField = new JTextField("9090");
    private final JTextField usernameField = new JTextField();
    private final JPasswordField passwordField = new JPasswordField();
    private final JButton testButton = new JButton("测试连接");
    private final JButton loginButton = new JButton("进入校园");
    private final JLabel statusLabel = new JLabel("请先启动 VCampus 应用服务器");

    public LoginFrame() {
        super("VCampus 统一登录门户");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(880, 570));
        setSize(960, 620);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new GridLayout(1, 2));
        root.setBackground(Theme.BACKGROUND);
        root.add(createBrandPanel());
        root.add(createFormPanel());
        setContentPane(root);

        getRootPane().setDefaultButton(loginButton);
        testButton.addActionListener(event -> testConnection());
        loginButton.addActionListener(event -> login());
    }

    private JPanel createBrandPanel() {
        JPanel panel = new JPanel();
        panel.setBackground(Theme.SURFACE);
        panel.setBorder(BorderFactory.createEmptyBorder(70, 58, 58, 58));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JLabel badge = new JLabel("VC", SwingConstants.CENTER);
        badge.setOpaque(true);
        badge.setBackground(Theme.ACCENT);
        badge.setForeground(Theme.PRIMARY_TEXT);
        badge.setFont(badge.getFont().deriveFont(Font.BOLD, 25f));
        badge.setPreferredSize(new Dimension(62, 62));
        badge.setMaximumSize(new Dimension(62, 62));
        badge.setAlignmentX(LEFT_ALIGNMENT);

        JLabel title = new JLabel("VCampus");
        title.setForeground(Theme.TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 38f));
        title.setAlignmentX(LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel("你的虚拟校园工作台");
        subtitle.setForeground(Theme.TEXT);
        subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 20f));
        subtitle.setAlignmentX(LEFT_ALIGNMENT);

        JLabel description = new JLabel("<html>一个入口连接学籍、教务、图书馆、<br>校园服务与同学社区。</html>");
        description.setForeground(Theme.MUTED);
        description.setFont(description.getFont().deriveFont(15f));
        description.setAlignmentX(LEFT_ALIGNMENT);

        JLabel architecture = new JLabel("SWING  ·  SOCKET  ·  MYSQL");
        architecture.setForeground(Theme.ACCENT);
        architecture.setFont(architecture.getFont().deriveFont(Font.BOLD, 12f));
        architecture.setAlignmentX(LEFT_ALIGNMENT);

        panel.add(badge);
        panel.add(Box.createVerticalStrut(30));
        panel.add(title);
        panel.add(Box.createVerticalStrut(10));
        panel.add(subtitle);
        panel.add(Box.createVerticalStrut(24));
        panel.add(description);
        panel.add(Box.createVerticalGlue());
        panel.add(architecture);
        return panel;
    }

    private JPanel createFormPanel() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(Theme.BACKGROUND);
        outer.setBorder(BorderFactory.createEmptyBorder(58, 58, 48, 58));

        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        JLabel heading = new JLabel("欢迎回来");
        heading.setForeground(Theme.TEXT);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 28f));
        heading.setAlignmentX(LEFT_ALIGNMENT);
        JLabel hint = new JLabel("使用校园账号登录");
        hint.setForeground(Theme.MUTED);
        hint.setAlignmentX(LEFT_ALIGNMENT);

        styleInput(usernameField, "学号、教工号或管理员账号");
        styleInput(passwordField, "密码");
        styleInput(hostField, "服务器地址");
        styleInput(portField, "端口");

        JPanel serverFields = new JPanel(new GridLayout(1, 2, 10, 0));
        serverFields.setOpaque(false);
        serverFields.setMaximumSize(new Dimension(Integer.MAX_VALUE, 43));
        serverFields.setAlignmentX(LEFT_ALIGNMENT);
        serverFields.add(hostField);
        serverFields.add(portField);

        JPanel buttons = new JPanel(new GridLayout(1, 2, 10, 0));
        buttons.setOpaque(false);
        buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        Theme.styleQuietButton(testButton);
        Theme.stylePrimaryButton(loginButton);
        buttons.add(testButton);
        buttons.add(loginButton);

        statusLabel.setForeground(Theme.MUTED);
        statusLabel.setAlignmentX(LEFT_ALIGNMENT);

        form.add(heading);
        form.add(Box.createVerticalStrut(8));
        form.add(hint);
        form.add(Box.createVerticalStrut(30));
        form.add(fieldLabel("账号"));
        form.add(Box.createVerticalStrut(7));
        form.add(usernameField);
        form.add(Box.createVerticalStrut(17));
        form.add(fieldLabel("密码"));
        form.add(Box.createVerticalStrut(7));
        form.add(passwordField);
        form.add(Box.createVerticalStrut(23));
        form.add(fieldLabel("连接设置"));
        form.add(Box.createVerticalStrut(7));
        form.add(serverFields);
        form.add(Box.createVerticalStrut(24));
        form.add(buttons);
        form.add(Box.createVerticalStrut(18));
        form.add(statusLabel);

        outer.add(form, BorderLayout.CENTER);
        return outer;
    }

    private JLabel fieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Theme.MUTED);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private void styleInput(JTextField field, String tooltip) {
        Theme.styleField(field);
        field.setToolTipText(tooltip);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 43));
        field.setAlignmentX(LEFT_ALIGNMENT);
    }

    private void testConnection() {
        runRequest(() -> createClient().ping(), response -> {
            statusLabel.setForeground(Theme.SUCCESS);
            statusLabel.setText("● " + response.message());
            JOptionPane.showMessageDialog(this, response.message(), "连接成功",
                    JOptionPane.INFORMATION_MESSAGE);
        });
    }

    private void login() {
        String username = usernameField.getText().trim();
        if (username.isEmpty() || passwordField.getPassword().length == 0) {
            statusLabel.setForeground(Theme.DANGER);
            statusLabel.setText("请输入账号和密码");
            return;
        }
        char[] password = passwordField.getPassword();
        VCampusClient client = createClient();
        runRequest(() -> {
            try {
                return client.login(username, password);
            } finally {
                Arrays.fill(password, '\0');
            }
        }, response -> {
            statusLabel.setText(response.message());
            if (response.success()) {
                String token = response.data().get("sessionToken");
                String displayName = response.data().getOrDefault("displayName", username);
                Set<UserRole> roles = parseRoles(response.data().get("roles"));
                if (Boolean.parseBoolean(response.data()
                        .getOrDefault("forcePasswordChange", "false"))) {
                    openRequiredPasswordChange(client, token, displayName, roles);
                } else {
                    openMainFrame(client, token, displayName, roles);
                }
            } else {
                statusLabel.setForeground(Theme.DANGER);
                JOptionPane.showMessageDialog(this, response.message(), "登录失败",
                        JOptionPane.WARNING_MESSAGE);
            }
        });
    }

    private void openRequiredPasswordChange(
            VCampusClient client, String token, String displayName, Set<UserRole> roles) {
        setVisible(false);
        PasswordChangeDialog dialog = new PasswordChangeDialog(
                this, client, token,
                () -> openMainFrame(client, token, displayName, roles),
                () -> CompletableFuture.runAsync(() -> {
                    try {
                        client.logout(token);
                    } catch (Exception exception) {
                        System.err.println("Server logout failed: " + exception.getMessage());
                    }
                }).whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
                    dispose();
                    new LoginFrame().setVisible(true);
                })));
        dialog.setVisible(true);
    }

    private void openMainFrame(
            VCampusClient client, String token, String displayName, Set<UserRole> roles) {
        dispose();
        new MainFrame(client, token, displayName, roles).setVisible(true);
    }

    private VCampusClient createClient() {
        int port = Integer.parseInt(portField.getText().trim());
        return new VCampusClient(hostField.getText().trim(), port);
    }

    private Set<UserRole> parseRoles(String encodedRoles) {
        if (encodedRoles == null || encodedRoles.isBlank()) {
            return Set.of();
        }
        Set<UserRole> roles = new LinkedHashSet<>();
        for (String role : encodedRoles.split(",")) {
            try {
                roles.add(UserRole.valueOf(role));
            } catch (IllegalArgumentException unknownRole) {
                System.err.println("Server returned an unknown role: " + role);
            }
        }
        return Set.copyOf(roles);
    }

    private void runRequest(RequestCall request, ResponseConsumer consumer) {
        setBusy(true);
        statusLabel.setForeground(Theme.MUTED);
        statusLabel.setText("正在连接服务器…");
        CompletableFuture.supplyAsync(() -> {
            try {
                return request.execute();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }).whenComplete((response, error) -> SwingUtilities.invokeLater(() -> {
            setBusy(false);
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                statusLabel.setForeground(Theme.DANGER);
                statusLabel.setText("连接失败：" + message);
                return;
            }
            consumer.accept(response);
        }));
    }

    private void setBusy(boolean busy) {
        testButton.setEnabled(!busy);
        loginButton.setEnabled(!busy);
    }

    @FunctionalInterface
    private interface RequestCall {
        ResponseMessage execute() throws Exception;
    }

    @FunctionalInterface
    private interface ResponseConsumer {
        void accept(ResponseMessage response);
    }
}
