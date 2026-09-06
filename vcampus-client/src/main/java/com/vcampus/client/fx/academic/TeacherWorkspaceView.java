package com.vcampus.client.fx.academic;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Teacher schedule, today's agenda and owned teaching-section list. */
final class TeacherWorkspaceView extends BorderPane {
    interface Listener {
        void loadTeacherWorkspace(long termId);
        void openGradebook(AcademicData.TeachingSection section);
    }

    private final Listener listener;
    private final ComboBox<AcademicData.Term> term = new ComboBox<>();
    private final Spinner<Integer> week = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 30, 1));
    private final ScheduleGrid grid = new ScheduleGrid(slot -> { });
    private final TableView<AcademicData.ScheduleEntry> today = new TableView<>();
    private final TableView<AcademicData.TeachingSection> sections = new TableView<>();
    private final Label notice = label("", "academic-notice");
    private final Button gradebook;
    private List<AcademicData.ScheduleEntry> entries = List.of();
    private boolean updatingTerms;

    TeacherWorkspaceView(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        setPadding(new Insets(24));
        term.setConverter(converter(AcademicData.Term::name));
        term.setOnAction(event -> {
            if (!updatingTerms && term.getValue() != null) listener.loadTeacherWorkspace(term.getValue().id());
        });
        Button refresh = button("刷新", "academic-secondary", () -> {
            if (term.getValue() != null) listener.loadTeacherWorkspace(term.getValue().id());
        });
        HBox tools = new HBox(8, term, new Label("教学周"), week, refresh);
        tools.setAlignment(Pos.CENTER_LEFT);
        notice.setManaged(false);
        notice.setVisible(false);
        setTop(new VBox(8, label("教师工作台", "academic-page-title"),
                label("查看已发布课表、任课班级和学生成绩。", "academic-muted"), tools, notice));

        grid.setMouseTransparent(true);
        addScheduleColumn("课程", 160, row -> row.courseCode() + " · " + row.courseName());
        addScheduleColumn("节次", 75, row -> row.slot().startPeriod() + "—" + row.slot().endPeriod());
        addScheduleColumn("教室", 95, row -> row.slot().classroom());
        today.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox schedulePane = new VBox(10, grid, label("今日课程", "academic-state-title"), today);
        VBox.setVgrow(grid, Priority.ALWAYS);

        addSectionColumn("教学班", 95, AcademicData.TeachingSection::sectionCode);
        addSectionColumn("课程", 190, row -> row.courseCode() + " · " + row.courseName());
        addSectionColumn("人数", 70, row -> row.enrolledCount() + "/" + row.capacity());
        addSectionColumn("时间", 170, AcademicData.TeachingSection::scheduleSummary);
        addSectionColumn("成绩", 80, row -> row.gradesPublished() ? "已发布" : "待维护");
        sections.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        gradebook = button("打开成绩册", "academic-primary", () -> {
            AcademicData.TeachingSection selected = sections.getSelectionModel().getSelectedItem();
            if (selected != null) listener.openGradebook(selected);
        });
        gradebook.setDisable(true);
        sections.getSelectionModel().selectedItemProperty().addListener((observable, old, selected) ->
                gradebook.setDisable(selected == null));
        VBox sectionPane = new VBox(10, sections, gradebook);
        VBox.setVgrow(sections, Priority.ALWAYS);

        Tab scheduleTab = new Tab("教师课表", schedulePane);
        Tab sectionTab = new Tab("任课班级与成绩", sectionPane);
        scheduleTab.setClosable(false);
        sectionTab.setClosable(false);
        setCenter(new TabPane(scheduleTab, sectionTab));
        week.valueProperty().addListener((observable, old, value) -> renderWeek());
    }

    static List<AcademicData.ScheduleEntry> forDay(List<AcademicData.ScheduleEntry> entries,
                                                    int dayOfWeek, int week) {
        return List.copyOf(entries.stream().filter(row -> row.slot().dayOfWeek() == dayOfWeek
                        && row.slot().startWeek() <= week && row.slot().endWeek() >= week)
                .sorted(Comparator.comparingInt(row -> row.slot().startPeriod())).toList());
    }

    void showTerms(List<AcademicData.Term> terms, long selectedTermId) {
        updatingTerms = true;
        try {
            term.getItems().setAll(terms);
            terms.stream().filter(item -> item.id() == selectedTermId).findFirst().ifPresent(term::setValue);
            if (term.getValue() == null && !terms.isEmpty()) term.getSelectionModel().selectFirst();
        } finally {
            updatingTerms = false;
        }
    }

    void showData(List<AcademicData.ScheduleEntry> entries,
                  List<AcademicData.TeachingSection> sections) {
        this.entries = List.copyOf(entries);
        this.sections.setItems(FXCollections.observableArrayList(sections));
        renderWeek();
    }

    void message(String text, boolean error) {
        notice.setText(Objects.requireNonNullElse(text, ""));
        notice.setManaged(!notice.getText().isBlank());
        notice.setVisible(!notice.getText().isBlank());
        notice.getStyleClass().remove("academic-form-error");
        if (error) notice.getStyleClass().add("academic-form-error");
    }

    void busy(boolean value) { setDisable(value); }

    private void renderWeek() {
        int selectedWeek = week.getValue();
        List<AcademicData.ScheduleEntry> visible = StudentScheduleView.forWeek(entries, selectedWeek);
        grid.setSlots(visible.stream().map(AcademicData.ScheduleEntry::slot).toList());
        int day = java.time.LocalDate.now().getDayOfWeek().getValue();
        today.setItems(FXCollections.observableArrayList(forDay(entries, day, selectedWeek)));
    }

    private void addScheduleColumn(String title, double width,
                                   java.util.function.Function<AcademicData.ScheduleEntry, String> value) {
        TableColumn<AcademicData.ScheduleEntry, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        today.getColumns().add(column);
    }

    private void addSectionColumn(String title, double width,
                                  java.util.function.Function<AcademicData.TeachingSection, String> value) {
        TableColumn<AcademicData.TeachingSection, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        sections.getColumns().add(column);
    }

    private static <T> StringConverter<T> converter(java.util.function.Function<T, String> label) {
        return new StringConverter<>() {
            @Override public String toString(T value) { return value == null ? "" : label.apply(value); }
            @Override public T fromString(String string) { throw new UnsupportedOperationException(); }
        };
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }

    private static Button button(String text, String style, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add(style);
        button.setOnAction(event -> action.run());
        return button;
    }
}
