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
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
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
    private static final List<AuditFilter> AUDIT_ACTION_FILTERS = List.of(
            new AuditFilter("", "全部动作"), new AuditFilter("auth.", "登录与认证"),
            new AuditFilter("account.", "账户管理"), new AuditFilter("student.", "学生管理"),
            new AuditFilter("profile.teacher.", "教师档案"), new AuditFilter("academic.", "教务管理"),
            new AuditFilter("library.", "图书管理"), new AuditFilter("bank.", "校园银行"),
            new AuditFilter("shop.", "校园商店"), new AuditFilter("forum.", "校园论坛"),
            new AuditFilter("notification.", "消息中心"), new AuditFilter("system.", "系统管理"));
    private static final List<AuditFilter> AUDIT_RESULT_FILTERS = List.of(
            new AuditFilter("", "全部结果"), new AuditFilter("SUCCESS", "成功"),
            new AuditFilter("DENIED", "权限拒绝"), new AuditFilter("DISABLED", "账号已禁用"),
            new AuditFilter("NO_ROLE", "未分配角色"), new AuditFilter("INVALID_ROLES", "角色组合无效"),
            new AuditFilter("DUPLICATE", "重复记录"), new AuditFilter("FAILED", "失败"),
            new AuditFilter("INVALID", "参数无效"), new AuditFilter("NOT_FOUND", "未找到"),
            new AuditFilter("CONFLICT", "状态冲突"), new AuditFilter("DATABASE_ERROR", "数据库错误"));

    private final ComboBox<SystemTermConfig> termSelector = new ComboBox<>();
    private final Label termAcademicYear = new Label();
    private final Label termNumber = new Label();
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
    private final ComboBox<String> auditAction = new ComboBox<>();
    private final ComboBox<String> auditResult = new ComboBox<>();
    private final DatePicker auditFrom = new DatePicker();
    private final DatePicker auditTo = new DatePicker();
    private final Button auditSearch = primary("查询", "audit-search");
    private final TableView<AuditLogEntry> auditTable = new TableView<>();
    private final Label auditDetailId = detailValue("audit-detail-id");
    private final Label auditDetailUserId = detailValue("audit-detail-user-id");
    private final Label auditDetailUsername = detailValue("audit-detail-username");
    private final Label auditDetailDisplayName = detailValue("audit-detail-display-name");
    private final Label auditDetailAction = detailValue("audit-detail-action");
    private final Label auditDetailResult = detailValue("audit-detail-result");
    private final Label auditDetailClientAddress = detailValue("audit-detail-client-address");
    private final Label auditDetailCreatedAt = detailValue("audit-detail-created-at");
    private final GridPane auditDetails = new GridPane();
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
        feedback.setId("system-feedback");
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
        termAcademicYear.setId("term-academic-year");
        termNumber.setId("term-number");
        termAcademicYear.getStyleClass().add("system-readonly-value");
        termNumber.getStyleClass().add("system-readonly-value");
        limit(termName, 100);
        limit(selectionStart, 16);
        limit(selectionEnd, 16);
        limit(dropDeadline, 16);
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
        addRow(termForm, 1, "学年", termAcademicYear, "学期序号", termNumber);
        addRow(termForm, 2, "开始日期", startDate, "结束日期", endDate);
        addRow(termForm, 3, "选课开始", selectionStart, "选课结束", selectionEnd);
        addRow(termForm, 4, "退课截止", dropDeadline, "学期状态", termStatus);
        saveTerm.setOnAction(event -> submitTerm());
        VBox termCard = card("学期设置", "只编辑已经存在的学期，不在这里新建或删除学期。", termForm, saveTerm);

        GridPane periods = formGrid();
        for (int number = 1; number <= 12; number++) {
            TextField start = new TextField();
            TextField end = new TextField();
            start.setPromptText("HH:mm");
            end.setPromptText("HH:mm");
            limit(start, 5);
            limit(end, 5);
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
        auditUser.setId("audit-user");
        auditAction.setId("audit-action");
        auditResult.setId("audit-result");
        auditUser.setPromptText("用户名或姓名");
        limit(auditUser, 100);
        auditAction.setEditable(false);
        auditAction.getItems().setAll(labels(AUDIT_ACTION_FILTERS));
        auditAction.setValue("全部动作");
        auditResult.setEditable(false);
        auditResult.getItems().setAll(labels(AUDIT_RESULT_FILTERS));
        auditResult.setValue("全部结果");
        Button reset = new Button("重置");
        reset.setId("audit-reset");
        auditSearch.setOnAction(event -> submitAudit(1));
        reset.setOnAction(event -> {
            auditUser.clear(); auditAction.setValue("全部动作"); auditResult.setValue("全部结果");
            auditFrom.setValue(null); auditTo.setValue(null); submitAudit(1);
        });
        GridPane filters = formGrid();
        addRow(filters, 0, "用户", auditUser, "动作模块", auditAction);
        addRow(filters, 1, "结果", auditResult, "开始日期", auditFrom);
        filters.add(new Label("结束日期"), 0, 2);
        filters.add(auditTo, 1, 2);
        filters.add(new HBox(8, auditSearch, reset), 3, 2);
        buildAuditColumns();
        auditTable.setId("audit-table");
        auditTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> showAuditDetails(newValue));
        VBox.setVgrow(auditTable, Priority.ALWAYS);
        auditDetails.setId("audit-details");
        auditDetails.getStyleClass().add("audit-detail-grid");
        for (int column = 0; column < 4; column++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setPercentWidth(25);
            constraints.setHgrow(Priority.ALWAYS);
            constraints.setFillWidth(true);
            constraints.setMinWidth(110);
            auditDetails.getColumnConstraints().add(constraints);
        }
        auditDetails.add(detailItem("审计编号", auditDetailId), 0, 0);
        auditDetails.add(detailItem("用户编号", auditDetailUserId), 1, 0);
        auditDetails.add(detailItem("用户名", auditDetailUsername), 2, 0);
        auditDetails.add(detailItem("显示名", auditDetailDisplayName), 3, 0);
        auditDetails.add(detailItem("动作", auditDetailAction), 0, 1);
        auditDetails.add(detailItem("结果", auditDetailResult), 1, 1);
        auditDetails.add(detailItem("客户端地址", auditDetailClientAddress), 2, 1);
        auditDetails.add(detailItem("创建时间", auditDetailCreatedAt), 3, 1);
        auditDetailAction.setTooltip(new Tooltip("—"));
        showAuditDetails(null);
        previous.setOnAction(event -> submitAudit(Math.max(1, currentPage - 1)));
        next.setOnAction(event -> submitAudit(currentPage + 1));
        HBox pager = new HBox(10, previous, auditPage, next);
        pager.setAlignment(Pos.CENTER_RIGHT);
        VBox box = new VBox(12, card("筛选条件", "统一审计页为只读查询。", filters), auditTable,
                card("记录详情", "选中一行后显示全部原始字段。", auditDetails), pager);
        box.setPadding(new Insets(14));
        return box;
    }

    private void buildAuditColumns() {
        auditTable.getColumns().setAll(List.of(column("时间", 170, row -> DATE_TIME.format(
                        LocalDateTime.ofInstant(row.createdAt(), CAMPUS_ZONE))),
                column("用户", 130, row -> row.displayName().isBlank() ? row.username() : row.displayName()),
                column("动作", 210, AuditLogEntry::actionCode),
                column("结果", 110, row -> auditLabel(AUDIT_RESULT_FILTERS, row.resultCode())),
                column("客户端地址", 130, AuditLogEntry::clientAddress)));
    }

    private TableColumn<AuditLogEntry, String> column(String title, double width,
                                                       java.util.function.Function<AuditLogEntry, String> value) {
        TableColumn<AuditLogEntry, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setMinWidth(width);
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
            LocalTime previousEnd = null;
            for (int index = 0; index < 12; index++) {
                String start = periodStarts.get(index).getText().trim();
                String end = periodEnds.get(index).getText().trim();
                ClassPeriodTime period = new ClassPeriodTime(index + 1, parseTime(start), parseTime(end));
                if (previousEnd != null && period.startTime() != null
                        && period.startTime().isBefore(previousEnd)) {
                    throw new IllegalArgumentException("相邻节次时间不能重叠");
                }
                if (period.endTime() != null) previousEnd = period.endTime();
                periods.add(period);
            }
            listener.savePeriods(List.copyOf(periods));
        } catch (RuntimeException exception) {
            showError(exception.getMessage() == null ? "节次时间格式不正确" : exception.getMessage());
        }
    }

    private void submitAudit(int page) {
        if (listener == null) return;
        try {
            listener.searchAudit(new SystemData.AuditQuery(auditUser.getText(), auditCode(AUDIT_ACTION_FILTERS, auditAction.getValue()),
                    auditCode(AUDIT_RESULT_FILTERS, auditResult.getValue()), auditFrom.getValue(), auditTo.getValue(), page));
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
        termAcademicYear.setText(term.academicYear());
        termNumber.setText("第 " + term.termNumber() + " 学期");
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
        auditTable.getSelectionModel().clearSelection();
        showAuditDetails(null);
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

    private void showAuditDetails(AuditLogEntry row) {
        if (row == null) {
            setAuditDetails("—", "—", "—", "—", "—", "—", "—", "—");
            return;
        }
        setAuditDetails(Long.toString(row.id()),
                row.userId() == null ? "—" : Long.toString(row.userId()),
                display(row.username()), display(row.displayName()),
                display(row.actionCode()), auditLabel(AUDIT_RESULT_FILTERS, row.resultCode()),
                display(row.clientAddress()), row.createdAt().toString());
    }

    private static List<String> labels(List<AuditFilter> filters) {
        return filters.stream().map(AuditFilter::label).toList();
    }

    private static String auditCode(List<AuditFilter> filters, String label) {
        return filters.stream().filter(filter -> filter.label().equals(label)).map(AuditFilter::code)
                .findFirst().orElse("");
    }

    private static String auditLabel(List<AuditFilter> filters, String code) {
        if (code == null || code.isBlank()) return "—";
        return filters.stream().filter(filter -> filter.code().equals(code)).map(AuditFilter::label)
                .findFirst().orElse(code);
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private void setAuditDetails(String id, String userId, String username, String displayName,
                                 String action, String result, String clientAddress, String createdAt) {
        auditDetailId.setText(id);
        auditDetailUserId.setText(userId);
        auditDetailUsername.setText(username);
        auditDetailDisplayName.setText(displayName);
        auditDetailAction.setText(action);
        auditDetailResult.setText(result);
        auditDetailClientAddress.setText(clientAddress);
        auditDetailCreatedAt.setText(createdAt);
        auditDetailAction.getTooltip().setText(action);
    }

    private static void limit(TextInputControl control, int maximum) {
        control.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().length() <= maximum ? change : null));
    }

    private static Button primary(String text, String id) {
        Button button = new Button(text);
        button.setId(id);
        button.getStyleClass().add("system-primary");
        return button;
    }

    private static Label detailValue(String id) {
        Label value = new Label("—");
        value.setId(id);
        value.setWrapText(true);
        value.setMaxWidth(Double.MAX_VALUE);
        value.setMinWidth(0);
        value.getStyleClass().add("audit-detail-value");
        return value;
    }

    private static VBox detailItem(String caption, Label value) {
        Label title = new Label(caption);
        title.getStyleClass().add("audit-detail-caption");
        VBox item = new VBox(4, title, value);
        item.setMinWidth(0);
        item.setMaxWidth(Double.MAX_VALUE);
        item.getStyleClass().add("audit-detail-item");
        GridPane.setHgrow(item, Priority.ALWAYS);
        return item;
    }

    private record AuditFilter(String code, String label) { }

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
