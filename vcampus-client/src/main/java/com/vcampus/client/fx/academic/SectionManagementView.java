package com.vcampus.client.fx.academic;

import com.vcampus.common.model.CourseSectionStatus;
import javafx.beans.property.ReadOnlyStringWrapper;
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
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.Year;
import java.util.List;
import java.util.Objects;

/** Searchable teaching-section administration with target and schedule entry points. */
final class SectionManagementView extends BorderPane {
    interface Listener {
        void search(long termId, String keyword, int page);
        void create(SectionForm.Submission submission);
        void setStatus(long sectionId, CourseSectionStatus status);
        void loadTargets(long sectionId);
        void saveTargets(long sectionId, List<AcademicData.SectionTarget> targets);
        void editSchedule(AcademicData.TeachingSection section);
    }

    private final Listener listener;
    private final ComboBox<AcademicData.Term> term = new ComboBox<>();
    private final TextField keyword = new TextField();
    private final TableView<AcademicData.TeachingSection> table = new TableView<>();
    private final Button status;
    private final Button targets;
    private final Button schedule;
    private final Button previous;
    private final Button next;
    private final Label pageText = new Label();
    private final Label notice = new Label();
    private AcademicData.ReferenceData references = new AcademicData.ReferenceData(List.of(), List.of(), List.of());
    private AcademicData.SectionPage page = new AcademicData.SectionPage(List.of(), 1, 8, 0);
    private Dialog<?> dialog;

    SectionManagementView(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        getStyleClass().add("academic-section-management");
        setPadding(new Insets(26));
        term.setPromptText("选择学期");
        term.setConverter(converter(AcademicData.Term::name));
        keyword.setPromptText("课程、教学班编号或教师");
        Button search = button("查询", "academic-primary", () -> search(1));
        keyword.setOnAction(event -> search.fire());
        Button create = button("新增教学班", "academic-primary", this::create);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        status = button("修改状态", "academic-secondary", this::changeStatus);
        targets = button("招生范围", "academic-secondary", this::loadTargets);
        schedule = button("编辑排课", "academic-primary", this::editSchedule);
        HBox tools = new HBox(8, term, keyword, search, spacer, status, targets, schedule, create);
        tools.setAlignment(Pos.CENTER_LEFT);
        notice.getStyleClass().add("academic-notice");
        notice.setManaged(false);
        notice.setVisible(false);
        setTop(new VBox(8, label("开课与教学班", "academic-page-title"),
                label("教学班承载教师、容量、招生范围和可独立发布的课表。", "academic-muted"),
                tools, notice));

        addColumn("教学班", 105, AcademicData.TeachingSection::sectionCode);
        addColumn("课程", 190, row -> row.courseCode() + " · " + row.courseName());
        addColumn("教师", 100, AcademicData.TeachingSection::teacherName);
        addColumn("人数", 70, row -> row.enrolledCount() + "/" + row.capacity());
        addColumn("状态", 75, row -> row.status().displayName());
        addColumn("上课时间", 180, AcademicData.TeachingSection::scheduleSummary);
        addColumn("教室", 110, AcademicData.TeachingSection::classroomSummary);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(label("本学期还没有教学班", "academic-muted"));
        table.getSelectionModel().selectedItemProperty().addListener((observable, old, selected) -> updateActions());
        table.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) editSchedule();
        });
        setCenter(table);

        previous = button("上一页", "academic-secondary", () -> search(page.page() - 1));
        next = button("下一页", "academic-secondary", () -> search(page.page() + 1));
        HBox pager = new HBox(8, pageText, previous, next);
        pager.setAlignment(Pos.CENTER_RIGHT);
        pager.setPadding(new Insets(14, 0, 0, 0));
        setBottom(pager);
        updateActions();
        updatePager();
    }

    void showReferences(AcademicData.ReferenceData references) {
        this.references = Objects.requireNonNull(references, "references");
        AcademicData.Term selected = term.getValue();
        term.getItems().setAll(references.terms());
        if (selected != null) references.terms().stream().filter(item -> item.id() == selected.id())
                .findFirst().ifPresent(term::setValue);
        if (term.getValue() == null && !term.getItems().isEmpty()) term.getSelectionModel().selectFirst();
    }

    void showPage(AcademicData.SectionPage page) {
        this.page = Objects.requireNonNull(page, "page");
        table.setItems(FXCollections.observableArrayList(page.rows()));
        table.getSelectionModel().clearSelection();
        updateActions();
        updatePager();
    }

    void showTargets(long sectionId, List<AcademicData.SectionTarget> values) {
        closeDialogs();
        Dialog<List<AcademicData.SectionTarget>> editor = targetDialog(values);
        dialog = editor;
        editor.setOnHidden(event -> dialog = null);
        editor.showAndWait().ifPresent(result -> listener.saveTargets(sectionId, result));
    }

    void message(String text, boolean error) {
        notice.setText(Objects.requireNonNullElse(text, ""));
        notice.setManaged(!notice.getText().isBlank());
        notice.setVisible(!notice.getText().isBlank());
        notice.getStyleClass().remove("academic-form-error");
        if (error) notice.getStyleClass().add("academic-form-error");
    }

    void busy(boolean value) { setDisable(value); }

    long selectedTermId() { return term.getValue() == null ? 0 : term.getValue().id(); }

    String keyword() { return keyword.getText().trim(); }

    int page() { return page.page(); }

    void closeDialogs() {
        if (dialog != null) {
            dialog.close();
            dialog = null;
        }
    }

    private void search(int requestedPage) {
        if (selectedTermId() > 0 && requestedPage > 0) {
            listener.search(selectedTermId(), keyword(), requestedPage);
        }
    }

    private void create() {
        if (references.terms().isEmpty() || references.courses().isEmpty()
                || references.teachers().isEmpty() || references.majors().isEmpty()) {
            message("请先配置学期、课程、教师账号和专业", true);
            return;
        }
        closeDialogs();
        SectionForm form = new SectionForm(references, term.getValue());
        dialog = form;
        form.setOnHidden(event -> dialog = null);
        form.showAndWait().ifPresent(listener::create);
    }

    private void changeStatus() {
        AcademicData.TeachingSection selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Dialog<CourseSectionStatus> chooser = new Dialog<>();
        chooser.setTitle("修改教学班状态");
        ComboBox<CourseSectionStatus> options = new ComboBox<>();
        options.getItems().setAll(CourseSectionStatus.values());
        options.setValue(selected.status());
        chooser.getDialogPane().setContent(options);
        ButtonType save = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        chooser.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        chooser.setResultConverter(type -> type == save ? options.getValue() : null);
        dialog = chooser;
        chooser.setOnHidden(event -> dialog = null);
        chooser.showAndWait().ifPresent(value -> {
            if (value == CourseSectionStatus.OPEN && selected.scheduleSummary().isBlank()) {
                message("请先完成并发布课表，再开放教学班", true);
            } else {
                listener.setStatus(selected.id(), value);
            }
        });
    }

    private void loadTargets() {
        AcademicData.TeachingSection selected = table.getSelectionModel().getSelectedItem();
        if (selected != null) listener.loadTargets(selected.id());
    }

    private void editSchedule() {
        AcademicData.TeachingSection selected = table.getSelectionModel().getSelectedItem();
        if (selected != null) listener.editSchedule(selected);
    }

    private Dialog<List<AcademicData.SectionTarget>> targetDialog(List<AcademicData.SectionTarget> initial) {
        Dialog<List<AcademicData.SectionTarget>> editor = new Dialog<>();
        editor.setTitle("编辑招生范围");
        editor.setHeaderText("仅匹配以下专业与入学年级的学生可选择该教学班");
        ListView<AcademicData.SectionTarget> rows = new ListView<>(FXCollections.observableArrayList(initial));
        rows.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(AcademicData.SectionTarget item, boolean empty) {
                super.updateItem(item, empty);
                AcademicData.MajorReference major = empty || item == null ? null : references.majors().stream()
                        .filter(candidate -> candidate.id() == item.majorId()).findFirst().orElse(null);
                setText(empty || item == null ? null : (major == null ? "专业 #" + item.majorId() : major.name())
                        + " · " + item.yearFrom() + "—" + item.yearTo() + " 级");
            }
        });
        ComboBox<AcademicData.MajorReference> major = new ComboBox<>();
        major.getItems().setAll(references.majors());
        major.setConverter(converter(item -> item.code() + " · " + item.name()));
        if (!major.getItems().isEmpty()) major.getSelectionModel().selectFirst();
        int year = Year.now().getValue();
        Spinner<Integer> from = spinner(2000, 2100, year);
        Spinner<Integer> to = spinner(2000, 2100, Math.min(2100, year + 3));
        Label validation = label("", "academic-form-error");
        Button add = button("添加", "academic-secondary", () -> {
            if (major.getValue() == null || to.getValue() < from.getValue()) {
                validation.setText("请选择专业，并确保结束年份不早于开始年份");
                return;
            }
            AcademicData.SectionTarget value = new AcademicData.SectionTarget(
                    major.getValue().id(), from.getValue(), to.getValue());
            if (!rows.getItems().contains(value)) rows.getItems().add(value);
            validation.setText("");
        });
        Button remove = button("移除所选", "academic-secondary", () ->
                rows.getItems().remove(rows.getSelectionModel().getSelectedItem()));
        HBox fields = new HBox(8, major, from, new Label("至"), to, add, remove);
        fields.setAlignment(Pos.CENTER_LEFT);
        editor.getDialogPane().setContent(new VBox(8, rows, fields, validation));
        editor.getDialogPane().setPrefSize(700, 460);
        ButtonType save = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        editor.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        editor.setResultConverter(type -> type == save ? List.copyOf(rows.getItems()) : null);
        return editor;
    }

    private void updateActions() {
        boolean none = table.getSelectionModel().getSelectedItem() == null;
        status.setDisable(none);
        targets.setDisable(none);
        schedule.setDisable(none);
    }

    private void updatePager() {
        int pages = Math.max(1, (page.total() + page.pageSize() - 1) / page.pageSize());
        pageText.setText("第 " + page.page() + " / " + pages + " 页 · 共 " + page.total() + " 个教学班");
        previous.setDisable(page.page() <= 1);
        next.setDisable(page.page() >= pages);
    }

    private void addColumn(String title, double width,
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

    private static Spinner<Integer> spinner(int min, int max, int value) {
        return new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, value));
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
