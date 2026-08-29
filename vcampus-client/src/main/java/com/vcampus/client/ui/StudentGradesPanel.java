package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.client.ui.AcademicViewData.GradeView;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.util.List;

final class StudentGradesPanel extends AcademicPanel {
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"学期", "课程号", "课程名称", "学分", "教师", "成绩", "绩点"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);

    StudentGradesPanel(VCampusClient client, String sessionToken) {
        super(client, sessionToken);
        setLayout(new BorderLayout(0, 14));
        setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JLabel publicationHint = new JLabel("仅显示已发布成绩；已录入但未发布的成绩不会显示");
        publicationHint.setForeground(Theme.TEXT);
        JButton refresh = primaryButton("刷新成绩");
        refresh.addActionListener(event -> load());
        header.add(publicationHint, BorderLayout.WEST);
        header.add(refresh, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        styleTable(table);
        add(new JScrollPane(table), BorderLayout.CENTER);
        SwingUtilities.invokeLater(this::load);
    }

    private void load() {
        runRequest(() -> client.myGrades(sessionToken), response -> {
            List<GradeView> rows = AcademicViewData.grades(response);
            model.setRowCount(0);
            for (GradeView row : rows) {
                model.addRow(new Object[]{
                        row.termName(), row.courseCode(), row.courseName(), row.credits(),
                        row.teacherName(), row.score(), row.gradePoint()});
            }
        });
    }
}
