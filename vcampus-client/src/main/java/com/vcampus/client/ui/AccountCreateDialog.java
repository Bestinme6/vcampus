package com.vcampus.client.ui;

import com.vcampus.common.model.UserRole;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

final class AccountCreateDialog {
    private final JComboBox<IdentityChoice> identity = new JComboBox<>(IdentityChoice.values());
    private final JTextField number = new JTextField(18);
    private final JTextField fullName = new JTextField(18);
    private final JTextField phone = new JTextField(18);
    private final JTextField email = new JTextField(18);
    private final JComboBox<AccountViewData.ReferenceItem> department;
    private final JComboBox<AccountViewData.ReferenceItem> major = new JComboBox<>();
    private final JComboBox<AccountViewData.ReferenceItem> academicClass = new JComboBox<>();
    private final JComboBox<GenderChoice> gender = new JComboBox<>(GenderChoice.values());
    private final JTextField birthDate = new JTextField(18);
    private final JTextField enrollmentYear = new JTextField(18);
    private final JTextField address = new JTextField(18);
    private final JTextField professionalTitle = new JTextField(18);
    private final JPasswordField password = new JPasswordField(18);
    private final JPasswordField confirmation = new JPasswordField(18);
    private final JPanel identityFields = new JPanel(new CardLayout());
    private final JPanel rolePanel = new JPanel();
    private final Map<UserRole, javax.swing.JCheckBox> roleBoxes = new LinkedHashMap<>();
    private final AccountViewData.ReferenceData references;

    private AccountCreateDialog(AccountViewData.ReferenceData references) {
        this.references = references;
        department = new JComboBox<>(references.departments().toArray(AccountViewData.ReferenceItem[]::new));
        configureReferences();
        buildIdentityFields();
        identity.addActionListener(event -> refreshIdentity());
        refreshIdentity();
    }

    static Optional<Map<String, String>> showDialog(
            Window owner, AccountViewData.ReferenceData references) {
        AccountCreateDialog dialog = new AccountCreateDialog(references);
        try {
            while (true) {
                int result = JOptionPane.showConfirmDialog(
                        owner, dialog.content(), "创建账号",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
                if (result != JOptionPane.OK_OPTION) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(dialog.values());
                } catch (IllegalArgumentException exception) {
                    JOptionPane.showMessageDialog(
                            owner, exception.getMessage(), "输入有误", JOptionPane.WARNING_MESSAGE);
                }
            }
        } finally {
            Arrays.fill(dialog.password.getPassword(), '\0');
            Arrays.fill(dialog.confirmation.getPassword(), '\0');
            dialog.password.setText("");
            dialog.confirmation.setText("");
        }
    }

    private JComponent content() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(Theme.BACKGROUND);
        int row = 0;
        addRow(form, row++, "基础身份", identity);
        addRow(form, row++, "学号/教师工号", number);
        addRow(form, row++, "姓名", fullName);
        addRow(form, row++, "学院", department);
        addRow(form, row++, "电话", phone);
        addRow(form, row++, "邮箱", email);
        addRow(form, row++, "身份资料", identityFields);
        addRow(form, row++, "管理员角色", rolePanel);
        addRow(form, row++, "临时密码", password);
        addRow(form, row, "确认密码", confirmation);
        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(610, 570));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private void buildIdentityFields() {
        JPanel student = new JPanel(new GridBagLayout());
        student.setOpaque(false);
        addRow(student, 0, "性别", gender);
        addRow(student, 1, "出生日期", birthDate);
        addRow(student, 2, "专业", major);
        addRow(student, 3, "班级", academicClass);
        addRow(student, 4, "入学年份", enrollmentYear);
        addRow(student, 5, "地址", address);
        JPanel teacher = new JPanel(new GridBagLayout());
        teacher.setOpaque(false);
        addRow(teacher, 0, "职称", professionalTitle);
        identityFields.setOpaque(false);
        identityFields.add(student, UserRole.STUDENT.name());
        identityFields.add(teacher, UserRole.TEACHER.name());
        rolePanel.setLayout(new BoxLayout(rolePanel, BoxLayout.Y_AXIS));
        rolePanel.setOpaque(false);
    }

    private void configureReferences() {
        department.addActionListener(event -> refreshMajors());
        major.addActionListener(event -> refreshClasses());
        academicClass.addActionListener(event -> {
            AccountViewData.ReferenceItem selected =
                    (AccountViewData.ReferenceItem) academicClass.getSelectedItem();
            enrollmentYear.setText(selected == null ? "" : Integer.toString(selected.year()));
        });
        enrollmentYear.setEditable(false);
        refreshMajors();
    }

    private void refreshMajors() {
        AccountViewData.ReferenceItem selected = (AccountViewData.ReferenceItem) department.getSelectedItem();
        major.removeAllItems();
        if (selected != null) {
            references.majors().stream().filter(item -> item.parentId() == selected.id())
                    .forEach(major::addItem);
        }
        refreshClasses();
    }

    private void refreshClasses() {
        AccountViewData.ReferenceItem selected = (AccountViewData.ReferenceItem) major.getSelectedItem();
        academicClass.removeAllItems();
        if (selected != null) {
            references.classes().stream().filter(item -> item.parentId() == selected.id())
                    .forEach(academicClass::addItem);
        }
        AccountViewData.ReferenceItem selectedClass =
                (AccountViewData.ReferenceItem) academicClass.getSelectedItem();
        enrollmentYear.setText(selectedClass == null ? "" : Integer.toString(selectedClass.year()));
    }

    private void refreshIdentity() {
        UserRole selected = ((IdentityChoice) identity.getSelectedItem()).role;
        ((CardLayout) identityFields.getLayout()).show(identityFields, selected.name());
        number.setToolTipText(selected == UserRole.STUDENT ? "10 位数字" : "大写 T 加 7 位数字");
        rolePanel.removeAll();
        roleBoxes.clear();
        for (UserRole role : AccountFormPolicy.allowedRoles(selected)) {
            javax.swing.JCheckBox box = new javax.swing.JCheckBox(AccountRolesDialog.roleLabel(role));
            box.setOpaque(false);
            roleBoxes.put(role, box);
            rolePanel.add(box);
        }
        rolePanel.revalidate();
        rolePanel.repaint();
    }

    private Map<String, String> values() {
        UserRole selected = ((IdentityChoice) identity.getSelectedItem()).role;
        String username = AccountFormPolicy.username(selected, number.getText().trim());
        if (fullName.getText().isBlank()) {
            throw new IllegalArgumentException("请填写姓名");
        }
        AccountViewData.ReferenceItem departmentItem = requiredItem(department, "学院");
        char[] secret = password.getPassword();
        char[] repeated = confirmation.getPassword();
        try {
            if (secret.length < 8 || secret.length > 128) {
                throw new IllegalArgumentException("临时密码长度必须为 8—128 位");
            }
            if (!Arrays.equals(secret, repeated)) {
                throw new IllegalArgumentException("两次输入的密码不一致");
            }
            Map<String, String> values = new LinkedHashMap<>();
            values.put("identity", selected.name());
            values.put("number", username);
            values.put("fullName", fullName.getText().trim());
            values.put("departmentId", Long.toString(departmentItem.id()));
            values.put("phone", phone.getText().trim());
            values.put("email", email.getText().trim());
            values.put("roles", roleBoxes.entrySet().stream()
                    .filter(entry -> entry.getValue().isSelected())
                    .map(entry -> entry.getKey().name()).sorted()
                    .collect(Collectors.joining(",")));
            values.put("initialPassword", new String(secret));
            if (selected == UserRole.STUDENT) {
                values.put("gender", ((GenderChoice) gender.getSelectedItem()).value);
                values.put("birthDate", birthDate.getText().trim());
                values.put("majorId", Long.toString(requiredItem(major, "专业").id()));
                values.put("classId", Long.toString(requiredItem(academicClass, "班级").id()));
                values.put("enrollmentYear", enrollmentYear.getText().trim());
                values.put("address", address.getText().trim());
            } else {
                values.put("professionalTitle", professionalTitle.getText().trim());
            }
            return Map.copyOf(values);
        } finally {
            Arrays.fill(secret, '\0');
            Arrays.fill(repeated, '\0');
        }
    }

    private static AccountViewData.ReferenceItem requiredItem(
            JComboBox<AccountViewData.ReferenceItem> box, String label) {
        AccountViewData.ReferenceItem item = (AccountViewData.ReferenceItem) box.getSelectedItem();
        if (item == null) {
            throw new IllegalArgumentException("请选择" + label);
        }
        return item;
    }

    private static void addRow(JPanel panel, int row, String label, JComponent field) {
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = row;
        left.anchor = GridBagConstraints.NORTHWEST;
        left.insets = new Insets(7, 4, 7, 12);
        panel.add(new JLabel(label), left);
        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.gridy = row;
        right.weightx = 1;
        right.fill = GridBagConstraints.HORIZONTAL;
        right.insets = new Insets(7, 4, 7, 4);
        panel.add(field, right);
    }

    private enum IdentityChoice {
        STUDENT(UserRole.STUDENT, "学生"), TEACHER(UserRole.TEACHER, "教师");
        private final UserRole role;
        private final String text;
        IdentityChoice(UserRole role, String text) { this.role = role; this.text = text; }
        @Override public String toString() { return text; }
    }

    private enum GenderChoice {
        MALE("MALE", "男"), FEMALE("FEMALE", "女"), UNSPECIFIED("UNSPECIFIED", "未指定");
        private final String value;
        private final String text;
        GenderChoice(String value, String text) { this.value = value; this.text = text; }
        @Override public String toString() { return text; }
    }
}
