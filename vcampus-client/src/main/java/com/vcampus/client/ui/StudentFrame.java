package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.Gender;
import com.vcampus.common.model.StudentStatus;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.ResponseMessage;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class StudentFrame extends JFrame {
    public StudentFrame(VCampusClient client, String sessionToken, Set<UserRole> roles) {
        super("VCampus · 虚拟学籍管理");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(980, 650));
        setSize(1180, 720);
        setLocationRelativeTo(null);
        setContentPane(new StudentModulePanel(client, sessionToken, roles, null));
    }
}

final class StudentModulePanel extends JPanel {
    private final VCampusClient client;
    private final String sessionToken;
    private final boolean administrator;
    private final JTextField keywordField = new JTextField();
    private final JComboBox<StatusOption> statusFilter = new JComboBox<>();
    private final DefaultTableModel tableModel = new DefaultTableModel(
            new String[]{"ID", "学号", "姓名", "性别", "学院", "专业", "班级", "状态", "电话", "邮箱"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(tableModel);
    private final JLabel pageLabel = new JLabel("第 1 页");
    private final Map<String, JLabel> selfValues = new LinkedHashMap<>();
    private Map<String, String> selfProfile = Map.of();
    private int currentPage = 1;
    private int totalRows;
    private boolean busy;

    StudentModulePanel(
            VCampusClient client,
            String sessionToken,
            Set<UserRole> roles,
            Runnable backToWorkspace) {
        this.client = client;
        this.sessionToken = sessionToken;
        this.administrator = roles.contains(UserRole.SUPER_ADMIN) || roles.contains(UserRole.STUDENT_ADMIN);
        setLayout(new BorderLayout(0, 20));
        setBackground(Theme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(26, 30, 28, 30));
        add(header(backToWorkspace), BorderLayout.NORTH);
        add(administrator ? adminPanel() : selfPanel(), BorderLayout.CENTER);

        if (administrator) {
            loadStudents(1);
        } else {
            loadSelf();
        }
    }

    private JPanel header(Runnable backToWorkspace) {
        JPanel panel = new JPanel(new BorderLayout(16, 0));
        panel.setOpaque(false);
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(administrator ? "学籍管理" : "我的学籍");
        title.setForeground(Theme.TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        JLabel subtitle = new JLabel(administrator
                ? "查询学生档案、创建学生账号并维护学籍状态"
                : "查看个人档案、联系方式和学籍状态记录");
        subtitle.setForeground(Theme.MUTED);
        titles.add(title);
        titles.add(Box.createVerticalStrut(6));
        titles.add(subtitle);
        panel.add(titles, BorderLayout.WEST);
        if (backToWorkspace != null) {
            JButton back = quietButton("← 返回工作台");
            back.addActionListener(event -> backToWorkspace.run());
            panel.add(back, BorderLayout.EAST);
        }
        return panel;
    }

    private JPanel adminPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 14));
        panel.setOpaque(false);

        RoundedPanel filters = new RoundedPanel(Theme.SURFACE, 16);
        filters.setLayout(new BorderLayout(12, 0));
        filters.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        Theme.styleField(keywordField);
        keywordField.setToolTipText("输入学号或姓名");
        statusFilter.setModel(new DefaultComboBoxModel<>(new StatusOption[]{
                new StatusOption("", "全部状态"),
                new StatusOption(StudentStatus.ENROLLED.name(), StudentStatus.ENROLLED.displayName()),
                new StatusOption(StudentStatus.SUSPENDED.name(), StudentStatus.SUSPENDED.displayName()),
                new StatusOption(StudentStatus.WITHDRAWN.name(), StudentStatus.WITHDRAWN.displayName()),
                new StatusOption(StudentStatus.GRADUATED.name(), StudentStatus.GRADUATED.displayName())
        }));
        statusFilter.setPreferredSize(new Dimension(130, 40));
        JButton search = new JButton("查询");
        Theme.styleCommandButton(search);
        search.addActionListener(event -> loadStudents(1));

        JPanel searchFields = new JPanel(new BorderLayout(10, 0));
        searchFields.setOpaque(false);
        searchFields.add(keywordField, BorderLayout.CENTER);
        searchFields.add(statusFilter, BorderLayout.EAST);
        filters.add(searchFields, BorderLayout.CENTER);
        filters.add(search, BorderLayout.EAST);
        panel.add(filters, BorderLayout.NORTH);

        table.setRowHeight(34);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setBackground(Theme.SURFACE);
        table.setForeground(Theme.TEXT);
        table.setSelectionBackground(Theme.SECONDARY);
        table.setSelectionForeground(Theme.TEXT);
        table.setGridColor(Theme.BORDER);
        table.getTableHeader().setBackground(Theme.SURFACE_HOVER);
        table.getTableHeader().setDefaultRenderer(createHeaderRenderer());
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scroll.getViewport().setBackground(Theme.SURFACE);
        panel.add(scroll, BorderLayout.CENTER);

        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        JPanel left = new JPanel();
        left.setOpaque(false);
        for (StudentManagementActionPolicy.Action action
                : StudentManagementActionPolicy.actions()) {
            JButton button = quietButton(action.label());
            button.addActionListener(event -> {
                switch (action.code()) {
                    case EDIT_PROFILE -> loadSelectedForEdit();
                    case EDIT_CONTACT -> editSelectedContact();
                    case CHANGE_STATUS -> changeSelectedStatus();
                    case STATUS_HISTORY -> showSelectedHistory();
                }
            });
            left.add(button);
        }

        JPanel paging = new JPanel();
        paging.setOpaque(false);
        JButton previous = quietButton("上一页");
        JButton next = quietButton("下一页");
        previous.addActionListener(event -> loadStudents(Math.max(1, currentPage - 1)));
        next.addActionListener(event -> {
            if (currentPage * 8 < totalRows) {
                loadStudents(currentPage + 1);
            }
        });
        pageLabel.setForeground(Theme.MUTED);
        paging.add(previous);
        paging.add(pageLabel);
        paging.add(next);
        actions.add(left, BorderLayout.WEST);
        actions.add(paging, BorderLayout.EAST);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel selfPanel() {
        RoundedPanel card = new RoundedPanel(Theme.SURFACE, 18);
        card.setLayout(new BorderLayout(0, 20));
        card.setBorder(BorderFactory.createEmptyBorder(24, 28, 24, 28));
        JPanel details = new JPanel(new GridLayout(0, 2, 24, 15));
        details.setOpaque(false);
        addSelfField(details, "学号", "studentNumber");
        addSelfField(details, "姓名", "fullName");
        addSelfField(details, "性别", "gender");
        addSelfField(details, "出生日期", "birthDate");
        addSelfField(details, "学院", "departmentName");
        addSelfField(details, "专业", "majorName");
        addSelfField(details, "行政班", "className");
        addSelfField(details, "学籍状态", "status");
        addSelfField(details, "电话", "phone");
        addSelfField(details, "邮箱", "email");
        addSelfField(details, "地址", "address");
        addSelfField(details, "入学年份", "enrollmentYear");
        card.add(details, BorderLayout.CENTER);

        JPanel actions = new JPanel();
        actions.setOpaque(false);
        JButton edit = quietButton("修改联系方式");
        JButton history = quietButton("查看状态历史");
        edit.addActionListener(event -> editSelfContact());
        history.addActionListener(event -> showHistory(null));
        actions.add(edit);
        actions.add(history);
        card.add(actions, BorderLayout.SOUTH);
        return card;
    }

    private void addSelfField(JPanel panel, String label, String key) {
        JLabel name = new JLabel(label);
        name.setForeground(Theme.MUTED);
        JLabel value = new JLabel("—");
        value.setForeground(Theme.TEXT);
        value.setFont(value.getFont().deriveFont(Font.BOLD, 14f));
        selfValues.put(key, value);
        panel.add(name);
        panel.add(value);
    }

    private void loadStudents(int page) {
        if (busy) {
            return;
        }
        StatusOption status = (StatusOption) statusFilter.getSelectedItem();
        runRequest(
                () -> client.searchStudents(
                        sessionToken,
                        keywordField.getText().trim(),
                        status == null ? "" : status.code(),
                        page),
                response -> {
                    tableModel.setRowCount(0);
                    int count = integer(response.data(), "count");
                    totalRows = integer(response.data(), "total");
                    currentPage = integer(response.data(), "page");
                    for (int index = 0; index < count; index++) {
                        String prefix = "row." + index + ".";
                        tableModel.addRow(new Object[]{
                                response.data().get(prefix + "id"),
                                response.data().get(prefix + "studentNumber"),
                                response.data().get(prefix + "fullName"),
                                genderName(response.data().get(prefix + "gender")),
                                response.data().get(prefix + "departmentName"),
                                response.data().get(prefix + "majorName"),
                                response.data().get(prefix + "className"),
                                statusName(response.data().get(prefix + "status")),
                                response.data().get(prefix + "phone"),
                                response.data().get(prefix + "email")
                        });
                    }
                    pageLabel.setText("第 " + currentPage + " 页 · 共 " + totalRows + " 条");
                });
    }

    private void loadSelf() {
        runRequest(() -> client.getMyStudentProfile(sessionToken), response -> {
            selfProfile = response.data();
            for (Map.Entry<String, JLabel> entry : selfValues.entrySet()) {
                String value = response.data().getOrDefault(entry.getKey(), "");
                if (entry.getKey().equals("gender")) {
                    value = genderName(value);
                } else if (entry.getKey().equals("status")) {
                    value = statusName(value);
                }
                entry.getValue().setText(value.isBlank() ? "—" : value);
            }
        });
    }

    private void loadSelectedForEdit() {
        int row = selectedRow();
        if (row < 0) {
            return;
        }
        long studentId = Long.parseLong(tableModel.getValueAt(row, 0).toString());
        runRequest(() -> client.getStudent(sessionToken, studentId), profile ->
                runRequest(() -> client.studentReferenceData(sessionToken), references ->
                        showEditDialog(profile, references)));
    }

    private void editSelectedContact() {
        int row = selectedRow();
        if (row < 0) {
            return;
        }
        long studentId = Long.parseLong(tableModel.getValueAt(row, 0).toString());
        runRequest(() -> client.getStudent(sessionToken, studentId), response -> editContact(
                studentId,
                response.data().getOrDefault("phone", ""),
                response.data().getOrDefault("email", ""),
                response.data().getOrDefault("address", ""),
                () -> loadStudents(currentPage)));
    }

    private void showEditDialog(ResponseMessage profile, ResponseMessage referenceResponse) {
        List<ReferenceOption> departments = references(referenceResponse.data(), "department");
        List<ReferenceOption> majors = references(referenceResponse.data(), "major");
        List<ReferenceOption> classes = references(referenceResponse.data(), "class");
        JTextField name = new JTextField(profile.data().getOrDefault("fullName", ""));
        JComboBox<Gender> gender = new JComboBox<>(Gender.values());
        gender.setSelectedItem(Gender.valueOf(profile.data().getOrDefault("gender", Gender.UNSPECIFIED.name())));
        JTextField birthDate = new JTextField(profile.data().getOrDefault("birthDate", ""));
        JComboBox<ReferenceOption> department = new JComboBox<>(departments.toArray(ReferenceOption[]::new));
        JComboBox<ReferenceOption> major = new JComboBox<>();
        JComboBox<ReferenceOption> studentClass = new JComboBox<>();
        JTextField phone = new JTextField(profile.data().getOrDefault("phone", ""));
        JTextField email = new JTextField(profile.data().getOrDefault("email", ""));
        JTextField address = new JTextField(profile.data().getOrDefault("address", ""));

        Runnable updateClasses = () -> {
            ReferenceOption selectedMajor = (ReferenceOption) major.getSelectedItem();
            studentClass.removeAllItems();
            if (selectedMajor != null) {
                classes.stream().filter(item -> item.parentId() == selectedMajor.id())
                        .forEach(studentClass::addItem);
            }
        };
        Runnable updateMajors = () -> {
            ReferenceOption selectedDepartment = (ReferenceOption) department.getSelectedItem();
            major.removeAllItems();
            if (selectedDepartment != null) {
                majors.stream().filter(item -> item.parentId() == selectedDepartment.id())
                        .forEach(major::addItem);
            }
            updateClasses.run();
        };
        department.addActionListener(event -> updateMajors.run());
        major.addActionListener(event -> updateClasses.run());

        selectById(department, Long.parseLong(profile.data().get("departmentId")));
        updateMajors.run();
        selectById(major, Long.parseLong(profile.data().get("majorId")));
        updateClasses.run();
        selectById(studentClass, Long.parseLong(profile.data().get("classId")));

        JPanel form = formPanel();
        addFormRow(form, "学号", new JLabel(profile.data().get("studentNumber")));
        addFormRow(form, "姓名", name);
        addFormRow(form, "性别", gender);
        addFormRow(form, "出生日期（yyyy-MM-dd）", birthDate);
        addFormRow(form, "学院", department);
        addFormRow(form, "专业", major);
        addFormRow(form, "行政班", studentClass);
        addFormRow(form, "电话", phone);
        addFormRow(form, "邮箱", email);
        addFormRow(form, "地址", address);
        if (JOptionPane.showConfirmDialog(
                this, form, "编辑学生档案", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) {
            return;
        }
        ReferenceOption selectedDepartment = (ReferenceOption) department.getSelectedItem();
        ReferenceOption selectedMajor = (ReferenceOption) major.getSelectedItem();
        ReferenceOption selectedClass = (ReferenceOption) studentClass.getSelectedItem();
        if (selectedDepartment == null || selectedMajor == null || selectedClass == null) {
            showError("请选择完整的学院、专业和班级");
            return;
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("studentId", profile.data().get("id"));
        values.put("fullName", name.getText().trim());
        values.put("gender", ((Gender) gender.getSelectedItem()).name());
        values.put("birthDate", birthDate.getText().trim());
        values.put("departmentId", Long.toString(selectedDepartment.id()));
        values.put("majorId", Long.toString(selectedMajor.id()));
        values.put("classId", Long.toString(selectedClass.id()));
        values.put("enrollmentYear", Integer.toString(selectedClass.year()));
        values.put("phone", phone.getText().trim());
        values.put("email", email.getText().trim());
        values.put("address", address.getText().trim());
        runRequest(() -> client.updateStudent(sessionToken, values), response -> {
            UiDialogs.showSuccess(this, response.message());
            loadStudents(currentPage);
        });
    }

    private void editSelfContact() {
        if (selfProfile.isEmpty()) {
            return;
        }
        editContact(
                null,
                selfProfile.getOrDefault("phone", ""),
                selfProfile.getOrDefault("email", ""),
                selfProfile.getOrDefault("address", ""),
                this::loadSelf);
    }

    private void editContact(Long studentId, String oldPhone, String oldEmail, String oldAddress, Runnable refresh) {
        JTextField phone = new JTextField(oldPhone);
        JTextField email = new JTextField(oldEmail);
        JTextField address = new JTextField(oldAddress);
        JPanel form = formPanel();
        addFormRow(form, "电话", phone);
        addFormRow(form, "邮箱", email);
        addFormRow(form, "地址", address);
        if (JOptionPane.showConfirmDialog(
                this, form, "修改联系方式", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) {
            return;
        }
        Map<String, String> values = new LinkedHashMap<>();
        if (studentId != null) {
            values.put("studentId", Long.toString(studentId));
        }
        values.put("phone", phone.getText().trim());
        values.put("email", email.getText().trim());
        values.put("address", address.getText().trim());
        runRequest(() -> client.updateStudentContact(sessionToken, values), response -> {
            UiDialogs.showSuccess(this, response.message());
            refresh.run();
        });
    }

    private void changeSelectedStatus() {
        int row = selectedRow();
        if (row < 0) {
            return;
        }
        long studentId = Long.parseLong(tableModel.getValueAt(row, 0).toString());
        JComboBox<StudentStatus> status = new JComboBox<>(StudentStatus.values());
        JTextField reason = new JTextField();
        JPanel form = formPanel();
        addFormRow(form, "新状态", status);
        addFormRow(form, "变更原因", reason);
        if (JOptionPane.showConfirmDialog(
                this, form, "变更学籍状态", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
                != JOptionPane.OK_OPTION) {
            return;
        }
        StudentStatus selected = (StudentStatus) status.getSelectedItem();
        runRequest(
                () -> client.changeStudentStatus(sessionToken, studentId, selected.name(), reason.getText().trim()),
                response -> {
                    UiDialogs.showSuccess(this, response.message());
                    loadStudents(currentPage);
                });
    }

    private void showSelectedHistory() {
        int row = selectedRow();
        if (row >= 0) {
            showHistory(Long.parseLong(tableModel.getValueAt(row, 0).toString()));
        }
    }

    private void showHistory(Long studentId) {
        runRequest(() -> client.studentStatusHistory(sessionToken, studentId), response -> {
            int count = integer(response.data(), "count");
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < count; index++) {
                String prefix = "row." + index + ".";
                String oldStatus = response.data().get(prefix + "oldStatus");
                text.append(response.data().get(prefix + "changedAt"))
                        .append("  ")
                        .append(oldStatus == null || oldStatus.isBlank() ? "建档" : statusName(oldStatus))
                        .append(" → ")
                        .append(statusName(response.data().get(prefix + "newStatus")))
                        .append("\n原因：").append(response.data().get(prefix + "reason"))
                        .append("  操作人：").append(response.data().get(prefix + "operator"))
                        .append("\n\n");
            }
            JTextArea area = new JTextArea(text.length() == 0 ? "暂无状态记录" : text.toString());
            area.setEditable(false);
            area.setRows(14);
            area.setColumns(52);
            JOptionPane.showMessageDialog(
                    this, new JScrollPane(area), "学籍状态历史", JOptionPane.INFORMATION_MESSAGE);
        });
    }

    private JPanel formPanel() {
        JPanel panel = new JPanel(new GridLayout(0, 2, 10, 9));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        return panel;
    }

    private void addFormRow(JPanel panel, String label, java.awt.Component field) {
        panel.add(new JLabel(label));
        panel.add(field);
    }

    private List<ReferenceOption> references(Map<String, String> data, String type) {
        int count = integer(data, type + ".count");
        List<ReferenceOption> items = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String prefix = type + "." + index + ".";
            items.add(new ReferenceOption(
                    Long.parseLong(data.get(prefix + "id")),
                    Long.parseLong(data.get(prefix + "parentId")),
                    data.get(prefix + "code"),
                    data.get(prefix + "name"),
                    Integer.parseInt(data.get(prefix + "year"))));
        }
        return items;
    }

    private void selectById(JComboBox<ReferenceOption> comboBox, long id) {
        for (int index = 0; index < comboBox.getItemCount(); index++) {
            if (comboBox.getItemAt(index).id() == id) {
                comboBox.setSelectedIndex(index);
                return;
            }
        }
    }

    private int selectedRow() {
        int row = table.getSelectedRow();
        if (row < 0) {
            showError("请先选择一名学生");
        }
        return row;
    }

    private JButton quietButton(String text) {
        JButton button = new JButton(text);
        Theme.styleQuietButton(button);
        button.setForeground(Theme.TEXT);
        button.setFont(button.getFont().deriveFont(Font.BOLD));
        return button;
    }

    private DefaultTableCellRenderer createHeaderRenderer() {
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();
        renderer.setOpaque(true);
        renderer.setBackground(Theme.HEADER);
        renderer.setForeground(Theme.TEXT);
        renderer.setFont(table.getFont().deriveFont(Font.BOLD));
        renderer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 1, Theme.BORDER),
                BorderFactory.createEmptyBorder(7, 8, 7, 8)));
        return renderer;
    }

    private int integer(Map<String, String> data, String key) {
        return Integer.parseInt(data.getOrDefault(key, "0"));
    }

    private String genderName(String code) {
        try {
            return Gender.valueOf(code).displayName();
        } catch (Exception ignored) {
            return code == null ? "" : code;
        }
    }

    private String statusName(String code) {
        try {
            return StudentStatus.valueOf(code).displayName();
        } catch (Exception ignored) {
            return code == null ? "" : code;
        }
    }

    private void runRequest(RequestCall call, ResponseConsumer consumer) {
        if (busy) {
            return;
        }
        setBusy(true);
        CompletableFuture.supplyAsync(() -> {
            try {
                return call.execute();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }).whenComplete((response, error) -> SwingUtilities.invokeLater(() -> {
            setBusy(false);
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

    private void setBusy(boolean value) {
        busy = value;
        setCursor(Cursor.getPredefinedCursor(value ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
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

    private record StatusOption(String code, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private record ReferenceOption(long id, long parentId, String code, String name, int year) {
        @Override
        public String toString() {
            return code + " · " + name;
        }
    }
}
