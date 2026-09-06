package com.vcampus.client.fx.academic;

import com.vcampus.common.model.CourseSectionStatus;
import com.vcampus.common.model.EnrollmentStatus;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.List;
import java.util.Objects;

/** Student course groups with explicit teaching-section enrollment and atomic switching. */
final class EnrollmentCenterView extends BorderPane {
    interface Listener {
        void load(long termId);
        void enroll(long termId, long sectionId);
        void drop(long termId, long sectionId);
        void switchSection(long termId, long fromSectionId, long toSectionId);
    }

    record ActionState(boolean canEnroll, boolean canSwitch, boolean canDropCandidate) { }

    private final Listener listener;
    private final ComboBox<AcademicData.Term> term = new ComboBox<>();
    private final VBox courseGroups = new VBox(12);
    private final VBox preview = new VBox(10);
    private final Label notice = label("", "academic-notice");
    private final Button enroll;
    private final Button change;
    private final Button drop;
    private AcademicData.AvailableCourse selectedCourse;
    private AcademicData.AvailableSection selectedCandidate;
    private boolean updatingTerms;

    EnrollmentCenterView(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        getStyleClass().add("academic-enrollment-center");
        setPadding(new Insets(24));
        term.setConverter(converter(AcademicData.Term::name));
        term.setOnAction(event -> {
            if (!updatingTerms && term.getValue() != null) listener.load(term.getValue().id());
        });
        Button refresh = button("刷新", "academic-secondary", () -> {
            if (term.getValue() != null) listener.load(term.getValue().id());
        });
        HBox tools = new HBox(8, term, refresh);
        tools.setAlignment(Pos.CENTER_LEFT);
        notice.setManaged(false);
        notice.setVisible(false);
        setTop(new VBox(8, label("选课中心", "academic-page-title"),
                label("课程属性来自你的培养方案；请在每门课程下选择具体教学班。", "academic-muted"),
                tools, notice));

        ScrollPane scroll = new ScrollPane(courseGroups);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        preview.setPrefWidth(270);
        preview.getChildren().setAll(label("选择一个教学班查看教师、时间、教室和余量", "academic-muted"));
        enroll = button("选择此教学班", "academic-primary", this::enroll);
        change = button("换到此教学班", "academic-primary", this::change);
        drop = button("退选当前教学班", "academic-secondary", this::drop);
        VBox previewHost = new VBox(12, label("教学班比较", "academic-state-title"), preview,
                enroll, change, drop);
        HBox body = new HBox(16, scroll, previewHost);
        HBox.setHgrow(scroll, Priority.ALWAYS);
        setCenter(body);
        updateActions();
    }

    static ActionState actionsFor(AcademicData.AvailableSection currentlySelected,
                                  AcademicData.AvailableSection candidate) {
        if (candidate == null) return new ActionState(false, false, false);
        boolean own = candidate.ownEnrollmentStatus() == EnrollmentStatus.ENROLLED;
        boolean eligible = candidate.status() == CourseSectionStatus.OPEN
                && !candidate.full() && !candidate.scheduleConflict();
        boolean canEnroll = currentlySelected == null && !own && eligible;
        boolean canSwitch = currentlySelected != null && currentlySelected.sectionId() != candidate.sectionId()
                && !own && eligible;
        return new ActionState(canEnroll, canSwitch, own);
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

    void showCatalog(AcademicData.EnrollmentCatalog catalog) {
        selectedCourse = null;
        selectedCandidate = null;
        courseGroups.getChildren().clear();
        for (AcademicData.AvailableCourse course : catalog.courses()) {
            courseGroups.getChildren().add(coursePane(course));
        }
        if (catalog.courses().isEmpty()) {
            courseGroups.getChildren().add(label("当前学期没有与你培养方案匹配的可选课程", "academic-muted"));
        }
        preview.getChildren().setAll(label("选择一个教学班查看详情", "academic-muted"));
        updateActions();
    }

    void message(String text, boolean error) {
        notice.setText(Objects.requireNonNullElse(text, ""));
        notice.setManaged(!notice.getText().isBlank());
        notice.setVisible(!notice.getText().isBlank());
        notice.getStyleClass().remove("academic-form-error");
        if (error) notice.getStyleClass().add("academic-form-error");
    }

    void busy(boolean value) { setDisable(value); }

    private TitledPane coursePane(AcademicData.AvailableCourse course) {
        TableView<AcademicData.AvailableSection> table = new TableView<>();
        addColumn(table, "教学班", 85, AcademicData.AvailableSection::sectionCode);
        addColumn(table, "教师", 90, AcademicData.AvailableSection::teacherName);
        addColumn(table, "时间", 160, AcademicData.AvailableSection::scheduleSummary);
        addColumn(table, "教室", 100, AcademicData.AvailableSection::classroomSummary);
        addColumn(table, "人数", 70, row -> row.enrolledCount() + "/" + row.capacity());
        addColumn(table, "状态", 95, this::sectionState);
        table.setItems(javafx.collections.FXCollections.observableArrayList(course.sections()));
        table.setPrefHeight(Math.max(120, Math.min(260, 42 + course.sections().size() * 36)));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().selectedItemProperty().addListener((observable, old, candidate) -> {
            if (candidate != null) select(course, candidate);
        });
        Label hint = label(course.requirementType().displayName() + " · "
                + course.credits().stripTrailingZeros().toPlainString() + " 学分 · 建议第 "
                + course.recommendedTermNumber() + " 学期", "academic-note");
        VBox content = new VBox(7, hint, table);
        TitledPane pane = new TitledPane(course.courseCode() + " · " + course.courseName(), content);
        pane.setExpanded(course.sections().stream()
                .anyMatch(row -> row.ownEnrollmentStatus() == EnrollmentStatus.ENROLLED));
        return pane;
    }

    private void select(AcademicData.AvailableCourse course, AcademicData.AvailableSection candidate) {
        selectedCourse = course;
        selectedCandidate = candidate;
        int remaining = Math.max(0, candidate.capacity() - candidate.enrolledCount());
        preview.getChildren().setAll(
                label(course.courseName() + " · " + candidate.sectionCode(), "academic-state-title"),
                label("授课教师：" + candidate.teacherName(), "academic-muted"),
                label("上课时间：" + empty(candidate.scheduleSummary()), "academic-muted"),
                label("教室：" + empty(candidate.classroomSummary()), "academic-muted"),
                label("剩余 " + remaining + " 个名额", "academic-note"),
                candidate.scheduleConflict() ? label("与你已选课程时间冲突", "academic-form-error")
                        : label("时间无冲突", "academic-muted"));
        updateActions();
    }

    private void enroll() {
        if (selectedCandidate != null && confirm("选择教学班", "确认选择 “"
                + selectedCourse.courseName() + " · " + selectedCandidate.sectionCode() + "” ？")) {
            listener.enroll(term.getValue().id(), selectedCandidate.sectionId());
        }
    }

    private void change() {
        AcademicData.AvailableSection current = currentSection();
        if (current != null && selectedCandidate != null && confirm("更换教学班",
                "将从 “" + current.sectionCode() + "” 原子切换到 “" + selectedCandidate.sectionCode()
                        + "”。若新班不可用，原选课保持不变。")) {
            listener.switchSection(term.getValue().id(), current.sectionId(), selectedCandidate.sectionId());
        }
    }

    private void drop() {
        if (selectedCandidate != null && confirm("退选教学班", "确认退选 “"
                + selectedCourse.courseName() + " · " + selectedCandidate.sectionCode() + "” ？")) {
            listener.drop(term.getValue().id(), selectedCandidate.sectionId());
        }
    }

    private AcademicData.AvailableSection currentSection() {
        return selectedCourse == null ? null : selectedCourse.sections().stream()
                .filter(row -> row.ownEnrollmentStatus() == EnrollmentStatus.ENROLLED)
                .findFirst().orElse(null);
    }

    private void updateActions() {
        ActionState state = actionsFor(currentSection(), selectedCandidate);
        enroll.setDisable(!state.canEnroll());
        change.setDisable(!state.canSwitch());
        drop.setDisable(!state.canDropCandidate());
    }

    private String sectionState(AcademicData.AvailableSection section) {
        if (section.ownEnrollmentStatus() == EnrollmentStatus.ENROLLED) return "已选";
        if (section.scheduleConflict()) return "时间冲突";
        if (section.full()) return "已满";
        return section.status().displayName();
    }

    private static boolean confirm(String title, String text) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle(title);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private static String empty(String value) { return value == null || value.isBlank() ? "待发布" : value; }

    private static <T> StringConverter<T> converter(java.util.function.Function<T, String> label) {
        return new StringConverter<>() {
            @Override public String toString(T value) { return value == null ? "" : label.apply(value); }
            @Override public T fromString(String string) { throw new UnsupportedOperationException(); }
        };
    }

    private static void addColumn(TableView<AcademicData.AvailableSection> table, String title, double width,
                                  java.util.function.Function<AcademicData.AvailableSection, String> value) {
        TableColumn<AcademicData.AvailableSection, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        table.getColumns().add(column);
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        return label;
    }

    private static Button button(String text, String style, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add(style);
        button.setOnAction(event -> action.run());
        return button;
    }
}
