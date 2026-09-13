package com.vcampus.client.fx.academic;

import com.vcampus.common.model.AcademicTermStatus;
import com.vcampus.common.model.CourseRequirementType;
import com.vcampus.common.model.CourseSectionStatus;
import com.vcampus.common.model.EnrollmentStatus;
import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudentCourseNavigationTest {
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
    void enrollmentAndMyCoursesOpenDifferentStudentPages() throws Exception {
        fx(() -> {
            AcademicController controller = new AcademicController(gateway(), Set.of(UserRole.STUDENT),
                    Runnable::run, () -> { }, () -> { });
            try {
                controller.open("enrollment");
                AcademicWorkspaceView workspace = (AcademicWorkspaceView) controller.view();
                Node enrollmentPage = workspace.getCenter();

                controller.open("my-courses");

                assertNotSame(enrollmentPage, workspace.getCenter());
            } finally {
                controller.close();
            }
            return null;
        });
    }

    @Test
    void myCoursesRendersOnlyCoursesWithAnActiveEnrollment() throws Exception {
        AcademicData.AvailableCourse selected = course(1, true);
        AcademicData.AvailableCourse notSelected = course(2, false);
        ManualExecutor worker = new ManualExecutor();
        AcademicController controller = fx(() -> {
            AcademicController created = new AcademicController(
                    gateway(new AcademicData.EnrollmentCatalog(List.of(selected, notSelected))),
                    Set.of(UserRole.STUDENT), worker, () -> { }, () -> { });
            new Scene(created.view(), 1000, 720);
            created.open("my-courses");
            return created;
        });
        try {
            worker.runNext();
            fx(() -> null);
            fx(() -> {
                AcademicWorkspaceView workspace = (AcademicWorkspaceView) controller.view();
                workspace.applyCss();
                workspace.layout();
                assertNotNull(workspace.lookup("#academic-my-course-1"));
                assertNull(workspace.lookup("#academic-my-course-2"));
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
    void myCoursesDropCallsTheAcademicGateway() throws Exception {
        AtomicLong droppedSection = new AtomicLong();
        AcademicGateway gateway = gateway(new AcademicData.EnrollmentCatalog(List.of()),
                (proxy, method, arguments) -> {
                    if ("drop".equals(method.getName())) {
                        droppedSection.set((long) arguments[0]);
                        return null;
                    }
                    throw new AssertionError("退课测试不应调用：" + method.getName());
                });
        ManualExecutor worker = new ManualExecutor();
        AcademicController controller = openMyCourses(gateway, worker);
        try {
            fx(() -> {
                controller.dropMyCourse(1, 11);
                return null;
            });
            worker.runNext();

            assertEquals(11, droppedSection.get());
        } finally {
            close(controller);
        }
    }

    @Test
    void myCoursesSwitchCallsTheAcademicGatewayWithBothSections() throws Exception {
        AtomicReference<List<Long>> switchedSections = new AtomicReference<>(List.of());
        AcademicGateway gateway = gateway(new AcademicData.EnrollmentCatalog(List.of()),
                (proxy, method, arguments) -> {
                    if ("switchSection".equals(method.getName())) {
                        switchedSections.set(List.of((long) arguments[0], (long) arguments[1]));
                        return null;
                    }
                    throw new AssertionError("换班测试不应调用：" + method.getName());
                });
        ManualExecutor worker = new ManualExecutor();
        AcademicController controller = openMyCourses(gateway, worker);
        try {
            fx(() -> {
                controller.switchMyCourseSection(1, 11, 12);
                return null;
            });
            worker.runNext();

            assertEquals(List.of(11L, 12L), switchedSections.get());
        } finally {
            close(controller);
        }
    }

    private static AcademicGateway gateway() {
        return gateway(new AcademicData.EnrollmentCatalog(List.of()));
    }

    private static AcademicGateway gateway(AcademicData.EnrollmentCatalog catalog) {
        return gateway(catalog, (proxy, method, arguments) -> {
            throw new AssertionError("学生课程导航不应调用：" + method.getName());
        });
    }

    private static AcademicGateway gateway(AcademicData.EnrollmentCatalog catalog,
                                           InvocationHandler extra) {
        return (AcademicGateway) Proxy.newProxyInstance(AcademicGateway.class.getClassLoader(),
                new Class<?>[]{AcademicGateway.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "references" -> new AcademicData.ReferenceData(List.of(
                            new AcademicData.Term(1, "2026 秋季学期", AcademicTermStatus.SELECTION,
                                    LocalDate.of(2026, 9, 1), LocalDate.of(2027, 1, 15))),
                            List.of(), List.of(), List.of());
                    case "enrollmentCatalog" -> catalog;
                    default -> extra.invoke(proxy, method, arguments);
                });
    }

    private static AcademicController openMyCourses(AcademicGateway gateway, ManualExecutor worker)
            throws Exception {
        AcademicController controller = fx(() -> {
            AcademicController created = new AcademicController(gateway, Set.of(UserRole.STUDENT),
                    worker, () -> { }, () -> { });
            created.open("my-courses");
            return created;
        });
        worker.runNext();
        fx(() -> null);
        return controller;
    }

    private static void close(AcademicController controller) throws Exception {
        fx(() -> {
            controller.close();
            return null;
        });
    }

    private static AcademicData.AvailableCourse course(long id, boolean enrolled) {
        AcademicData.AvailableSection section = new AcademicData.AvailableSection(
                10 + id, 1, "2026 秋季学期", "0" + id + "班", 100 + id, "教师" + id,
                40, 10, CourseSectionStatus.OPEN, false, "周一 1—2 节", "教一-101",
                enrolled ? 900 + id : null, enrolled ? EnrollmentStatus.ENROLLED : null,
                false, false);
        return new AcademicData.AvailableCourse(id, "C" + id, "课程" + id,
                new BigDecimal("3.0"), CourseRequirementType.REQUIRED, 1, List.of(section));
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            tasks.remove().run();
        }
    }
}
