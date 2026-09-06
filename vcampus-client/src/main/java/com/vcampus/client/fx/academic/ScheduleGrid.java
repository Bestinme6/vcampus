package com.vcampus.client.fx.academic;

import com.vcampus.common.model.ScheduleSlot;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Accessible 7 × 12 schedule grid supporting pointer and keyboard range selection. */
final class ScheduleGrid extends GridPane {
    private static final List<String> DAYS = List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日");

    record Cell(int dayOfWeek, int period) {
        Cell {
            if (dayOfWeek < 1 || dayOfWeek > 7 || period < 1 || period > 12) {
                throw new IllegalArgumentException("课表单元格无效");
            }
        }
    }

    record Selection(List<ScheduleSlot> slots, boolean hitBlockedCell) {
        Selection {
            slots = List.copyOf(slots);
        }
    }

    private final Map<Cell, Label> cells = new LinkedHashMap<>();
    private final Consumer<ScheduleSlot> slotConsumer;
    private List<ScheduleSlot> slots = List.of();
    private List<ScheduleSlot> blockedSlots = List.of();
    private Cell anchor;
    private Cell extent;
    private int startWeek = 1;
    private int endWeek = 16;
    private String classroom = "";

    ScheduleGrid(Consumer<ScheduleSlot> slotConsumer) {
        this.slotConsumer = Objects.requireNonNull(slotConsumer, "slotConsumer");
        getStyleClass().add("academic-schedule-grid");
        setHgap(3);
        setVgap(3);

        ColumnConstraints periods = new ColumnConstraints(48);
        getColumnConstraints().add(periods);
        for (int day = 1; day <= 7; day++) {
            ColumnConstraints column = new ColumnConstraints(64, 90, Double.MAX_VALUE);
            column.setHgrow(Priority.ALWAYS);
            getColumnConstraints().add(column);
            Label header = label(DAYS.get(day - 1), "academic-grid-header");
            add(header, day, 0);
        }
        for (int period = 1; period <= 12; period++) {
            RowConstraints row = new RowConstraints(34, 42, 48);
            row.setVgrow(Priority.ALWAYS);
            getRowConstraints().add(row);
            add(label("第 " + period + " 节", "academic-grid-period"), 0, period);
            for (int day = 1; day <= 7; day++) addCell(new Cell(day, period));
        }
    }

    static Selection select(Cell start, Cell end, Set<Cell> blocked,
                            int startWeek, int endWeek, String classroom) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        blocked = Set.copyOf(Objects.requireNonNull(blocked, "blocked"));
        int first = Math.min(start.period(), end.period());
        int last = Math.max(start.period(), end.period());
        boolean hit = false;
        if (blocked.contains(new Cell(start.dayOfWeek(), start.period()))) {
            return new Selection(List.of(), true);
        }
        int direction = end.period() >= start.period() ? 1 : -1;
        int reachable = start.period();
        for (int period = start.period(); period != end.period() + direction; period += direction) {
            if (blocked.contains(new Cell(start.dayOfWeek(), period))) {
                hit = true;
                break;
            }
            reachable = period;
        }
        first = Math.min(start.period(), reachable);
        last = Math.max(start.period(), reachable);
        return new Selection(List.of(new ScheduleSlot(start.dayOfWeek(), first, last,
                startWeek, endWeek, classroom)), hit);
    }

    void configure(int startWeek, int endWeek, String classroom) {
        if (startWeek < 1 || endWeek < startWeek || endWeek > 30) {
            throw new IllegalArgumentException("周次范围无效");
        }
        this.startWeek = startWeek;
        this.endWeek = endWeek;
        this.classroom = Objects.requireNonNullElse(classroom, "").trim();
        refresh();
    }

    void setSlots(List<ScheduleSlot> slots) {
        this.slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
        refresh();
    }

    void setBlockedSlots(List<ScheduleSlot> blockedSlots) {
        this.blockedSlots = List.copyOf(Objects.requireNonNull(blockedSlots, "blockedSlots"));
        refresh();
    }

    private void addCell(Cell cell) {
        Label node = label("", "academic-grid-cell");
        node.setFocusTraversable(true);
        node.setAccessibleRole(AccessibleRole.BUTTON);
        node.setAccessibleText(DAYS.get(cell.dayOfWeek() - 1) + "，第 " + cell.period() + " 节");
        node.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        node.setOnMousePressed(event -> {
            anchor = cell;
            extent = cell;
            node.startFullDrag();
            refresh();
        });
        node.setOnMouseDragEntered(event -> {
            if (anchor != null) {
                extent = new Cell(anchor.dayOfWeek(), cell.period());
                refresh();
            }
        });
        node.setOnMouseReleased(event -> commitSelection());
        node.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                if (anchor == null || anchor.dayOfWeek() != cell.dayOfWeek()) anchor = cell;
                if (extent == null) extent = cell;
                commitSelection();
                event.consume();
            } else if (event.isShiftDown() && (event.getCode() == KeyCode.UP || event.getCode() == KeyCode.DOWN)) {
                if (anchor == null || anchor.dayOfWeek() != cell.dayOfWeek()) anchor = cell;
                int delta = event.getCode() == KeyCode.UP ? -1 : 1;
                int period = Math.max(1, Math.min(12, (extent == null ? cell.period() : extent.period()) + delta));
                extent = new Cell(anchor.dayOfWeek(), period);
                refresh();
                event.consume();
            }
        });
        cells.put(cell, node);
        add(node, cell.dayOfWeek(), cell.period());
    }

    private void commitSelection() {
        if (anchor == null || extent == null) return;
        if (classroom.isBlank()) {
            anchor = null;
            extent = null;
            refresh();
            return;
        }
        Selection selection = select(anchor, extent, blockedCells(), startWeek, endWeek, classroom);
        selection.slots().forEach(slotConsumer);
        anchor = null;
        extent = null;
        refresh();
    }

    private Set<Cell> blockedCells() {
        java.util.LinkedHashSet<Cell> blocked = new java.util.LinkedHashSet<>();
        for (ScheduleSlot slot : blockedSlots) {
            if (slot.startWeek() <= endWeek && slot.endWeek() >= startWeek) {
                for (int period = slot.startPeriod(); period <= slot.endPeriod(); period++) {
                    blocked.add(new Cell(slot.dayOfWeek(), period));
                }
            }
        }
        return Set.copyOf(blocked);
    }

    private void refresh() {
        Set<Cell> blocked = blockedCells();
        Set<Cell> preview = previewCells(blocked);
        cells.forEach((cell, node) -> {
            node.getStyleClass().removeAll("academic-grid-selected", "academic-grid-blocked", "academic-grid-preview");
            if (blocked.contains(cell)) node.getStyleClass().add("academic-grid-blocked");
            if (contains(slots, cell)) node.getStyleClass().add("academic-grid-selected");
            if (preview.contains(cell)) node.getStyleClass().add("academic-grid-preview");
            node.setAccessibleHelp(blocked.contains(cell) ? "该时段已有安排，不可选择" : "可选择时段");
        });
    }

    private Set<Cell> previewCells(Set<Cell> blocked) {
        if (anchor == null || extent == null || classroom.isBlank()) return Set.of();
        Selection selection = select(anchor, extent, blocked, startWeek, endWeek, classroom);
        java.util.LinkedHashSet<Cell> result = new java.util.LinkedHashSet<>();
        selection.slots().forEach(slot -> {
            for (int period = slot.startPeriod(); period <= slot.endPeriod(); period++) {
                result.add(new Cell(slot.dayOfWeek(), period));
            }
        });
        return result;
    }

    private static boolean contains(List<ScheduleSlot> slots, Cell cell) {
        return slots.stream().anyMatch(slot -> slot.dayOfWeek() == cell.dayOfWeek()
                && slot.startPeriod() <= cell.period() && slot.endPeriod() >= cell.period());
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        label.setAlignment(Pos.CENTER);
        return label;
    }
}
