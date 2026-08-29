package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.protocol.ResponseMessage;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

final class PasswordChangeDialog extends JDialog {
    private final VCampusClient client;
    private final String sessionToken;
    private final Runnable completed;
    private final Runnable cancelled;
    private final JPasswordField current = new JPasswordField(22);
    private final JPasswordField password = new JPasswordField(22);
    private final JPasswordField confirmation = new JPasswordField(22);
    private final JButton confirm = new JButton("修改密码并继续");
    private final JLabel status = new JLabel("新密码长度为 8—128 位");
    private boolean finished;

    PasswordChangeDialog(
            Window owner, VCampusClient client, String sessionToken,
            Runnable completed, Runnable cancelled) {
        super(owner, "首次登录 · 修改密码", ModalityType.APPLICATION_MODAL);
        this.client = client;
        this.sessionToken = sessionToken;
        this.completed = completed;
        this.cancelled = cancelled;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(new Dimension(500, 410));
        setLocationRelativeTo(owner);
        setContentPane(content());
        getRootPane().setDefaultButton(confirm);
        confirm.addActionListener(event -> submit());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { cancel(); }
        });
    }

    private JPanel content() {
        JPanel root = new JPanel(new BorderLayout(0, 20));
        root.setBackground(Theme.BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(28, 34, 28, 34));
        JPanel heading = new JPanel(new GridBagLayout());
        heading.setOpaque(false);
        JLabel title = new JLabel("请先设置新密码");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 25f));
        JLabel explanation = new JLabel("管理员创建或重置的账号必须完成此步骤才能进入工作台。");
        explanation.setForeground(Theme.MUTED);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.WEST;
        heading.add(title, c);
        c.gridy = 1; c.insets = new Insets(7, 0, 0, 0);
        heading.add(explanation, c);
        root.add(heading, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        addRow(form, 0, "当前临时密码", current);
        addRow(form, 1, "新密码", password);
        addRow(form, 2, "确认新密码", confirmation);
        root.add(form, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(10, 10));
        footer.setOpaque(false);
        status.setForeground(Theme.MUTED);
        Theme.stylePrimaryButton(confirm);
        footer.add(status, BorderLayout.NORTH);
        footer.add(confirm, BorderLayout.SOUTH);
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    private void submit() {
        char[] oldSecret = current.getPassword();
        char[] newSecret = password.getPassword();
        char[] repeated = confirmation.getPassword();
        Optional<String> violation = PasswordChangeForm.validate(oldSecret, newSecret, repeated);
        if (violation.isPresent()) {
            clear(oldSecret, newSecret, repeated);
            status.setForeground(Theme.DANGER);
            status.setText(violation.get());
            return;
        }
        char[] requestOld = Arrays.copyOf(oldSecret, oldSecret.length);
        char[] requestNew = Arrays.copyOf(newSecret, newSecret.length);
        clear(oldSecret, newSecret, repeated);
        setBusy(true);
        CompletableFuture.supplyAsync(() -> {
            try {
                return client.changePassword(sessionToken, requestOld, requestNew);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            } finally {
                Arrays.fill(requestOld, '\0');
                Arrays.fill(requestNew, '\0');
            }
        }).whenComplete((response, error) -> SwingUtilities.invokeLater(() -> finishRequest(response, error)));
    }

    private void finishRequest(ResponseMessage response, Throwable error) {
        setBusy(false);
        if (error != null) {
            status.setForeground(Theme.DANGER);
            status.setText("连接失败，请检查服务器后重试");
            return;
        }
        if (!response.success()) {
            status.setForeground(Theme.DANGER);
            status.setText(response.message());
            current.setText("");
            return;
        }
        finished = true;
        UiDialogs.showSuccess(this, response.message());
        dispose();
        completed.run();
    }

    private void cancel() {
        if (finished) {
            return;
        }
        int result = JOptionPane.showConfirmDialog(this,
                "尚未修改密码。退出后需要重新登录，确定退出吗？",
                "退出首次改密", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            finished = true;
            clearFields();
            dispose();
            cancelled.run();
        }
    }

    @Override public void dispose() {
        clearFields();
        super.dispose();
    }

    private void clearFields() {
        current.setText("");
        password.setText("");
        confirmation.setText("");
    }

    private void setBusy(boolean busy) {
        confirm.setEnabled(!busy);
        current.setEnabled(!busy);
        password.setEnabled(!busy);
        confirmation.setEnabled(!busy);
        status.setForeground(Theme.MUTED);
        status.setText(busy ? "正在修改密码…" : "新密码长度为 8—128 位");
    }

    private static void clear(char[]... values) {
        for (char[] value : values) {
            if (value != null) Arrays.fill(value, '\0');
        }
    }

    private static void addRow(JPanel panel, int row, String label, JPasswordField field) {
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0; left.gridy = row; left.anchor = GridBagConstraints.WEST;
        left.insets = new Insets(8, 0, 8, 14);
        panel.add(new JLabel(label), left);
        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1; right.gridy = row; right.weightx = 1;
        right.fill = GridBagConstraints.HORIZONTAL;
        right.insets = new Insets(8, 0, 8, 0);
        Theme.styleField(field);
        panel.add(field, right);
    }
}
