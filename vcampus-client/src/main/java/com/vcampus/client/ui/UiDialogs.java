package com.vcampus.client.ui;

import javax.swing.JOptionPane;
import java.awt.Component;

final class UiDialogs {
    private UiDialogs() {
    }

    static void showSuccess(Component parent, String message) {
        JOptionPane.showMessageDialog(
                parent, message, "操作成功", JOptionPane.INFORMATION_MESSAGE);
    }
}
