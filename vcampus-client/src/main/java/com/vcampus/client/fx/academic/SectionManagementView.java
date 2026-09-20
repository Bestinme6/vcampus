package com.vcampus.client.fx.academic;

import com.vcampus.common.model.CourseSectionStatus;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Alert;
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
        default void reviewSchedule(AcademicData.TeachingSection section) { }
        default void clearSchedule(AcademicData.TeachingSection section) { }
        default void deleteSection(AcademicData.TeachingSection section) { }
        default void loadOccupancy(long termId, java.util.function.Consumer<List<AcademicData.ScheduleOccupancy>> success,
                                   java.util.function.Consumer<String> failure) {
            failure.accept("排课占用查询不可用");
        }
    }

    private final Listener listener;
    private final ComboBox<AcademicData.Term> term = new ComboBox<>();
    private final TextField keyword = new TextField();
    private final TableView<AcademicData.TeachingSection> table = new TableView<>();
    private final Button status;
    private final Button targets;
    private final Button schedule;
    private final Button create;
    private final Label heading = label("开课管理", "academic-page-title");
    private final Label description = label("", "academic-muted");
    private String route = "sections";
    private final VBox publicationCards = new VBox(12);
    private final javafx.scene.control.ScrollPane publicationScroll = new javafx.scene.control.ScrollPane(publicationCards);
    private final Button previous;
    private final Button next;
    private final Label pageText = new Label();
    private final Label notice = new Label();
    private AcademicData.ReferenceData references = new AcademicData.ReferenceData(List.of(), List.of(), List.of());
    private AcademicData.SectionPage page = new AcademicData.SectionPage(List.of(), 1, 20, 0);
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
        create = button("新增教学班", "academic-primary", this::create);
        status = button("修改状态", "academic-secondary", this::changeStatus);
        targets = button("招生范围", "academic-secondary", this::loadTargets);
        schedule = button("编辑排课", "academic-primary", this::editSchedule);
        javafx.scene.layout.FlowPane tools = new javafx.scene.layout.FlowPane(8, 8, term, keyword, search, status, targets, schedule, create);
        tools.setAlignment(Pos.CENTER_LEFT);
        notice.getStyleClass().add("academic-notice");
        notice.setManaged(false);
        notice.setVisible(false);
        setTop(new VBox(8, heading, description, tools, notice));

        addColumn("教学班", 105, AcademicData.TeachingSection::sectionCode);
        addColumn("课程", 190, row -> row.courseCode() + " · " + row.courseName());
        addColumn("教师", 100, AcademicData.TeachingSection::teacherName);
        addColumn("人数", 70, row -> row.enrolledCount() + "/" + row.capacity());
        addColumn("状态", 75, row -> row.status().displayName());
        addColumn("上课时间", 180, AcademicData.TeachingSection::scheduleSummary);
        addColumn("教室", 110, AcademicData.TeachingSection::classroomSummary);
        addColumn("课表发布状态", 110, row -> row.scheduleSummary().isBlank() ? "尚未发布" : "已有已发布课表");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(label("本学期还没有教学班", "academic-muted"));
        table.getSelectionModel().selectedItemProperty().addListener((observable, old, selected) -> updateActions());
        table.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) editSchedule();
        });
        setCenter(table);
        publicationScroll.setFitToWidth(true);
        publicationScroll.setMinWidth(0);

        previous = button("上一页", "academic-secondary", () -> search(page.page() - 1));
        next = button("下一页", "academic-secondary", () -> search(page.page() + 1));
        HBox pager = new HBox(8, pageText, previous, next);
        pager.setAlignment(Pos.CENTER_RIGHT);
        pager.setPadding(new Insets(14, 0, 0, 0));
        setBottom(pager);
        updateActions();
        updatePager();
        open("sections");
    }

    void open(String route) {
        this.route = "scheduling".equals(route) ? "publishing" : route;
        boolean managing = "sections".equals(this.route);
        boolean publishing = "publishing".equals(this.route);
        setId("academic-" + this.route + "-page");
        heading.setText(managing ? "开课管理" : "排课与发布");
        description.setText(managing ? "维护教学班状态、容量与招生范围；创建后进入排课。"
                : "在同一页编辑或清空草稿、检查并发布课表；未开放且没有选课或发布历史的教学班可以删除。");
        for (Button button : List.of(create, status, targets)) {
            button.setVisible(managing);
            button.setManaged(managing);
        }
        schedule.setText("前往排课");
        schedule.setVisible(managing);
        schedule.setManaged(managing);
        setCenter(publishing ? publicationScroll : table);
        if (publishing) showPublicationCards();
        for (var column : table.getColumns()) {
            column.setVisible(switch (column.getText()) {
                case "人数", "状态" -> managing;
                case "上课时间", "教室", "课表发布状态" -> !managing;
                default -> true;
            });
        }
    }

    void showReferences(AcademicData.ReferenceData references) {
        this.references = Objects.requireNonNull(references, "references");
        AcademicData.Term selected = term.getValue();
        term.getItems().setAll(references.terms());
        if (selected != null) references.terms().stream().filter(item -> item.id() == selected.id())
                .findFirst().ifPresent(term::setValue);
        if (term.getValue() == null && !term.getItems().isEmpty()) term.getSelectionModel().selectFirst();
    }

    void showFilter(long termId, String value) {
        references.terms().stream().filter(item -> item.id() == termId)
                .findFirst().ifPresent(term::setValue);
        keyword.setText(Objects.requireNonNullElse(value, ""));
    }

    void showPage(AcademicData.SectionPage page) {
        this.page = Objects.requireNonNull(page, "page");
        table.setItems(FXCollections.observableArrayList(page.rows()));
        table.getSelectionModel().clearSelection();
        updateActions();
        updatePager();
        if ("publishing".equals(route)) showPublicationCards();
    }

    private void showPublicationCards() {
        publicationCards.getChildren().clear();
        if (page.rows().isEmpty()) {
            publicationCards.getChildren().add(label("没有可检查的教学班，请先在开课管理创建教学班并保存排课草稿。", "academic-muted"));
        }
        for (var section : page.rows()) {
            Label title = label(section.courseCode() + " · " + section.courseName() + " · " + section.sectionCode(), "academic-state-title");
            Label state = label(section.scheduleSummary().isBlank() ? "尚未发布课表" : "已有已发布课表", "academic-note");
            Label summary = label(section.teacherName() + " · " + section.termName() + "\n"
                    + (section.scheduleSummary().isBlank() ? "尚无已发布课表；可先编辑排课，再检查并发布。"
                    : "当前已发布：" + section.scheduleSummary() + " · " + section.classroomSummary()), "academic-muted");
            Button edit = button("编辑排课", "academic-primary", () -> listener.editSchedule(section));
            edit.setId("academic-edit-section-" + section.id());
            Button clear = button("清空草稿", "academic-secondary", () -> {
                if (confirm("清空课表草稿", "确定清空“" + section.sectionCode() + "”的课表草稿吗？",
                        "当前草稿将被放弃；再次编辑时会恢复已发布课表。尚未发布过的教学班将回到空草稿。")) {
                    listener.clearSchedule(section);
                }
            });
            clear.setId("academic-clear-section-" + section.id());
            Button review = button("检查并发布", "academic-primary", () -> listener.reviewSchedule(section));
            review.setId("academic-review-section-" + section.id());
            Button delete = button("删除教学班", "academic-secondary", () -> {
                if (confirm("删除教学班", "确定删除“" + section.sectionCode() + "”吗？",
                        "只有未开放、没有选课记录且没有发布课表历史的教学班可以删除。")) {
                    listener.deleteSection(section);
                }
            });
            delete.setId("academic-delete-section-" + section.id());
            VBox text = new VBox(8, title, state, summary);
            text.setMinWidth(0);
            javafx.scene.layout.FlowPane actions = new javafx.scene.layout.FlowPane(8, 8,
                    edit, clear, review, delete);
            actions.setAlignment(Pos.CENTER_LEFT);
            VBox card = new VBox(12, text, actions);
            card.setPadding(new Insets(16));
            card.setStyle("-fx-background-color: white; -fx-border-color: #e5e9f2; -fx-background-radius: 8; -fx-border-radius: 8;");
            publicationCards.getChildren().add(card);
        }
    }

    private boolean confirm(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, content, ButtonType.CANCEL, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(header);
        dialog = alert;
        alert.setOnHidden(event -> dialog = null);
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
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
        SectionForm form = new SectionForm(references, term.getValue(), listener::loadOccupancy);
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
        if (selected == null) return;
        if ("publishing".equals(route)) listener.reviewSchedule(selected);
        else listener.editSchedule(selected);
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
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(event -> action.run());
        return button;
    }
}
