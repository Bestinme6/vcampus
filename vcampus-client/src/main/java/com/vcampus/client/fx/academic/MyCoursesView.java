package com.vcampus.client.fx.academic;

import com.vcampus.common.model.CourseSectionStatus;
import com.vcampus.common.model.EnrollmentStatus;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.collections.FXCollections;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.List;
import java.util.Objects;

/** Student workspace for enrolled courses and enrollment maintenance. */
final class MyCoursesView extends BorderPane {
    interface Listener {
        void loadMyCourses(long termId);

        void dropMyCourse(long termId, long sectionId);

        void switchMyCourseSection(long termId, long fromSectionId, long toSectionId);
    }

    @FunctionalInterface
    interface Confirmation {
        boolean confirm(String title, String text);
    }

    private final Listener listener;
    private final Confirmation confirmation;
    private final ComboBox<AcademicData.Term> term = new ComboBox<>();
    private final VBox courses = new VBox(12);
    private final Label notice = label("", "academic-notice");
    private boolean updatingTerms;

    MyCoursesView(Listener listener) {
        this(listener, MyCoursesView::confirm);
    }

    MyCoursesView(Listener listener, Confirmation confirmation) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.confirmation = Objects.requireNonNull(confirmation, "confirmation");
        setId("academic-my-courses-page");
        getStyleClass().add("academic-my-courses");
        setPadding(new Insets(24));
        term.setConverter(converter(AcademicData.Term::name));
        term.setOnAction(event -> {
            if (!updatingTerms && term.getValue() != null) {
                listener.loadMyCourses(term.getValue().id());
            }
        });
        Button refresh = button("刷新", "academic-secondary", () -> {
            if (term.getValue() != null) listener.loadMyCourses(term.getValue().id());
        });
        HBox tools = new HBox(8, term, refresh);
        tools.setAlignment(Pos.CENTER_LEFT);
        notice.setManaged(false);
        notice.setVisible(false);
        setTop(new VBox(8, label("我的课程", "academic-page-title"),
                label("这里只显示已经选中的课程；你可以退课或更换同一课程的教学班。", "academic-muted"),
                tools, notice));

        ScrollPane scroll = new ScrollPane(courses);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        setCenter(scroll);
    }

    static List<AcademicData.AvailableCourse> enrolledCourses(AcademicData.EnrollmentCatalog catalog) {
        return catalog.courses().stream()
                .filter(course -> course.sections().stream()
                        .anyMatch(section -> section.ownEnrollmentStatus() == EnrollmentStatus.ENROLLED))
                .toList();
    }

    static List<AcademicData.AvailableSection> switchCandidates(AcademicData.AvailableCourse course) {
        return course.sections().stream()
                .filter(section -> section.ownEnrollmentStatus() != EnrollmentStatus.ENROLLED)
                .filter(section -> section.status() == CourseSectionStatus.OPEN)
                .filter(section -> !section.full() && !section.scheduleConflict())
                .toList();
    }

    void showTerms(List<AcademicData.Term> terms, long selectedTermId) {
        updatingTerms = true;
        try {
            term.getItems().setAll(terms);
            terms.stream().filter(item -> item.id() == selectedTermId).findFirst().ifPresent(term::setValue);
            if (term.getValue() == null && !terms.isEmpty()) term.getSelectionModel().selectFirst();
        } finally {
            updatingTerms = false;
        }
    }

    void showCatalog(AcademicData.EnrollmentCatalog catalog) {
        courses.getChildren().clear();
        List<AcademicData.AvailableCourse> enrolled = enrolledCourses(catalog);
        for (AcademicData.AvailableCourse course : enrolled) courses.getChildren().add(courseCard(course));
        if (enrolled.isEmpty()) {
            courses.getChildren().add(label("当前学期还没有已选课程，请先到“选课中心”选择教学班。",
                    "academic-muted"));
        }
    }

    void message(String text, boolean error) {
        notice.setText(Objects.requireNonNullElse(text, ""));
        notice.setManaged(!notice.getText().isBlank());
        notice.setVisible(!notice.getText().isBlank());
        notice.getStyleClass().remove("academic-form-error");
        if (error) notice.getStyleClass().add("academic-form-error");
    }

    void busy(boolean value) {
        setDisable(value);
    }

    private VBox courseCard(AcademicData.AvailableCourse course) {
        AcademicData.AvailableSection current = course.sections().stream()
                .filter(section -> section.ownEnrollmentStatus() == EnrollmentStatus.ENROLLED)
                .findFirst().orElseThrow();
        ComboBox<AcademicData.AvailableSection> alternatives = new ComboBox<>(
                FXCollections.observableArrayList(switchCandidates(course)));
        alternatives.setId("academic-my-course-switch-choice-" + course.courseId());
        alternatives.setPromptText("暂无可换教学班");
        alternatives.setConverter(converter(section -> section.sectionCode() + " · "
                + section.teacherName() + " · " + empty(section.scheduleSummary())));
        alternatives.setMaxWidth(Double.MAX_VALUE);
        if (!alternatives.getItems().isEmpty()) alternatives.getSelectionModel().selectFirst();

        Button switchButton = button("更换教学班", "academic-primary", () -> {
            AcademicData.AvailableSection candidate = alternatives.getValue();
            if (candidate != null && confirmation.confirm("更换教学班",
                    "将从 “" + current.sectionCode() + "” 原子切换到 “" + candidate.sectionCode()
                            + "”。若新班不可用，原选课保持不变。")) {
                listener.switchMyCourseSection(term.getValue().id(), current.sectionId(),
                        candidate.sectionId());
            }
        });
        switchButton.setId("academic-my-course-switch-" + course.courseId());
        switchButton.setDisable(alternatives.getItems().isEmpty());

        Button dropButton = button("退选当前教学班", "academic-secondary", () -> {
            if (confirmation.confirm("退选课程", "确认退选 “" + course.courseName() + " · "
                    + current.sectionCode() + "” ？")) {
                listener.dropMyCourse(term.getValue().id(), current.sectionId());
            }
        });
        dropButton.setId("academic-my-course-drop-" + course.courseId());

        HBox actions = new HBox(8, alternatives, switchButton, dropButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(alternatives, javafx.scene.layout.Priority.ALWAYS);

        VBox card = new VBox(8,
                label(course.courseCode() + " · " + course.courseName(), "academic-state-title"),
                label(course.requirementType().displayName() + " · "
                        + course.credits().stripTrailingZeros().toPlainString() + " 学分", "academic-note"),
                label("当前教学班：" + current.sectionCode(), "academic-muted"),
                label("授课教师：" + current.teacherName(), "academic-muted"),
                label("上课时间：" + empty(current.scheduleSummary()), "academic-muted"),
                label("教室：" + empty(current.classroomSummary()), "academic-muted"), actions);
        card.setId("academic-my-course-" + course.courseId());
        card.getStyleClass().add("academic-my-course-card");
        return card;
    }

    private static String empty(String value) {
        return value == null || value.isBlank() ? "待发布" : value;
    }

    private static <T> StringConverter<T> converter(java.util.function.Function<T, String> label) {
        return new StringConverter<>() {
            @Override public String toString(T value) { return value == null ? "" : label.apply(value); }
            @Override public T fromString(String string) { throw new UnsupportedOperationException(); }
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

    private static boolean confirm(String title, String text) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle(title);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }
}
