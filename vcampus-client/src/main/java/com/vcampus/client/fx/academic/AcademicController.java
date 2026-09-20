package com.vcampus.client.fx.academic;

import com.vcampus.common.model.RoleCompositionPolicy;
import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import javafx.scene.Parent;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/** Session-scoped coordinator for the native JavaFX academic module. */
public final class AcademicController implements AutoCloseable, CourseCatalogView.Listener,
        CurriculumPlanView.Listener, SectionManagementView.Listener, ScheduleEditorView.Listener,
        EnrollmentCenterView.Listener, MyCoursesView.Listener, StudentScheduleView.Listener, TeacherWorkspaceView.Listener,
        GradeEditorView.Listener {
    private final AcademicGateway gateway;
    private final Set<UserRole> roles;
    private final AcademicAsync async;
    private final AcademicAsync occupancyAsync;
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
    private EnrollmentCenterView enrollmentCenter;
    private MyCoursesView myCourses;
    private StudentScheduleView studentSchedule;
    private StudentGradesView studentGrades;
    private long enrollmentTermId;
    private long myCoursesTermId;
    private long studentScheduleTermId;
    private String enrollmentNotice = "";
    private boolean enrollmentNoticeError;
    private String myCoursesNotice = "";
    private boolean myCoursesNoticeError;
    private TeacherWorkspaceView teacherWorkspace;
    private GradeEditorView gradeEditor;
    private long teacherTermId;
    private String teacherRoute = "teacher-schedule";
    private AcademicData.TeachingSection gradeSection;
    private String teacherNotice = "";
    private String gradeNotice = "";
    private boolean active = true;
    private boolean closed;

    private record TeacherData(java.util.List<AcademicData.ScheduleEntry> schedule,
                               java.util.List<AcademicData.TeachingSection> sections) { }

    public AcademicController(AcademicGateway gateway, Set<UserRole> roles, Executor executor,
                              Runnable back, Runnable unreadRefresh) {
        requireFx();
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        RoleCompositionPolicy.requireValid(this.roles);
        this.async = new AcademicAsync(Objects.requireNonNull(executor, "executor"), Platform::runLater);
        this.occupancyAsync = new AcademicAsync(executor, Platform::runLater);
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
        String resolvedRoute = "scheduling".equals(route) ? "publishing" : route;
        if (!workspace.open(resolvedRoute)) return;
        if ("courses".equals(workspace.activeRoute())) openCourses();
        else if ("curricula".equals(workspace.activeRoute())) openCurricula();
        else if ("sections".equals(workspace.activeRoute())
                || "publishing".equals(workspace.activeRoute())) openSections();
        else if ("enrollment".equals(workspace.activeRoute())) openEnrollment();
        else if ("my-courses".equals(workspace.activeRoute())) openMyCourses();
        else if ("student-schedule".equals(workspace.activeRoute())) openStudentSchedule();
        else if ("grades".equals(workspace.activeRoute())) openStudentGrades();
        else if ("teacher-schedule".equals(workspace.activeRoute())
                || "teaching-sections".equals(workspace.activeRoute())
                || "gradebook".equals(workspace.activeRoute())) {
            openTeacherWorkspace(workspace.activeRoute());
        }
    }

    public String activeRoute() {
        return workspace.activeRoute();
    }

    /** Opens one administrator-visible teaching section from a notification deep link. */
    public void openSection(long sectionId) {
        requireFx();
        if (closed || sectionId < 1) return;
        active = true;
        async.invalidate();
        if (!workspace.open("publishing")) return;
        workspace.showLoading("正在定位教学班", "正在读取相关学期和教学班数据。");
        async.submit(() -> findSection(sectionId), this::editSchedule,
                error -> workspace.showFailure(error, () -> openSection(sectionId)));
    }

    public void deactivate() {
        requireFx();
        active = false;
        async.invalidate();
        occupancyAsync.invalidate();
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
        occupancyAsync.close();
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
            sectionTermId = submission.section().termId();
            sectionKeyword = submission.section().sectionCode().trim();
            sectionPage = 1;
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
        openSchedule(section, false);
    }

    @Override
    public void reviewSchedule(AcademicData.TeachingSection section) {
        openSchedule(section, true);
    }

    @Override
    public void clearSchedule(AcademicData.TeachingSection section) {
        sectionMutation(() -> {
            gateway.resetScheduleDraft(section.id());
            return section.id();
        }, "课表草稿已放弃；再次编辑时会恢复已发布课表");
    }

    @Override
    public void deleteSection(AcademicData.TeachingSection section) {
        sectionMutation(() -> {
            gateway.deleteSection(section.id());
            return section.id();
        }, "教学班已删除");
    }

    @Override
    public void review() {
        if (scheduleSection != null) reviewSchedule(scheduleSection);
    }

    @Override
    public void edit() {
        if (scheduleSection != null) editSchedule(scheduleSection);
    }

    private void openSchedule(AcademicData.TeachingSection section, boolean review) {
        requireFx();
        if (!active()) return;
        if (!workspace.open("publishing")) return;
        scheduleSection = Objects.requireNonNull(section, "section");
        if (scheduleEditor == null) scheduleEditor = new ScheduleEditorView(this);
        scheduleEditor.showSection(section);
        scheduleEditor.setReviewMode(review);
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
        long termId = scheduleSection.termId();
        target.loadingOccupancy();
        record Loaded(AcademicData.ScheduleDraft draft, java.util.List<AcademicData.ScheduleOccupancy> occupancy) { }
        async.submit(() -> new Loaded(gateway.scheduleDraft(sectionId), gateway.scheduleOccupancy(termId)), result -> {
            if (target != scheduleEditor || !active()) return;
            target.busy(false);
            target.showDraft(result.draft());
            target.showOccupancy(result.occupancy());
        }, error -> {
            if (target == scheduleEditor && active()) {
                target.busy(false);
                target.failure(error);
            }
        });
    }

    @Override
    public void loadOccupancy(long termId, java.util.function.Consumer<java.util.List<AcademicData.ScheduleOccupancy>> success,
                              java.util.function.Consumer<String> failure) {
        occupancyAsync.submit(() -> gateway.scheduleOccupancy(termId), success, failure);
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
        target.open(workspace.activeRoute());
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
                    ? new AcademicData.SectionPage(java.util.List.of(), 1, 20, 0)
                    : gateway.sections(termId, requestedKeyword, requestedPage);
            return new Initial(references, termId, sections);
        }, result -> {
            if (target != sectionManagement || !active()) return;
            target.busy(false);
            sectionReferences = result.references();
            sectionTermId = result.termId();
            target.showReferences(result.references());
            target.showFilter(result.termId(), requestedKeyword);
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

    @Override
    public void load(long termId) {
        requireFx();
        if (!active() || enrollmentCenter == null || termId < 1) return;
        enrollmentTermId = termId;
        EnrollmentCenterView target = enrollmentCenter;
        target.busy(true);
        async.submit(() -> gateway.enrollmentCatalog(termId), result -> {
            if (target != enrollmentCenter || !active()) return;
            target.busy(false);
            target.showCatalog(result);
            target.message(enrollmentNotice, enrollmentNoticeError);
            enrollmentNotice = "";
            enrollmentNoticeError = false;
        }, error -> {
            if (target == enrollmentCenter && active()) {
                target.busy(false);
                target.message(error, true);
            }
        });
    }

    @Override
    public void enroll(long termId, long sectionId) {
        enrollmentMutation(termId, () -> {
            gateway.enroll(sectionId);
            return sectionId;
        }, "选课成功");
    }

    @Override
    public void drop(long termId, long sectionId) {
        enrollmentMutation(termId, () -> {
            gateway.drop(sectionId);
            return sectionId;
        }, "退课成功");
    }

    @Override
    public void switchSection(long termId, long fromSectionId, long toSectionId) {
        enrollmentMutation(termId, () -> {
            gateway.switchSection(fromSectionId, toSectionId);
            return toSectionId;
        }, "教学班更换成功");
    }

    @Override
    public void loadSchedule(long termId) {
        requireFx();
        if (!active() || studentSchedule == null || termId < 1) return;
        studentScheduleTermId = termId;
        StudentScheduleView target = studentSchedule;
        target.busy(true);
        async.submit(() -> gateway.studentSchedule(termId), result -> {
            if (target != studentSchedule || !active()) return;
            target.busy(false);
            target.showEntries(result);
            target.message("", false);
        }, error -> {
            if (target == studentSchedule && active()) {
                target.busy(false);
                target.message(error, true);
            }
        });
    }

    private void openEnrollment() {
        if (enrollmentCenter == null) enrollmentCenter = new EnrollmentCenterView(this);
        EnrollmentCenterView target = enrollmentCenter;
        workspace.showContent(target);
        target.busy(true);
        long requestedTerm = enrollmentTermId;
        record Initial(AcademicData.ReferenceData references, long termId,
                       AcademicData.EnrollmentCatalog catalog) { }
        async.submit(() -> {
            AcademicData.ReferenceData references = gateway.references();
            long termId = requestedTerm > 0 ? requestedTerm
                    : references.terms().isEmpty() ? 0 : references.terms().getFirst().id();
            AcademicData.EnrollmentCatalog catalog = termId == 0
                    ? new AcademicData.EnrollmentCatalog(java.util.List.of())
                    : gateway.enrollmentCatalog(termId);
            return new Initial(references, termId, catalog);
        }, result -> {
            if (target != enrollmentCenter || !active()) return;
            target.busy(false);
            enrollmentTermId = result.termId();
            target.showTerms(result.references().terms(), result.termId());
            target.showCatalog(result.catalog());
            target.message(enrollmentNotice, enrollmentNoticeError);
            enrollmentNotice = "";
            enrollmentNoticeError = false;
        }, error -> {
            if (target != enrollmentCenter || !active()) return;
            target.busy(false);
            workspace.showFailure(error, this::openEnrollment);
        });
    }

    private void openMyCourses() {
        if (myCourses == null) myCourses = new MyCoursesView(this);
        MyCoursesView target = myCourses;
        workspace.showContent(target);
        target.busy(true);
        long requestedTerm = myCoursesTermId;
        record Initial(AcademicData.ReferenceData references, long termId,
                       AcademicData.EnrollmentCatalog catalog) { }
        async.submit(() -> {
            AcademicData.ReferenceData references = gateway.references();
            long termId = requestedTerm > 0 ? requestedTerm
                    : references.terms().isEmpty() ? 0 : references.terms().getFirst().id();
            AcademicData.EnrollmentCatalog catalog = termId == 0
                    ? new AcademicData.EnrollmentCatalog(java.util.List.of())
                    : gateway.enrollmentCatalog(termId);
            return new Initial(references, termId, catalog);
        }, result -> {
            if (target != myCourses || !active()) return;
            target.busy(false);
            myCoursesTermId = result.termId();
            target.showTerms(result.references().terms(), result.termId());
            target.showCatalog(result.catalog());
            target.message(myCoursesNotice, myCoursesNoticeError);
            myCoursesNotice = "";
            myCoursesNoticeError = false;
        }, error -> {
            if (target != myCourses || !active()) return;
            target.busy(false);
            workspace.showFailure(error, this::openMyCourses);
        });
    }

    @Override
    public void loadMyCourses(long termId) {
        requireFx();
        if (!active() || myCourses == null || termId < 1) return;
        myCoursesTermId = termId;
        MyCoursesView target = myCourses;
        target.busy(true);
        async.submit(() -> gateway.enrollmentCatalog(termId), result -> {
            if (target != myCourses || !active()) return;
            target.busy(false);
            target.showCatalog(result);
            target.message(myCoursesNotice, myCoursesNoticeError);
            myCoursesNotice = "";
            myCoursesNoticeError = false;
        }, error -> {
            if (target == myCourses && active()) {
                target.busy(false);
                target.message(error, true);
            }
        });
    }

    @Override
    public void dropMyCourse(long termId, long sectionId) {
        myCoursesMutation(termId, () -> {
            gateway.drop(sectionId);
            return sectionId;
        }, "退课成功");
    }

    @Override
    public void switchMyCourseSection(long termId, long fromSectionId, long toSectionId) {
        myCoursesMutation(termId, () -> {
            gateway.switchSection(fromSectionId, toSectionId);
            return toSectionId;
        }, "教学班更换成功");
    }

    private void myCoursesMutation(long termId, java.util.concurrent.Callable<Long> operation,
                                   String successMessage) {
        requireFx();
        if (!active() || myCourses == null) return;
        MyCoursesView target = myCourses;
        target.busy(true);
        async.submit(operation, ignored -> {
            if (target != myCourses || !active()) return;
            myCoursesNotice = successMessage;
            myCoursesNoticeError = false;
            loadMyCourses(termId);
        }, error -> {
            if (target != myCourses || !active()) return;
            myCoursesNotice = error + "；已重新读取最新选课状态";
            myCoursesNoticeError = true;
            loadMyCourses(termId);
        });
    }

    private void enrollmentMutation(long termId, java.util.concurrent.Callable<Long> operation,
                                    String successMessage) {
        requireFx();
        if (!active() || enrollmentCenter == null) return;
        EnrollmentCenterView target = enrollmentCenter;
        target.busy(true);
        async.submit(operation, ignored -> {
            if (target != enrollmentCenter || !active()) return;
            enrollmentNotice = successMessage;
            enrollmentNoticeError = false;
            load(termId);
        }, error -> {
            if (target != enrollmentCenter || !active()) return;
            enrollmentNotice = error + "；已重新读取最新选课状态";
            enrollmentNoticeError = true;
            load(termId);
        });
    }

    private void openStudentSchedule() {
        if (studentSchedule == null) studentSchedule = new StudentScheduleView(this);
        StudentScheduleView target = studentSchedule;
        workspace.showContent(target);
        target.busy(true);
        long requestedTerm = studentScheduleTermId;
        record Initial(AcademicData.ReferenceData references, long termId,
                       java.util.List<AcademicData.ScheduleEntry> entries) { }
        async.submit(() -> {
            AcademicData.ReferenceData references = gateway.references();
            long termId = requestedTerm > 0 ? requestedTerm
                    : references.terms().isEmpty() ? 0 : references.terms().getFirst().id();
            return new Initial(references, termId, termId == 0 ? java.util.List.of()
                    : gateway.studentSchedule(termId));
        }, result -> {
            if (target != studentSchedule || !active()) return;
            target.busy(false);
            studentScheduleTermId = result.termId();
            target.showTerms(result.references().terms(), result.termId());
            target.showPeriods(result.references().periods());
            target.showEntries(result.entries());
            target.message("", false);
        }, error -> {
            if (target != studentSchedule || !active()) return;
            target.busy(false);
            workspace.showFailure(error, this::openStudentSchedule);
        });
    }

    private void openStudentGrades() {
        if (studentGrades == null) studentGrades = new StudentGradesView();
        StudentGradesView target = studentGrades;
        workspace.showContent(target);
        target.busy(true);
        async.submit(gateway::myGrades, result -> {
            if (target != studentGrades || !active()) return;
            target.busy(false);
            target.showGrades(result);
            target.message("", false);
        }, error -> {
            if (target == studentGrades && active()) {
                target.busy(false);
                target.message(error, true);
            }
        });
    }

    @Override
    public void loadTeacherWorkspace(long termId) {
        requireFx();
        if (!active() || teacherWorkspace == null || termId < 1) return;
        teacherTermId = termId;
        TeacherWorkspaceView target = teacherWorkspace;
        String requestedRoute = teacherRoute;
        target.busy(true);
        async.submit(() -> loadTeacherData(requestedRoute, termId), result -> {
            if (target != teacherWorkspace || !active() || !requestedRoute.equals(teacherRoute)) return;
            target.busy(false);
            showTeacherData(target, requestedRoute, result);
            target.message(teacherNotice, false);
            teacherNotice = "";
        }, error -> {
            if (target == teacherWorkspace && active() && requestedRoute.equals(teacherRoute)) {
                target.busy(false);
                target.message(error, true);
            }
        });
    }

    @Override
    public void openGradebook(AcademicData.TeachingSection section) {
        requireFx();
        if (!active()) return;
        gradeSection = Objects.requireNonNull(section, "section");
        if (gradeEditor == null) gradeEditor = new GradeEditorView(this);
        gradeEditor.showSection(section);
        workspace.showContent(gradeEditor);
        reloadRoster(section.id());
    }

    @Override
    public void saveGrade(long sectionId, long enrollmentId, AcademicCommands.GradeDraft draft) {
        requireFx();
        if (!active() || gradeEditor == null || gradeSection == null
                || gradeSection.id() != sectionId) return;
        GradeEditorView target = gradeEditor;
        target.busy(true);
        async.submit(() -> {
            gateway.saveGrade(sectionId, enrollmentId, draft);
            return sectionId;
        }, ignored -> {
            if (target != gradeEditor || !active()) return;
            gradeNotice = "成绩已保存，绩点已由服务器重新计算";
            reloadRoster(sectionId);
        }, error -> {
            if (target == gradeEditor && active()) {
                target.busy(false);
                target.message(error, true);
            }
        });
    }

    @Override
    public void publishGrades(long sectionId) {
        requireFx();
        if (!active() || gradeEditor == null || gradeSection == null
                || gradeSection.id() != sectionId) return;
        GradeEditorView target = gradeEditor;
        target.busy(true);
        async.submit(() -> {
            gateway.publishGrades(sectionId);
            return sectionId;
        }, ignored -> {
            if (target != gradeEditor || !active()) return;
            target.busy(false);
            unreadRefresh.run();
            teacherNotice = "全班成绩已发布";
            openTeacherWorkspace(teacherRoute);
        }, error -> {
            if (target == gradeEditor && active()) {
                target.busy(false);
                target.message(error, true);
            }
        });
    }

    @Override
    public void reloadRoster(long sectionId) {
        requireFx();
        if (!active() || gradeEditor == null || gradeSection == null
                || gradeSection.id() != sectionId) return;
        GradeEditorView target = gradeEditor;
        target.busy(true);
        async.submit(() -> gateway.roster(sectionId), result -> {
            if (target != gradeEditor || !active()) return;
            target.busy(false);
            target.showRoster(result);
            target.message(gradeNotice, false);
            gradeNotice = "";
        }, error -> {
            if (target == gradeEditor && active()) {
                target.busy(false);
                target.message(error, true);
            }
        });
    }

    @Override
    public void backToTeacher() {
        requireFx();
        if (active()) openTeacherWorkspace(teacherRoute);
    }

    private void openTeacherWorkspace(String route) {
        teacherRoute = route;
        String requestedRoute = route;
        if (teacherWorkspace == null) teacherWorkspace = new TeacherWorkspaceView(this);
        TeacherWorkspaceView target = teacherWorkspace;
        target.open(route);
        workspace.showContent(target);
        target.busy(true);
        long requestedTerm = teacherTermId;
        record Initial(AcademicData.ReferenceData references, long termId,
                       TeacherData data) { }
        async.submit(() -> {
            AcademicData.ReferenceData references = gateway.references();
            long termId = requestedTerm > 0 ? requestedTerm
                    : references.terms().isEmpty() ? 0 : references.terms().getFirst().id();
            return new Initial(references, termId, termId == 0
                    ? new TeacherData(java.util.List.of(), java.util.List.of())
                    : loadTeacherData(requestedRoute, termId));
        }, result -> {
            if (target != teacherWorkspace || !active() || !requestedRoute.equals(teacherRoute)) return;
            target.busy(false);
            teacherTermId = result.termId();
            target.showTerms(result.references().terms(), result.termId());
            target.showPeriods(result.references().periods());
            showTeacherData(target, requestedRoute, result.data());
            target.message(teacherNotice, false);
            teacherNotice = "";
        }, error -> {
            if (target != teacherWorkspace || !active() || !requestedRoute.equals(teacherRoute)) return;
            target.busy(false);
            workspace.showFailure(error, () -> openTeacherWorkspace(requestedRoute));
        });
    }

    private TeacherData loadTeacherData(String route, long termId) throws IOException {
        return switch (route) {
            case "teacher-schedule" -> new TeacherData(gateway.teacherSchedule(termId, null),
                    java.util.List.of());
            case "teaching-sections", "gradebook" -> new TeacherData(java.util.List.of(),
                    gateway.teacherSections(termId));
            default -> throw new IllegalArgumentException("未知教师教务页面：" + route);
        };
    }

    private static void showTeacherData(TeacherWorkspaceView target, String route, TeacherData data) {
        switch (route) {
            case "teacher-schedule" -> target.showSchedule(data.schedule());
            case "teaching-sections", "gradebook" -> target.showSections(data.sections());
            default -> throw new IllegalArgumentException("未知教师教务页面：" + route);
        }
    }

    private AcademicData.TeachingSection findSection(long sectionId) throws IOException {
        AcademicData.ReferenceData references = gateway.references();
        for (AcademicData.Term term : references.terms()) {
            for (int pageNumber = 1; pageNumber <= 1_000; pageNumber++) {
                AcademicData.SectionPage page = gateway.sections(term.id(), "", pageNumber);
                AcademicData.TeachingSection match = page.rows().stream()
                        .filter(section -> section.id() == sectionId).findFirst().orElse(null);
                if (match != null) return match;
                if (pageNumber * page.pageSize() >= page.total()) break;
            }
        }
        throw new IOException("未找到通知对应的教学班，可能已删除或当前账号无权访问");
    }

    private static void requireFx() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("教务界面必须在 JavaFX 线程操作");
        }
    }
}
