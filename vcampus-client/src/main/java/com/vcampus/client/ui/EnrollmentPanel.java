package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.client.ui.AcademicViewData.SectionView;
import com.vcampus.client.ui.AcademicViewData.TermOption;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.List;

final class EnrollmentPanel extends AcademicPanel {
    private final JComboBox<TermOption> term = new JComboBox<>();
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"课程", "教学班", "教师", "学分", "人数", "上课时间", "教室", "选课状态"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private List<SectionView> rows = List.of();

    EnrollmentPanel(VCampusClient client, String sessionToken) {
        super(client, sessionToken);
        setLayout(new BorderLayout(0, 14));
        setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.setOpaque(false);
        term.setPreferredSize(new Dimension(300, 40));
        JButton refresh = primaryButton("刷新课程");
        refresh.addActionListener(event -> loadSections());
        header.add(term, BorderLayout.WEST);
        header.add(refresh, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        styleTable(table);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel actions = new JPanel();
        actions.setOpaque(false);
        JButton enroll = actionButton("选择课程");
        JButton drop = actionButton("退选课程");
        enroll.addActionListener(event -> enroll());
        drop.addActionListener(event -> drop());
        actions.add(enroll);
        actions.add(drop);
        add(actions, BorderLayout.SOUTH);
        SwingUtilities.invokeLater(this::loadReferences);
    }

    private void loadReferences() {
        runRequest(() -> client.academicReferenceData(sessionToken), response -> {
            term.removeAllItems();
            AcademicViewData.references(response).terms().forEach(term::addItem);
            if (term.getItemCount() > 0) {
                loadSections();
            }
        });
    }

    private void loadSections() {
        TermOption selected = (TermOption) term.getSelectedItem();
        if (selected == null) {
            return;
        }
        runRequest(() -> client.availableCourseSections(sessionToken, selected.id()), response -> {
            rows = AcademicViewData.sections(response);
            model.setRowCount(0);
            for (SectionView row : rows) {
                model.addRow(new Object[]{
                        row.courseCode() + " · " + row.courseName(), row.sectionCode(), row.teacherName(),
                        row.credits(), row.enrolledCount() + "/" + row.capacity(), row.schedule(),
                        row.classroomSummary(), enrollmentName(row.ownEnrollmentStatus())});
            }
        });
    }

    private void enroll() {
        SectionView selected = selectedSection();
        if (selected == null) {
            return;
        }
        if ("ENROLLED".equals(selected.ownEnrollmentStatus())) {
            showError("已经选择该课程");
            return;
        }
        runRequest(() -> client.enrollCourse(sessionToken, selected.id()), response -> {
            showInfo(response.message());
            loadSections();
        });
    }

    private void drop() {
        SectionView selected = selectedSection();
        if (selected == null) {
            return;
        }
        if (!"ENROLLED".equals(selected.ownEnrollmentStatus())) {
            showError("尚未选择该课程");
            return;
        }
        if (JOptionPane.showConfirmDialog(
                this, "确认退选“" + selected.courseName() + "”吗？",
                "退选课程", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        runRequest(() -> client.dropCourse(sessionToken, selected.id()), response -> {
            showInfo(response.message());
            loadSections();
        });
    }

    private SectionView selectedSection() {
        int selected = selectedRow(table);
        return selected < 0 ? null : rows.get(selected);
    }

    private String enrollmentName(String status) {
        return switch (status == null ? "" : status) {
            case "ENROLLED" -> "已选";
            case "DROPPED" -> "已退";
            default -> "未选";
        };
    }
}
