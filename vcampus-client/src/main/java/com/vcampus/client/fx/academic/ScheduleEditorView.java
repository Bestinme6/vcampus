package com.vcampus.client.fx.academic;

import com.vcampus.common.model.ScheduleSlot;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;

/** Draft-preserving schedule editor with structured publication-conflict feedback. */
final class ScheduleEditorView extends BorderPane {
    interface Listener {
        void save(AcademicCommands.ScheduleDraftCommand command);
        void publish(AcademicCommands.SchedulePublishCommand command);
        void reload(long sectionId);
        void back();
    }

    private final Listener listener;
    private final Label title = label("排课草稿", "academic-page-title");
    private final Label meta = label("", "academic-muted");
    private final Label notice = label("", "academic-notice");
    private final Label conflicts = label("", "academic-note");
    private final Spinner<Integer> startWeek = spinner(1, 30, 1);
    private final Spinner<Integer> endWeek = spinner(1, 30, 16);
    private final TextField classroom = new TextField();
    private final TableView<ScheduleSlot> slots = new TableView<>();
    private final ScheduleGrid grid = new ScheduleGrid(this::addSlot);
    private final Button save;
    private final Button publish;
    private final Button reload;
    private AcademicData.TeachingSection section;
    private AcademicData.ScheduleDraft draft;
    private boolean dirty;
    private boolean reloadRequired;

    ScheduleEditorView(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        getStyleClass().add("academic-schedule-editor");
        setPadding(new Insets(24));
        Button back = button("返回教学班", "academic-secondary", listener::back);
        save = button("保存草稿", "academic-primary", this::save);
        publish = button("发布课表", "academic-primary", this::publish);
        reload = button("重新加载服务器版本", "academic-secondary", this::reload);
        HBox heading = new HBox(10, back, new VBox(3, title, meta));
        heading.setAlignment(Pos.CENTER_LEFT);
        notice.setManaged(false);
        notice.setVisible(false);
        conflicts.setManaged(false);
        conflicts.setVisible(false);
        setTop(new VBox(8, heading, notice, conflicts));

        classroom.setPromptText("教室");
        classroom.setPrefWidth(220);
        Button remove = button("移除所选", "academic-secondary", this::removeSelected);
        HBox fields = new HBox(8, new Label("开始周"), startWeek, new Label("结束周"), endWeek,
                new Label("教室"), classroom, remove);
        fields.setAlignment(Pos.CENTER_LEFT);
        addColumn("星期", 70, slot -> "周" + slot.dayOfWeek());
        addColumn("节次", 90, slot -> slot.startPeriod() + "—" + slot.endPeriod());
        addColumn("周次", 90, slot -> slot.startWeek() + "—" + slot.endWeek());
        addColumn("教室", 130, ScheduleSlot::classroom);
        slots.setPrefWidth(360);
        slots.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox list = new VBox(8, fields, slots);
        HBox.setHgrow(grid, Priority.ALWAYS);
        VBox.setVgrow(slots, Priority.ALWAYS);
        HBox body = new HBox(16, grid, list);
        setCenter(body);

        HBox actions = new HBox(8, save, publish, reload);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setPadding(new Insets(14, 0, 0, 0));
        setBottom(actions);
        startWeek.valueProperty().addListener((observable, old, value) -> configureGrid());
        endWeek.valueProperty().addListener((observable, old, value) -> configureGrid());
        classroom.textProperty().addListener((observable, old, value) -> configureGrid());
        updateActions();
    }

    void showSection(AcademicData.TeachingSection section) {
        this.section = Objects.requireNonNull(section, "section");
        title.setText(section.courseCode() + " · " + section.courseName() + " · " + section.sectionCode());
        meta.setText(section.termName() + " · " + section.teacherName() + " · "
                + section.enrolledCount() + "/" + section.capacity() + " 人");
    }

    void showDraft(AcademicData.ScheduleDraft draft) {
        this.draft = Objects.requireNonNull(draft, "draft");
        slots.setItems(FXCollections.observableArrayList(draft.slots()));
        grid.setSlots(draft.slots());
        grid.setBlockedSlots(List.of());
        dirty = false;
        reloadRequired = false;
        conflicts.setText("");
        conflicts.setManaged(false);
        conflicts.setVisible(false);
        message("草稿版本 " + draft.revisionNo() + " 已加载", false);
        updateActions();
    }

    void saved(AcademicData.ScheduleDraft saved) {
        showDraft(saved);
        message("课表草稿已保存", false);
    }

    void failure(String message) {
        ScheduleEditorPolicy.FailureState state = ScheduleEditorPolicy.afterFailure(localSlots(), message);
        reloadRequired = state.reloadRequired();
        this.message(state.message() + (reloadRequired ? "；本地改动已保留，请重新加载后再提交" : ""), true);
        updateActions();
    }

    void showPublishResult(AcademicData.SchedulePublishResult result) {
        Objects.requireNonNull(result, "result");
        if (result.success()) {
            message(result.message(), false);
            return;
        }
        List<AcademicData.ScheduleConflict> rows = result.conflicts();
        message(result.message(), true);
        String detail = rows.stream().map(conflict -> conflict.kind().displayName() + "冲突："
                + conflict.displayName() + "，周" + conflict.dayOfWeek() + "第 "
                + conflict.startPeriod() + "—" + conflict.endPeriod() + " 节，"
                + conflict.startWeek() + "—" + conflict.endWeek() + " 周")
                .collect(java.util.stream.Collectors.joining("\n"));
        conflicts.setText(detail);
        conflicts.setManaged(!detail.isBlank());
        conflicts.setVisible(!detail.isBlank());
        grid.setBlockedSlots(rows.stream().map(row -> new ScheduleSlot(row.dayOfWeek(),
                row.startPeriod(), row.endPeriod(), row.startWeek(), row.endWeek(), "冲突占用")).toList());
        updateActions();
    }

    void busy(boolean value) { setDisable(value); }

    List<ScheduleSlot> localSlots() { return List.copyOf(slots.getItems()); }

    private void addSlot(ScheduleSlot slot) {
        if (slots.getItems().stream().anyMatch(existing -> existing.overlaps(slot))) {
            message("新时段与本教学班已有时段重叠", true);
            return;
        }
        slots.getItems().add(slot);
        grid.setSlots(slots.getItems());
        dirty = true;
        message("存在尚未保存的修改", false);
        updateActions();
    }

    private void removeSelected() {
        ScheduleSlot selected = slots.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        slots.getItems().remove(selected);
        grid.setSlots(slots.getItems());
        dirty = true;
        updateActions();
    }

    private void save() {
        if (draft == null || reloadRequired) return;
        listener.save(new AcademicCommands.ScheduleDraftCommand(section.id(), draft.scheduleRevisionId(),
                draft.revisionNo(), localSlots()));
    }

    private void publish() {
        if (draft == null || dirty || reloadRequired || slots.getItems().isEmpty()) return;
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "发布后学生与教师将看到这份课表。确认发布当前草稿版本 " + draft.revisionNo() + "？",
                ButtonType.OK, ButtonType.CANCEL);
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            listener.publish(new AcademicCommands.SchedulePublishCommand(section.id(),
                    draft.scheduleRevisionId(), draft.revisionNo()));
        }
    }

    private void reload() {
        if (section != null) listener.reload(section.id());
    }

    private void configureGrid() {
        int from = startWeek.getValue();
        int to = Math.max(from, endWeek.getValue());
        if (to != endWeek.getValue()) endWeek.getValueFactory().setValue(to);
        grid.configure(from, to, classroom.getText());
    }

    private void updateActions() {
        boolean absent = draft == null;
        save.setDisable(absent || reloadRequired);
        publish.setDisable(absent || reloadRequired || dirty || slots.getItems().isEmpty());
        reload.setDisable(section == null);
    }

    private void message(String text, boolean error) {
        notice.setText(Objects.requireNonNullElse(text, ""));
        notice.setManaged(!notice.getText().isBlank());
        notice.setVisible(!notice.getText().isBlank());
        notice.getStyleClass().remove("academic-form-error");
        if (error) notice.getStyleClass().add("academic-form-error");
    }

    private void addColumn(String title, double width,
                           java.util.function.Function<ScheduleSlot, String> value) {
        TableColumn<ScheduleSlot, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        slots.getColumns().add(column);
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
