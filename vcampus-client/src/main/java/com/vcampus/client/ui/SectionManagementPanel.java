package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.CourseSectionStatus;
import com.vcampus.client.ui.AcademicViewData.ReferenceData;
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
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.List;
import java.util.Map;

final class SectionManagementPanel extends AcademicPanel {
    private final JComboBox<TermOption> termFilter = new JComboBox<>();
    private final JTextField keyword = new JTextField();
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"教学班", "课程", "教师", "人数", "状态", "上课时间", "教室", "成绩"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JLabel pageLabel = new JLabel("第 1 页");
    private ReferenceData references = new ReferenceData(List.of(), List.of(), List.of());
    private List<SectionView> rows = List.of();
    private int page = 1;
    private int total;

    SectionManagementPanel(VCampusClient client, String sessionToken) {
        super(client, sessionToken);
        setLayout(new BorderLayout(0, 14));
        setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        JPanel filters = new JPanel(new BorderLayout(10, 0));
        filters.setOpaque(false);
        termFilter.setPreferredSize(new Dimension(250, 40));
        Theme.styleField(keyword);
        JButton search = primaryButton("查询");
        search.addActionListener(event -> load(1));
        filters.add(termFilter, BorderLayout.WEST);
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
        JButton create = actionButton("新增教学班");
        JButton status = actionButton("修改状态");
        JButton roster = actionButton("查看名单");
        JButton publish = actionButton("发布成绩");
        create.addActionListener(event -> createSection());
        status.addActionListener(event -> changeStatus());
        roster.addActionListener(event -> showRoster());
        publish.addActionListener(event -> publishGrades());
        left.add(create);
        left.add(status);
        left.add(roster);
        left.add(publish);

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
    }

    void refreshReferences() {
        runRequest(() -> client.academicReferenceData(sessionToken), response -> {
            references = AcademicViewData.references(response);
            termFilter.removeAllItems();
            references.terms().forEach(termFilter::addItem);
            if (!references.terms().isEmpty()) {
                load(1);
            }
        });
    }

    private void load(int requestedPage) {
        TermOption term = (TermOption) termFilter.getSelectedItem();
        long termId = term == null ? 0 : term.id();
        runRequest(
                () -> client.searchCourseSections(
                        sessionToken, termId, keyword.getText().trim(), requestedPage),
                response -> {
                    rows = AcademicViewData.sections(response);
                    model.setRowCount(0);
                    for (SectionView row : rows) {
                        model.addRow(new Object[]{
                                row.sectionCode(), row.courseCode() + " · " + row.courseName(),
                                row.teacherName(), row.enrolledCount() + "/" + row.capacity(),
                                row.status().displayName(), row.schedule(), row.classroomSummary(),
                                row.gradesPublished() ? "已发布" : "未发布"});
                    }
                    page = Integer.parseInt(response.data().getOrDefault("page", "1"));
                    total = Integer.parseInt(response.data().getOrDefault("total", "0"));
                    pageLabel.setText("第 " + page + " 页 · 共 " + total + " 条");
                });
    }

    private void createSection() {
        if (references.terms().isEmpty() || references.courses().isEmpty() || references.teachers().isEmpty()) {
            showError("请先配置学期、课程和教师账号");
            return;
        }
        TermOption selectedFilter = (TermOption) termFilter.getSelectedItem();
        Map<String, String> values = SectionCreateDialog.show(
                this, client, sessionToken, references, selectedFilter);
        if (values == null) {
            return;
        }
        runRequest(() -> client.createCourseSection(sessionToken, values), response -> {
            showInfo(response.message());
            load(1);
        });
    }

    private void changeStatus() {
        SectionView selected = selectedSection();
        if (selected == null) {
            return;
        }
        JComboBox<CourseSectionStatus> status = new JComboBox<>(CourseSectionStatus.values());
        status.setSelectedItem(selected.status());
        if (JOptionPane.showConfirmDialog(
                this, status, "修改教学班状态", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        runRequest(
                () -> client.setCourseSectionStatus(
                        sessionToken, selected.id(), ((CourseSectionStatus) status.getSelectedItem()).name()),
                response -> {
                    showInfo(response.message());
                    load(page);
                });
    }

    private void showRoster() {
        SectionView selected = selectedSection();
        if (selected == null) {
            return;
        }
        runRequest(() -> client.sectionRoster(sessionToken, selected.id()), response -> {
            List<RosterView> roster = AcademicViewData.roster(response);
            DefaultTableModel rosterModel = new DefaultTableModel(
                    new String[]{"学号", "姓名", "成绩", "绩点", "备注"}, 0);
            for (RosterView row : roster) {
                rosterModel.addRow(new Object[]{
                        row.studentNumber(), row.fullName(), row.score(), row.gradePoint(), row.comment()});
            }
            JTable rosterTable = new JTable(rosterModel);
            styleTable(rosterTable);
            JScrollPane scroll = new JScrollPane(rosterTable);
            scroll.setPreferredSize(new Dimension(760, 380));
            JOptionPane.showMessageDialog(
                    this, scroll, selected.courseName() + " · 学生名单", JOptionPane.INFORMATION_MESSAGE);
        });
    }

    private void publishGrades() {
        SectionView selected = selectedSection();
        if (selected == null) {
            return;
        }
        int confirmation = JOptionPane.showConfirmDialog(
                this,
                "发布后教师不能直接修改成绩。确认发布“" + selected.courseName() + "”的成绩吗？",
                "发布成绩",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (confirmation != JOptionPane.YES_OPTION) {
            return;
        }
        runRequest(() -> client.publishGrades(sessionToken, selected.id()), response -> {
            showInfo(response.message());
            load(page);
        });
    }

    private SectionView selectedSection() {
        int selected = selectedRow(table);
        return selected < 0 ? null : rows.get(selected);
    }

}
