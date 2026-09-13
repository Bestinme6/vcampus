package com.vcampus.client.fx.academic;

import com.vcampus.common.model.AcademicAccessPolicy;
import com.vcampus.common.model.UserRole;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Role-aware native JavaFX shell shared by every academic workflow. */
public final class AcademicWorkspaceView extends BorderPane {
    private static final List<Route> STUDENT_ROUTES = List.of(
            new Route("enrollment", "选课中心"),
            new Route("my-courses", "我的课程"),
            new Route("student-schedule", "我的课表"),
            new Route("grades", "我的成绩"));
    private static final List<Route> TEACHER_ROUTES = List.of(
            new Route("teacher-schedule", "教师课表"),
            new Route("gradebook", "授课与成绩"));
    private static final List<Route> ADMIN_ROUTES = List.of(
            new Route("courses", "课程库"),
            new Route("curricula", "培养方案"),
            new Route("sections", "开课管理"),
            new Route("publishing", "排课与发布"));
    private static final List<RouteGroup> ROUTE_GROUPS = List.of(
            new RouteGroup("learning", "学习", STUDENT_ROUTES),
            new RouteGroup("teaching", "教学", TEACHER_ROUTES),
            new RouteGroup("administration", "教务", ADMIN_ROUTES));

    private final Set<String> availableRoutes;
    private final Map<String, Button> routeButtons = new LinkedHashMap<>();
    private final Map<String, Button> groupButtons = new LinkedHashMap<>();
    private final Map<String, RouteGroup> visibleGroups = new LinkedHashMap<>();
    private final Set<Dialog<?>> dialogs = new HashSet<>();
    private final Consumer<String> navigator;
    private final HBox primaryNavigation = new HBox(8);
    private final HBox navigation = new HBox(8);
    private String activeRoute = "overview";

    public AcademicWorkspaceView(Set<UserRole> roles, Runnable back, Consumer<String> navigator) {
        Objects.requireNonNull(roles, "roles");
        Objects.requireNonNull(back, "back");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        availableRoutes = routesFor(roles);
        setId("academic-workspace");
        getStyleClass().add("academic-root");
        getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("academic.css"), "academic.css").toExternalForm());

        Label eyebrow = label("VCAMPUS  /  ACADEMIC", "academic-eyebrow");
        Label title = label("教务与课程", "academic-title");
        Button backButton = button("返回工作台", "academic-back", back);
        backButton.setId("academic-back");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox heading = new HBox(18, new VBox(4, eyebrow, title), spacer, backButton);
        heading.setAlignment(Pos.CENTER_LEFT);
        primaryNavigation.setId("academic-primary-navigation");
        primaryNavigation.getStyleClass().add("academic-primary-navigation");
        navigation.setId("academic-secondary-navigation");
        navigation.getStyleClass().add("academic-navigation");
        addVisibleGroups();
        visibleGroups.keySet().stream().findFirst().ifPresent(this::showGroup);
        VBox header = new VBox(0, heading, primaryNavigation, navigation);
        header.getStyleClass().add("academic-header");
        setTop(header);

        showOverview();
    }

    public static Set<String> routesFor(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        LinkedHashSet<String> routes = new LinkedHashSet<>();
        if (AcademicAccessPolicy.canStudy(roles)) STUDENT_ROUTES.forEach(route -> routes.add(route.key()));
        if (AcademicAccessPolicy.canTeach(roles)) TEACHER_ROUTES.forEach(route -> routes.add(route.key()));
        if (AcademicAccessPolicy.canManage(roles)) ADMIN_ROUTES.forEach(route -> routes.add(route.key()));
        return Collections.unmodifiableSet(routes);
    }

    public boolean open(String route) {
        String requested = route == null || route.isBlank() || "academic".equals(route)
                ? "overview" : route;
        String resolved = canonicalRoute(requested);
        if (!"overview".equals(resolved) && !availableRoutes.contains(resolved)) {
            showFailure("当前账号没有访问该教务页面的权限", null);
            return false;
        }
        activeRoute = resolved;
        select(resolved);
        if ("overview".equals(resolved)) showOverview();
        else showLoading("正在打开" + labelFor(resolved), "请稍候，正在准备页面数据。");
        return true;
    }

    public String activeRoute() {
        return activeRoute;
    }

    Set<String> availableRoutes() {
        return availableRoutes;
    }

    void showLoading(String title, String detail) {
        VBox state = statePane("academic-loading", title, detail);
        state.getStyleClass().add("academic-loading");
        setCenter(state);
    }

    void showContent(Node content) {
        Objects.requireNonNull(content, "content");
        if (!content.getStyleClass().contains("academic-content")) {
            content.getStyleClass().add("academic-content");
        }
        setCenter(content);
    }

    void showEmpty(String title, String detail) {
        VBox state = statePane("academic-empty", title, detail);
        state.getStyleClass().add("academic-empty");
        setCenter(state);
    }

    void showFailure(String message, Runnable retry) {
        VBox state = statePane("academic-failure", "暂时无法打开", message);
        state.getStyleClass().add("academic-failure");
        if (retry != null) {
            Button retryButton = button("重新加载", "academic-primary", retry);
            retryButton.setId("academic-retry");
            state.getChildren().add(retryButton);
        }
        setCenter(state);
    }

    void busy(boolean value) {
        primaryNavigation.setDisable(value);
        navigation.setDisable(value);
        if (getCenter() != null) getCenter().setDisable(value);
    }

    <T extends Dialog<?>> T own(T dialog) {
        dialogs.add(Objects.requireNonNull(dialog, "dialog"));
        dialog.setOnHidden(event -> dialogs.remove(dialog));
        return dialog;
    }

    void closeDialogs() {
        for (Dialog<?> dialog : List.copyOf(dialogs)) dialog.close();
        dialogs.clear();
    }

    private void addVisibleGroups() {
        for (RouteGroup group : ROUTE_GROUPS) {
            List<Route> routes = group.routes().stream()
                    .filter(route -> availableRoutes.contains(route.key())).toList();
            if (routes.isEmpty()) continue;
            RouteGroup visible = new RouteGroup(group.key(), group.label(), routes);
            visibleGroups.put(group.key(), visible);
            Button groupButton = button(group.label(), "academic-group",
                    () -> navigator.accept(routes.getFirst().key()));
            groupButton.setId("academic-group-" + group.key());
            groupButtons.put(group.key(), groupButton);
            primaryNavigation.getChildren().add(groupButton);
            for (Route route : routes) {
                Button routeButton = button(route.label(), "academic-route",
                        () -> navigator.accept(route.key()));
                routeButton.setId("academic-route-" + route.key());
                routeButtons.put(route.key(), routeButton);
            }
        }
    }

    private void showGroup(String groupKey) {
        RouteGroup group = visibleGroups.get(groupKey);
        if (group == null) return;
        navigation.getChildren().setAll(group.routes().stream()
                .map(route -> routeButtons.get(route.key())).toList());
        groupButtons.forEach((key, button) -> {
            button.getStyleClass().remove("academic-group-selected");
            if (key.equals(groupKey)) button.getStyleClass().add("academic-group-selected");
        });
    }

    private void select(String route) {
        groupFor(route).ifPresent(group -> showGroup(group.key()));
        routeButtons.forEach((key, button) -> {
            button.getStyleClass().remove("academic-route-selected");
            if (key.equals(route)) button.getStyleClass().add("academic-route-selected");
        });
    }

    private void showOverview() {
        VBox cards = new VBox(14,
                label("统一管理课程、培养方案与教学安排", "academic-hero-title"),
                label("课程只创建一次；必修或选修由专业培养方案决定。学生可在课程下选择具体教学班。",
                        "academic-muted"),
                label(availableRoutes.isEmpty() ? "当前账号没有可用的教务功能。"
                        : "请从上方选择要进入的教务页面。", "academic-note"));
        cards.setId("academic-overview");
        cards.getStyleClass().add("academic-overview");
        setCenter(cards);
    }

    private String labelFor(String route) {
        return allRoutes().stream().filter(item -> item.key().equals(route))
                .map(Route::label).findFirst().orElse("教务页面");
    }

    private static List<Route> allRoutes() {
        return ROUTE_GROUPS.stream().flatMap(group -> group.routes().stream()).toList();
    }

    private static java.util.Optional<RouteGroup> groupFor(String route) {
        return ROUTE_GROUPS.stream().filter(group -> group.routes().stream()
                .anyMatch(item -> item.key().equals(route))).findFirst();
    }

    private static String canonicalRoute(String route) {
        return "teaching-sections".equals(route) ? "gradebook" : route;
    }

    private static VBox statePane(String id, String title, String detail) {
        VBox pane = new VBox(12, label(title, "academic-state-title"),
                label(Objects.requireNonNullElse(detail, ""), "academic-muted"));
        pane.setId(id);
        pane.setAlignment(Pos.CENTER);
        pane.setPadding(new Insets(50));
        return pane;
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

    private record Route(String key, String label) {
    }

    private record RouteGroup(String key, String label, List<Route> routes) {
    }
}
