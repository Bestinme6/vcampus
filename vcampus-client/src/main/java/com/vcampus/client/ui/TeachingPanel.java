package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.client.ui.AcademicViewData.RosterView;
import com.vcampus.client.ui.AcademicViewData.SectionView;
import com.vcampus.client.ui.AcademicViewData.TermOption;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TeachingPanel extends AcademicPanel {
    private final JComboBox<TermOption> term = new JComboBox<>();
    private final DefaultTableModel sectionModel = new DefaultTableModel(
            new String[]{"课程", "教学班", "人数", "上课时间", "教室", "成绩状态"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable sectionTable = new JTable(sectionModel);
    private final DefaultTableModel rosterModel = new DefaultTableModel(
            new String[]{"学号", "姓名", "成绩", "绩点", "备注"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable rosterTable = new JTable(rosterModel);
    private List<SectionView> sections = List.of();
    private List<RosterView> roster = List.of();
    private SectionView activeSection;

    TeachingPanel(VCampusClient client, String sessionToken) {
        super(client, sessionToken);
        setLayout(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.setOpaque(false);
        term.setPreferredSize(new Dimension(300, 40));
        JButton refresh = primaryButton("刷新授课班");
        refresh.addActionListener(event -> loadSections());
        header.add(term, BorderLayout.WEST);
        header.add(refresh, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        styleTable(sectionTable);
        styleTable(rosterTable);
        JScrollPane sectionScroll = new JScrollPane(sectionTable);
        JScrollPane rosterScroll = new JScrollPane(rosterTable);
        sectionScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Theme.BORDER), "我的教学班"));
        rosterScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Theme.BORDER), "学生与成绩"));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sectionScroll, rosterScroll);
        split.setResizeWeight(0.42);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        JPanel actions = new JPanel();
        actions.setOpaque(false);
        JButton loadRoster = actionButton("加载学生名单");
        JButton grade = actionButton("录入或修改成绩");
        JButton publish = actionButton("发布最终成绩");
        loadRoster.addActionListener(event -> loadSelectedRoster());
        grade.addActionListener(event -> editGrade());
        publish.addActionListener(event -> publishGrades());
        actions.add(loadRoster);
        actions.add(grade);
        actions.add(publish);
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
        long termId = selected == null ? 0 : selected.id();
        runRequest(() -> client.teacherSections(sessionToken, termId), response -> {
            sections = AcademicViewData.sections(response);
            sectionModel.setRowCount(0);
            rosterModel.setRowCount(0);
            roster = List.of();
            activeSection = null;
            for (SectionView row : sections) {
                sectionModel.addRow(new Object[]{
                        row.courseCode() + " · " + row.courseName(), row.sectionCode(),
                        row.enrolledCount() + "/" + row.capacity(), row.schedule(), row.classroomSummary(),
                        row.gradesPublished() ? "已发布" : "未发布"});
            }
        });
    }

    private void loadSelectedRoster() {
        int selected = selectedRow(sectionTable);
        if (selected < 0) {
            return;
        }
        activeSection = sections.get(selected);
        loadRoster(activeSection.id());
    }

    private void loadRoster(long sectionId) {
        runRequest(() -> client.sectionRoster(sessionToken, sectionId), response -> {
            roster = AcademicViewData.roster(response);
            rosterModel.setRowCount(0);
            for (RosterView row : roster) {
                rosterModel.addRow(new Object[]{
                        row.studentNumber(), row.fullName(), row.score(), row.gradePoint(), row.comment()});
            }
        });
    }

    private void editGrade() {
        if (activeSection == null) {
            showError("请先加载一个教学班的学生名单");
            return;
        }
        if (activeSection.gradesPublished()) {
            showError("成绩已发布，不能直接修改");
            return;
        }
        int selected = selectedRow(rosterTable);
        if (selected < 0) {
            return;
        }
        RosterView student = roster.get(selected);
        JTextField score = new JTextField(student.score() == null ? "" : student.score().toPlainString());
        JTextField comment = new JTextField(student.comment() == null ? "" : student.comment());
        JTextField reason = new JTextField(student.score() == null ? "首次录入" : "成绩修正");
        JPanel form = new JPanel(new GridLayout(0, 2, 10, 9));
        form.add(new JLabel("学生"));
        form.add(new JLabel(student.studentNumber() + " · " + student.fullName()));
        form.add(new JLabel("成绩（0-100）"));
        form.add(score);
        form.add(new JLabel("备注"));
        form.add(comment);
        form.add(new JLabel("录入/修改原因"));
        form.add(reason);
        if (JOptionPane.showConfirmDialog(
                this, form, "录入成绩", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("sectionId", Long.toString(activeSection.id()));
        values.put("enrollmentId", Long.toString(student.enrollmentId()));
        values.put("score", score.getText().trim());
        values.put("comment", comment.getText().trim());
        values.put("reason", reason.getText().trim());
        runRequest(() -> client.saveGrade(sessionToken, values), response -> {
            showInfo(response.message());
            loadRoster(activeSection.id());
        });
    }

    private void publishGrades() {
        int selected = selectedRow(sectionTable);
        if (selected < 0) {
            return;
        }
        SectionView section = sections.get(selected);
        if (section.gradesPublished()) {
            showError("该教学班成绩已经发布");
            return;
        }
        int confirmation = JOptionPane.showConfirmDialog(
                this,
                "发布后不能直接修改成绩。确认发布“" + section.courseName() + "”的最终成绩吗？",
                "发布最终成绩",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (confirmation != JOptionPane.YES_OPTION) {
            return;
        }
        runRequest(() -> client.publishGrades(sessionToken, section.id()), response -> {
            showInfo(response.message());
            loadSections();
        });
    }
}
