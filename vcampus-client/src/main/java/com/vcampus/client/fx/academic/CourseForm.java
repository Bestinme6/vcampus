package com.vcampus.client.fx.academic;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Course create/edit dialog whose values remain available after a server-side failure. */
final class CourseForm extends Dialog<Void> {
    interface Listener {
        void save(AcademicCommands.CourseDraft draft, boolean enabled);
    }

    private final AcademicData.Course existing;
    private final Listener listener;
    private final TextField code = new TextField();
    private final TextField name = new TextField();
    private final TextField credits = new TextField();
    private final TextField hours = new TextField();
    private final TextArea description = new TextArea();
    private final CheckBox enabled = new CheckBox("课程启用");
    private final Label error = new Label();
    private final ButtonType saveType = new ButtonType("保存", ButtonBar.ButtonData.APPLY);
    private Button save;

    CourseForm(AcademicData.Course existing, Listener listener) {
        this.existing = existing;
        this.listener = Objects.requireNonNull(listener, "listener");
        setTitle(existing == null ? "新增课程" : "编辑课程");
        setHeaderText(existing == null ? "创建全校课程库条目" : "修改课程基本信息");
        getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);
        getDialogPane().getStyleClass().add("academic-root");
        getDialogPane().getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("academic.css"), "academic.css").toExternalForm());

        code.setPromptText("C 加 6 位数字，例如 C000101");
        code.setId("academic-course-form-code");
        name.setId("academic-course-form-name");
        credits.setId("academic-course-form-credits");
        hours.setId("academic-course-form-hours");
        description.setId("academic-course-form-description");
        description.setPrefRowCount(4);
        description.setWrapText(true);
        enabled.setId("academic-course-form-enabled");
        error.setId("academic-course-form-error");
        error.getStyleClass().add("academic-form-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        GridPane fields = new GridPane();
        fields.setHgap(12);
        fields.setVgap(12);
        fields.setPadding(new Insets(8, 0, 0, 0));
        fields.addRow(0, new Label("课程号"), code);
        fields.addRow(1, new Label("课程名称"), name);
        fields.addRow(2, new Label("学分"), credits);
        fields.addRow(3, new Label("总学时"), hours);
        fields.addRow(4, new Label("课程说明"), description);
        fields.add(enabled, 1, 5);
        fields.add(error, 0, 6, 2, 1);
        getDialogPane().setContent(fields);

        if (existing != null) {
            code.setText(existing.code());
            code.setDisable(true);
            name.setText(existing.name());
            credits.setText(existing.credits().toPlainString());
            hours.setText(Integer.toString(existing.totalHours()));
            description.setText(existing.description());
            enabled.setSelected(existing.enabled());
        } else {
            enabled.setSelected(true);
            enabled.setVisible(false);
            enabled.setManaged(false);
        }

        save = (Button) getDialogPane().lookupButton(saveType);
        save.setId("academic-course-form-save");
        save.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            submit();
        });
    }

    static List<String> validateCreate(String code, String name, String credits,
                                       String hours, String description) {
        List<String> errors = new ArrayList<>();
        if (code == null || !code.trim().toUpperCase().matches("C[0-9]{6}")) {
            errors.add("课程号必须为字母 C 加 6 位数字");
        }
        validateCommon(name, credits, hours, description, errors);
        return List.copyOf(errors);
    }

    static List<String> validateUpdate(String name, String credits,
                                       String hours, String description) {
        List<String> errors = new ArrayList<>();
        validateCommon(name, credits, hours, description, errors);
        return List.copyOf(errors);
    }

    void busy(boolean value) {
        save.setDisable(value);
        name.setDisable(value);
        credits.setDisable(value);
        hours.setDisable(value);
        description.setDisable(value);
        enabled.setDisable(value);
        if (existing == null) code.setDisable(value);
    }

    void failure(String message) {
        busy(false);
        error.setText(Objects.requireNonNullElse(message, "保存失败，请稍后重试"));
        error.setManaged(true);
        error.setVisible(true);
    }

    void complete() {
        close();
    }

    private void submit() {
        List<String> errors = existing == null
                ? validateCreate(code.getText(), name.getText(), credits.getText(),
                        hours.getText(), description.getText())
                : validateUpdate(name.getText(), credits.getText(), hours.getText(), description.getText());
        if (!errors.isEmpty()) {
            failure(String.join("\n", errors));
            return;
        }
        error.setManaged(false);
        error.setVisible(false);
        String courseCode = existing == null ? code.getText().trim().toUpperCase() : existing.code();
        listener.save(new AcademicCommands.CourseDraft(courseCode, name.getText().trim(),
                new BigDecimal(credits.getText().trim()), Integer.parseInt(hours.getText().trim()),
                description.getText().trim()), enabled.isSelected());
    }

    private static void validateCommon(String name, String credits, String hours,
                                       String description, List<String> errors) {
        String courseName = name == null ? "" : name.trim();
        if (courseName.isEmpty()) errors.add("课程名称不能为空");
        else if (courseName.length() > 120) errors.add("课程名称不能超过 120 位");

        try {
            BigDecimal value = new BigDecimal(credits == null ? "" : credits.trim());
            if (value.signum() <= 0 || value.compareTo(new BigDecimal("20")) > 0) {
                errors.add("学分必须大于 0 且不超过 20");
            }
        } catch (NumberFormatException exception) {
            errors.add("学分必须大于 0 且不超过 20");
        }

        try {
            int value = Integer.parseInt(hours == null ? "" : hours.trim());
            if (value < 1 || value > 400) errors.add("总学时必须为 1—400 的整数");
        } catch (NumberFormatException exception) {
            errors.add("总学时必须为 1—400 的整数");
        }

        if (description != null && description.trim().length() > 500) {
            errors.add("课程说明不能超过 500 位");
        }
    }
}
