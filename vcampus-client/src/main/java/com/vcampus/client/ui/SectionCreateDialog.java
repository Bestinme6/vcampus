package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.client.ui.AcademicViewData.CourseOption;
import com.vcampus.client.ui.AcademicViewData.ReferenceData;
import com.vcampus.client.ui.AcademicViewData.TeacherOption;
import com.vcampus.client.ui.AcademicViewData.TermOption;
import com.vcampus.common.model.CourseSectionStatus;
import com.vcampus.common.model.ScheduleSlot;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

final class SectionCreateDialog {
    private final Component parent;
    private final VCampusClient client;
    private final String sessionToken;
    private final JComboBox<TermOption> term;
    private final JComboBox<CourseOption> course;
    private final JComboBox<TeacherOption> teacher;
    private final JTextField code = new JTextField();
    private final JTextField capacity = new JTextField("40");
    private final JComboBox<CourseSectionStatus> status = new JComboBox<>(CourseSectionStatus.values());
    private final JSpinner startWeek = new JSpinner(new SpinnerNumberModel(1, 1, 30, 1));
    private final JSpinner endWeek = new JSpinner(new SpinnerNumberModel(16, 1, 30, 1));
    private final JTextField classroom = new JTextField();
    private final JLabel availabilityStatus = new JLabel("请选择学期和授课教师");
    private final ScheduleGridPanel scheduleGrid = new ScheduleGridPanel(true);
    private final AtomicLong availabilitySequence = new AtomicLong();
    private final JPanel content = new JPanel(new BorderLayout(0, 10));

    private SectionCreateDialog(
            Component parent,
            VCampusClient client,
            String sessionToken,
            ReferenceData references,
            TermOption selectedTerm) {
        this.parent = parent;
        this.client = client;
        this.sessionToken = sessionToken;
        term = new JComboBox<>(references.terms().toArray(TermOption[]::new));
        course = new JComboBox<>(references.courses().toArray(CourseOption[]::new));
        teacher = new JComboBox<>(references.teachers().toArray(TeacherOption[]::new));
        status.setSelectedItem(CourseSectionStatus.OPEN);
        selectTerm(selectedTerm);
        buildContent();
        term.addActionListener(event -> loadAvailability());
        teacher.addActionListener(event -> loadAvailability());
        startWeek.addChangeListener(event -> refreshWeekRange());
        endWeek.addChangeListener(event -> refreshWeekRange());
        refreshWeekRange();
        loadAvailability();
    }

    static Map<String, String> show(
            Component parent,
            VCampusClient client,
            String sessionToken,
            ReferenceData references,
            TermOption selectedTerm) {
        SectionCreateDialog dialog = new SectionCreateDialog(
                parent, client, sessionToken, references, selectedTerm);
        return dialog.showUntilValid();
    }

    private void buildContent() {
        content.setBackground(Theme.BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        content.setPreferredSize(new Dimension(1120, 750));
        Theme.styleField(code);
        Theme.styleField(capacity);
        Theme.styleField(classroom);

        JPanel fields = new JPanel(new GridLayout(3, 6, 9, 7));
        fields.setBackground(Theme.BACKGROUND);
        addField(fields, "学期", term);
        addField(fields, "课程", course);
        addField(fields, "教学班编号", code);
        addField(fields, "授课教师", teacher);
        addField(fields, "容量", capacity);
        addField(fields, "初始状态", status);
        addField(fields, "开始周", startWeek);
        addField(fields, "结束周", endWeek);
        addField(fields, "教室", classroom);

        JPanel heading = new JPanel(new BorderLayout(0, 6));
        heading.setBackground(Theme.BACKGROUND);
        heading.add(fields, BorderLayout.NORTH);
        JLabel hint = new JLabel("在课表中点击单个格子，或在同一天纵向拖动选择连续节次；红色格子与该教师已有课程冲突。");
        hint.setForeground(Theme.MUTED);
        heading.add(hint, BorderLayout.CENTER);
        availabilityStatus.setForeground(Theme.MUTED);
        heading.add(availabilityStatus, BorderLayout.SOUTH);
        content.add(heading, BorderLayout.NORTH);
        content.add(scheduleGrid, BorderLayout.CENTER);
    }

    private void addField(JPanel fields, String label, Component field) {
        JLabel name = new JLabel(label);
        name.setForeground(Theme.TEXT);
        fields.add(name);
        fields.add(field);
    }

    private Map<String, String> showUntilValid() {
        while (true) {
            int result = JOptionPane.showConfirmDialog(
                    parent, content, "新增教学班 · 图形化排课",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return null;
            }
            try {
                return values();
            } catch (IllegalArgumentException exception) {
                JOptionPane.showMessageDialog(
                        parent, exception.getMessage(), "操作失败", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private Map<String, String> values() {
        TermOption selectedTerm = (TermOption) term.getSelectedItem();
        CourseOption selectedCourse = (CourseOption) course.getSelectedItem();
        TeacherOption selectedTeacher = (TeacherOption) teacher.getSelectedItem();
        if (selectedTerm == null || selectedCourse == null || selectedTeacher == null) {
            throw new IllegalArgumentException("请选择学期、课程和授课教师");
        }
        String selectedClassroom = classroom.getText().trim();
        if (selectedClassroom.isBlank()) {
            throw new IllegalArgumentException("请填写教室");
        }
        int fromWeek = (Integer) startWeek.getValue();
        int toWeek = (Integer) endWeek.getValue();
        if (toWeek < fromWeek) {
            throw new IllegalArgumentException("结束周不能早于开始周");
        }
        List<ScheduleSlot> slots = scheduleGrid.selectedSlots(fromWeek, toWeek, selectedClassroom);
        if (slots.isEmpty()) {
            throw new IllegalArgumentException("请在课表中至少选择一个上课时段");
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("termId", Long.toString(selectedTerm.id()));
        values.put("courseId", Long.toString(selectedCourse.id()));
        values.put("sectionCode", code.getText().trim());
        values.put("teacherUserId", Long.toString(selectedTeacher.userId()));
        values.put("capacity", capacity.getText().trim());
        values.put("status", ((CourseSectionStatus) status.getSelectedItem()).name());
        values.put("schedule.count", Integer.toString(slots.size()));
        for (int index = 0; index < slots.size(); index++) {
            ScheduleSlot slot = slots.get(index);
            values.put("schedule." + index, RowCodec.encode(
                    Integer.toString(slot.dayOfWeek()), Integer.toString(slot.startPeriod()),
                    Integer.toString(slot.endPeriod()), Integer.toString(slot.startWeek()),
                    Integer.toString(slot.endWeek()), slot.classroom()));
        }
        return values;
    }

    private void selectTerm(TermOption selectedTerm) {
        if (selectedTerm == null) {
            return;
        }
        for (int index = 0; index < term.getItemCount(); index++) {
            if (term.getItemAt(index).id() == selectedTerm.id()) {
                term.setSelectedIndex(index);
                return;
            }
        }
    }

    private void refreshWeekRange() {
        int fromWeek = (Integer) startWeek.getValue();
        int toWeek = (Integer) endWeek.getValue();
        if (toWeek < fromWeek) {
            endWeek.setValue(fromWeek);
            toWeek = fromWeek;
        }
        scheduleGrid.setWeekRange(fromWeek, toWeek);
    }

    private void loadAvailability() {
        TermOption selectedTerm = (TermOption) term.getSelectedItem();
        TeacherOption selectedTeacher = (TeacherOption) teacher.getSelectedItem();
        if (selectedTerm == null || selectedTeacher == null) {
            scheduleGrid.setEntries(List.of());
            availabilityStatus.setText("请选择学期和授课教师");
            return;
        }
        long sequence = availabilitySequence.incrementAndGet();
        scheduleGrid.setEntries(List.of());
        availabilityStatus.setForeground(Theme.MUTED);
        availabilityStatus.setText("正在加载教师已有课表……");
        CompletableFuture.supplyAsync(() -> requestAvailability(selectedTerm.id(), selectedTeacher.userId()))
                .whenComplete((response, error) -> SwingUtilities.invokeLater(() -> {
                    if (sequence != availabilitySequence.get()) {
                        return;
                    }
                    if (error != null) {
                        availabilityStatus.setForeground(Theme.DANGER);
                        availabilityStatus.setText("已有课表加载失败：" + message(error));
                        return;
                    }
                    if (!response.success()) {
                        availabilityStatus.setForeground(Theme.DANGER);
                        availabilityStatus.setText(response.message());
                        return;
                    }
                    scheduleGrid.setEntries(AcademicViewData.schedules(response));
                    availabilityStatus.setForeground(Theme.SUCCESS);
                    availabilityStatus.setText("已有课表已加载；与当前周次重叠的格子不可选择");
                }));
    }

    private ResponseMessage requestAvailability(long termId, long teacherUserId) {
        try {
            return client.teacherSchedule(sessionToken, termId, teacherUserId);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private String message(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return cause.getMessage() == null ? "请求失败" : cause.getMessage();
    }
}
