package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.AcademicAccessPolicy;
import com.vcampus.common.model.UserRole;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class AcademicFrame extends JFrame {
    private final AcademicModulePanel modulePanel;

    public AcademicFrame(VCampusClient client, String sessionToken, Set<UserRole> roles) {
        super("VCampus · 虚拟教务管理");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1080, 700));
        setSize(1280, 780);
        setLocationRelativeTo(null);
        modulePanel = new AcademicModulePanel(client, sessionToken, roles, null);
        setContentPane(modulePanel);
    }

    public void openTeacherSchedule() {
        modulePanel.openTeacherSchedule();
    }

    public void openStudentGrades() {
        modulePanel.openStudentGrades();
    }
}

final class AcademicModulePanel extends JPanel {
    private final JTabbedPane tabs = new JTabbedPane();

    AcademicModulePanel(
            VCampusClient client,
            String sessionToken,
            Set<UserRole> roles,
            Runnable backToWorkspace) {
        setLayout(new BorderLayout(0, 18));
        setBackground(Theme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(25, 28, 28, 28));
        JPanel heading = new JPanel(new BorderLayout(16, 0));
        heading.setOpaque(false);
        JPanel titles = new JPanel(new BorderLayout());
        titles.setOpaque(false);
        JLabel title = new JLabel("教务管理");
        title.setForeground(Theme.TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        JLabel subtitle = new JLabel("课程、教学班、选课、课表与成绩");
        subtitle.setForeground(Theme.MUTED);
        titles.add(title, BorderLayout.NORTH);
        titles.add(subtitle, BorderLayout.SOUTH);
        heading.add(titles, BorderLayout.WEST);
        if (backToWorkspace != null) {
            JButton back = new JButton("← 返回工作台");
            Theme.styleQuietButton(back);
            back.setForeground(Theme.TEXT);
            back.setFont(back.getFont().deriveFont(Font.BOLD));
            back.addActionListener(event -> backToWorkspace.run());
            heading.add(back, BorderLayout.EAST);
        }
        add(heading, BorderLayout.NORTH);

        tabs.setFont(tabs.getFont().deriveFont(Font.BOLD, 14f));
        tabs.setForeground(Theme.TEXT);
        SectionManagementPanel sectionManagement = null;
        if (AcademicAccessPolicy.canManage(roles)) {
            tabs.addTab("课程管理", new CourseManagementPanel(client, sessionToken));
            sectionManagement = new SectionManagementPanel(client, sessionToken);
            tabs.addTab(AcademicTabRefreshPolicy.SECTION_MANAGEMENT_TITLE, sectionManagement);
        }
        if (AcademicAccessPolicy.canStudy(roles)) {
            tabs.addTab("选课中心", new EnrollmentPanel(client, sessionToken));
            tabs.addTab("我的课表", new StudentSchedulePanel(client, sessionToken));
            tabs.addTab("我的成绩", new StudentGradesPanel(client, sessionToken));
        }
        if (AcademicAccessPolicy.canTeach(roles)) {
            tabs.addTab("教师课表", new TeacherSchedulePanel(client, sessionToken));
            tabs.addTab("授课与成绩", new TeachingPanel(client, sessionToken));
        }
        SectionManagementPanel refreshableSectionManagement = sectionManagement;
        tabs.addChangeListener(event -> {
            int selectedIndex = tabs.getSelectedIndex();
            if (refreshableSectionManagement != null
                    && selectedIndex >= 0
                    && AcademicTabRefreshPolicy.refreshesReferenceData(
                            tabs.getTitleAt(selectedIndex))) {
                refreshableSectionManagement.refreshReferences();
            }
        });
        add(tabs, BorderLayout.CENTER);
    }

    void openTeacherSchedule() {
        selectTab(AcademicModuleNavigation.teacherScheduleIndex(tabTitles()));
    }

    void openStudentGrades() {
        selectTab(AcademicModuleNavigation.studentGradesIndex(tabTitles()));
    }

    private void selectTab(int index) {
        if (index >= 0) {
            tabs.setSelectedIndex(index);
        }
    }

    private List<String> tabTitles() {
        List<String> titles = new ArrayList<>(tabs.getTabCount());
        for (int index = 0; index < tabs.getTabCount(); index++) {
            titles.add(tabs.getTitleAt(index));
        }
        return titles;
    }
}
