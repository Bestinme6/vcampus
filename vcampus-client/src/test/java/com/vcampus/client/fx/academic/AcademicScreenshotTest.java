package com.vcampus.client.fx.academic;

import com.vcampus.common.model.AcademicTermStatus;
import com.vcampus.common.model.CourseRequirementType;
import com.vcampus.common.model.CourseSectionStatus;
import com.vcampus.common.model.CurriculumPlanStatus;
import com.vcampus.common.model.EnrollmentStatus;
import com.vcampus.common.model.ScheduleRevisionStatus;
import com.vcampus.common.model.ScheduleSlot;
import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Group;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcademicScreenshotTest {
    private static final Path OUTPUT = Path.of("..", "docs", "design", "javafx-academic", "screenshots")
            .normalize();

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
    void rendersStudentTeacherAndAdministratorAcademicWorkspaces() throws Exception {
        fx(() -> {
            AcademicWorkspaceView student = shell(Set.of(UserRole.STUDENT), "enrollment");
            EnrollmentCenterView enrollment = new EnrollmentCenterView(new EnrollmentListener());
            enrollment.showTerms(terms(), 1);
            enrollment.showCatalog(enrollmentCatalog());
            student.showContent(enrollment);
            capture(student, "student-enrollment-1000", 1000, 760);

            student = shell(Set.of(UserRole.STUDENT), "student-schedule");
            StudentScheduleView schedule = new StudentScheduleView(termId -> { });
            schedule.showTerms(terms(), 1);
            schedule.showEntries(scheduleEntries());
            student.showContent(schedule);
            capture(student, "student-schedule-1220", 1220, 820);

            AcademicWorkspaceView administrator = shell(Set.of(UserRole.SUPER_ADMIN), "curricula");
            CurriculumPlanView curricula = new CurriculumPlanView(new CurriculumListener());
            curricula.showReferences(references());
            AcademicData.CurriculumDetail detail = curriculum();
            curricula.showPlans(new AcademicData.CurriculumPage(List.of(summary(detail)), 1, 8, 1));
            curricula.showDetail(detail);
            administrator.showContent(curricula);
            capture(administrator, "admin-curriculum-1440", 1440, 900);

            administrator = shell(Set.of(UserRole.SUPER_ADMIN), "scheduling");
            ScheduleEditorView editor = new ScheduleEditorView(new ScheduleListener());
            editor.showSection(section(false));
            editor.showDraft(new AcademicData.ScheduleDraft(801, 301, 3,
                    ScheduleRevisionStatus.DRAFT, slots()));
            editor.showPublishResult(new AcademicData.SchedulePublishResult(false, "发现排课冲突，草稿仍已保留",
                    List.of(new AcademicData.ScheduleConflict(AcademicData.ScheduleConflictKind.CLASSROOM,
                            409, "软件工程 02 班", 3, 3, 4, 1, 16))));
            administrator.showContent(editor);
            capture(administrator, "admin-schedule-1440", 1440, 900);

            AcademicWorkspaceView teacher = shell(Set.of(UserRole.TEACHER), "gradebook");
            GradeEditorView grades = new GradeEditorView(new GradeListener());
            grades.showSection(section(false));
            grades.showRoster(roster());
            teacher.showContent(grades);
            capture(teacher, "teacher-grades-1220", 1220, 820);

            AcademicWorkspaceView states = shell(Set.of(UserRole.STUDENT), "enrollment");
            states.showEmpty("当前没有可选课程", "培养方案已匹配，但本学期尚未发布可选教学班。");
            capture(states, "state-empty-1000", 1000, 760);
            states.showFailure("网络连接暂时中断；服务器状态未知，请重新加载确认。", () -> { });
            capture(states, "state-error-1000", 1000, 760);
            states.showLoading("正在加载教务数据", "正在从应用服务器读取培养方案与教学班。");
            capture(states, "state-loading-1000", 1000, 760);
            return null;
        });
    }

    private static AcademicWorkspaceView shell(Set<UserRole> roles, String route) {
        AcademicWorkspaceView workspace = new AcademicWorkspaceView(roles, () -> { }, ignored -> { });
        assertTrue(workspace.open(route));
        return workspace;
    }

    private static List<AcademicData.Term> terms() {
        return List.of(new AcademicData.Term(1, "2026—2027 学年第一学期", AcademicTermStatus.IN_PROGRESS,
                LocalDate.of(2026, 8, 31), LocalDate.of(2027, 1, 17)));
    }

    private static AcademicData.ReferenceData references() {
        return new AcademicData.ReferenceData(terms(), List.of(
                new AcademicData.CourseReference(101, "CS2101", "面向对象程序设计", new BigDecimal("3.5")),
                new AcademicData.CourseReference(102, "CS2204", "数据库系统原理与大型课程设计", new BigDecimal("4")),
                new AcademicData.CourseReference(103, "GE1308", "科学、技术与社会专题研讨", new BigDecimal("2"))),
                List.of(new AcademicData.TeacherReference(201, "teacher.zhang", "张明远副教授")),
                List.of(new AcademicData.MajorReference(11, 2, "080901", "计算机科学与技术")));
    }

    private static AcademicData.EnrollmentCatalog enrollmentCatalog() {
        AcademicData.AvailableSection selected = available(301, "01 班", "张明远副教授", 40, 36,
                "周一 1—2 节 / 1—16 周", "教一-401", 901L, false);
        AcademicData.AvailableSection alternative = available(302, "02 班", "李思齐讲师", 40, 40,
                "周三 3—4 节 / 1—16 周", "实验中心 A-308", null, false);
        AcademicData.AvailableSection conflicting = available(303, "03 班（全英文荣誉教学实验班）", "王文博教授",
                36, 28, "周五 5—6 节 / 1—16 周", "教二-205", null, true);
        return new AcademicData.EnrollmentCatalog(List.of(
                new AcademicData.AvailableCourse(101, "CS2101", "面向对象程序设计",
                        new BigDecimal("3.5"), CourseRequirementType.REQUIRED, 3,
                        List.of(selected, alternative, conflicting)),
                new AcademicData.AvailableCourse(103, "GE1308", "科学、技术与社会专题研讨",
                        new BigDecimal("2"), CourseRequirementType.ELECTIVE, 3,
                        List.of(available(304, "01 班", "周嘉宁老师", 60, 12,
                                "周四 7—8 节 / 1—8 周", "人文楼-报告厅", null, false)))));
    }

    private static AcademicData.AvailableSection available(long id, String code, String teacher,
                                                            int capacity, int enrolled, String time,
                                                            String room, Long enrollmentId, boolean conflict) {
        return new AcademicData.AvailableSection(id, 1, terms().getFirst().name(), code, 200 + id, teacher,
                capacity, enrolled, CourseSectionStatus.OPEN, false, time, room, enrollmentId,
                enrollmentId == null ? null : EnrollmentStatus.ENROLLED, enrolled == capacity, conflict);
    }

    private static List<ScheduleSlot> slots() {
        return List.of(new ScheduleSlot(1, 1, 2, 1, 16, "教一-401"),
                new ScheduleSlot(3, 3, 4, 1, 16, "实验中心 A-308"),
                new ScheduleSlot(5, 7, 8, 1, 8, "人文楼-报告厅"));
    }

    private static List<AcademicData.ScheduleEntry> scheduleEntries() {
        return List.of(
                new AcademicData.ScheduleEntry(301, 1, terms().getFirst().name(), "CS2101",
                        "面向对象程序设计", new BigDecimal("3.5"), "01 班",
                        "张明远副教授", slots().get(0)),
                new AcademicData.ScheduleEntry(305, 1, terms().getFirst().name(), "MA2102",
                        "离散数学与组合结构", new BigDecimal("3"), "02 班",
                        "陈清华教授", slots().get(1)),
                new AcademicData.ScheduleEntry(304, 1, terms().getFirst().name(), "GE1308",
                        "科学、技术与社会专题研讨", new BigDecimal("2"), "01 班",
                        "周嘉宁老师", new ScheduleSlot(5, 7, 7, 1, 8, "人文楼-报告厅")));
    }

    private static AcademicData.CurriculumDetail curriculum() {
        List<AcademicData.CurriculumCourse> courses = List.of(
                new AcademicData.CurriculumCourse(101, "CS2101", "面向对象程序设计",
                        new BigDecimal("3.5"), CourseRequirementType.REQUIRED, 3),
                new AcademicData.CurriculumCourse(102, "CS2204", "数据库系统原理与大型课程设计",
                        new BigDecimal("4"), CourseRequirementType.REQUIRED, 4),
                new AcademicData.CurriculumCourse(103, "GE1308", "科学、技术与社会专题研讨",
                        new BigDecimal("2"), CourseRequirementType.ELECTIVE, 3));
        return new AcademicData.CurriculumDetail(501, 11, "计算机科学与技术",
                "计算机科学与技术专业 2026 版本科人才培养方案", 2, 2026, 2029,
                CurriculumPlanStatus.DRAFT, Instant.parse("2026-09-01T02:00:00Z"), "", null, courses);
    }

    private static AcademicData.CurriculumSummary summary(AcademicData.CurriculumDetail detail) {
        return new AcademicData.CurriculumSummary(detail.id(), detail.majorId(), detail.majorName(),
                detail.planName(), detail.versionNo(), detail.yearFrom(), detail.yearTo(),
                detail.status(), detail.courses().size());
    }

    private static AcademicData.TeachingSection section(boolean published) {
        return new AcademicData.TeachingSection(301, 1, terms().getFirst().name(), 101, "CS2101",
                "面向对象程序设计", new BigDecimal("3.5"), "01 班", 201, "张明远副教授",
                40, 3, CourseSectionStatus.OPEN, published, "周一 1—2 节 / 1—16 周", "教一-401",
                null, null);
    }

    private static AcademicData.Roster roster() {
        return new AcademicData.Roster(List.of(
                new AcademicData.RosterRow(901, 601, "202601010001", "林知夏", EnrollmentStatus.ENROLLED,
                        new BigDecimal("92"), new BigDecimal("4.2"), "设计思路清晰，工程规范良好"),
                new AcademicData.RosterRow(902, 602, "202601010002", "沈星河", EnrollmentStatus.ENROLLED,
                        new BigDecimal("86"), new BigDecimal("3.7"), "课程项目完整"),
                new AcademicData.RosterRow(903, 603, "202601010003", "顾言", EnrollmentStatus.ENROLLED,
                        null, null, "")));
    }

    private static void capture(Parent root, String name, int width, int height) throws Exception {
        if (root.getScene() != null) root.getScene().setRoot(new Group());
        Scene scene = new Scene(root, width, height);
        root.resize(width, height);
        root.applyCss();
        root.layout();
        WritableImage image = root.snapshot(null, null);
        assertEquals(width, image.getWidth(), 1);
        assertEquals(height, image.getHeight(), 1);
        Files.createDirectories(OUTPUT);
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", OUTPUT.resolve(name + ".png").toFile());
        scene.setRoot(new Group());
    }

    private static <T> T fx(java.util.concurrent.Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(60, TimeUnit.SECONDS);
    }

    private static final class EnrollmentListener implements EnrollmentCenterView.Listener {
        public void load(long termId) { }
        public void enroll(long termId, long sectionId) { }
        public void drop(long termId, long sectionId) { }
        public void switchSection(long termId, long fromSectionId, long toSectionId) { }
    }

    private static final class CurriculumListener implements CurriculumPlanView.Listener {
        public void search(Long majorId, CurriculumPlanStatus status, int page) { }
        public void open(long planId) { }
        public void create(AcademicCommands.CurriculumDraft draft) { }
        public void update(long planId, AcademicCommands.CurriculumDraft draft) { }
        public void copy(long planId, AcademicCommands.CurriculumDraft draft) { }
        public void saveCourses(long planId, List<AcademicCommands.CurriculumCourseDraft> drafts) { }
        public void removeCourses(long planId, Set<Long> courseIds) { }
        public void publish(long planId) { }
        public void archive(long planId) { }
    }

    private static final class ScheduleListener implements ScheduleEditorView.Listener {
        public void save(AcademicCommands.ScheduleDraftCommand command) { }
        public void publish(AcademicCommands.SchedulePublishCommand command) { }
        public void reload(long sectionId) { }
        public void back() { }
    }

    private static final class GradeListener implements GradeEditorView.Listener {
        public void saveGrade(long sectionId, long enrollmentId, AcademicCommands.GradeDraft draft) { }
        public void publishGrades(long sectionId) { }
        public void reloadRoster(long sectionId) { }
        public void backToTeacher() { }
    }
}
