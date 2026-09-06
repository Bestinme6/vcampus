package com.vcampus.client.fx.academic;

import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcademicWorkspaceNavigationTest {
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
    void studentDoesNotSeeAdministrationRoutes() {
        assertEquals(Set.of("enrollment", "my-courses", "student-schedule", "grades"),
                AcademicWorkspaceView.routesFor(Set.of(UserRole.STUDENT)));
    }

    @Test
    void multiRoleNavigationUsesStudentTeacherAdministratorOrder() {
        assertEquals(List.of("enrollment", "my-courses", "student-schedule", "grades",
                        "teacher-schedule", "teaching-sections", "gradebook",
                        "courses", "curricula", "sections", "scheduling", "publishing"),
                List.copyOf(AcademicWorkspaceView.routesFor(Set.of(
                        UserRole.STUDENT, UserRole.TEACHER, UserRole.ACADEMIC_ADMIN))));
    }

    @Test
    void unavailableRouteIsRejectedWithoutCreatingItsControl() throws Exception {
        fx(() -> {
            List<String> requested = new ArrayList<>();
            AcademicWorkspaceView view = new AcademicWorkspaceView(
                    Set.of(UserRole.STUDENT), () -> { }, requested::add);
            new Scene(view, 1000, 720);
            view.applyCss();
            view.layout();

            assertNull(view.lookup("#academic-route-courses"));
            assertFalse(view.open("courses"));
            assertEquals("overview", view.activeRoute());
            assertTrue(requested.isEmpty());
            assertNotNull(view.lookup("#academic-failure"));

            ((Button) view.lookup("#academic-route-enrollment")).fire();
            assertEquals(List.of("enrollment"), requested);
            return null;
        });
    }

    @Test
    void sharedFailureStateInvokesRetry() throws Exception {
        fx(() -> {
            AtomicInteger retries = new AtomicInteger();
            AcademicWorkspaceView view = new AcademicWorkspaceView(
                    Set.of(UserRole.STUDENT), () -> { }, route -> { });
            new Scene(view, 1000, 720);
            view.showFailure("网络暂不可用", retries::incrementAndGet);

            ((Button) view.lookup("#academic-retry")).fire();
            assertEquals(1, retries.get());
            return null;
        });
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }
}
