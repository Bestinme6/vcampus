package com.vcampus.client.fx.academic;

import com.vcampus.common.model.AcademicTermStatus;
import com.vcampus.common.model.CourseSectionStatus;
import com.vcampus.common.model.ScheduleSlot;
import com.vcampus.common.model.UserRole;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AcademicSectionWorkflowTest {
    @BeforeAll static void toolkit() throws Exception { ScheduleGridTest.toolkit(); }

    @Test void schedulingAndPublishingShareOneStableActionPage() throws Exception {
        fx(() -> {
            var view = new SectionManagementView(new SectionManagementView.Listener() {
                public void search(long termId, String keyword, int page) { }
                public void create(SectionForm.Submission value) { }
                public void setStatus(long id, CourseSectionStatus status) { }
                public void loadTargets(long id) { }
                public void saveTargets(long id, List<AcademicData.SectionTarget> targets) { }
                public void editSchedule(AcademicData.TeachingSection section) { }
            });
            new Scene(view, 1000, 720);
            view.open("sections"); view.applyCss(); view.layout();
            assertTrue(button(view, "新增教学班").isVisible());
            assertTrue(button(view, "招生范围").isVisible());
            view.open("publishing");
            view.showPage(new AcademicData.SectionPage(List.of(new AcademicData.TeachingSection(1, 1, "2026秋", 1,
                    "C000001", "课程甲", java.math.BigDecimal.ONE, "01班", 10, "教师甲", 30, 0,
                    CourseSectionStatus.PLANNED, false, "", "", null, null)), 1, 8, 1));
            view.applyCss(); view.layout();
            assertFalse(button(view, "修改状态").isVisible());
            for (String action : List.of("编辑排课", "清空草稿", "检查并发布", "删除教学班")) {
                Button button = button(view, action);
                assertTrue(button.isVisible());
                assertEquals(javafx.scene.layout.Region.USE_PREF_SIZE, button.getMinWidth());
            }
            assertEquals("排课与发布", ((javafx.scene.control.Label) view.lookup(".academic-page-title")).getText());
        });
    }

    @Test void publicationPreviewCannotEditAndEditingCannotPublishDirectly() throws Exception {
        fx(() -> {
            var view = new ScheduleEditorView(new ScheduleEditorView.Listener() {
                public void save(AcademicCommands.ScheduleDraftCommand command) { }
                public void publish(AcademicCommands.SchedulePublishCommand command) { }
                public void reload(long id) { }
                public void back() { }
            });
            new Scene(view, 1000, 720);
            view.setReviewMode(false); view.applyCss(); view.layout();
            assertTrue(button(view, "保存草稿").isVisible());
            assertFalse(button(view, "发布课表").isVisible());
            view.setReviewMode(true);
            assertFalse(button(view, "保存草稿").isVisible());
            assertTrue(button(view, "发布课表").isVisible());
            assertFalse(view.lookup("#schedule-classroom").getParent().getParent().isVisible());
        });
    }

    @Test void createdSectionBecomesTheSharedListFilterAfterReturningFromItsDraft() throws Exception {
        record Query(long termId, String keyword, int page) { }
        AtomicReference<Query> latestQuery = new AtomicReference<>();
        AcademicData.ReferenceData references = new AcademicData.ReferenceData(
                List.of(term(1, "2026 春"), term(2, "2026 秋")),
                List.of(new AcademicData.CourseReference(10, "C000010", "软件工程", BigDecimal.valueOf(3))),
                List.of(new AcademicData.TeacherReference(20, "T000020", "王老师")),
                List.of(new AcademicData.MajorReference(30, 40, "080901", "计算机科学")));
        AcademicGateway gateway = (AcademicGateway) Proxy.newProxyInstance(
                AcademicGateway.class.getClassLoader(), new Class<?>[]{AcademicGateway.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "references" -> references;
                    case "sections" -> {
                        latestQuery.set(new Query((long) arguments[0], (String) arguments[1], (int) arguments[2]));
                        yield new AcademicData.SectionPage(List.of(), (int) arguments[2], 20, 0);
                    }
                    case "createSection" -> 99L;
                    case "saveSectionTargets" -> null;
                    default -> throw new AssertionError("本测试不应调用：" + method.getName());
                });
        ManualExecutor worker = new ManualExecutor();
        AcademicController controller = fxValue(() -> {
            AcademicController value = new AcademicController(gateway, Set.of(UserRole.SUPER_ADMIN),
                    worker, () -> { }, () -> { });
            new Scene(value.view(), 1200, 800);
            value.open("sections");
            return value;
        });
        try {
            worker.runAll();
            fx(() -> { });
            SectionForm.Submission submission = new SectionForm.Submission(
                    new AcademicCommands.SectionDraft(2, 10, "NEW-02", 20, 40,
                            CourseSectionStatus.PLANNED,
                            List.of(new ScheduleSlot(1, 1, 2, 1, 16, "A101")), false),
                    List.of(new AcademicData.SectionTarget(30, 2026, 2026)));
            fx(() -> controller.create(submission));
            worker.runAll();
            fx(() -> { });

            fx(controller::back);
            worker.runAll();
            fx(() -> { });

            assertEquals(new Query(2, "NEW-02", 1), latestQuery.get());
            fx(() -> {
                AcademicWorkspaceView workspace = (AcademicWorkspaceView) controller.view();
                SectionManagementView list = (SectionManagementView) workspace.getCenter();
                assertEquals(2, list.selectedTermId());
                assertEquals("NEW-02", list.keyword());
            });
        } finally {
            fx(controller::close);
        }
    }

    private static Button button(javafx.scene.Parent parent, String text) {
        return parent.lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
    }
    private static void fx(Runnable work) throws Exception {
        var task = new FutureTask<Void>(() -> { work.run(); return null; });
        javafx.application.Platform.runLater(task);
        task.get(20, TimeUnit.SECONDS);
    }

    private static <T> T fxValue(java.util.concurrent.Callable<T> work) throws Exception {
        var task = new FutureTask<>(work);
        javafx.application.Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    private static AcademicData.Term term(long id, String name) {
        return new AcademicData.Term(id, name, AcademicTermStatus.PLANNED,
                LocalDate.of(2026, id == 1 ? 2 : 9, 1),
                LocalDate.of(2027, id == 1 ? 1 : 2, 1));
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        void runAll() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }
}
