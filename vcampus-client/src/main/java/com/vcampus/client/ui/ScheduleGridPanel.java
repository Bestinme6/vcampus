package com.vcampus.client.ui;

import com.vcampus.client.ui.AcademicViewData.ScheduleEntryView;
import com.vcampus.common.model.ScheduleSlot;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ScheduleGridPanel extends JPanel {
    static final int PERIOD_COUNT = 12;
    private static final int DAY_COUNT = 7;
    private static final String[] DAYS = {
            "节次 / 星期", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"
    };
    private static final Color BLOCKED = new Color(245, 226, 226);
    private static final Color OTHER_WEEKS = new Color(255, 248, 225);
    private static final Color[] COURSE_COLORS = {
            new Color(229, 238, 255), new Color(226, 245, 237),
            new Color(244, 235, 255), new Color(255, 239, 222),
            new Color(226, 244, 248), new Color(242, 242, 225)
    };

    private final boolean selectable;
    private final boolean[][] selected = new boolean[PERIOD_COUNT][DAY_COUNT];
    private final JTable table = new JTable(new ScheduleTableModel());
    private List<ScheduleEntryView> entries = List.of();
    private int startWeek = 1;
    private int endWeek = 30;
    private int dragStartRow = -1;
    private int dragColumn = -1;
    private boolean dragSelect;

    ScheduleGridPanel(boolean selectable) {
        this.selectable = selectable;
        setLayout(new BorderLayout());
        setBackground(Theme.BACKGROUND);
        table.setRowHeight(selectable ? 52 : 68);
        table.setBackground(Theme.BACKGROUND);
        table.setForeground(Theme.TEXT);
        table.setGridColor(Theme.BORDER);
        table.setShowGrid(true);
        table.setCellSelectionEnabled(false);
        table.setRowSelectionAllowed(false);
        table.setColumnSelectionAllowed(false);
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setDefaultRenderer(Object.class, new ScheduleRenderer());
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setBackground(Theme.HEADER);
        table.getTableHeader().setForeground(Theme.TEXT);
        table.getTableHeader().setFont(table.getFont().deriveFont(Font.BOLD));
        table.getTableHeader().setPreferredSize(new Dimension(0, 42));
        configureColumns();
        if (selectable) {
            installSelectionHandler();
        }
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scroll.getViewport().setBackground(Theme.BACKGROUND);
        add(scroll, BorderLayout.CENTER);
    }

    void setEntries(List<ScheduleEntryView> values) {
        entries = values == null ? List.of() : List.copyOf(values);
        removeBlockedSelections();
        table.repaint();
    }

    void setWeekRange(int fromWeek, int toWeek) {
        startWeek = Math.max(1, Math.min(fromWeek, 30));
        endWeek = Math.max(startWeek, Math.min(toWeek, 30));
        removeBlockedSelections();
        table.repaint();
    }

    List<ScheduleSlot> selectedSlots(int fromWeek, int toWeek, String classroom) {
        List<ScheduleSlot> slots = new ArrayList<>();
        for (int day = 0; day < DAY_COUNT; day++) {
            int period = 0;
            while (period < PERIOD_COUNT) {
                if (!selected[period][day]) {
                    period++;
                    continue;
                }
                int first = period;
                while (period + 1 < PERIOD_COUNT && selected[period + 1][day]) {
                    period++;
                }
                slots.add(new ScheduleSlot(day + 1, first + 1, period + 1, fromWeek, toWeek, classroom));
                period++;
            }
        }
        return List.copyOf(slots);
    }

    private void configureColumns() {
        TableColumn period = table.getColumnModel().getColumn(0);
        period.setPreferredWidth(92);
        period.setMinWidth(92);
        period.setMaxWidth(92);
        for (int column = 1; column <= DAY_COUNT; column++) {
            TableColumn day = table.getColumnModel().getColumn(column);
            day.setPreferredWidth(selectable ? 132 : 155);
            day.setMinWidth(selectable ? 112 : 130);
        }
    }

    private void installSelectionHandler() {
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                int row = table.rowAtPoint(event.getPoint());
                int column = table.columnAtPoint(event.getPoint());
                if (row < 0 || column < 1) {
                    return;
                }
                int day = column - 1;
                if (isBlocked(row, day)) {
                    Toolkit.getDefaultToolkit().beep();
                    return;
                }
                dragStartRow = row;
                dragColumn = column;
                dragSelect = !selected[row][day];
                selected[row][day] = dragSelect;
                table.repaint();
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (dragStartRow < 0) {
                    return;
                }
                int row = table.rowAtPoint(event.getPoint());
                int column = table.columnAtPoint(event.getPoint());
                if (row < 0 || column != dragColumn) {
                    return;
                }
                int day = column - 1;
                int from = Math.min(dragStartRow, row);
                int to = Math.max(dragStartRow, row);
                for (int current = from; current <= to; current++) {
                    if (!isBlocked(current, day)) {
                        selected[current][day] = dragSelect;
                    }
                }
                table.repaint();
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                dragStartRow = -1;
                dragColumn = -1;
            }
        };
        table.addMouseListener(mouse);
        table.addMouseMotionListener(mouse);
    }

    private void removeBlockedSelections() {
        for (int row = 0; row < PERIOD_COUNT; row++) {
            for (int day = 0; day < DAY_COUNT; day++) {
                if (isBlocked(row, day)) {
                    selected[row][day] = false;
                }
            }
        }
    }

    private boolean isBlocked(int row, int day) {
        int period = row + 1;
        int dayOfWeek = day + 1;
        return entries.stream().anyMatch(entry -> entry.dayOfWeek() == dayOfWeek
                && entry.startPeriod() <= period && entry.endPeriod() >= period
                && entry.overlapsWeeks(startWeek, endWeek));
    }

    private List<ScheduleEntryView> cellEntries(int row, int day) {
        int period = row + 1;
        int dayOfWeek = day + 1;
        return entries.stream().filter(entry -> entry.dayOfWeek() == dayOfWeek
                && entry.startPeriod() <= period && entry.endPeriod() >= period).toList();
    }

    private String cellText(List<ScheduleEntryView> cellEntries, boolean blocked) {
        if (cellEntries.isEmpty()) {
            return "";
        }
        Set<String> rendered = new LinkedHashSet<>();
        StringBuilder html = new StringBuilder("<html>");
        for (ScheduleEntryView entry : cellEntries) {
            String key = entry.sectionId() + ":" + entry.startWeek() + ":" + entry.endWeek();
            if (!rendered.add(key)) {
                continue;
            }
            if (html.length() > 6) {
                html.append("<br>");
            }
            if (selectable && blocked && entry.overlapsWeeks(startWeek, endWeek)) {
                html.append("<b>已占用 · ");
            } else {
                html.append("<b>");
            }
            html.append(escape(entry.courseName())).append("</b><br>")
                    .append(escape(entry.sectionCode())).append(" · ")
                    .append(entry.startWeek()).append('-').append(entry.endWeek()).append("周<br>")
                    .append(escape(entry.classroom()));
            if (!selectable) {
                html.append(" · ").append(escape(entry.teacherName()));
            }
        }
        return html.append("</html>").toString();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private final class ScheduleRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable currentTable, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(
                    currentTable, value, false, false, row, column);
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(5, 7, 5, 7));
            label.setForeground(Theme.TEXT);
            if (column == 0) {
                label.setText("第 " + (row + 1) + " 节");
                label.setHorizontalAlignment(SwingConstants.CENTER);
                label.setVerticalAlignment(SwingConstants.CENTER);
                label.setFont(label.getFont().deriveFont(Font.BOLD));
                label.setBackground(Theme.HEADER);
                return label;
            }
            int day = column - 1;
            List<ScheduleEntryView> cellEntries = cellEntries(row, day);
            boolean blocked = selectable && isBlocked(row, day);
            label.setHorizontalAlignment(SwingConstants.LEFT);
            label.setVerticalAlignment(SwingConstants.TOP);
            label.setFont(label.getFont().deriveFont(Font.PLAIN, selectable ? 12f : 11.5f));
            if (selectable && selected[row][day]) {
                label.setText("<html><b>已选择</b><br>可拖动连续选择</html>");
                label.setBackground(Theme.SECONDARY);
                label.setForeground(Theme.ACCENT);
            } else if (blocked) {
                label.setText(cellText(cellEntries, true));
                label.setBackground(BLOCKED);
                label.setForeground(Theme.DANGER);
            } else if (!cellEntries.isEmpty()) {
                label.setText(cellText(cellEntries, false));
                ScheduleEntryView first = cellEntries.get(0);
                label.setBackground(selectable
                        ? OTHER_WEEKS
                        : COURSE_COLORS[Math.floorMod(Long.hashCode(first.sectionId()), COURSE_COLORS.length)]);
            } else {
                label.setText(selectable ? "点击或纵向拖动选择" : "");
                label.setBackground(Theme.BACKGROUND);
                label.setForeground(selectable ? Theme.MUTED : Theme.TEXT);
            }
            return label;
        }
    }

    private static final class ScheduleTableModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return PERIOD_COUNT;
        }

        @Override
        public int getColumnCount() {
            return DAYS.length;
        }

        @Override
        public String getColumnName(int column) {
            return DAYS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return "";
        }
    }
}
