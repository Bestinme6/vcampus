package com.vcampus.client.fx.academic;

import com.vcampus.common.model.CourseSectionStatus;
import com.vcampus.common.model.ScheduleSlot;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Teaching-section creation dialog with an initial draft schedule and target cohorts. */
final class SectionForm extends Dialog<SectionForm.Submission> {
    record Submission(AcademicCommands.SectionDraft section,
                      List<AcademicData.SectionTarget> targets) {
        Submission {
            section = Objects.requireNonNull(section, "section");
            targets = List.copyOf(targets);
        }
    }

    private final ComboBox<AcademicData.Term> term = new ComboBox<>();
    private final ComboBox<AcademicData.CourseReference> course = new ComboBox<>();
    private final ComboBox<AcademicData.TeacherReference> teacher = new ComboBox<>();
    private final ComboBox<CourseSectionStatus> status = new ComboBox<>();
    private final TextField code = new TextField();
    private final Spinner<Integer> capacity = spinner(1, 500, 40);
    private final Spinner<Integer> startWeek = spinner(1, 30, 1);
    private final Spinner<Integer> endWeek = spinner(1, 30, 16);
    private final TextField classroom = new TextField();
    private final ListView<ScheduleSlot> slots = new ListView<>();
    private final ComboBox<AcademicData.MajorReference> targetMajor = new ComboBox<>();
    private final Spinner<Integer> targetFrom = spinner(2000, 2100, java.time.Year.now().getValue());
    private final Spinner<Integer> targetTo = spinner(2000, 2100, java.time.Year.now().getValue() + 3);
    private final ListView<AcademicData.SectionTarget> targets = new ListView<>();
    private final Label error = new Label();
    private final ScheduleGrid scheduleGrid = new ScheduleGrid(this::addSlot);

    SectionForm(AcademicData.ReferenceData references, AcademicData.Term selectedTerm) {
        Objects.requireNonNull(references, "references");
        setTitle("新增教学班");
        setHeaderText("配置教学班、招生范围和初始课表草稿");
        getDialogPane().getButtonTypes().addAll(
                new ButtonType("创建并进入排课", ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL);
        getDialogPane().setPrefSize(1080, 780);

        term.getItems().setAll(references.terms());
        course.getItems().setAll(references.courses());
        teacher.getItems().setAll(references.teachers());
        status.getItems().setAll(CourseSectionStatus.values());
        status.setValue(CourseSectionStatus.PLANNED);
        term.setConverter(converter(AcademicData.Term::name));
        course.setConverter(converter(item -> item.code() + " · " + item.name()));
        teacher.setConverter(converter(item -> item.username() + " · " + item.displayName()));
        targetMajor.setConverter(converter(item -> item.code() + " · " + item.name()));
        targetMajor.getItems().setAll(references.majors());
        selectTerm(selectedTerm);
        if (!course.getItems().isEmpty()) course.getSelectionModel().selectFirst();
        if (!teacher.getItems().isEmpty()) teacher.getSelectionModel().selectFirst();
        if (!targetMajor.getItems().isEmpty()) targetMajor.getSelectionModel().selectFirst();
        code.setPromptText("例如 CS-2026-01");
        classroom.setPromptText("例如 教一-101");

        slots.setPrefHeight(112);
        slots.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(ScheduleSlot slot, boolean empty) {
                super.updateItem(slot, empty);
                setText(empty || slot == null ? null : slotText(slot));
            }
        });
        targets.setPrefHeight(100);
        targets.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(AcademicData.SectionTarget item, boolean empty) {
                super.updateItem(item, empty);
                AcademicData.MajorReference major = empty || item == null ? null : references.majors().stream()
                        .filter(candidate -> candidate.id() == item.majorId()).findFirst().orElse(null);
                setText(item == null || empty ? null : (major == null ? "专业 #" + item.majorId() : major.name())
                        + " · " + item.yearFrom() + "—" + item.yearTo() + " 级");
            }
        });

        GridPane basics = new GridPane();
        basics.setHgap(10);
        basics.setVgap(8);
        addField(basics, 0, "学期", term);
        addField(basics, 1, "课程", course);
        addField(basics, 2, "教学班编号", code);
        addField(basics, 3, "授课教师", teacher);
        addField(basics, 4, "容量", capacity);
        addField(basics, 5, "初始状态", status);

        Button removeSlot = new Button("移除所选时段");
        removeSlot.getStyleClass().add("academic-secondary");
        removeSlot.setOnAction(event -> {
            slots.getItems().remove(slots.getSelectionModel().getSelectedItem());
            scheduleGrid.setSlots(slots.getItems());
        });
        HBox scheduleFields = new HBox(8, new Label("开始周"), startWeek, new Label("结束周"), endWeek,
                new Label("教室"), classroom, removeSlot);
        scheduleFields.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(classroom, Priority.ALWAYS);

        Button addTarget = new Button("添加范围");
        addTarget.getStyleClass().add("academic-secondary");
        addTarget.setOnAction(event -> addTarget());
        Button removeTarget = new Button("移除所选范围");
        removeTarget.getStyleClass().add("academic-secondary");
        removeTarget.setOnAction(event -> targets.getItems().remove(targets.getSelectionModel().getSelectedItem()));
        HBox targetFields = new HBox(8, targetMajor, new Label("入学年份"), targetFrom,
                new Label("至"), targetTo, addTarget, removeTarget);
        targetFields.setAlignment(Pos.CENTER_LEFT);

        error.getStyleClass().add("academic-form-error");
        VBox content = new VBox(10, basics, scheduleFields,
                new Label("拖动同一天的连续格子添加时段；也可用 Shift+方向键后按 Enter。"),
                scheduleGrid, slots, new Label("招生范围"), targetFields, targets, error);
        content.setPadding(new Insets(8));
        VBox.setVgrow(scheduleGrid, Priority.ALWAYS);
        getDialogPane().setContent(content);

        startWeek.valueProperty().addListener((observable, old, value) -> configureGrid());
        endWeek.valueProperty().addListener((observable, old, value) -> configureGrid());
        classroom.textProperty().addListener((observable, old, value) -> configureGrid());
        configureGrid();

        Button save = (Button) getDialogPane().lookupButton(getDialogPane().getButtonTypes().getFirst());
        save.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                Submission submission = submission();
                event.consume();
                setResult(submission);
                close();
            } catch (IllegalArgumentException exception) {
                event.consume();
                error.setText(exception.getMessage());
            }
        });
    }

    private Submission submission() {
        AcademicData.Term selectedTerm = required(term.getValue(), "请选择学期");
        AcademicData.CourseReference selectedCourse = required(course.getValue(), "请选择课程");
        AcademicData.TeacherReference selectedTeacher = required(teacher.getValue(), "请选择授课教师");
        if (slots.getItems().isEmpty()) throw new IllegalArgumentException("请至少添加一个上课时段");
        if (targets.getItems().isEmpty()) throw new IllegalArgumentException("请至少添加一个招生专业与年级范围");
        AcademicCommands.SectionDraft draft = new AcademicCommands.SectionDraft(
                selectedTerm.id(), selectedCourse.id(), code.getText(), selectedTeacher.userId(),
                capacity.getValue(), status.getValue(), List.copyOf(slots.getItems()), false);
        return new Submission(draft, List.copyOf(targets.getItems()));
    }

    private void addSlot(ScheduleSlot slot) {
        if (slots.getItems().stream().anyMatch(existing -> existing.overlaps(slot))) {
            error.setText("同一教学班的上课时段不能重叠");
            return;
        }
        slots.getItems().add(slot);
        scheduleGrid.setSlots(slots.getItems());
        error.setText("");
    }

    private void addTarget() {
        AcademicData.MajorReference selected = targetMajor.getValue();
        if (selected == null) {
            error.setText("请选择招生专业");
            return;
        }
        try {
            AcademicData.SectionTarget target = new AcademicData.SectionTarget(
                    selected.id(), targetFrom.getValue(), targetTo.getValue());
            if (targets.getItems().contains(target)) throw new IllegalArgumentException("该招生范围已添加");
            targets.getItems().add(target);
            error.setText("");
        } catch (IllegalArgumentException exception) {
            error.setText(exception.getMessage());
        }
    }

    private void configureGrid() {
        int from = startWeek.getValue();
        int to = Math.max(from, endWeek.getValue());
        if (endWeek.getValue() < from) endWeek.getValueFactory().setValue(from);
        scheduleGrid.configure(from, to, classroom.getText());
    }

    private void selectTerm(AcademicData.Term selected) {
        if (selected == null && !term.getItems().isEmpty()) term.getSelectionModel().selectFirst();
        else if (selected != null) term.getItems().stream().filter(item -> item.id() == selected.id())
                .findFirst().ifPresent(term::setValue);
    }

    private static String slotText(ScheduleSlot slot) {
        return "周" + slot.dayOfWeek() + " 第 " + slot.startPeriod() + "—" + slot.endPeriod()
                + " 节 · " + slot.startWeek() + "—" + slot.endWeek() + " 周 · " + slot.classroom();
    }

    private static <T> T required(T value, String message) {
        if (value == null) throw new IllegalArgumentException(message);
        return value;
    }

    private static <T> StringConverter<T> converter(java.util.function.Function<T, String> label) {
        return new StringConverter<>() {
            @Override public String toString(T value) { return value == null ? "" : label.apply(value); }
            @Override public T fromString(String string) { throw new UnsupportedOperationException(); }
        };
    }

    private static Spinner<Integer> spinner(int min, int max, int value) {
        Spinner<Integer> spinner = new Spinner<>();
        spinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max,
                Math.max(min, Math.min(max, value))));
        spinner.setEditable(true);
        return spinner;
    }

    private static void addField(GridPane grid, int column, String name, javafx.scene.Node field) {
        VBox box = new VBox(4, new Label(name), field);
        grid.add(box, column, 0);
    }
}
