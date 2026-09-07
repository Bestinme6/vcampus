package com.vcampus.client.fx.academic;

import com.vcampus.common.model.AcademicTermStatus;
import com.vcampus.common.model.CourseSectionStatus;
import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeacherWorkspaceNavigationTest {
    @BeforeAll
    static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException started) {
            ready.countDown();
        }
        assertTrue(ready.await(15, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }

    @Test
    void teacherRoutesDisplayThreeDistinctPages() throws Exception {
        fx(() -> {
            TeacherWorkspaceView view = new TeacherWorkspaceView(listener(new AtomicReference<>()));
            new Scene(view, 1000, 720);

            view.open("teacher-schedule");
            Node schedule = view.getCenter();
            assertEquals("academic-teacher-schedule-page", schedule.getId());
            assertTrue(view.lookup("#academic-week").isVisible());

            view.open("teaching-sections");
            Node sections = view.getCenter();
            assertEquals("academic-teaching-sections-page", sections.getId());
            assertFalse(view.lookup("#academic-week").isVisible());

            view.open("gradebook");
            Node grades = view.getCenter();
            assertEquals("academic-gradebook-page", grades.getId());
            assertNotSame(schedule, sections);
            assertNotSame(sections, grades);
            return null;
        });
    }

    @Test
    void teacherScheduleUsesTheSameRichCourseCardsAsStudentSchedule() throws Exception {
        fx(() -> {
            TeacherWorkspaceView view = new TeacherWorkspaceView(listener(new AtomicReference<>()));
            new Scene(view, 1000, 720);
            AcademicData.ScheduleEntry entry = scheduleEntry();

            view.showSchedule(List.of(entry));

            VBox schedulePage = (VBox) view.getCenter();
            ScheduleGrid grid = (ScheduleGrid) ((ScrollPane) schedulePage.getChildren().getFirst()).getContent();
            Label card = (Label) grid.getChildren().stream()
                    .filter(node -> "academic-course-card-301-1-1".equals(node.getId()))
                    .findFirst().orElseThrow();
            assertTrue(card.getText().contains("面向对象程序设计"));
            assertTrue(card.getText().contains("3.5 学分"));
            assertTrue(card.getText().contains("张明远副教授"));
            assertTrue(card.getText().contains("教一-401"));
            @SuppressWarnings("unchecked")
            TableView<AcademicData.ScheduleEntry> today =
                    (TableView<AcademicData.ScheduleEntry>) view.lookup("#academic-teacher-today");
            assertEquals(List.of("课程", "教学班", "节次", "教室"),
                    today.getColumns().stream().map(TableColumn::getText).toList());
            return null;
        });
    }

    @Test
    void gradebookActionEnablesAfterSelectingASection() throws Exception {
        fx(() -> {
            AtomicReference<AcademicData.TeachingSection> opened = new AtomicReference<>();
            TeacherWorkspaceView view = new TeacherWorkspaceView(listener(opened));
            new Scene(view, 1000, 720);
            AcademicData.TeachingSection section = section();
            view.showData(List.of(), List.of(section));
            view.open("gradebook");

            Button open = (Button) view.lookup("#academic-open-gradebook");
            assertTrue(open.isDisabled());

            @SuppressWarnings("unchecked")
            TableView<AcademicData.TeachingSection> table =
                    (TableView<AcademicData.TeachingSection>) view.lookup("#academic-gradebook-sections");
            table.getSelectionModel().select(section);
            assertFalse(open.isDisabled());
            open.fire();
            assertEquals(section, opened.get());
            return null;
        });
    }

    @Test
    void controllerRoutesTeacherNavigationToMatchingPage() throws Exception {
        fx(() -> {
            AcademicController controller = new AcademicController(gateway(), Set.of(UserRole.TEACHER),
                    Runnable::run, () -> { }, () -> { });
            try {
                assertControllerPage(controller, "teacher-schedule", "academic-teacher-schedule-page");
                assertControllerPage(controller, "teaching-sections", "academic-teaching-sections-page");
                assertControllerPage(controller, "gradebook", "academic-gradebook-page");
            } finally {
                controller.close();
            }
            return null;
        });
    }

    @Test
    void controllerLoadsOnlyDataRequiredByTeacherRoute() throws Exception {
        assertEndpointCalls("teacher-schedule", 1, 0);
        assertEndpointCalls("teaching-sections", 0, 1);
        assertEndpointCalls("gradebook", 0, 1);
    }

    @Test
    void returningFromGradeEditorKeepsGradebookRoute() throws Exception {
        ManualExecutor worker = new ManualExecutor();
        AcademicGateway gateway = gateway((proxy, method, arguments) -> switch (method.getName()) {
            case "references" -> references();
            case "teacherSections" -> List.of(section());
            case "roster" -> new AcademicData.Roster(List.of());
            default -> throw new AssertionError("成绩册返回测试不应调用：" + method.getName());
        });
        AcademicController controller = fx(() -> {
            AcademicController created = new AcademicController(gateway, Set.of(UserRole.TEACHER),
                    worker, () -> { }, () -> { });
            created.open("gradebook");
            return created;
        });
        try {
            worker.runNext();
            fx(() -> null);
            fx(() -> {
                AcademicWorkspaceView workspace = (AcademicWorkspaceView) controller.view();
                TeacherWorkspaceView teacher = (TeacherWorkspaceView) workspace.getCenter();
                TableView<AcademicData.TeachingSection> table = gradebookTable(teacher);
                table.getSelectionModel().selectFirst();
                ((Button) teacher.lookup("#academic-open-gradebook")).fire();
                assertTrue(workspace.getCenter() instanceof GradeEditorView);
                return null;
            });
            worker.runNext();
            fx(() -> null);
            fx(() -> {
                controller.backToTeacher();
                return null;
            });
            worker.runNext();
            fx(() -> null);
            fx(() -> {
                assertEquals("gradebook", controller.activeRoute());
                assertCurrentControllerPage(controller, "academic-gradebook-page");
                AcademicWorkspaceView workspace = (AcademicWorkspaceView) controller.view();
                TeacherWorkspaceView teacher = (TeacherWorkspaceView) workspace.getCenter();
                assertEquals(List.of(section()), gradebookTable(teacher).getItems());
                return null;
            });
        } finally {
            fx(() -> {
                controller.close();
                return null;
            });
        }
    }

    @Test
    void retryKeepsFailedGradebookRoute() throws Exception {
        ManualExecutor worker = new ManualExecutor();
        AtomicInteger attempts = new AtomicInteger();
        AcademicGateway failing = gateway((proxy, method, arguments) -> {
            if ("references".equals(method.getName())) {
                if (attempts.incrementAndGet() == 1) {
                    throw new IOException("forced teacher load failure");
                }
                return references();
            }
            if ("teacherSections".equals(method.getName())) return List.of(section());
            throw new AssertionError("成绩册重试不应调用：" + method.getName());
        });
        AcademicController controller = fx(() -> {
            AcademicController created = new AcademicController(failing, Set.of(UserRole.TEACHER),
                    worker, () -> { }, () -> { });
            created.open("gradebook");
            return created;
        });
        try {
            worker.runNext();
            fx(() -> null);
            fx(() -> {
                AcademicWorkspaceView workspace = (AcademicWorkspaceView) controller.view();
                ((Button) workspace.lookup("#academic-retry")).fire();
                return null;
            });
            worker.runNext();
            fx(() -> null);
            fx(() -> {
                assertEquals(2, attempts.get());
                assertEquals("gradebook", controller.activeRoute());
                AcademicWorkspaceView workspace = (AcademicWorkspaceView) controller.view();
                TeacherWorkspaceView teacher = (TeacherWorkspaceView) workspace.getCenter();
                assertEquals("academic-gradebook-page", teacher.getCenter().getId());
                assertEquals(List.of(section()), gradebookTable(teacher).getItems());
                return null;
            });
        } finally {
            fx(() -> {
                controller.close();
                return null;
            });
        }
    }

    private static void assertControllerPage(AcademicController controller, String route, String pageId) {
        controller.open(route);
        assertCurrentControllerPage(controller, pageId);
    }

    private static void assertCurrentControllerPage(AcademicController controller, String pageId) {
        AcademicWorkspaceView workspace = (AcademicWorkspaceView) controller.view();
        TeacherWorkspaceView teacher = (TeacherWorkspaceView) workspace.getCenter();
        assertEquals(pageId, teacher.getCenter().getId());
    }

    @SuppressWarnings("unchecked")
    private static TableView<AcademicData.TeachingSection> gradebookTable(TeacherWorkspaceView view) {
        return (TableView<AcademicData.TeachingSection>) view.lookup("#academic-gradebook-sections");
    }

    private static void assertEndpointCalls(String route, int expectedSchedule, int expectedSections)
            throws Exception {
        AtomicInteger scheduleCalls = new AtomicInteger();
        AtomicInteger sectionCalls = new AtomicInteger();
        AcademicGateway gateway = gateway((proxy, method, arguments) -> switch (method.getName()) {
            case "references" -> references();
            case "teacherSchedule" -> {
                scheduleCalls.incrementAndGet();
                yield List.of();
            }
            case "teacherSections" -> {
                sectionCalls.incrementAndGet();
                yield List.of();
            }
            default -> throw new AssertionError("教师导航不应调用：" + method.getName());
        });
        ManualExecutor worker = new ManualExecutor();
        AcademicController controller = fx(() -> {
            AcademicController created = new AcademicController(gateway, Set.of(UserRole.TEACHER),
                    worker, () -> { }, () -> { });
            created.open(route);
            return created;
        });
        try {
            worker.runNext();
            assertEquals(expectedSchedule, scheduleCalls.get(), route);
            assertEquals(expectedSections, sectionCalls.get(), route);
        } finally {
            fx(() -> {
                controller.close();
                return null;
            });
        }
    }

    private static AcademicGateway gateway() {
        return gateway((proxy, method, arguments) -> switch (method.getName()) {
                    case "references" -> references();
                    case "teacherSchedule", "teacherSections" -> List.of();
                    case "roster" -> new AcademicData.Roster(List.of());
                    default -> throw new AssertionError("教师导航不应调用：" + method.getName());
                });
    }

    private static AcademicGateway gateway(java.lang.reflect.InvocationHandler handler) {
        return (AcademicGateway) Proxy.newProxyInstance(AcademicGateway.class.getClassLoader(),
                new Class<?>[]{AcademicGateway.class}, handler);
    }

    private static AcademicData.ReferenceData references() {
        return new AcademicData.ReferenceData(List.of(new AcademicData.Term(
                1, "2026 秋", AcademicTermStatus.IN_PROGRESS,
                LocalDate.of(2026, 9, 1), LocalDate.of(2027, 1, 20))), List.of(), List.of());
    }

    private static TeacherWorkspaceView.Listener listener(
            AtomicReference<AcademicData.TeachingSection> opened) {
        return new TeacherWorkspaceView.Listener() {
            @Override public void loadTeacherWorkspace(long termId) { }
            @Override public void openGradebook(AcademicData.TeachingSection section) {
                opened.set(section);
            }
        };
    }

    private static AcademicData.TeachingSection section() {
        return new AcademicData.TeachingSection(301, 1, "2026 秋", 101, "CS2101",
                "面向对象程序设计", new BigDecimal("3.5"), "01 班", 201, "张老师",
                40, 3, CourseSectionStatus.OPEN, false, "周一 1—2 节 / 1—16 周", "教一-401",
                null, null);
    }

    private static AcademicData.ScheduleEntry scheduleEntry() {
        return new AcademicData.ScheduleEntry(301, 1, "2026 秋", "CS2101",
                "面向对象程序设计", new BigDecimal("3.5"), "01 班",
                "张明远副教授", new com.vcampus.common.model.ScheduleSlot(
                        1, 1, 2, 1, 16, "教一-401"));
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            Runnable task = tasks.remove();
            task.run();
        }
    }
}
