package com.vcampus.client.fx;

import com.vcampus.common.model.AuditLogEntry;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Collectors;

final class AccountSecurityView extends BorderPane {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"));

    @FunctionalInterface
    interface Listener { void changePassword(char[] current, char[] next); }

    private final Label username = value("security-username");
    private final Label displayName = value("security-display-name");
    private final Label roles = value("security-roles");
    private final Label expiresAt = value("security-expires-at");
    private final FxPasswordInput current = new FxPasswordInput("security-current-password");
    private final FxPasswordInput next = new FxPasswordInput("security-new-password");
    private final FxPasswordInput repeat = new FxPasswordInput("security-repeat-password");
    private final Label feedback = FxStyles.label("新密码长度为 8—128 位", "muted");
    private final Button submit;
    private final TableView<AuditLogEntry> activities = new TableView<>();
    private Listener listener;

    AccountSecurityView(Runnable back) {
        setId("account-security-view");
        getStyleClass().add("security-page");
        FxStyles.install(this);
        submit = FxStyles.button("修改密码", Feather.LOCK, this::submit, "primary");
        submit.setId("security-change-password");
        feedback.setId("security-feedback");
        configureActivities();

        VBox content = new VBox(18, header(back), accountCard(), passwordCard(), activityCard());
        content.getStyleClass().add("security-content");
        content.setMaxWidth(1080);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("security-scroll");
        setCenter(scroll);
    }

    void setListener(Listener listener) { this.listener = listener; }
    String usernameText() { return username.getText(); }

    void show(AccountSecurityData.Summary summary) {
        username.setText(summary.username());
        displayName.setText(summary.displayName());
        roles.setText(summary.roles().stream().sorted(Comparator.comparing(Enum::ordinal))
                .map(AccountSecurityView::roleLabel).collect(Collectors.joining("、")));
        expiresAt.setText(TIME.format(summary.expiresAt()));
        activities.setItems(FXCollections.observableArrayList(summary.activities()));
        setBusy(false);
        feedback.getStyleClass().remove("error");
        if (!feedback.getStyleClass().contains("muted")) feedback.getStyleClass().add("muted");
        feedback.setText("新密码长度为 8—128 位");
    }

    void setBusy(boolean busy) {
        current.setDisable(busy);
        next.setDisable(busy);
        repeat.setDisable(busy);
        submit.setDisable(busy);
        if (busy) feedback.setText("正在处理…");
    }

    void showError(String message) {
        setBusy(false);
        feedback.getStyleClass().remove("muted");
        if (!feedback.getStyleClass().contains("error")) feedback.getStyleClass().add("error");
        feedback.setText(message == null || message.isBlank() ? "请求失败" : message);
    }

    private HBox header(Runnable back) {
        VBox titles = new VBox(5, FxStyles.label("账号安全", "page-title"),
                FxStyles.label("管理登录密码并查看近期安全活动", "muted"));
        Region spacer = FxStyles.spacer();
        Button button = FxStyles.button("返回工作台", Feather.ARROW_LEFT, back, "quiet");
        button.setId("security-back");
        HBox row = new HBox(16, titles, spacer, button);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private VBox accountCard() {
        GridPane grid = new GridPane();
        grid.setHgap(30);
        grid.setVgap(14);
        grid.add(detail("账号", username), 0, 0);
        grid.add(detail("姓名", displayName), 1, 0);
        grid.add(detail("身份与权限", roles), 0, 1);
        grid.add(detail("当前会话有效期至", expiresAt), 1, 1);
        GridPane.setHgrow(grid.getChildren().get(0), Priority.ALWAYS);
        GridPane.setHgrow(grid.getChildren().get(1), Priority.ALWAYS);
        return card(FxStyles.label("账号概览", "section-title"), grid);
    }

    private VBox passwordCard() {
        GridPane form = new GridPane();
        form.setHgap(14);
        form.setVgap(8);
        addPassword(form, 0, "当前密码", current);
        addPassword(form, 1, "新密码", next);
        addPassword(form, 2, "确认新密码", repeat);
        HBox actions = new HBox(14, submit, feedback);
        actions.setAlignment(Pos.CENTER_LEFT);
        return card(FxStyles.label("修改密码", "section-title"),
                FxStyles.label("修改成功后将退出当前账号，请使用新密码重新登录。", "muted"), form, actions);
    }

    private VBox activityCard() {
        VBox box = card(FxStyles.label("最近安全活动", "section-title"),
                FxStyles.label("仅展示本账号最近 5 条登录、退出和修改密码记录。", "muted"), activities);
        VBox.setVgrow(activities, Priority.ALWAYS);
        return box;
    }

    private void submit() {
        char[] old = current.value();
        char[] fresh = next.value();
        char[] confirmation = repeat.value();
        try {
            Optional<String> violation = AccountSecurityForm.validate(old, fresh, confirmation);
            if (violation.isPresent()) {
                showError(violation.get());
                return;
            }
            if (listener != null) listener.changePassword(
                    Arrays.copyOf(old, old.length), Arrays.copyOf(fresh, fresh.length));
            current.clear();
            next.clear();
            repeat.clear();
        } finally {
            Arrays.fill(old, '\0');
            Arrays.fill(fresh, '\0');
            Arrays.fill(confirmation, '\0');
        }
    }

    private void configureActivities() {
        activities.setId("security-activity-table");
        activities.setPrefHeight(240);
        activities.setPlaceholder(FxStyles.label("暂无安全活动", "muted"));
        activities.getColumns().add(column("时间", 180, row -> TIME.format(row.createdAt())));
        activities.getColumns().add(column("操作", 160, row -> actionLabel(row.actionCode())));
        activities.getColumns().add(column("结果", 100, row -> resultLabel(row.resultCode())));
        activities.getColumns().add(column("登录地址", 180,
                row -> row.clientAddress().isBlank() ? "—" : row.clientAddress()));
        activities.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private TableColumn<AuditLogEntry, String> column(
            String title, double width, java.util.function.Function<AuditLogEntry, String> value) {
        TableColumn<AuditLogEntry, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        return column;
    }

    private static void addPassword(GridPane grid, int column, String name, FxPasswordInput input) {
        Label label = FxStyles.label(name, "field-label");
        grid.add(label, column, 0);
        grid.add(input, column, 1);
        GridPane.setHgrow(input, Priority.ALWAYS);
    }

    private static VBox detail(String name, Label value) {
        VBox box = new VBox(5, FxStyles.label(name, "helper"), value);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private static Label value(String id) {
        Label label = new Label("—");
        label.setId(id);
        label.getStyleClass().add("security-value");
        label.setWrapText(true);
        return label;
    }

    private static VBox card(Node... children) {
        VBox card = new VBox(14, children);
        card.getStyleClass().add("security-card");
        return card;
    }

    private static String actionLabel(String action) {
        return switch (action) {
            case Actions.AUTH_LOGIN -> "登录";
            case Actions.AUTH_LOGOUT -> "退出登录";
            case Actions.AUTH_CHANGE_PASSWORD -> "修改密码";
            default -> action;
        };
    }

    private static String resultLabel(String result) {
        return switch (result) {
            case "SUCCESS" -> "成功";
            case "DENIED" -> "拒绝";
            case "DISABLED" -> "账号停用";
            case "NO_ROLE" -> "未分配角色";
            case "INVALID_ROLES" -> "角色异常";
            case "DATABASE_ERROR" -> "系统异常";
            default -> result;
        };
    }

    private static String roleLabel(UserRole role) {
        return switch (role) {
            case STUDENT -> "学生";
            case TEACHER -> "教师";
            case SUPER_ADMIN -> "超级管理员";
            case STUDENT_ADMIN -> "学籍管理员";
            case ACADEMIC_ADMIN -> "教务管理员";
            case LIBRARY_ADMIN -> "图书管理员";
            case SHOP_ADMIN -> "商店管理员";
            case BANK_ADMIN -> "银行管理员";
            case FORUM_ADMIN -> "论坛管理员";
        };
    }
}
