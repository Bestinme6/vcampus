package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.protocol.ResponseMessage;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class TeacherProfileFrame extends JFrame {
    public TeacherProfileFrame(VCampusClient client, String sessionToken) {
        super("VCampus · 教师信息");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(720, 480));
        setSize(820, 540);
        setLocationRelativeTo(null);
        setContentPane(new TeacherProfileModulePanel(client, sessionToken, null));
    }
}

final class TeacherProfileModulePanel extends JPanel {
    private final VCampusClient client;
    private final String sessionToken;
    private final Map<String, JLabel> values = new LinkedHashMap<>();
    private TeacherProfileData profile = TeacherProfileData.from(Map.of());
    private boolean busy;

    TeacherProfileModulePanel(
            VCampusClient client,
            String sessionToken,
            Runnable backToWorkspace) {
        this.client = client;
        this.sessionToken = sessionToken;
        setLayout(new BorderLayout());
        add(createContent(backToWorkspace), BorderLayout.CENTER);
        loadProfile();
    }

    private JPanel createContent(Runnable backToWorkspace) {
        JPanel root = new JPanel(new BorderLayout(0, 22));
        root.setBackground(Theme.BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(28, 34, 30, 34));

        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setOpaque(false);
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("教师信息");
        title.setForeground(Theme.TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        JLabel subtitle = new JLabel("查看教师档案并维护本人联系方式");
        subtitle.setForeground(Theme.MUTED);
        titles.add(title);
        titles.add(Box.createVerticalStrut(6));
        titles.add(subtitle);
        header.add(titles, BorderLayout.WEST);
        if (backToWorkspace != null) {
            JButton back = new JButton("← 返回工作台");
            Theme.styleQuietButton(back);
            back.setForeground(Theme.TEXT);
            back.setFont(back.getFont().deriveFont(Font.BOLD));
            back.addActionListener(event -> backToWorkspace.run());
            header.add(back, BorderLayout.EAST);
        }
        root.add(header, BorderLayout.NORTH);

        RoundedPanel card = new RoundedPanel(Theme.SURFACE, 18);
        card.setLayout(new BorderLayout(0, 22));
        card.setBorder(BorderFactory.createEmptyBorder(26, 30, 24, 30));
        JPanel fields = new JPanel(new GridLayout(0, 2, 28, 18));
        fields.setOpaque(false);
        addField(fields, "教师工号", "teacherNumber");
        addField(fields, "姓名", "fullName");
        addField(fields, "学院", "departmentName");
        addField(fields, "职称", "professionalTitle");
        addField(fields, "电话", "phone");
        addField(fields, "邮箱", "email");
        card.add(fields, BorderLayout.CENTER);

        JPanel actions = new JPanel();
        actions.setOpaque(false);
        JButton edit = new JButton("修改联系方式");
        Theme.styleQuietButton(edit);
        edit.setForeground(Theme.TEXT);
        edit.setFont(edit.getFont().deriveFont(Font.BOLD));
        edit.addActionListener(event -> editContact());
        actions.add(edit);
        card.add(actions, BorderLayout.SOUTH);
        root.add(card, BorderLayout.CENTER);
        return root;
    }

    private void addField(JPanel panel, String label, String key) {
        JLabel name = new JLabel(label);
        name.setForeground(Theme.MUTED);
        JLabel value = new JLabel("—");
        value.setForeground(Theme.TEXT);
        value.setFont(value.getFont().deriveFont(Font.BOLD, 14f));
        values.put(key, value);
        panel.add(name);
        panel.add(value);
    }

    private void loadProfile() {
        runRequest(() -> client.getMyTeacherProfile(sessionToken), response -> {
            profile = TeacherProfileData.from(response.data());
            values.get("teacherNumber").setText(display(profile.teacherNumber()));
            values.get("fullName").setText(display(profile.fullName()));
            values.get("departmentName").setText(display(profile.departmentName()));
            values.get("professionalTitle").setText(display(profile.professionalTitle()));
            values.get("phone").setText(display(profile.phone()));
            values.get("email").setText(display(profile.email()));
        });
    }

    private void editContact() {
        JTextField phone = new JTextField(profile.phone());
        JTextField email = new JTextField(profile.email());
        Theme.styleField(phone);
        Theme.styleField(email);
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 8));
        form.setBackground(Theme.BACKGROUND);
        form.add(new JLabel("电话"));
        form.add(phone);
        form.add(new JLabel("邮箱"));
        form.add(email);
        if (JOptionPane.showConfirmDialog(
                this, form, "修改联系方式", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        runRequest(() -> client.updateTeacherContact(
                sessionToken, phone.getText().trim(), email.getText().trim()), response -> {
            UiDialogs.showSuccess(this, response.message());
            loadProfile();
        });
    }

    private String display(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private void runRequest(RequestCall call, ResponseConsumer consumer) {
        if (busy) {
            return;
        }
        busy = true;
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        CompletableFuture.supplyAsync(() -> {
            try {
                return call.execute();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }).whenComplete((response, error) -> SwingUtilities.invokeLater(() -> {
            busy = false;
            setCursor(Cursor.getDefaultCursor());
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                showError(cause.getMessage() == null ? "请求失败" : cause.getMessage());
            } else if (!response.success()) {
                showError(response.message());
            } else {
                consumer.accept(response);
            }
        }));
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "操作失败", JOptionPane.ERROR_MESSAGE);
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
