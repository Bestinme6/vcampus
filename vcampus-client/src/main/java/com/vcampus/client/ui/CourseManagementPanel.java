package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CourseManagementPanel extends AcademicPanel {
    private final JTextField keyword = new JTextField();
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"课程号", "课程名称", "学分", "学时", "状态", "课程说明"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JLabel pageLabel = new JLabel("第 1 页");
    private List<CourseRow> rows = List.of();
    private int page = 1;
    private int total;

    CourseManagementPanel(VCampusClient client, String sessionToken) {
        super(client, sessionToken);
        setLayout(new BorderLayout(0, 14));
        setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        JPanel filters = new JPanel(new BorderLayout(10, 0));
        filters.setOpaque(false);
        Theme.styleField(keyword);
        keyword.setToolTipText("输入课程号或课程名称");
        JButton search = primaryButton("查询");
        search.addActionListener(event -> load(1));
        filters.add(keyword, BorderLayout.CENTER);
        filters.add(search, BorderLayout.EAST);
        add(filters, BorderLayout.NORTH);

        styleTable(table);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        add(scroll, BorderLayout.CENTER);

        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        JPanel left = new JPanel();
        left.setOpaque(false);
        JButton create = actionButton("新增课程");
        JButton edit = actionButton("编辑课程");
        create.addActionListener(event -> editCourse(null));
        edit.addActionListener(event -> {
            int selected = selectedRow(table);
            if (selected >= 0) {
                editCourse(rows.get(selected));
            }
        });
        left.add(create);
        left.add(edit);

        JPanel paging = new JPanel();
        paging.setOpaque(false);
        JButton previous = actionButton("上一页");
        JButton next = actionButton("下一页");
        previous.addActionListener(event -> load(Math.max(1, page - 1)));
        next.addActionListener(event -> {
            if (page * 8 < total) {
                load(page + 1);
            }
        });
        pageLabel.setForeground(Theme.MUTED);
        paging.add(previous);
        paging.add(pageLabel);
        paging.add(next);
        actions.add(left, BorderLayout.WEST);
        actions.add(paging, BorderLayout.EAST);
        add(actions, BorderLayout.SOUTH);
        SwingUtilities.invokeLater(() -> load(1));
    }

    private void load(int requestedPage) {
        runRequest(() -> client.searchCourses(sessionToken, keyword.getText().trim(), requestedPage), response -> {
            List<CourseRow> loaded = new ArrayList<>();
            model.setRowCount(0);
            int count = Integer.parseInt(response.data().getOrDefault("count", "0"));
            for (int index = 0; index < count; index++) {
                List<String> values = RowCodec.decode(response.data().get("row." + index));
                CourseRow row = new CourseRow(
                        Long.parseLong(values.get(0)), values.get(1), values.get(2), values.get(3),
                        values.get(4), values.get(5), Boolean.parseBoolean(values.get(6)));
                loaded.add(row);
                model.addRow(new Object[]{
                        row.code(), row.name(), row.credits(), row.hours(),
                        row.enabled() ? "启用" : "停用", row.description()});
            }
            rows = List.copyOf(loaded);
            page = Integer.parseInt(response.data().getOrDefault("page", "1"));
            total = Integer.parseInt(response.data().getOrDefault("total", "0"));
            pageLabel.setText("第 " + page + " 页 · 共 " + total + " 条");
        });
    }

    private void editCourse(CourseRow existing) {
        JTextField code = new JTextField(existing == null ? "" : existing.code());
        JTextField name = new JTextField(existing == null ? "" : existing.name());
        JTextField credits = new JTextField(existing == null ? "" : existing.credits());
        JTextField hours = new JTextField(existing == null ? "" : existing.hours());
        JTextArea description = new JTextArea(existing == null ? "" : existing.description(), 3, 28);
        JCheckBox enabled = new JCheckBox("启用课程", existing == null || existing.enabled());
        code.setEditable(existing == null);

        JPanel form = new JPanel(new GridLayout(0, 2, 10, 9));
        form.add(new JLabel("课程号（C加6位数字）"));
        form.add(code);
        form.add(new JLabel("课程名称"));
        form.add(name);
        form.add(new JLabel("学分"));
        form.add(credits);
        form.add(new JLabel("总学时"));
        form.add(hours);
        form.add(new JLabel("课程说明"));
        form.add(new JScrollPane(description));
        form.add(new JLabel("状态"));
        form.add(enabled);

        if (JOptionPane.showConfirmDialog(
                this, form, existing == null ? "新增课程" : "编辑课程",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("courseCode", code.getText().trim());
        values.put("courseName", name.getText().trim());
        values.put("credits", credits.getText().trim());
        values.put("totalHours", hours.getText().trim());
        values.put("description", description.getText().trim());
        values.put("enabled", Boolean.toString(enabled.isSelected()));
        if (existing != null) {
            values.put("courseId", Long.toString(existing.id()));
        }
        runRequest(
                () -> existing == null
                        ? client.createCourse(sessionToken, values)
                        : client.updateCourse(sessionToken, values),
                response -> {
                    showInfo(response.message());
                    load(existing == null ? 1 : page);
                });
    }

    private record CourseRow(
            long id, String code, String name, String credits,
            String hours, String description, boolean enabled) {
    }
}
