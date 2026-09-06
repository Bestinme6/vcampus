package com.vcampus.client.fx.academic;

import com.vcampus.common.model.RoleCompositionPolicy;
import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import javafx.scene.Parent;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/** Session-scoped coordinator for the native JavaFX academic module. */
public final class AcademicController implements AutoCloseable, CourseCatalogView.Listener,
        CurriculumPlanView.Listener, SectionManagementView.Listener, ScheduleEditorView.Listener {
    private final AcademicGateway gateway;
    private final Set<UserRole> roles;
    private final AcademicAsync async;
    private final AcademicWorkspaceView workspace;
    private final Runnable unreadRefresh;
    private CourseCatalogView courseCatalog;
    private String courseKeyword = "";
    private int coursePage = 1;
    private String courseNotice = "";
    private CurriculumPlanView curriculumPlans;
    private Long curriculumMajorId;
    private com.vcampus.common.model.CurriculumPlanStatus curriculumStatus;
    private int curriculumPage = 1;
    private String curriculumNotice = "";
    private SectionManagementView sectionManagement;
    private ScheduleEditorView scheduleEditor;
    private AcademicData.ReferenceData sectionReferences;
    private long sectionTermId;
    private String sectionKeyword = "";
    private int sectionPage = 1;
    private String sectionNotice = "";
    private AcademicData.TeachingSection scheduleSection;
    private boolean active = true;
    private boolean closed;

    public AcademicController(AcademicGateway gateway, Set<UserRole> roles, Executor executor,
                              Runnable back, Runnable unreadRefresh) {
        requireFx();
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        RoleCompositionPolicy.requireValid(this.roles);
        this.async = new AcademicAsync(Objects.requireNonNull(executor, "executor"), Platform::runLater);
        this.unreadRefresh = Objects.requireNonNull(unreadRefresh, "unreadRefresh");
        this.workspace = new AcademicWorkspaceView(this.roles,
                () -> {
                    if (!closed) back.run();
                }, this::open);
    }

    public Parent view() {
        return workspace;
    }

    public void open(String route) {
        requireFx();
        if (closed) return;
        active = true;
        async.invalidate();
        if (courseCatalog != null) courseCatalog.closeForm();
        if (sectionManagement != null) sectionManagement.closeDialogs();
        if (!workspace.open(route)) return;
        if ("courses".equals(workspace.activeRoute())) openCourses();
        else if ("curricula".equals(workspace.activeRoute())) openCurricula();
        else if ("sections".equals(workspace.activeRoute())
                || "scheduling".equals(workspace.activeRoute())) openSections();
    }

    public String activeRoute() {
        return workspace.activeRoute();
    }

    public void deactivate() {
        requireFx();
        active = false;
        async.invalidate();
        workspace.closeDialogs();
        if (courseCatalog != null) courseCatalog.closeForm();
        if (sectionManagement != null) sectionManagement.closeDialogs();
        workspace.busy(false);
    }

    @Override
    public void close() {
        requireFx();
        if (closed) return;
        closed = true;
        active = false;
        async.close();
        workspace.closeDialogs();
        if (courseCatalog != null) courseCatalog.closeForm();
        if (sectionManagement != null) sectionManagement.closeDialogs();
        workspace.busy(false);
    }

    AcademicGateway gateway() {
        return gateway;
    }

    Set<UserRole> roles() {
        return roles;
    }

    AcademicAsync async() {
        return async;
    }

    AcademicWorkspaceView workspace() {
        return workspace;
    }

    Runnable unreadRefresh() {
        return unreadRefresh;
    }

    boolean active() {
        return active && !closed;
    }

    @Override
    public void search(String keyword, int page) {
        requireFx();
        if (!active() || courseCatalog == null || page < 1) return;
        courseKeyword = Objects.requireNonNullElse(keyword, "").trim();
        coursePage = page;
        String requestKeyword = courseKeyword;
        int requestPage = coursePage;
        CourseCatalogView target = courseCatalog;
        target.busy(true);
        target.message("正在读取课程数据…", false);
        async.submit(() -> gateway.courses(requestKeyword, requestPage), result -> {
            if (target != courseCatalog || !active()) return;
            target.busy(false);
            target.message(courseNotice, false);
            courseNotice = "";
            target.showPage(result);
        }, error -> {
            if (target != courseCatalog || !active()) return;
            target.busy(false);
            workspace.showFailure(error, this::retryCourses);
        });
    }

    @Override
    public void create(AcademicCommands.CourseDraft draft) {
        requireFx();
        if (!active() || courseCatalog == null) return;
        CourseCatalogView target = courseCatalog;
        async.submit(() -> gateway.createCourse(draft), id -> {
            if (target != courseCatalog || !active()) return;
            target.formComplete();
            courseNotice = "课程创建成功";
            search(courseKeyword, 1);
        }, error -> {
            if (target == courseCatalog && active()) target.formFailure(error);
        });
    }

    @Override
    public void update(long courseId, AcademicCommands.CourseDraft draft, boolean enabled) {
        requireFx();
        if (!active() || courseCatalog == null) return;
        CourseCatalogView target = courseCatalog;
        async.submit(() -> {
            gateway.updateCourse(courseId, draft, enabled);
            return courseId;
        }, ignored -> {
            if (target != courseCatalog || !active()) return;
            target.formComplete();
            courseNotice = "课程信息已更新";
            search(courseKeyword, coursePage);
        }, error -> {
            if (target == courseCatalog && active()) target.formFailure(error);
        });
    }

    private void openCourses() {
        if (courseCatalog == null) courseCatalog = new CourseCatalogView(this);
        workspace.showContent(courseCatalog);
        search(courseKeyword, coursePage);
    }

    private void retryCourses() {
        if (!active() || courseCatalog == null) return;
        workspace.showContent(courseCatalog);
        search(courseKeyword, coursePage);
    }

    @Override
    public void search(Long majorId, com.vcampus.common.model.CurriculumPlanStatus status, int page) {
        requireFx();
        if (!active() || curriculumPlans == null || page < 1) return;
        curriculumMajorId = majorId;
        curriculumStatus = status;
        curriculumPage = page;
        CurriculumPlanView target = curriculumPlans;
        target.busy(true);
        async.submit(() -> gateway.curricula(majorId, status, page), result -> {
            if (target != curriculumPlans || !active()) return;
            target.busy(false);
            target.showPlans(result);
            if (!curriculumNotice.isBlank()) target.showMessage(curriculumNotice, false);
            curriculumNotice = "";
        }, error -> {
            if (target == curriculumPlans && active()) {
                target.busy(false);
                target.showMessage(error, true);
            }
        });
    }

    @Override
    public void open(long planId) {
        requireFx();
        if (!active() || curriculumPlans == null) return;
        CurriculumPlanView target = curriculumPlans;
        target.busy(true);
        async.submit(() -> gateway.curriculum(planId), result -> {
            if (target != curriculumPlans || !active()) return;
            target.busy(false);
            target.showDetail(result);
            target.showMessage(curriculumNotice, false);
            curriculumNotice = "";
        }, error -> {
            if (target == curriculumPlans && active()) {
                target.busy(false);
                target.showMessage(error, true);
            }
        });
    }

    @Override
    public void create(AcademicCommands.CurriculumDraft draft) {
        curriculumMutation(() -> gateway.createCurriculum(draft), "培养方案创建成功", false);
    }

    @Override
    public void update(long planId, AcademicCommands.CurriculumDraft draft) {
        curriculumMutation(() -> {
            gateway.updateCurriculum(planId, draft);
            return planId;
        }, "培养方案已更新", true);
    }

    @Override
    public void copy(long planId, AcademicCommands.CurriculumDraft draft) {
        curriculumMutation(() -> gateway.copyCurriculum(planId, draft.planName(),
                draft.yearFrom(), draft.yearTo()), "培养方案已复制为新版本", false);
    }

    @Override
    public void saveCourses(long planId, java.util.List<AcademicCommands.CurriculumCourseDraft> drafts) {
        curriculumMutation(() -> {
            for (AcademicCommands.CurriculumCourseDraft draft : drafts) {
                gateway.saveCurriculumCourse(planId, draft);
            }
            return planId;
        }, "培养方案课程已更新", true);
    }

    @Override
    public void removeCourses(long planId, java.util.Set<Long> courseIds) {
        curriculumMutation(() -> {
            for (Long courseId : courseIds) gateway.removeCurriculumCourse(planId, courseId);
            return planId;
        }, "课程已从培养方案移除", true);
    }

    @Override
    public void publish(long planId) {
        curriculumMutation(() -> {
            gateway.publishCurriculum(planId);
            return planId;
        }, "培养方案已发布", true);
    }

    @Override
    public void archive(long planId) {
        curriculumMutation(() -> {
            gateway.archiveCurriculum(planId);
            return planId;
        }, "培养方案已归档", true);
    }

    private void openCurricula() {
        if (curriculumPlans == null) curriculumPlans = new CurriculumPlanView(this);
        CurriculumPlanView target = curriculumPlans;
        workspace.showContent(target);
        target.busy(true);
        record Initial(AcademicData.ReferenceData references, AcademicData.CurriculumPage plans) { }
        Long majorId = curriculumMajorId;
        var status = curriculumStatus;
        int page = curriculumPage;
        async.submit(() -> new Initial(gateway.references(), gateway.curricula(majorId, status, page)),
                result -> {
                    if (target != curriculumPlans || !active()) return;
                    target.busy(false);
                    target.showReferences(result.references());
                    target.showPlans(result.plans());
                }, error -> {
                    if (target != curriculumPlans || !active()) return;
                    target.busy(false);
                    workspace.showFailure(error, this::openCurricula);
                });
    }

    private void curriculumMutation(java.util.concurrent.Callable<Long> operation,
                                    String message, boolean reopenDetail) {
        requireFx();
        if (!active() || curriculumPlans == null) return;
        CurriculumPlanView target = curriculumPlans;
        target.busy(true);
        async.submit(operation, planId -> {
            if (target != curriculumPlans || !active()) return;
            target.busy(false);
            curriculumNotice = message;
            if (reopenDetail) open(planId);
            else search(curriculumMajorId, curriculumStatus, curriculumPage);
        }, error -> {
            if (target == curriculumPlans && active()) {
                target.busy(false);
                target.showMessage(error, true);
            }
        });
    }

    @Override
    public void search(long termId, String keyword, int page) {
        requireFx();
        if (!active() || sectionManagement == null || termId < 1 || page < 1) return;
        sectionTermId = termId;
        sectionKeyword = Objects.requireNonNullElse(keyword, "").trim();
        sectionPage = page;
        SectionManagementView target = sectionManagement;
        target.busy(true);
        async.submit(() -> gateway.sections(termId, sectionKeyword, page), result -> {
            if (target != sectionManagement || !active()) return;
            target.busy(false);
            target.showPage(result);
            target.message(sectionNotice, false);
            sectionNotice = "";
        }, error -> {
            if (target == sectionManagement && active()) {
                target.busy(false);
                target.message(error, true);
            }
        });
    }

    @Override
    public void create(SectionForm.Submission submission) {
        requireFx();
        if (!active() || sectionManagement == null) return;
        SectionManagementView target = sectionManagement;
        target.busy(true);
        async.submit(() -> {
            long id = gateway.createSection(submission.section());
            gateway.saveSectionTargets(id, submission.targets());
            return createdSection(id, submission.section());
        }, section -> {
            if (target != sectionManagement || !active()) return;
            target.busy(false);
            sectionNotice = "教学班创建成功，已进入课表草稿";
            editSchedule(section);
        }, error -> {
            if (target == sectionManagement && active()) {
                target.busy(false);
                target.message(error, true);
            }
        });
    }

    @Override
    public void setStatus(long sectionId, com.vcampus.common.model.CourseSectionStatus status) {
        sectionMutation(() -> {
            gateway.setSectionStatus(sectionId, status);
            return sectionId;
        }, "教学班状态已更新");
    }

    @Override
    public void loadTargets(long sectionId) {
        requireFx();
        if (!active() || sectionManagement == null) return;
        SectionManagementView target = sectionManagement;
        target.busy(true);
        async.submit(() -> gateway.sectionTargets(sectionId), result -> {
            if (target != sectionManagement || !active()) return;
            target.busy(false);
            target.showTargets(sectionId, result);
        }, error -> {
            if (target == sectionManagement && active()) {
                target.busy(false);
                target.message(error, true);
            }
        });
    }

    @Override
    public void saveTargets(long sectionId, java.util.List<AcademicData.SectionTarget> targets) {
        sectionMutation(() -> {
            gateway.saveSectionTargets(sectionId, targets);
            return sectionId;
        }, "教学班招生范围已更新");
    }

    @Override
    public void editSchedule(AcademicData.TeachingSection section) {
        requireFx();
        if (!active()) return;
        scheduleSection = Objects.requireNonNull(section, "section");
        if (scheduleEditor == null) scheduleEditor = new ScheduleEditorView(this);
        scheduleEditor.showSection(section);
        workspace.showContent(scheduleEditor);
        reload(section.id());
    }

    @Override
    public void save(AcademicCommands.ScheduleDraftCommand command) {
        requireFx();
        if (!active() || scheduleEditor == null) return;
        ScheduleEditorView target = scheduleEditor;
        target.busy(true);
        async.submit(() -> gateway.saveSchedule(command), result -> {
            if (target != scheduleEditor || !active()) return;
            target.busy(false);
            target.saved(result);
        }, error -> {
            if (target == scheduleEditor && active()) {
                target.busy(false);
                target.failure(error);
            }
        });
    }

    @Override
    public void publish(AcademicCommands.SchedulePublishCommand command) {
        requireFx();
        if (!active() || scheduleEditor == null) return;
        ScheduleEditorView target = scheduleEditor;
        target.busy(true);
        async.submit(() -> gateway.publishSchedule(command), result -> {
            if (target != scheduleEditor || !active()) return;
            target.busy(false);
            target.showPublishResult(result);
            if (result.success()) {
                unreadRefresh.run();
                sectionNotice = result.message();
                openSections();
            }
        }, error -> {
            if (target == scheduleEditor && active()) {
                target.busy(false);
                target.failure(error);
            }
        });
    }

    @Override
    public void reload(long sectionId) {
        requireFx();
        if (!active() || scheduleEditor == null || scheduleSection == null
                || scheduleSection.id() != sectionId) return;
        ScheduleEditorView target = scheduleEditor;
        target.busy(true);
        async.submit(() -> gateway.scheduleDraft(sectionId), result -> {
            if (target != scheduleEditor || !active()) return;
            target.busy(false);
            target.showDraft(result);
        }, error -> {
            if (target == scheduleEditor && active()) {
                target.busy(false);
                target.failure(error);
            }
        });
    }

    @Override
    public void back() {
        requireFx();
        if (!active()) return;
        openSections();
    }

    private void openSections() {
        if (sectionManagement == null) sectionManagement = new SectionManagementView(this);
        SectionManagementView target = sectionManagement;
        workspace.showContent(target);
        target.busy(true);
        long requestedTerm = sectionTermId;
        String requestedKeyword = sectionKeyword;
        int requestedPage = sectionPage;
        record Initial(AcademicData.ReferenceData references, long termId,
                       AcademicData.SectionPage sections) { }
        async.submit(() -> {
            AcademicData.ReferenceData references = gateway.references();
            long termId = requestedTerm > 0 ? requestedTerm
                    : references.terms().isEmpty() ? 0 : references.terms().getFirst().id();
            AcademicData.SectionPage sections = termId == 0
                    ? new AcademicData.SectionPage(java.util.List.of(), 1, 8, 0)
                    : gateway.sections(termId, requestedKeyword, requestedPage);
            return new Initial(references, termId, sections);
        }, result -> {
            if (target != sectionManagement || !active()) return;
            target.busy(false);
            sectionReferences = result.references();
            sectionTermId = result.termId();
            target.showReferences(result.references());
            target.showPage(result.sections());
            target.message(sectionNotice, false);
            sectionNotice = "";
        }, error -> {
            if (target != sectionManagement || !active()) return;
            target.busy(false);
            workspace.showFailure(error, this::openSections);
        });
    }

    private void sectionMutation(java.util.concurrent.Callable<Long> operation, String notice) {
        requireFx();
        if (!active() || sectionManagement == null) return;
        SectionManagementView target = sectionManagement;
        target.busy(true);
        async.submit(operation, ignored -> {
            if (target != sectionManagement || !active()) return;
            target.busy(false);
            sectionNotice = notice;
            search(sectionTermId, sectionKeyword, sectionPage);
        }, error -> {
            if (target == sectionManagement && active()) {
                target.busy(false);
                target.message(error, true);
            }
        });
    }

    private AcademicData.TeachingSection createdSection(long id, AcademicCommands.SectionDraft draft) {
        AcademicData.ReferenceData references = Objects.requireNonNull(sectionReferences, "sectionReferences");
        AcademicData.Term term = references.terms().stream().filter(item -> item.id() == draft.termId())
                .findFirst().orElseThrow();
        AcademicData.CourseReference course = references.courses().stream()
                .filter(item -> item.id() == draft.courseId()).findFirst().orElseThrow();
        AcademicData.TeacherReference teacher = references.teachers().stream()
                .filter(item -> item.userId() == draft.teacherUserId()).findFirst().orElseThrow();
        String schedule = draft.slots().stream().map(slot -> "周" + slot.dayOfWeek() + " "
                + slot.startPeriod() + "—" + slot.endPeriod() + "节")
                .collect(java.util.stream.Collectors.joining("；"));
        String rooms = draft.slots().stream().map(com.vcampus.common.model.ScheduleSlot::classroom)
                .distinct().collect(java.util.stream.Collectors.joining("；"));
        return new AcademicData.TeachingSection(id, term.id(), term.name(), course.id(),
                course.code(), course.name(), course.credits(), draft.sectionCode(), teacher.userId(),
                teacher.displayName(), draft.capacity(), 0, draft.status(), false,
                schedule, rooms, null, null);
    }

    private static void requireFx() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("教务界面必须在 JavaFX 线程操作");
        }
    }
}
