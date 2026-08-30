package com.vcampus.client.ui;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagLayout;

final class WorkspaceHeaderTools {
    private WorkspaceHeaderTools() {
    }

    static JPanel create(String initial, String roleNames) {
        JPanel tools = new JPanel(new GridBagLayout());
        tools.setOpaque(false);
        JLabel avatar = new CircularAvatarLabel(initial);
        avatar.setBackground(Theme.SECONDARY);
        avatar.setForeground(Theme.ACCENT);
        avatar.setFont(avatar.getFont().deriveFont(Font.BOLD, 15f));
        avatar.setPreferredSize(new Dimension(42, 42));
        avatar.setToolTipText("当前角色：" + roleNames);
        tools.add(avatar);
        return tools;
    }
}
