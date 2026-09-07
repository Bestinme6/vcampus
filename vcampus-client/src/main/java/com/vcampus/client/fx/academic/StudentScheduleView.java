package com.vcampus.client.fx.academic;

import com.vcampus.common.model.ScheduleSlot;
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

import java.util.List;
import java.util.Objects;

/** Read-only week-filtered student schedule built only from published server entries. */
final class StudentScheduleView extends BorderPane {
    interface Listener { void loadSchedule(long termId); }

    private final Listener listener;
    private final ComboBox<AcademicData.Term> term = new ComboBox<>();
    private final Spinner<Integer> week = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 30, 1));
    private final ScheduleGrid grid = new ScheduleGrid(slot -> { });
    private final TableView<AcademicData.ScheduleEntry> table = new TableView<>();
    private final Label notice = label("", "academic-notice");
    private List<AcademicData.ScheduleEntry> entries = List.of();
    private boolean updatingTerms;

    StudentScheduleView(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        setPadding(new Insets(24));
        term.setConverter(converter(AcademicData.Term::name));
        term.setOnAction(event -> {
            if (!updatingTerms && term.getValue() != null) listener.loadSchedule(term.getValue().id());
        });
        Button refresh = button("刷新", "academic-secondary", () -> {
            if (term.getValue() != null) listener.loadSchedule(term.getValue().id());
        });
        HBox tools = new HBox(8, term, new Label("教学周"), week, refresh);
        tools.setAlignment(Pos.CENTER_LEFT);
        notice.setManaged(false);
        notice.setVisible(false);
        setTop(new VBox(8, label("我的课表", "academic-page-title"),
                label("按教学周筛选；只显示已经发布的教学班课表。", "academic-muted"), tools, notice));
        grid.setReadOnly(true);
        addColumn("课程", 160, row -> row.courseCode() + " · " + row.courseName());
        addColumn("教学班", 85, AcademicData.ScheduleEntry::sectionCode);
        addColumn("教师", 90, AcademicData.ScheduleEntry::teacherName);
        addColumn("学分", 55, row -> row.credits().stripTrailingZeros().toPlainString());
        addColumn("时间", 100, row -> "周" + row.slot().dayOfWeek() + " 第 "
                + row.slot().startPeriod() + "—" + row.slot().endPeriod() + " 节");
        addColumn("教室", 100, row -> row.slot().classroom());
        table.setPrefWidth(430);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        ScrollPane scheduleScroll = scheduleScroll(grid);
        HBox body = new HBox(16, scheduleScroll, table);
        HBox.setHgrow(scheduleScroll, Priority.ALWAYS);
        setCenter(body);
        week.valueProperty().addListener((observable, old, value) -> renderWeek());
    }

    static List<AcademicData.ScheduleEntry> forWeek(List<AcademicData.ScheduleEntry> entries, int week) {
        if (week < 1 || week > 30) throw new IllegalArgumentException("教学周无效");
        return List.copyOf(entries.stream().filter(row -> row.slot().startWeek() <= week
                && row.slot().endWeek() >= week).toList());
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

    void showEntries(List<AcademicData.ScheduleEntry> entries) {
        this.entries = List.copyOf(entries);
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
        List<AcademicData.ScheduleEntry> visible = forWeek(entries, week.getValue());
        table.setItems(FXCollections.observableArrayList(visible));
        grid.setCourseEntries(visible);
    }

    private void addColumn(String title, double width,
                           java.util.function.Function<AcademicData.ScheduleEntry, String> value) {
        TableColumn<AcademicData.ScheduleEntry, String> column = new TableColumn<>(title);
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

    static ScrollPane scheduleScroll(ScheduleGrid grid) {
        ScrollPane scroll = new ScrollPane(grid);
        scroll.getStyleClass().add("academic-schedule-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        return scroll;
    }
}
