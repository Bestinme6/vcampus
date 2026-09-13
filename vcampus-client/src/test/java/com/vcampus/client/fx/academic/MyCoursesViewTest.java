package com.vcampus.client.fx.academic;

import com.vcampus.common.model.AcademicTermStatus;
import com.vcampus.common.model.CourseRequirementType;
import com.vcampus.common.model.CourseSectionStatus;
import com.vcampus.common.model.EnrollmentStatus;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MyCoursesViewTest {
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
    void enrolledCoursesExcludeCatalogEntriesWithoutAnActiveEnrollment() {
        AcademicData.AvailableCourse selected = course(1, List.of(
                section(11, true, CourseSectionStatus.OPEN, false, false),
                section(12, false, CourseSectionStatus.OPEN, false, false)));
        AcademicData.AvailableCourse notSelected = course(2, List.of(
                section(21, false, CourseSectionStatus.OPEN, false, false)));

        assertEquals(List.of(selected), MyCoursesView.enrolledCourses(
                new AcademicData.EnrollmentCatalog(List.of(selected, notSelected))));
    }

    @Test
    void switchCandidatesContainOnlyOtherEligibleSections() {
        AcademicData.AvailableSection eligible = section(12, false, CourseSectionStatus.OPEN, false, false);
        AcademicData.AvailableCourse selected = course(1, List.of(
                section(11, true, CourseSectionStatus.OPEN, false, false),
                eligible,
                section(13, false, CourseSectionStatus.OPEN, true, false),
                section(14, false, CourseSectionStatus.OPEN, false, true),
                section(15, false, CourseSectionStatus.CLOSED, false, false)));

        assertEquals(List.of(eligible), MyCoursesView.switchCandidates(selected));
    }

    @Test
    void switchActionSubmitsTheCurrentAndChosenSection() throws Exception {
        RecordingListener listener = new RecordingListener();
        AcademicData.AvailableSection current = section(11, true, CourseSectionStatus.OPEN, false, false);
        AcademicData.AvailableSection alternative = section(12, false, CourseSectionStatus.OPEN, false, false);
        AcademicData.AvailableCourse selected = course(1, List.of(current, alternative));

        fx(() -> {
            MyCoursesView view = view(listener, selected);
            @SuppressWarnings("unchecked")
            ComboBox<AcademicData.AvailableSection> choices =
                    (ComboBox<AcademicData.AvailableSection>) view.lookup("#academic-my-course-switch-choice-1");
            assertEquals(List.of(alternative), choices.getItems());
            choices.getSelectionModel().selectFirst();
            ((Button) view.lookup("#academic-my-course-switch-1")).fire();

            assertEquals(List.of(1L, 11L, 12L), listener.switchCall);
            assertNull(view.lookup("#academic-my-course-enroll-1"));
            return null;
        });
    }

    @Test
    void dropActionSubmitsTheActiveEnrollmentSection() throws Exception {
        RecordingListener listener = new RecordingListener();
        AcademicData.AvailableCourse selected = course(1, List.of(
                section(11, true, CourseSectionStatus.OPEN, false, false)));

        fx(() -> {
            MyCoursesView view = view(listener, selected);
            ((Button) view.lookup("#academic-my-course-drop-1")).fire();

            assertEquals(List.of(1L, 11L), listener.dropCall);
            return null;
        });
    }

    private static MyCoursesView view(RecordingListener listener,
                                      AcademicData.AvailableCourse selected) {
        MyCoursesView view = new MyCoursesView(listener, (title, text) -> true);
        new Scene(view, 1000, 720);
        view.showTerms(List.of(new AcademicData.Term(1, "2026 秋季学期",
                AcademicTermStatus.SELECTION, LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 15))), 1);
        view.showCatalog(new AcademicData.EnrollmentCatalog(List.of(selected)));
        view.applyCss();
        view.layout();
        return view;
    }

    private static AcademicData.AvailableCourse course(long id,
                                                        List<AcademicData.AvailableSection> sections) {
        return new AcademicData.AvailableCourse(id, "C" + id, "课程" + id,
                new BigDecimal("3.0"), CourseRequirementType.REQUIRED, 1, sections);
    }

    private static AcademicData.AvailableSection section(long id, boolean enrolled,
                                                          CourseSectionStatus status,
                                                          boolean full, boolean conflict) {
        int capacity = 40;
        int enrolledCount = full ? capacity : 10;
        return new AcademicData.AvailableSection(id, 1, "2026 秋季学期", "0" + id + "班",
                100 + id, "教师" + id, capacity, enrolledCount, status, false,
                "周一 1—2 节", "教一-101", enrolled ? 900 + id : null,
                enrolled ? EnrollmentStatus.ENROLLED : null, full, conflict);
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    private static final class RecordingListener implements MyCoursesView.Listener {
        private List<Long> dropCall = List.of();
        private List<Long> switchCall = List.of();

        @Override
        public void loadMyCourses(long termId) { }

        @Override
        public void dropMyCourse(long termId, long sectionId) {
            dropCall = List.of(termId, sectionId);
        }

        @Override
        public void switchMyCourseSection(long termId, long fromSectionId, long toSectionId) {
            switchCall = List.of(termId, fromSectionId, toSectionId);
        }
    }
}
