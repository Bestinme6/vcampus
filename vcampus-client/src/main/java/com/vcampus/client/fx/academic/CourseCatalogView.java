package com.vcampus.client.fx.academic;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Objects;

/** Searchable JavaFX table for the global course catalog. */
final class CourseCatalogView extends BorderPane {
    interface Listener {
        void search(String keyword, int page);
        void create(AcademicCommands.CourseDraft draft);
        void update(long courseId, AcademicCommands.CourseDraft draft, boolean enabled);
    }

    private final Listener listener;
    private final TextField keyword = new TextField();
    private final TableView<AcademicData.Course> table = new TableView<>();
    private final Button search;
    private final Button create;
    private final Button edit;
    private final Button previous;
    private final Button next;
    private final Label pageText = new Label();
    private final Label notice = new Label();
    private AcademicData.CoursePage page = new AcademicData.CoursePage(java.util.List.of(), 1, 8, 0);
    private CourseForm form;

    CourseCatalogView(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        getStyleClass().add("academic-course-catalog");
        setPadding(new Insets(28));

        Label title = label("课程库", "academic-page-title");
        Label description = label("课程只创建一次；必修或选修由各专业培养方案决定。", "academic-muted");
        keyword.setPromptText("按课程号或课程名称搜索");
        keyword.setId("academic-course-keyword");
        keyword.setPrefWidth(280);
        search = button("搜索", "academic-primary", () -> listener.search(keyword.getText().trim(), 1));
        search.setId("academic-course-search");
        keyword.setOnAction(event -> search.fire());
        create = button("新增课程", "academic-primary", this::openCreate);
        create.setId("academic-course-create");
        edit = button("编辑所选", "academic-secondary", this::openEdit);
        edit.setId("academic-course-edit");
        edit.setDisable(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(10, keyword, search, spacer, edit, create);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("academic-toolbar");
        notice.getStyleClass().add("academic-notice");
        notice.setManaged(false);
        notice.setVisible(false);
        setTop(new VBox(8, title, description, toolbar, notice));

        addColumn("课程号", 120, AcademicData.Course::code);
        addColumn("课程名称", 220, AcademicData.Course::name);
        addColumn("学分", 80, course -> course.credits().stripTrailingZeros().toPlainString());
        addColumn("总学时", 85, course -> Integer.toString(course.totalHours()));
        addColumn("状态", 90, course -> course.enabled() ? "启用" : "停用");
        addColumn("课程说明", 260, AcademicData.Course::description);
        table.setId("academic-course-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(label("没有找到匹配的课程", "academic-muted"));
        table.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, selected) -> edit.setDisable(selected == null || table.isDisabled()));
        setCenter(table);

        previous = button("上一页", "academic-secondary", () -> listener.search(keyword.getText().trim(), page.page() - 1));
        previous.setId("academic-course-previous");
        next = button("下一页", "academic-secondary", () -> listener.search(keyword.getText().trim(), page.page() + 1));
        next.setId("academic-course-next");
        HBox pager = new HBox(10, pageText, previous, next);
        pager.setAlignment(Pos.CENTER_RIGHT);
        pager.setPadding(new Insets(16, 0, 0, 0));
        setBottom(pager);
        updatePager();
    }

    void showPage(AcademicData.CoursePage page) {
        this.page = Objects.requireNonNull(page, "page");
        table.setItems(FXCollections.observableArrayList(page.rows()));
        table.getSelectionModel().clearSelection();
        edit.setDisable(true);
        updatePager();
    }

    void busy(boolean value) {
        keyword.setDisable(value);
        search.setDisable(value);
        create.setDisable(value);
        table.setDisable(value);
        previous.setDisable(value || page.page() <= 1);
        next.setDisable(value || page.page() * page.pageSize() >= page.total());
        edit.setDisable(value || table.getSelectionModel().getSelectedItem() == null);
        if (form != null) form.busy(value);
    }

    void message(String text, boolean error) {
        notice.setText(Objects.requireNonNullElse(text, ""));
        notice.setManaged(!notice.getText().isBlank());
        notice.setVisible(!notice.getText().isBlank());
        notice.getStyleClass().remove("academic-form-error");
        if (error) notice.getStyleClass().add("academic-form-error");
    }

    void formFailure(String message) {
        if (form != null) form.failure(message);
    }

    void formComplete() {
        if (form != null) {
            form.complete();
            form = null;
        }
    }

    void closeForm() {
        if (form != null) {
            form.close();
            form = null;
        }
    }

    private void openCreate() {
        openForm(null);
    }

    private void openEdit() {
        AcademicData.Course selected = table.getSelectionModel().getSelectedItem();
        if (selected != null) openForm(selected);
    }

    private void openForm(AcademicData.Course course) {
        closeForm();
        form = new CourseForm(course, (draft, enabled) -> {
            form.busy(true);
            if (course == null) listener.create(draft);
            else listener.update(course.id(), draft, enabled);
        });
        if (getScene() != null && getScene().getWindow() != null) form.initOwner(getScene().getWindow());
        form.setOnHidden(event -> form = null);
        form.show();
    }

    private void updatePager() {
        int pages = Math.max(1, (page.total() + page.pageSize() - 1) / page.pageSize());
        pageText.setText("第 " + page.page() + " / " + pages + " 页 · 共 " + page.total() + " 门课程");
        previous.setDisable(page.page() <= 1);
        next.setDisable(page.page() >= pages);
    }

    private void addColumn(String title, double width,
                           java.util.function.Function<AcademicData.Course, String> value) {
        TableColumn<AcademicData.Course, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        table.getColumns().add(column);
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
