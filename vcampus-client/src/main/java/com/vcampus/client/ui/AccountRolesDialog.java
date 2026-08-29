package com.vcampus.client.ui;

import com.vcampus.common.model.UserRole;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class AccountRolesDialog {
    private AccountRolesDialog() {
    }

    static Optional<Set<UserRole>> showDialog(
            Window owner, AccountViewData.AccountRow account) {
        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(Theme.BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(new JLabel("为 “" + account.displayName() + "” 分配业务管理员角色"),
                BorderLayout.NORTH);

        JPanel choices = new JPanel(new GridLayout(0, 2, 12, 10));
        choices.setOpaque(false);
        Map<UserRole, JCheckBox> boxes = new LinkedHashMap<>();
        for (UserRole role : AccountFormPolicy.allowedRoles(account.baseIdentity())) {
            JCheckBox box = new JCheckBox(roleLabel(role));
            box.setOpaque(false);
            box.setForeground(Theme.TEXT);
            box.setSelected(account.administrativeRoles().contains(role));
            boxes.put(role, box);
            choices.add(box);
        }
        root.add(choices, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(
                owner, root, "分配管理员角色",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return Optional.empty();
        }
        Set<UserRole> selected = new LinkedHashSet<>();
        boxes.forEach((role, box) -> {
            if (box.isSelected()) {
                selected.add(role);
            }
        });
        return Optional.of(Set.copyOf(selected));
    }

    static String roleLabel(UserRole role) {
        return switch (role) {
            case STUDENT_ADMIN -> "学籍管理员";
            case ACADEMIC_ADMIN -> "教务管理员";
            case LIBRARY_ADMIN -> "图书管理员";
            case SHOP_ADMIN -> "商店管理员";
            case BANK_ADMIN -> "银行管理员";
            case FORUM_ADMIN -> "论坛管理员";
            case STUDENT -> "学生";
            case TEACHER -> "教师";
            case SUPER_ADMIN -> "超级管理员";
        };
    }
}
