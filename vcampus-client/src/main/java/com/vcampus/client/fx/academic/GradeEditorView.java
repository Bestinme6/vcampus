package com.vcampus.client.fx.academic;

import com.vcampus.common.model.EnrollmentStatus;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.Objects;

/** Section roster and reasoned grade editing with publication lock. */
final class GradeEditorView extends BorderPane {
    interface Listener {
        void saveGrade(long sectionId, long enrollmentId, AcademicCommands.GradeDraft draft);
        void publishGrades(long sectionId);
        void reloadRoster(long sectionId);
        void backToTeacher();
    }

    private final Listener listener;
    private final Label title = label("成绩册", "academic-page-title");
    private final Label meta = label("", "academic-muted");
    private final Label notice = label("", "academic-notice");
    private final TableView<AcademicData.RosterRow> table = new TableView<>();
    private final TextField score = new TextField();
    private final TextArea comment = new TextArea();
    private final TextField reason = new TextField();
    private final Button save;
    private final Button publish;
    private AcademicData.TeachingSection section;
    private AcademicData.Roster roster = new AcademicData.Roster(java.util.List.of());

    GradeEditorView(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        setPadding(new Insets(24));
        Button back = button("返回教师工作台", "academic-secondary", listener::backToTeacher);
        HBox heading = new HBox(10, back, new VBox(3, title, meta));
        heading.setAlignment(Pos.CENTER_LEFT);
        notice.setManaged(false);
        notice.setVisible(false);
        setTop(new VBox(8, heading, notice));
        addColumn("学号", 110, AcademicData.RosterRow::studentNumber);
        addColumn("姓名", 100, AcademicData.RosterRow::fullName);
        addColumn("状态", 70, row -> row.status().displayName());
        addColumn("成绩", 70, row -> row.score() == null ? "—" : row.score().stripTrailingZeros().toPlainString());
        addColumn("绩点", 70, row -> row.gradePoint() == null ? "—" : row.gradePoint().stripTrailingZeros().toPlainString());
        addColumn("评语", 160, AcademicData.RosterRow::comment);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().selectedItemProperty().addListener((observable, old, row) -> select(row));

        score.setPromptText("0—100");
        comment.setPromptText("评语（可选）");
        comment.setPrefRowCount(3);
        reason.setPromptText("本次录入/修改原因（必填）");
        save = button("保存所选学生成绩", "academic-primary", this::save);
        publish = button("发布全班成绩", "academic-primary", this::publish);
        VBox editor = new VBox(8, label("成绩编辑", "academic-state-title"),
                new Label("成绩"), score, new Label("评语"), comment, new Label("操作原因"), reason,
                save, publish);
        editor.setPrefWidth(290);
        HBox body = new HBox(16, table, editor);
        HBox.setHgrow(table, Priority.ALWAYS);
        setCenter(body);
        updateActions();
    }

    void showSection(AcademicData.TeachingSection section) {
        this.section = Objects.requireNonNull(section, "section");
        title.setText(section.courseCode() + " · " + section.courseName() + " · " + section.sectionCode());
        meta.setText(section.termName() + " · " + section.enrolledCount() + " 名学生 · "
                + (section.gradesPublished() ? "成绩已发布" : "成绩未发布"));
    }

    void showRoster(AcademicData.Roster roster) {
        this.roster = Objects.requireNonNull(roster, "roster");
        table.setItems(FXCollections.observableArrayList(roster.rows()));
        table.getSelectionModel().clearSelection();
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

    private void select(AcademicData.RosterRow row) {
        score.setText(row == null || row.score() == null ? "" : row.score().toPlainString());
        comment.setText(row == null ? "" : row.comment());
        reason.clear();
        updateActions();
    }

    private void save() {
        AcademicData.RosterRow row = table.getSelectionModel().getSelectedItem();
        if (section == null || row == null) return;
        try {
            AcademicCommands.GradeDraft draft = new AcademicCommands.GradeDraft(
                    new BigDecimal(score.getText().trim()), comment.getText(), reason.getText());
            listener.saveGrade(section.id(), row.enrollmentId(), draft);
        } catch (RuntimeException exception) {
            message(exception.getMessage() == null ? "请填写有效成绩与操作原因" : exception.getMessage(), true);
        }
    }

    private void publish() {
        if (section == null) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "确认发布 “" + section.courseName() + " · " + section.sectionCode() + "” 的 "
                        + section.enrolledCount() + " 名学生成绩？发布后不可直接修改。",
                ButtonType.OK, ButtonType.CANCEL);
        if (alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            listener.publishGrades(section.id());
        }
    }

    private void updateActions() {
        GradeEditorPolicy.State state = GradeEditorPolicy.forSection(
                section != null && section.gradesPublished(), roster);
        AcademicData.RosterRow selected = table.getSelectionModel().getSelectedItem();
        save.setDisable(!state.editable() || selected == null || selected.status() != EnrollmentStatus.ENROLLED);
        publish.setDisable(!state.canPublish());
        score.setDisable(!state.editable());
        comment.setDisable(!state.editable());
        reason.setDisable(!state.editable());
    }

    private void addColumn(String title, double width,
                           java.util.function.Function<AcademicData.RosterRow, String> value) {
        TableColumn<AcademicData.RosterRow, String> column = new TableColumn<>(title);
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
