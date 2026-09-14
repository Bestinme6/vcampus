package com.vcampus.client.fx.academic;

import com.vcampus.common.model.CourseRequirementType;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurriculumPlanFormTest {
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
    void batchRequirementChangePreservesSuggestedTerms() {
        List<AcademicData.CurriculumCourse> rows = List.of(
                course(1, CourseRequirementType.REQUIRED, 3),
                course(2, CourseRequirementType.REQUIRED, 5));

        List<AcademicCommands.CurriculumCourseDraft> changed = CurriculumPlanForm.setRequirement(
                rows, Set.of(1L), CourseRequirementType.ELECTIVE);

        assertEquals(1, changed.size());
        assertEquals(rows.getFirst().recommendedTermNumber(),
                changed.getFirst().recommendedTermNumber());
        assertEquals(CourseRequirementType.ELECTIVE, changed.getFirst().requirementType());
        assertTrue(changed.getFirst().alreadyInPlan());
    }

    @Test
    void batchSuggestedTermPreservesRequirementType() {
        List<AcademicData.CurriculumCourse> rows = List.of(
                course(1, CourseRequirementType.REQUIRED, 3));

        AcademicCommands.CurriculumCourseDraft changed = CurriculumPlanForm.setRecommendedTerm(
                rows, Set.of(1L), 6).getFirst();

        assertEquals(CourseRequirementType.REQUIRED, changed.requirementType());
        assertEquals(6, changed.recommendedTermNumber());
    }

    @Test
    void choosingMajorRefreshesStaleValidationMessage() throws Exception {
        AcademicData.MajorReference softwareEngineering =
                new AcademicData.MajorReference(1, 1, "0102", "软件工程");

        fx(() -> {
            CurriculumPlanForm form = new CurriculumPlanForm(
                    CurriculumPlanForm.Mode.CREATE, List.of(softwareEngineering), null);
            try {
                button(form, "保存").fire();
                Label error = (Label) form.getDialogPane().lookup(
                        "#academic-curriculum-form-error");
                assertTrue(error.getText().contains("请选择专业"));

                @SuppressWarnings("unchecked")
                ComboBox<AcademicData.MajorReference> major =
                        (ComboBox<AcademicData.MajorReference>) ((GridPane) form.getDialogPane()
                                .getContent()).getChildren().stream()
                                .filter(ComboBox.class::isInstance)
                                .findFirst().orElseThrow();
                major.setValue(softwareEngineering);

                assertFalse(error.getText().contains("请选择专业"));
                assertTrue(error.getText().contains("方案名称不能为空"));
            } finally {
                form.close();
            }
            return null;
        });
    }

    @Test
    void validationErrorsKeepDialogButtonsInsideVisibleWindow() throws Exception {
        AcademicData.MajorReference softwareEngineering =
                new AcademicData.MajorReference(1, 1, "0102", "软件工程");

        fx(() -> {
            CurriculumPlanForm form = new CurriculumPlanForm(
                    CurriculumPlanForm.Mode.CREATE, List.of(softwareEngineering), null);
            form.show();
            try {
                Button save = button(form, "保存");
                Button cancel = button(form, "取消");
                save.fire();
                form.getDialogPane().applyCss();
                form.getDialogPane().layout();

                double visibleHeight = form.getDialogPane().getScene().getHeight();
                Bounds saveBounds = save.localToScene(save.getBoundsInLocal());
                Bounds cancelBounds = cancel.localToScene(cancel.getBoundsInLocal());
                assertTrue(saveBounds.getMaxY() <= visibleHeight,
                        "保存按钮超出窗口：" + saveBounds.getMaxY() + " > " + visibleHeight);
                assertTrue(cancelBounds.getMaxY() <= visibleHeight,
                        "取消按钮超出窗口：" + cancelBounds.getMaxY() + " > " + visibleHeight);
            } finally {
                form.close();
            }
            return null;
        });
    }

    private static AcademicData.CurriculumCourse course(
            long id, CourseRequirementType type, int term) {
        return new AcademicData.CurriculumCourse(id, "C00000" + id, "课程" + id,
                new BigDecimal("3.0"), type, term);
    }

    private static Button button(CurriculumPlanForm form, String text) {
        return (Button) form.getDialogPane().lookupButton(
                form.getDialogPane().getButtonTypes().stream()
                        .filter(type -> text.equals(type.getText()))
                        .findFirst().orElseThrow());
    }

    private static <T> T fx(java.util.concurrent.Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }
}
