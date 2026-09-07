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
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
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
    private final Label weekLabel = new Label("教学周");
    private final ScheduleGrid grid = new ScheduleGrid(slot -> { });
    private final TableView<AcademicData.ScheduleEntry> today = new TableView<>();
    private final TableView<AcademicData.TeachingSection> sections = new TableView<>();
    private final TableView<AcademicData.TeachingSection> gradeSections = new TableView<>();
    private final Label pageTitle = label("教师课表", "academic-page-title");
    private final Label pageSubtitle = label("查看已发布的周课表和今日课程。", "academic-muted");
    private final Label notice = label("", "academic-notice");
    private final Button gradebook;
    private final VBox schedulePage;
    private final VBox sectionsPage;
    private final VBox gradebookPage;
    private List<AcademicData.ScheduleEntry> entries = List.of();
    private boolean updatingTerms;

    TeacherWorkspaceView(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        setPadding(new Insets(24));
        term.setConverter(converter(AcademicData.Term::name));
        weekLabel.setId("academic-week-label");
        week.setId("academic-week");
        term.setOnAction(event -> {
            if (!updatingTerms && term.getValue() != null) listener.loadTeacherWorkspace(term.getValue().id());
        });
        Button refresh = button("刷新", "academic-secondary", () -> {
            if (term.getValue() != null) listener.loadTeacherWorkspace(term.getValue().id());
        });
        HBox tools = new HBox(8, term, weekLabel, week, refresh);
        tools.setAlignment(Pos.CENTER_LEFT);
        notice.setManaged(false);
        notice.setVisible(false);
        setTop(new VBox(8, pageTitle, pageSubtitle, tools, notice));

        grid.setReadOnly(true);
        addScheduleColumn("课程", 160, row -> row.courseCode() + " · " + row.courseName());
        addScheduleColumn("教学班", 75, AcademicData.ScheduleEntry::sectionCode);
        addScheduleColumn("节次", 75, row -> row.slot().startPeriod() + "—" + row.slot().endPeriod());
        addScheduleColumn("教室", 95, row -> row.slot().classroom());
        today.setId("academic-teacher-today");
        today.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        ScrollPane scheduleScroll = StudentScheduleView.scheduleScroll(grid);
        schedulePage = new VBox(10, scheduleScroll, label("今日课程", "academic-state-title"), today);
        schedulePage.setId("academic-teacher-schedule-page");
        VBox.setVgrow(scheduleScroll, Priority.ALWAYS);

        configureSectionTable(sections);
        sections.setId("academic-teaching-sections");
        sectionsPage = new VBox(10, sections);
        sectionsPage.setId("academic-teaching-sections-page");
        VBox.setVgrow(sections, Priority.ALWAYS);

        configureSectionTable(gradeSections);
        gradeSections.setId("academic-gradebook-sections");
        gradebook = button("打开成绩册", "academic-primary", () -> {
            AcademicData.TeachingSection selected = gradeSections.getSelectionModel().getSelectedItem();
            if (selected != null) listener.openGradebook(selected);
        });
        gradebook.setId("academic-open-gradebook");
        gradebook.setDisable(true);
        gradeSections.getSelectionModel().selectedItemProperty().addListener((observable, old, selected) ->
                gradebook.setDisable(selected == null));
        gradebookPage = new VBox(10,
                label("请先选择一个任课班级，再打开成绩册。", "academic-note"),
                gradeSections, gradebook);
        gradebookPage.setId("academic-gradebook-page");
        VBox.setVgrow(gradeSections, Priority.ALWAYS);

        open("teacher-schedule");
        week.valueProperty().addListener((observable, old, value) -> renderWeek());
    }

    void open(String route) {
        String requestedRoute = Objects.requireNonNull(route, "route");
        boolean scheduleRoute = "teacher-schedule".equals(requestedRoute);
        weekLabel.setManaged(scheduleRoute);
        weekLabel.setVisible(scheduleRoute);
        week.setManaged(scheduleRoute);
        week.setVisible(scheduleRoute);
        switch (requestedRoute) {
            case "teacher-schedule" -> {
                pageTitle.setText("教师课表");
                pageSubtitle.setText("查看已发布的周课表和今日课程。");
                setCenter(schedulePage);
            }
            case "teaching-sections" -> {
                pageTitle.setText("任课班级");
                pageSubtitle.setText("查看当前学期由你负责的教学班、人数和上课安排。");
                setCenter(sectionsPage);
            }
            case "gradebook" -> {
                pageTitle.setText("成绩管理");
                pageSubtitle.setText("选择任课班级，录入、修改或发布学生成绩。");
                setCenter(gradebookPage);
            }
            default -> throw new IllegalArgumentException("未知教师教务页面：" + route);
        }
    }

    static List<AcademicData.ScheduleEntry> forDay(List<AcademicData.ScheduleEntry> entries,
                                                    int dayOfWeek, int week) {
        return List.copyOf(entries.stream().filter(row -> row.slot().dayOfWeek() == dayOfWeek
                        && row.slot().startWeek() <= week && row.slot().endWeek() >= week)
                .sorted(Comparator.comparingInt(row -> row.slot().startPeriod())).toList());
    }

    static int teachingWeek(AcademicData.Term term, LocalDate date) {
        Objects.requireNonNull(term, "term");
        Objects.requireNonNull(date, "date");
        if (term.startDate() == null || date.isBefore(term.startDate()) || date.isAfter(term.endDate())) {
            return 0;
        }
        LocalDate firstMonday = term.startDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return Math.toIntExact(ChronoUnit.WEEKS.between(firstMonday, date) + 1);
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
        showSchedule(entries);
        showSections(sections);
    }

    void showSchedule(List<AcademicData.ScheduleEntry> entries) {
        this.entries = List.copyOf(entries);
        renderWeek();
    }

    void showSections(List<AcademicData.TeachingSection> sections) {
        this.sections.setItems(FXCollections.observableArrayList(sections));
        this.gradeSections.setItems(FXCollections.observableArrayList(sections));
        this.gradeSections.getSelectionModel().clearSelection();
        gradebook.setDisable(true);
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
        grid.setCourseEntries(visible);
        LocalDate date = LocalDate.now();
        int currentWeek = term.getValue() == null ? 0 : teachingWeek(term.getValue(), date);
        List<AcademicData.ScheduleEntry> todayEntries = currentWeek == 0 ? List.of()
                : forDay(entries, date.getDayOfWeek().getValue(), currentWeek);
        today.setItems(FXCollections.observableArrayList(todayEntries));
    }

    private void addScheduleColumn(String title, double width,
                                   java.util.function.Function<AcademicData.ScheduleEntry, String> value) {
        TableColumn<AcademicData.ScheduleEntry, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        today.getColumns().add(column);
    }

    private void configureSectionTable(TableView<AcademicData.TeachingSection> table) {
        addSectionColumn(table, "教学班", 95, AcademicData.TeachingSection::sectionCode);
        addSectionColumn(table, "课程", 190, row -> row.courseCode() + " · " + row.courseName());
        addSectionColumn(table, "人数", 70, row -> row.enrolledCount() + "/" + row.capacity());
        addSectionColumn(table, "时间", 170, AcademicData.TeachingSection::scheduleSummary);
        addSectionColumn(table, "成绩", 80, row -> row.gradesPublished() ? "已发布" : "待维护");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private void addSectionColumn(TableView<AcademicData.TeachingSection> table,
                                  String title, double width,
                                  java.util.function.Function<AcademicData.TeachingSection, String> value) {
        TableColumn<AcademicData.TeachingSection, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        table.getColumns().add(column);
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
