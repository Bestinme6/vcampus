package com.vcampus.client.fx.academic;

import com.vcampus.common.model.CourseRequirementType;
import com.vcampus.common.model.CurriculumPlanStatus;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Master-detail curriculum manager for versions, year ranges and course requirements. */
final class CurriculumPlanView extends BorderPane {
    interface Listener {
        void search(Long majorId, CurriculumPlanStatus status, int page);
        void open(long planId);
        void create(AcademicCommands.CurriculumDraft draft);
        void update(long planId, AcademicCommands.CurriculumDraft draft);
        void copy(long planId, AcademicCommands.CurriculumDraft draft);
        void saveCourses(long planId, List<AcademicCommands.CurriculumCourseDraft> drafts);
        void removeCourses(long planId, Set<Long> courseIds);
        void publish(long planId);
        void archive(long planId);
    }

    record ActionState(boolean canEdit, boolean canRemoveCourse, boolean canCopy,
                       boolean canPublish, boolean canArchive) {
    }

    private final Listener listener;
    private final ComboBox<AcademicData.MajorReference> major = new ComboBox<>();
    private final ComboBox<CurriculumPlanStatus> status = new ComboBox<>();
    private final ListView<AcademicData.CurriculumSummary> plans = new ListView<>();
    private final TableView<AcademicData.CurriculumCourse> courses = new TableView<>();
    private final VBox detail = new VBox(14);
    private final Label notice = new Label();
    private final Button edit;
    private final Button copy;
    private final Button addCourse;
    private final Button editRequirements;
    private final Button editTerms;
    private final Button removeCourses;
    private final Button publish;
    private final Button archive;
    private final Button previous;
    private final Button next;
    private final Label pageText = new Label();
    private List<AcademicData.MajorReference> majors = List.of();
    private List<AcademicData.CourseReference> courseReferences = List.of();
    private AcademicData.CurriculumPage page = new AcademicData.CurriculumPage(List.of(), 1, 8, 0);
    private AcademicData.CurriculumDetail selected;

    CurriculumPlanView(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        getStyleClass().add("academic-curriculum");
        setPadding(new Insets(24));

        major.setPromptText("全部专业");
        major.setConverter(majorConverter());
        status.setPromptText("全部状态");
        status.getItems().setAll(CurriculumPlanStatus.values());
        Button search = button("筛选", "academic-primary", () -> listener.search(
                major.getValue() == null ? null : major.getValue().id(), status.getValue(), 1));
        Button create = button("新建方案", "academic-primary", this::createPlan);
        HBox filters = new HBox(8, major, status, search, create);
        filters.setAlignment(Pos.CENTER_LEFT);

        plans.setId("academic-curriculum-list");
        plans.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(AcademicData.CurriculumSummary item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.planName() + "  v" + item.versionNo()
                        + "\n" + item.majorName() + " · " + item.yearFrom() + "—" + item.yearTo()
                        + " · " + item.status().displayName());
            }
        });
        plans.getSelectionModel().selectedItemProperty().addListener((observable, old, value) -> {
            if (value != null) listener.open(value.id());
        });
        VBox master = new VBox(12, label("培养方案", "academic-page-title"),
                label("专业 + 连续入学年份范围 + 版本", "academic-muted"), filters, plans);
        master.setPrefWidth(360);
        VBox.setVgrow(plans, Priority.ALWAYS);
        previous = button("上一页", "academic-secondary", () -> listener.search(
                selectedMajorId(), selectedStatus(), page.page() - 1));
        next = button("下一页", "academic-secondary", () -> listener.search(
                selectedMajorId(), selectedStatus(), page.page() + 1));
        HBox pager = new HBox(8, pageText, previous, next);
        pager.setAlignment(Pos.CENTER_RIGHT);
        master.getChildren().add(pager);

        addColumn("课程号", 105, AcademicData.CurriculumCourse::courseCode);
        addColumn("课程名称", 180, AcademicData.CurriculumCourse::courseName);
        addColumn("学分", 60, row -> row.credits().stripTrailingZeros().toPlainString());
        addColumn("属性", 70, row -> row.requirementType().displayName());
        addColumn("建议学期", 80, row -> "第 " + row.recommendedTermNumber() + " 学期");
        courses.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        courses.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        courses.setId("academic-curriculum-courses");

        edit = button("编辑方案", "academic-secondary", this::editPlan);
        copy = button("复制新版本", "academic-secondary", this::copyPlan);
        addCourse = button("添加课程", "academic-primary", this::addCourse);
        editRequirements = button("批量属性", "academic-secondary", this::editSelectedRequirements);
        editTerms = button("批量学期", "academic-secondary", this::editSelectedTerms);
        removeCourses = button("移除所选", "academic-secondary", this::removeSelectedCourses);
        publish = button("发布方案", "academic-primary", this::publishPlan);
        archive = button("归档", "academic-secondary", this::archivePlan);
        HBox actions = new HBox(8, edit, copy, addCourse, editRequirements, editTerms,
                removeCourses, publish, archive);
        actions.setAlignment(Pos.CENTER_LEFT);
        notice.getStyleClass().add("academic-notice");
        notice.setVisible(false);
        notice.setManaged(false);
        detail.getChildren().addAll(label("选择左侧方案查看详情", "academic-state-title"));
        VBox detailHost = new VBox(14, detail, courses, actions, notice);
        VBox.setVgrow(courses, Priority.ALWAYS);
        HBox body = new HBox(20, master, detailHost);
        HBox.setHgrow(detailHost, Priority.ALWAYS);
        setCenter(body);
        applyActions(null);
        updatePager();
    }

    static ActionState actionsFor(CurriculumPlanStatus status, boolean archiveEligible) {
        Objects.requireNonNull(status, "status");
        return switch (status) {
            case DRAFT -> new ActionState(true, true, true, true, false);
            case PUBLISHED -> new ActionState(false, false, true, false, archiveEligible);
            case ARCHIVED -> new ActionState(false, false, true, false, false);
        };
    }

    void showReferences(AcademicData.ReferenceData references) {
        majors = references.majors();
        courseReferences = references.courses();
        major.getItems().setAll(majors);
    }

    void showPlans(AcademicData.CurriculumPage page) {
        this.page = Objects.requireNonNull(page, "page");
        plans.setItems(FXCollections.observableArrayList(page.rows()));
        updatePager();
        showMessage(page.rows().isEmpty() ? "没有符合条件的培养方案" : "", false);
    }

    void showDetail(AcademicData.CurriculumDetail value) {
        selected = Objects.requireNonNull(value, "value");
        detail.getChildren().setAll(
                label(value.planName() + "  v" + value.versionNo(), "academic-page-title"),
                label(value.majorName() + " · 适用 " + value.yearFrom() + "—" + value.yearTo()
                        + " 级 · " + value.status().displayName(), "academic-muted"),
                label("共 " + value.courses().size() + " 门课程；课程属性仅属于本培养方案。", "academic-note"));
        courses.setItems(FXCollections.observableArrayList(value.courses()));
        applyActions(actionsFor(value.status(), true));
    }

    void busy(boolean value) {
        setDisable(value);
    }

    void showMessage(String text, boolean error) {
        notice.setText(Objects.requireNonNullElse(text, ""));
        notice.setVisible(!notice.getText().isBlank());
        notice.setManaged(!notice.getText().isBlank());
        notice.getStyleClass().remove("academic-form-error");
        if (error) notice.getStyleClass().add("academic-form-error");
    }

    Long selectedMajorId() {
        return major.getValue() == null ? null : major.getValue().id();
    }

    CurriculumPlanStatus selectedStatus() {
        return status.getValue();
    }

    int page() {
        return page.page();
    }

    private void createPlan() {
        CurriculumPlanForm form = new CurriculumPlanForm(CurriculumPlanForm.Mode.CREATE, majors, null);
        form.showAndWait().ifPresent(listener::create);
    }

    private void editPlan() {
        AcademicData.CurriculumSummary summary = summary();
        if (summary == null) return;
        CurriculumPlanForm form = new CurriculumPlanForm(CurriculumPlanForm.Mode.EDIT, majors, summary);
        form.showAndWait().ifPresent(draft -> listener.update(summary.id(), draft));
    }

    private void copyPlan() {
        AcademicData.CurriculumSummary summary = summary();
        if (summary == null) return;
        CurriculumPlanForm form = new CurriculumPlanForm(CurriculumPlanForm.Mode.COPY, majors, summary);
        form.showAndWait().ifPresent(draft -> listener.copy(summary.id(), draft));
    }

    private void addCourse() {
        if (selected == null || courseReferences.isEmpty()) return;
        Set<Long> existing = selected.courses().stream().map(AcademicData.CurriculumCourse::courseId)
                .collect(java.util.stream.Collectors.toSet());
        List<AcademicData.CourseReference> available = courseReferences.stream()
                .filter(course -> !existing.contains(course.id())).toList();
        if (available.isEmpty()) {
            showMessage("所有启用课程都已加入本方案", false);
            return;
        }
        java.util.Map<String, AcademicData.CourseReference> choices = available.stream()
                .collect(java.util.stream.Collectors.toMap(
                        course -> course.code() + " · " + course.name(), course -> course,
                        (left, right) -> left, java.util.LinkedHashMap::new));
        ChoiceDialog<String> dialog = new ChoiceDialog<>(choices.keySet().iterator().next(), choices.keySet());
        dialog.setTitle("添加课程");
        dialog.setHeaderText("选择要加入培养方案的课程");
        dialog.showAndWait().map(choices::get).ifPresent(course -> listener.saveCourses(selected.id(), List.of(
                new AcademicCommands.CurriculumCourseDraft(course.id(), CourseRequirementType.ELECTIVE, 1))));
    }

    private void editSelectedRequirements() {
        if (selected == null || courses.getSelectionModel().getSelectedItems().isEmpty()) return;
        Set<Long> ids = selectedCourseIds();
        ChoiceDialog<CourseRequirementType> type = new ChoiceDialog<>(CourseRequirementType.ELECTIVE,
                CourseRequirementType.values());
        type.setTitle("批量修改课程属性");
        type.setHeaderText("选择所选课程的新属性；建议学期保持不变");
        type.showAndWait().ifPresent(value -> listener.saveCourses(selected.id(),
                CurriculumPlanForm.setRequirement(selected.courses(), ids, value)));
    }

    private void editSelectedTerms() {
        if (selected == null || courses.getSelectionModel().getSelectedItems().isEmpty()) return;
        Set<Long> ids = selectedCourseIds();
        List<Integer> terms = java.util.stream.IntStream.rangeClosed(1, 12).boxed().toList();
        ChoiceDialog<Integer> term = new ChoiceDialog<>(1, terms);
        term.setTitle("批量修改建议学期");
        term.setHeaderText("选择所选课程的新建议学期；必修/选修属性保持不变");
        term.showAndWait().ifPresent(value -> listener.saveCourses(selected.id(),
                CurriculumPlanForm.setRecommendedTerm(selected.courses(), ids, value)));
    }

    private void removeSelectedCourses() {
        if (selected == null || courses.getSelectionModel().getSelectedItems().isEmpty()) return;
        if (confirm("移除课程", "从本培养方案移除所选课程？全校课程库不会被删除。")) {
            listener.removeCourses(selected.id(), selectedCourseIds());
        }
    }

    private void publishPlan() {
        if (selected != null && confirm("发布培养方案", "发布“" + selected.planName() + "”的 "
                + selected.courses().size() + " 门课程，适用于 " + selected.yearFrom() + "—"
                + selected.yearTo() + " 级？发布后内容不可直接修改。")) listener.publish(selected.id());
    }

    private void archivePlan() {
        if (selected != null && confirm("归档培养方案", "归档后不再用于新匹配，确定继续？")) {
            listener.archive(selected.id());
        }
    }

    private boolean confirm(String title, String text) {
        return new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL)
                .showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private AcademicData.CurriculumSummary summary() {
        if (selected == null) return null;
        return new AcademicData.CurriculumSummary(selected.id(), selected.majorId(), selected.majorName(),
                selected.planName(), selected.versionNo(), selected.yearFrom(), selected.yearTo(),
                selected.status(), selected.courses().size());
    }

    private Set<Long> selectedCourseIds() {
        return courses.getSelectionModel().getSelectedItems().stream()
                .map(AcademicData.CurriculumCourse::courseId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private void applyActions(ActionState state) {
        boolean none = state == null;
        edit.setDisable(none || !state.canEdit());
        addCourse.setDisable(none || !state.canEdit());
        editRequirements.setDisable(none || !state.canEdit());
        editTerms.setDisable(none || !state.canEdit());
        removeCourses.setDisable(none || !state.canRemoveCourse());
        copy.setDisable(none || !state.canCopy());
        publish.setDisable(none || !state.canPublish());
        archive.setDisable(none || !state.canArchive());
    }

    private void updatePager() {
        int pages = Math.max(1, (page.total() + page.pageSize() - 1) / page.pageSize());
        pageText.setText("第 " + page.page() + " / " + pages + " 页 · 共 " + page.total() + " 个方案");
        previous.setDisable(page.page() <= 1);
        next.setDisable(page.page() >= pages);
    }

    private void addColumn(String title, double width,
                           java.util.function.Function<AcademicData.CurriculumCourse, String> value) {
        TableColumn<AcademicData.CurriculumCourse, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        courses.getColumns().add(column);
    }

    private static StringConverter<AcademicData.MajorReference> majorConverter() {
        return new StringConverter<>() {
            @Override public String toString(AcademicData.MajorReference value) {
                return value == null ? "" : value.code() + " · " + value.name();
            }
            @Override public AcademicData.MajorReference fromString(String string) { throw new UnsupportedOperationException(); }
        };
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
