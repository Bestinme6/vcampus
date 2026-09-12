package com.vcampus.client.fx.system;

import com.vcampus.common.model.AcademicTermStatus;
import com.vcampus.common.model.AuditLogEntry;
import com.vcampus.common.model.ClassPeriodTime;
import com.vcampus.common.model.SystemTermConfig;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class SystemManagementView extends BorderPane implements SystemManagementController.View {
    public interface Listener {
        void saveTerm(SystemTermConfig term);
        void savePeriods(List<ClassPeriodTime> periods);
        void searchAudit(SystemData.AuditQuery query);
    }

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");

    private final ComboBox<SystemTermConfig> termSelector = new ComboBox<>();
    private final TextField termName = new TextField();
    private final DatePicker startDate = new DatePicker();
    private final DatePicker endDate = new DatePicker();
    private final TextField selectionStart = new TextField();
    private final TextField selectionEnd = new TextField();
    private final TextField dropDeadline = new TextField();
    private final ComboBox<AcademicTermStatus> termStatus = new ComboBox<>();
    private final Button saveTerm = primary("保存学期配置", "save-term");
    private final List<TextField> periodStarts = new ArrayList<>();
    private final List<TextField> periodEnds = new ArrayList<>();
    private final Button savePeriods = primary("保存节次时间", "save-periods");
    private final TextField auditUser = new TextField();
    private final TextField auditAction = new TextField();
    private final ComboBox<String> auditResult = new ComboBox<>();
    private final DatePicker auditFrom = new DatePicker();
    private final DatePicker auditTo = new DatePicker();
    private final Button auditSearch = primary("查询", "audit-search");
    private final TableView<AuditLogEntry> auditTable = new TableView<>();
    private final Label auditPage = new Label("第 1 页 · 共 0 条");
    private final Button previous = new Button("上一页");
    private final Button next = new Button("下一页");
    private final Label feedback = new Label();
    private Listener listener;
    private int currentPage = 1;
    private int total = 0;
    private int pageSize = 20;

    public SystemManagementView(Runnable back) {
        setId("system-management-view");
        getStyleClass().add("system-page");
        Button backButton = new Button("返回工作台");
        backButton.setOnAction(event -> back.run());
        Label title = new Label("系统管理");
        title.getStyleClass().add("system-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, title, spacer, backButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("system-header");

        Tab configuration = new Tab("基础配置", configurationPane());
        configuration.setId("system-config-tab");
        Tab audit = new Tab("审计日志", auditPane());
        audit.setId("system-audit-tab");
        TabPane tabs = new TabPane(configuration, audit);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        feedback.setWrapText(true);
        VBox body = new VBox(10, header, feedback, tabs);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        setCenter(body);
        String css = getClass().getResource("system.css").toExternalForm();
        getStylesheets().add(css);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private ScrollPane configurationPane() {
        termStatus.getItems().setAll(AcademicTermStatus.values());
        termSelector.setConverter(new StringConverter<>() {
            @Override public String toString(SystemTermConfig term) {
                return term == null ? "" : term.academicYear() + " · " + term.termName();
            }
            @Override public SystemTermConfig fromString(String value) { return null; }
        });
        termSelector.valueProperty().addListener((observable, oldValue, newValue) -> showTerm(newValue));
        selectionStart.setPromptText("yyyy-MM-dd HH:mm");
        selectionEnd.setPromptText("yyyy-MM-dd HH:mm");
        dropDeadline.setPromptText("yyyy-MM-dd HH:mm");
        GridPane termForm = formGrid();
        addRow(termForm, 0, "选择学期", termSelector, "学期名称", termName);
        addRow(termForm, 1, "开始日期", startDate, "结束日期", endDate);
        addRow(termForm, 2, "选课开始", selectionStart, "选课结束", selectionEnd);
        addRow(termForm, 3, "退课截止", dropDeadline, "学期状态", termStatus);
        saveTerm.setOnAction(event -> submitTerm());
        VBox termCard = card("学期设置", "只编辑已经存在的学期，不在这里新建或删除学期。", termForm, saveTerm);

        GridPane periods = formGrid();
        for (int number = 1; number <= 12; number++) {
            TextField start = new TextField();
            TextField end = new TextField();
            start.setPromptText("HH:mm");
            end.setPromptText("HH:mm");
            start.setId("period-" + number + "-start");
            end.setId("period-" + number + "-end");
            periodStarts.add(start);
            periodEnds.add(end);
            addRow(periods, number - 1, "第 " + number + " 节开始", start, "结束", end);
        }
        savePeriods.setOnAction(event -> submitPeriods());
        VBox periodCard = card("节次时间", "可整行留空；填写时开始与结束必须同时存在，且各节次不能重叠。", periods, savePeriods);
        VBox content = new VBox(16, termCard, periodCard);
        content.setPadding(new Insets(14));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private VBox auditPane() {
        auditUser.setPromptText("用户名或姓名");
        auditAction.setPromptText("如 system.config.");
        auditResult.getItems().setAll("", "SUCCESS", "FAILED", "INVALID", "NOT_FOUND", "DATABASE_ERROR");
        auditResult.setValue("");
        Button reset = new Button("重置");
        reset.setId("audit-reset");
        auditSearch.setOnAction(event -> submitAudit(1));
        reset.setOnAction(event -> {
            auditUser.clear(); auditAction.clear(); auditResult.setValue("");
            auditFrom.setValue(null); auditTo.setValue(null); submitAudit(1);
        });
        GridPane filters = formGrid();
        addRow(filters, 0, "用户", auditUser, "动作前缀", auditAction);
        addRow(filters, 1, "结果", auditResult, "开始日期", auditFrom);
        filters.add(new Label("结束日期"), 0, 2);
        filters.add(auditTo, 1, 2);
        filters.add(new HBox(8, auditSearch, reset), 3, 2);
        buildAuditColumns();
        auditTable.setId("audit-table");
        auditTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(auditTable, Priority.ALWAYS);
        previous.setOnAction(event -> submitAudit(Math.max(1, currentPage - 1)));
        next.setOnAction(event -> submitAudit(currentPage + 1));
        HBox pager = new HBox(10, previous, auditPage, next);
        pager.setAlignment(Pos.CENTER_RIGHT);
        VBox box = new VBox(12, card("筛选条件", "统一审计页为只读查询。", filters), auditTable, pager);
        box.setPadding(new Insets(14));
        return box;
    }

    private void buildAuditColumns() {
        auditTable.getColumns().setAll(column("时间", 170, row -> DATE_TIME.format(
                        LocalDateTime.ofInstant(row.createdAt(), CAMPUS_ZONE))),
                column("用户", 130, row -> row.displayName().isBlank() ? row.username() : row.displayName()),
                column("动作", 210, AuditLogEntry::actionCode),
                column("结果", 110, AuditLogEntry::resultCode),
                column("客户端地址", 130, AuditLogEntry::clientAddress));
    }

    private TableColumn<AuditLogEntry, String> column(String title, double width,
                                                       java.util.function.Function<AuditLogEntry, String> value) {
        TableColumn<AuditLogEntry, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        return column;
    }

    private void submitTerm() {
        if (listener == null) return;
        try {
            SystemTermConfig source = termSelector.getValue();
            if (source == null) throw new IllegalArgumentException("请选择学期");
            listener.saveTerm(new SystemTermConfig(source.id(), source.academicYear(), source.termNumber(),
                    termName.getText(), startDate.getValue(), endDate.getValue(),
                    parseDateTime(selectionStart.getText()), parseDateTime(selectionEnd.getText()),
                    parseDateTime(dropDeadline.getText()), termStatus.getValue()));
        } catch (RuntimeException exception) {
            showError(exception.getMessage() == null ? "学期配置格式不正确" : exception.getMessage());
        }
    }

    private void submitPeriods() {
        if (listener == null) return;
        try {
            List<ClassPeriodTime> periods = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                String start = periodStarts.get(index).getText().trim();
                String end = periodEnds.get(index).getText().trim();
                periods.add(new ClassPeriodTime(index + 1, parseTime(start), parseTime(end)));
            }
            listener.savePeriods(List.copyOf(periods));
        } catch (RuntimeException exception) {
            showError(exception.getMessage() == null ? "节次时间格式不正确" : exception.getMessage());
        }
    }

    private void submitAudit(int page) {
        if (listener == null) return;
        try {
            listener.searchAudit(new SystemData.AuditQuery(auditUser.getText(), auditAction.getText(),
                    auditResult.getValue(), auditFrom.getValue(), auditTo.getValue(), page));
        } catch (RuntimeException exception) {
            showError(exception.getMessage());
        }
    }

    private LocalDateTime parseDateTime(String value) {
        return LocalDateTime.parse(value.trim(), DATE_TIME);
    }

    private LocalTime parseTime(String value) {
        return value.isBlank() ? null : LocalTime.parse(value, TIME);
    }

    @Override
    public void showConfig(SystemData.Config config) {
        SystemTermConfig selected = termSelector.getValue();
        termSelector.getItems().setAll(config.terms());
        if (!config.terms().isEmpty()) {
            termSelector.setValue(config.terms().stream()
                    .filter(term -> selected != null && term.id() == selected.id())
                    .findFirst().orElse(config.terms().getFirst()));
        }
        for (int index = 0; index < 12; index++) {
            ClassPeriodTime period = config.periods().get(index);
            periodStarts.get(index).setText(period.startTime() == null ? "" : TIME.format(period.startTime()));
            periodEnds.get(index).setText(period.endTime() == null ? "" : TIME.format(period.endTime()));
        }
    }

    private void showTerm(SystemTermConfig term) {
        if (term == null) return;
        termName.setText(term.termName());
        startDate.setValue(term.startDate());
        endDate.setValue(term.endDate());
        selectionStart.setText(DATE_TIME.format(term.selectionStart()));
        selectionEnd.setText(DATE_TIME.format(term.selectionEnd()));
        dropDeadline.setText(DATE_TIME.format(term.dropDeadline()));
        termStatus.setValue(term.status());
    }

    @Override
    public void showAudit(SystemData.AuditPage page) {
        auditTable.getItems().setAll(page.rows());
        currentPage = page.page(); total = page.total(); pageSize = page.pageSize();
        int pages = Math.max(1, (total + pageSize - 1) / pageSize);
        auditPage.setText("第 " + currentPage + " / " + pages + " 页 · 共 " + total + " 条");
        previous.setDisable(currentPage <= 1);
        next.setDisable(currentPage >= pages);
    }

    @Override public void setConfigBusy(boolean busy) { saveTerm.setDisable(busy); savePeriods.setDisable(busy); }
    @Override public void setAuditBusy(boolean busy) { auditSearch.setDisable(busy); previous.setDisable(busy); next.setDisable(busy); }
    @Override public void showMessage(String message) { feedback.getStyleClass().remove("system-error"); feedback.setText(message); }
    @Override public void showError(String message) { if (!feedback.getStyleClass().contains("system-error")) feedback.getStyleClass().add("system-error"); feedback.setText(message == null ? "操作失败" : message); }

    private static Button primary(String text, String id) {
        Button button = new Button(text);
        button.setId(id);
        button.getStyleClass().add("system-primary");
        return button;
    }

    private static GridPane formGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(10);
        grid.getColumnConstraints().setAll(new ColumnConstraints(95), grow(), new ColumnConstraints(95), grow());
        return grid;
    }

    private static ColumnConstraints grow() {
        ColumnConstraints column = new ColumnConstraints();
        column.setHgrow(Priority.ALWAYS);
        column.setFillWidth(true);
        return column;
    }

    private static void addRow(GridPane grid, int row, String firstLabel, javafx.scene.Node first,
                               String secondLabel, javafx.scene.Node second) {
        grid.add(new Label(firstLabel), 0, row); grid.add(first, 1, row);
        grid.add(new Label(secondLabel), 2, row); grid.add(second, 3, row);
        GridPane.setHgrow(first, Priority.ALWAYS); GridPane.setHgrow(second, Priority.ALWAYS);
    }

    private static VBox card(String title, String help, javafx.scene.Node... nodes) {
        Label heading = new Label(title); heading.getStyleClass().add("system-card-title");
        Label hint = new Label(help); hint.getStyleClass().add("system-muted"); hint.setWrapText(true);
        VBox card = new VBox(12, heading, hint);
        card.getChildren().addAll(nodes);
        card.getStyleClass().add("system-card");
        return card;
    }
}
