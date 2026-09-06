package com.vcampus.client.fx.academic;

import com.vcampus.common.model.CourseRequirementType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Curriculum create/edit/copy dialog and side-effect-free batch edit policies. */
final class CurriculumPlanForm extends Dialog<AcademicCommands.CurriculumDraft> {
    enum Mode { CREATE, EDIT, COPY }

    private final Mode mode;
    private final ComboBox<AcademicData.MajorReference> major = new ComboBox<>();
    private final TextField name = new TextField();
    private final TextField version = new TextField();
    private final TextField yearFrom = new TextField();
    private final TextField yearTo = new TextField();
    private final Label error = new Label();
    private final ButtonType saveType = new ButtonType("保存", ButtonBar.ButtonData.APPLY);

    CurriculumPlanForm(Mode mode, List<AcademicData.MajorReference> majors,
                       AcademicData.CurriculumSummary source) {
        this.mode = Objects.requireNonNull(mode, "mode");
        major.getItems().setAll(Objects.requireNonNull(majors, "majors"));
        major.setConverter(new StringConverter<>() {
            @Override public String toString(AcademicData.MajorReference value) {
                return value == null ? "" : value.code() + " · " + value.name();
            }
            @Override public AcademicData.MajorReference fromString(String string) {
                throw new UnsupportedOperationException();
            }
        });
        setTitle(switch (mode) {
            case CREATE -> "新建培养方案";
            case EDIT -> "编辑培养方案";
            case COPY -> "复制培养方案";
        });
        setHeaderText("一个方案可覆盖同一专业连续多个入学年份");
        getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);
        getDialogPane().getStyleClass().add("academic-root");
        getDialogPane().getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("academic.css"), "academic.css").toExternalForm());

        name.setId("academic-curriculum-form-name");
        version.setId("academic-curriculum-form-version");
        yearFrom.setId("academic-curriculum-form-year-from");
        yearTo.setId("academic-curriculum-form-year-to");
        error.setId("academic-curriculum-form-error");
        error.getStyleClass().add("academic-form-error");
        error.setVisible(false);
        error.setManaged(false);
        GridPane fields = new GridPane();
        fields.setHgap(12);
        fields.setVgap(12);
        fields.addRow(0, new Label("专业"), major);
        fields.addRow(1, new Label("方案名称"), name);
        fields.addRow(2, new Label("版本号"), version);
        fields.addRow(3, new Label("适用起始年"), yearFrom);
        fields.addRow(4, new Label("适用结束年"), yearTo);
        fields.add(error, 0, 5, 2, 1);
        getDialogPane().setContent(fields);

        if (source != null) {
            majors.stream().filter(item -> item.id() == source.majorId()).findFirst()
                    .ifPresent(major::setValue);
            name.setText(mode == Mode.COPY ? source.planName() + "（副本）" : source.planName());
            version.setText(mode == Mode.COPY ? Integer.toString(source.versionNo() + 1)
                    : Integer.toString(source.versionNo()));
            yearFrom.setText(Integer.toString(source.yearFrom()));
            yearTo.setText(Integer.toString(source.yearTo()));
        }
        major.setDisable(mode != Mode.CREATE);
        version.setDisable(mode == Mode.EDIT);

        Button save = (Button) getDialogPane().lookupButton(saveType);
        save.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            List<String> errors = validate(major.getValue(), name.getText(), version.getText(),
                    yearFrom.getText(), yearTo.getText());
            if (!errors.isEmpty()) {
                event.consume();
                error.setText(String.join("\n", errors));
                error.setVisible(true);
                error.setManaged(true);
                return;
            }
            event.consume();
            setResult(new AcademicCommands.CurriculumDraft(major.getValue().id(), name.getText().trim(),
                    Integer.parseInt(version.getText().trim()), Integer.parseInt(yearFrom.getText().trim()),
                    Integer.parseInt(yearTo.getText().trim())));
            close();
        });
    }

    static List<AcademicCommands.CurriculumCourseDraft> setRequirement(
            List<AcademicData.CurriculumCourse> rows, Set<Long> selectedIds,
            CourseRequirementType requirementType) {
        Objects.requireNonNull(requirementType, "requirementType");
        return selected(rows, selectedIds).stream()
                .map(row -> new AcademicCommands.CurriculumCourseDraft(row.courseId(), requirementType,
                        row.recommendedTermNumber(), true))
                .toList();
    }

    static List<AcademicCommands.CurriculumCourseDraft> setRecommendedTerm(
            List<AcademicData.CurriculumCourse> rows, Set<Long> selectedIds, int term) {
        if (term < 1 || term > 12) throw new IllegalArgumentException("建议学期必须为 1—12");
        return selected(rows, selectedIds).stream()
                .map(row -> new AcademicCommands.CurriculumCourseDraft(row.courseId(),
                        row.requirementType(), term, true))
                .toList();
    }

    static List<String> validate(AcademicData.MajorReference major, String name,
                                 String version, String yearFrom, String yearTo) {
        List<String> errors = new ArrayList<>();
        if (major == null) errors.add("请选择专业");
        if (name == null || name.isBlank()) errors.add("方案名称不能为空");
        else if (name.trim().length() > 120) errors.add("方案名称不能超过 120 位");
        Integer parsedVersion = parse(version);
        if (parsedVersion == null || parsedVersion < 1) errors.add("版本号必须为正整数");
        Integer from = parse(yearFrom);
        Integer to = parse(yearTo);
        if (from == null || to == null || from < 1900 || to > 2200 || to < from) {
            errors.add("适用年份必须在 1900—2200 之间且结束年不早于起始年");
        }
        return List.copyOf(errors);
    }

    private static List<AcademicData.CurriculumCourse> selected(
            List<AcademicData.CurriculumCourse> rows, Set<Long> selectedIds) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(selectedIds, "selectedIds");
        return rows.stream().filter(row -> selectedIds.contains(row.courseId())).toList();
    }

    private static Integer parse(String value) {
        try {
            return Integer.valueOf(value == null ? "" : value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
