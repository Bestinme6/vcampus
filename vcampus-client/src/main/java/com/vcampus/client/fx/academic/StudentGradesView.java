package com.vcampus.client.fx.academic;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;

/** Read-only published grade table for students. */
final class StudentGradesView extends BorderPane {
    private final TableView<AcademicData.GradeRow> table = new TableView<>();
    private final Label notice = label("", "academic-notice");

    StudentGradesView() {
        setPadding(new Insets(24));
        notice.setManaged(false);
        notice.setVisible(false);
        setTop(new VBox(8, label("我的成绩", "academic-page-title"),
                label("仅显示已发布成绩。", "academic-muted"), notice));
        addColumn("学期", 120, AcademicData.GradeRow::termName);
        addColumn("课程", 200, row -> row.courseCode() + " · " + row.courseName());
        addColumn("学分", 70, row -> row.credits().stripTrailingZeros().toPlainString());
        addColumn("教师", 100, AcademicData.GradeRow::teacherName);
        addColumn("成绩", 80, row -> row.score().stripTrailingZeros().toPlainString());
        addColumn("绩点", 80, row -> row.gradePoint().stripTrailingZeros().toPlainString());
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(label("暂无已发布成绩", "academic-muted"));
        setCenter(table);
    }

    void showGrades(List<AcademicData.GradeRow> grades) {
        table.setItems(FXCollections.observableArrayList(grades));
    }

    void message(String text, boolean error) {
        notice.setText(Objects.requireNonNullElse(text, ""));
        notice.setManaged(!notice.getText().isBlank());
        notice.setVisible(!notice.getText().isBlank());
        notice.getStyleClass().remove("academic-form-error");
        if (error) notice.getStyleClass().add("academic-form-error");
    }

    void busy(boolean value) { setDisable(value); }

    private void addColumn(String title, double width,
                           java.util.function.Function<AcademicData.GradeRow, String> value) {
        TableColumn<AcademicData.GradeRow, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        table.getColumns().add(column);
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }
}
