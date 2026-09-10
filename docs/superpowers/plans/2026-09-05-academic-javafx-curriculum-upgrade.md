# Academic Curriculum and JavaFX Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add versioned major-specific curricula, conflict-safe schedule publication, section selection and atomic switching, then migrate every academic workflow to a native JavaFX workspace.

**Architecture:** Preserve MySQL → application server → length-prefixed Socket → JavaFX client. Keep the existing `AcademicRepository` responsible for courses, enrollment, rosters and grades; add focused curriculum and schedule-revision repositories, expose them through `AcademicService`, and consume typed response decoders through a session-scoped JavaFX gateway/controller/view stack. Migrate complete role workflows in stages and retain explicit `--swing` compatibility until native parity is verified.

**Tech Stack:** Java 21, JavaFX 21.0.11, CSS, MySQL 8, JDBC, H2 MySQL mode, Socket, `MessageCodec`, `RowCodec`, Maven, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-05-academic-javafx-curriculum-design.md`

## Global Constraints

- Preserve MySQL → application server → client; `vcampus-client` must not import JDBC or connect to MySQL.
- Keep shared action names, enums and pure validation policies in `vcampus-common`.
- Keep Socket handling, business transactions, permissions and JDBC in `vcampus-server`.
- Continue using the length-prefixed `MessageCodec`; do not introduce Java native object serialization.
- Run Socket work off the JavaFX Application Thread and mutate JavaFX nodes only on that thread.
- Java release stays 21 and JavaFX stays 21.0.11; add no UI framework or FXML dependency.
- Use `academic.` for every new protocol action.
- The working tree already contains unrelated accepted changes; stage only the exact files listed by each task and never reset or overwrite unrelated edits.
- Use `database/migrations/014_academic_curriculum_javafx.sql`; migration 013 is reserved by the existing shop JavaFX design.
- Update `database/schema.sql`, `docs/requirements.md` and `docs/academic-management.md` with the code they describe.
- Root verification command is `mvn clean verify`.

## File Map

### Shared vocabulary

- `vcampus-common/src/main/java/com/vcampus/common/model/CourseRequirementType.java`: required/elective vocabulary.
- `vcampus-common/src/main/java/com/vcampus/common/model/CurriculumPlanStatus.java`: curriculum state transitions.
- `vcampus-common/src/main/java/com/vcampus/common/model/ScheduleRevisionStatus.java`: schedule revision state transitions.
- `vcampus-common/src/main/java/com/vcampus/common/model/CurriculumPlanPolicy.java`: year-range and state-transition validation.
- `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`: new `academic.` protocol constants.

### Server

- `CurriculumRepository.java`: curriculum persistence, matching, copy and publication locks.
- `ScheduleRevisionRepository.java`: draft schedule persistence, conflict detection and publication.
- `AcademicRepository.java`: existing course/section/enrollment/grade persistence plus section targets, grouped availability and atomic switch.
- `AcademicService.java`: request validation, permissions and response encoding for all academic actions.
- `RequestRouter.java`: routes new actions to `AcademicService`.
- `VCampusServer.java`: constructs the two new repositories and injects them into `AcademicService`.

### JavaFX client

- `AcademicData.java`: immutable typed view data and strict response decoders.
- `AcademicCommands.java`: immutable form/command values passed from views to the gateway.
- `AcademicGateway.java`: synchronous typed academic boundary.
- `SocketAcademicGateway.java`: maps typed calls to `VCampusClient` and response decoders.
- `AcademicController.java`: FX-thread lifecycle, request generations, navigation and mutations.
- `AcademicWorkspaceView.java`: role-aware academic navigation and shared loading/error host.
- `vcampus-client/src/main/resources/com/vcampus/client/fx/academic/academic.css`: academic workspace, tables, course cards and schedule-grid styles.
- Focused views: course catalog, curriculum, section management, schedule editor, enrollment center, student schedule and teacher workspace.

---

### Task 1: Add Shared Academic Vocabulary and Actions

**Files:**
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/CourseRequirementType.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/CurriculumPlanStatus.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/ScheduleRevisionStatus.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/CurriculumPlanPolicy.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/NotificationType.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/NotificationTarget.java`
- Create: `vcampus-common/src/test/java/com/vcampus/common/model/CurriculumPlanPolicyTest.java`
- Create: `vcampus-common/src/test/java/com/vcampus/common/protocol/AcademicActionsTest.java`

**Interfaces:**
- Produces: `CourseRequirementType`, `CurriculumPlanStatus`, `ScheduleRevisionStatus` with Chinese `displayName()`.
- Produces: `CurriculumPlanPolicy.validateYears(int,int)`, `requireEditable(CurriculumPlanStatus)`, `canTransition(CurriculumPlanStatus,CurriculumPlanStatus)`.
- Produces: constants matching every action in section 6 of the spec.

- [ ] **Step 1: Write failing vocabulary and transition tests**

```java
@Test void publishedCurriculumIsImmutableButCanBeArchived() {
    assertThrows(IllegalStateException.class,
            () -> CurriculumPlanPolicy.requireEditable(CurriculumPlanStatus.PUBLISHED));
    assertTrue(CurriculumPlanPolicy.canTransition(
            CurriculumPlanStatus.DRAFT, CurriculumPlanStatus.PUBLISHED));
    assertTrue(CurriculumPlanPolicy.canTransition(
            CurriculumPlanStatus.PUBLISHED, CurriculumPlanStatus.ARCHIVED));
    assertFalse(CurriculumPlanPolicy.canTransition(
            CurriculumPlanStatus.ARCHIVED, CurriculumPlanStatus.PUBLISHED));
}

@Test void academicActionsUseTheRequiredPrefix() {
    assertEquals("academic.enrollment.switchSection",
            Actions.ACADEMIC_ENROLLMENT_SWITCH_SECTION);
    assertTrue(Actions.ACADEMIC_SCHEDULE_DRAFT_SAVE.startsWith("academic."));
}
```

- [ ] **Step 2: Run the common tests and verify they fail**

Run: `mvn -pl vcampus-common -Dtest=CurriculumPlanPolicyTest,AcademicActionsTest test`

Expected: compilation fails because the new enums, policy and action constants do not exist.

- [ ] **Step 3: Implement enums, policy and exact action constants**

```java
public enum CourseRequirementType {
    REQUIRED("必修"), ELECTIVE("选修");
    private final String displayName;
    CourseRequirementType(String displayName) { this.displayName = displayName; }
    public String displayName() { return displayName; }
}

public final class CurriculumPlanPolicy {
    public static void validateYears(int from, int to) {
        if (from < 2000 || to > 2100 || from > to) {
            throw new IllegalArgumentException("适用入学年份必须在 2000—2100 之间且起始年份不晚于结束年份");
        }
    }
    public static void requireEditable(CurriculumPlanStatus status) {
        if (status != CurriculumPlanStatus.DRAFT) {
            throw new IllegalStateException("只有草稿培养方案可以修改");
        }
    }
}
```

Add these exact constants and values:

```java
public static final String ACADEMIC_CURRICULUM_SEARCH = "academic.curriculum.search";
public static final String ACADEMIC_CURRICULUM_GET = "academic.curriculum.get";
public static final String ACADEMIC_CURRICULUM_CREATE = "academic.curriculum.create";
public static final String ACADEMIC_CURRICULUM_UPDATE = "academic.curriculum.update";
public static final String ACADEMIC_CURRICULUM_COPY = "academic.curriculum.copy";
public static final String ACADEMIC_CURRICULUM_PUBLISH = "academic.curriculum.publish";
public static final String ACADEMIC_CURRICULUM_ARCHIVE = "academic.curriculum.archive";
public static final String ACADEMIC_CURRICULUM_COURSE_ADD = "academic.curriculum.course.add";
public static final String ACADEMIC_CURRICULUM_COURSE_UPDATE = "academic.curriculum.course.update";
public static final String ACADEMIC_CURRICULUM_COURSE_REMOVE = "academic.curriculum.course.remove";
public static final String ACADEMIC_SECTION_TARGETS_GET = "academic.section.targets.get";
public static final String ACADEMIC_SECTION_TARGETS_SAVE = "academic.section.targets.save";
public static final String ACADEMIC_SCHEDULE_DRAFT_GET = "academic.schedule.draft.get";
public static final String ACADEMIC_SCHEDULE_DRAFT_SAVE = "academic.schedule.draft.save";
public static final String ACADEMIC_SCHEDULE_PUBLISH = "academic.schedule.publish";
public static final String ACADEMIC_ENROLLMENT_SWITCH_SECTION = "academic.enrollment.switchSection";
```

Add `SCHEDULE_CHANGED` to `NotificationType` and `ACADEMIC_SCHEDULE` to `NotificationTarget` in this task so database checks and routing use one shared vocabulary.

- [ ] **Step 4: Run the common module tests**

Run: `mvn -pl vcampus-common test`

Expected: all common tests pass, including existing protocol compatibility tests.

- [ ] **Step 5: Commit shared vocabulary**

```powershell
git add -- vcampus-common/src/main/java/com/vcampus/common/model/CourseRequirementType.java vcampus-common/src/main/java/com/vcampus/common/model/CurriculumPlanStatus.java vcampus-common/src/main/java/com/vcampus/common/model/ScheduleRevisionStatus.java vcampus-common/src/main/java/com/vcampus/common/model/CurriculumPlanPolicy.java vcampus-common/src/main/java/com/vcampus/common/model/NotificationType.java vcampus-common/src/main/java/com/vcampus/common/model/NotificationTarget.java vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java vcampus-common/src/test/java/com/vcampus/common/model/CurriculumPlanPolicyTest.java vcampus-common/src/test/java/com/vcampus/common/protocol/AcademicActionsTest.java
git commit -m "feat(academic): add curriculum vocabulary"
```

### Task 2: Add the Curriculum and Schedule Revision Schema

**Files:**
- Create: `database/migrations/014_academic_curriculum_javafx.sql`
- Modify: `database/schema.sql`
- Modify: `database/seed.sql`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/AcademicCurriculumMigrationTest.java`

**Interfaces:**
- Produces: tables `curriculum_plans`, `curriculum_plan_courses`, `course_section_targets`, `course_section_schedule_revisions`.
- Produces: required `class_schedules.revision_id` and enforced `(revision_id, section_id)` consistency.
- Produces: deterministic anonymous seed curricula for the existing sample majors and courses.

- [ ] **Step 1: Write a failing migration contract test**

```java
@Test void migrationAndFreshSchemaDefineCurriculumAndRevisionTables() throws Exception {
    String migration = Files.readString(Path.of("..", "database", "migrations",
            "014_academic_curriculum_javafx.sql"));
    String schema = Files.readString(Path.of("..", "database", "schema.sql"));
    for (String table : List.of("curriculum_plans", "curriculum_plan_courses",
            "course_section_targets", "course_section_schedule_revisions")) {
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS " + table));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS " + table));
    }
    assertTrue(migration.contains("ADD COLUMN revision_id"));
    assertTrue(schema.contains("FOREIGN KEY (revision_id, section_id)"));
    for (String literal : List.of("SCHEDULE_CHANGED", "ACADEMIC_SCHEDULE")) {
        assertTrue(migration.contains("'" + literal + "'"));
        assertTrue(schema.contains("'" + literal + "'"));
    }
}
```

- [ ] **Step 2: Run the migration test and verify it fails**

Run: `mvn -pl vcampus-server -am -Dtest=AcademicCurriculumMigrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because migration 014 and its tables are absent.

- [ ] **Step 3: Write the guarded migration and matching fresh schema**

Use these state checks and indexes in both definitions:

```sql
CREATE TABLE IF NOT EXISTS curriculum_plans (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    major_id BIGINT NOT NULL,
    plan_name VARCHAR(120) NOT NULL,
    version_no INT NOT NULL,
    enrollment_year_start SMALLINT NOT NULL,
    enrollment_year_end SMALLINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_by_user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_by_user_id BIGINT NULL,
    published_at TIMESTAMP NULL,
    UNIQUE KEY uk_curriculum_major_version (major_id, version_no),
    INDEX idx_curriculum_match (major_id, status, enrollment_year_start, enrollment_year_end),
    CONSTRAINT chk_curriculum_years CHECK
        (enrollment_year_start BETWEEN 2000 AND 2100
         AND enrollment_year_end BETWEEN enrollment_year_start AND 2100),
    CONSTRAINT chk_curriculum_status CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    FOREIGN KEY (major_id) REFERENCES majors(id),
    FOREIGN KEY (created_by_user_id) REFERENCES users(id),
    FOREIGN KEY (published_by_user_id) REFERENCES users(id)
);
```

Create `curriculum_plan_courses` with `plan_id`, `course_id`, `requirement_type`, `recommended_term_number`, a unique key on `(plan_id, course_id)`, checks for `REQUIRED`/`ELECTIVE` and term 1—12, and foreign keys to plans and courses. Create `course_section_targets` with `section_id`, `major_id`, both year bounds, a unique key on all four business columns, year checks matching `CurriculumPlanPolicy`, and foreign keys to sections and majors. Create `course_section_schedule_revisions` with `section_id`, `revision_no`, status, creator/publisher audit columns, a unique key on `(section_id, revision_no)`, `UNIQUE KEY uk_schedule_revision_identity (id, section_id)`, status/audit checks, and foreign keys to sections and users.

Refresh `chk_notification_type` and `chk_notification_target` in both migration and fresh schema so they include `SCHEDULE_CHANGED` and `ACADEMIC_SCHEDULE` while preserving every existing literal.

The migration must create one `PUBLISHED` revision for every existing section with schedule rows, backfill `class_schedules.revision_id`, make it non-null, then add the composite foreign key. Use `information_schema` guards consistent with the repository's existing guarded migrations.

- [ ] **Step 4: Seed an idempotent demonstration curriculum**

```sql
INSERT INTO curriculum_plans
    (major_id, plan_name, version_no, enrollment_year_start, enrollment_year_end,
     status, created_by_user_id, published_by_user_id, published_at)
SELECT m.id, CONCAT(m.major_name, ' 2024—2027培养方案'), 1, 2024, 2027,
       'PUBLISHED', creator.id, creator.id, CURRENT_TIMESTAMP
  FROM majors m
  JOIN (
        SELECT MIN(u.id) AS id
          FROM users u
          JOIN user_roles ur ON ur.user_id = u.id
          JOIN roles r ON r.id = ur.role_id
         WHERE u.enabled = TRUE AND r.role_code = 'SUPER_ADMIN'
       ) creator ON creator.id IS NOT NULL
ON DUPLICATE KEY UPDATE plan_name = VALUES(plan_name);
```

Insert existing seed courses into the seeded plans with an `INSERT`-from-`SELECT` statement and `ON DUPLICATE KEY UPDATE`, marking `C000001` as `REQUIRED` and `C000002`/`C000003` as `ELECTIVE`. When no enabled super administrator exists yet, the guarded seed inserts no plan; rerunning `seed.sql` after secure administrator bootstrap creates it. Do not add names, accounts or identifiers beyond existing anonymized seed entities.

- [ ] **Step 5: Run migration and existing database contract tests**

Run: `mvn -pl vcampus-server -am -Dtest=AcademicCurriculumMigrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS; migration and fresh schema contain the same tables, constraints, backfill and notification vocabulary.

- [ ] **Step 6: Commit the schema slice**

```powershell
git add -- database/migrations/014_academic_curriculum_javafx.sql database/schema.sql database/seed.sql vcampus-server/src/test/java/com/vcampus/server/database/AcademicCurriculumMigrationTest.java
git commit -m "feat(academic): add curriculum and schedule schema"
```

### Task 3: Implement Curriculum Persistence and Matching

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/CurriculumRepository.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/CurriculumRepositoryTest.java`

**Interfaces:**
- Produces: `CurriculumPage search(CurriculumQuery query)`, `CurriculumDetail get(long planId)`, `long create(CreateCurriculum command,long operatorId)`, `void update(UpdateCurriculum command,long operatorId)`, `long copy(long sourcePlanId,CreateCurriculum target,long operatorId)`, `void addCourse(long planId,long courseId,CourseRequirementType type,int recommendedTerm,long operatorId)`, `void updateCourse(long planId,long courseId,CourseRequirementType type,int recommendedTerm,long operatorId)`, `void removeCourse(long planId,long courseId,long operatorId)`, `void publish(long planId,long operatorId)`, `void archive(long planId,long operatorId)`, `MatchedCurriculum matchForStudent(long userId)`.
- Produces records: `CurriculumQuery`, `CurriculumPage`, `CurriculumSummary`, `CurriculumDetail`, `CurriculumCourse`, `CreateCurriculum`, `UpdateCurriculum`, `MatchedCurriculum`.

- [ ] **Step 1: Write repository tests for matching and immutability**

```java
@Test void sameCourseCanHaveDifferentRequirementByMajor() throws Exception {
    long cs = repository.create(plan(10, 2025, 2027), 1);
    long art = repository.create(plan(20, 2025, 2027), 1);
    repository.addCourse(cs, 100, REQUIRED, 2, 1);
    repository.addCourse(art, 100, ELECTIVE, 3, 1);
    repository.publish(cs, 1);
    repository.publish(art, 1);

    assertEquals(REQUIRED, repository.matchForStudent(501).courses().getFirst().requirementType());
    assertEquals(ELECTIVE, repository.matchForStudent(502).courses().getFirst().requirementType());
}

@Test void publishedRangesForOneMajorCannotOverlap() throws Exception {
    long first = publishedPlan(10, 2024, 2026);
    long second = repository.create(plan(10, 2026, 2028), 1);
    assertThrows(AcademicRuleException.class, () -> repository.publish(second, 1));
}
```

Also test copy creates an editable higher version, published course rows reject mutation, archive rejects active matching students, and no unique plan returns “未配置适用培养方案”.

- [ ] **Step 2: Run the repository test and verify it fails**

Run: `mvn -pl vcampus-server -am -Dtest=CurriculumRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because `CurriculumRepository` does not exist.

- [ ] **Step 3: Implement focused SQL methods with publication locks**

```java
public MatchedCurriculum matchForStudent(long userId) throws SQLException {
    try (Connection connection = connectionFactory.openConnection()) {
        List<CurriculumSummary> matches = findPublishedMatches(connection, userId);
        if (matches.isEmpty()) throw new AcademicRuleException("未配置适用培养方案");
        if (matches.size() > 1) throw new AcademicRuleException("培养方案配置存在重叠，请联系教务管理员");
        CurriculumSummary plan = matches.getFirst();
        return new MatchedCurriculum(plan, loadCourses(connection, plan.id()));
    }
}

public void publish(long planId, long operatorUserId) throws SQLException {
    try (Connection connection = connectionFactory.openConnection()) {
        connection.setAutoCommit(false);
        try {
            LockedPlan plan = lockPlan(connection, planId);
            CurriculumPlanPolicy.requireEditable(plan.status());
            requireCourseCount(connection, planId);
            rejectOverlappingPublishedRange(connection, plan);
            markPublished(connection, planId, operatorUserId);
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            connection.rollback();
            throw failure;
        }
    }
}
```

Use prepared statements for all filters. Do not concatenate client-provided sort or status values. Use `CurriculumPlanPolicy` before writes and repeat state checks under row locks.

- [ ] **Step 4: Run curriculum repository tests**

Run: `mvn -pl vcampus-server -am -Dtest=CurriculumRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: all matching, copy, publish, archive and immutability tests pass.

- [ ] **Step 5: Commit curriculum persistence**

```powershell
git add -- vcampus-server/src/main/java/com/vcampus/server/database/CurriculumRepository.java vcampus-server/src/test/java/com/vcampus/server/database/CurriculumRepositoryTest.java
git commit -m "feat(academic): persist versioned curricula"
```

### Task 4: Expose Curriculum Actions Through the Server

**Files:**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/AcademicService.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/service/AcademicCurriculumServiceTest.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterAcademicTest.java`

**Interfaces:**
- Consumes: all `CurriculumRepository` records and methods from Task 3.
- Produces: service methods `searchCurricula`, `getCurriculum`, `createCurriculum`, `updateCurriculum`, `copyCurriculum`, `publishCurriculum`, `archiveCurriculum`, `addCurriculumCourse`, `updateCurriculumCourse`, `removeCurriculumCourse`.
- Produces: curriculum rows encoded with `RowCodec` and `schemaVersion=2` metadata.

- [ ] **Step 1: Write failing permission and response-contract tests**

```java
@Test void onlyManagersCanCreateCurricula() {
    assertFalse(service.createCurriculum(studentRequest()).success());
    assertEquals("没有执行该操作的权限", service.createCurriculum(studentRequest()).message());
}

@Test void detailResponseCarriesStableVersionedRows() {
    ResponseMessage response = service.getCurriculum(adminRequest("planId", "10"));
    assertEquals("2", response.data().get("schemaVersion"));
    assertEquals(11, RowCodec.decode(response.data().get("plan")).size());
    assertEquals(6, RowCodec.decode(response.data().get("course.0")).size());
}
```

- [ ] **Step 2: Run service tests and verify they fail**

Run: `mvn -pl vcampus-server -am -Dtest=AcademicCurriculumServiceTest,RequestRouterAcademicTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because curriculum service methods and routes are absent.

- [ ] **Step 3: Inject `CurriculumRepository` and implement request parsing**

```java
public AcademicService(AcademicRepository academic,
        CurriculumRepository curricula, SessionManager sessions) {
    this.academic = Objects.requireNonNull(academic);
    this.curricula = Objects.requireNonNull(curricula);
    this.sessions = Objects.requireNonNull(sessions);
}
```

Keep the existing two-argument constructor only until all tests and server wiring are migrated; have it supply an explicit unsupported curriculum collaborator that fails if new actions are invoked, then remove incomplete constructors in Task 15 after all callers use the final four-argument constructor introduced by Task 6.

Validate names at 1—120 characters, version as a positive integer, years through `CurriculumPlanPolicy`, suggested term at 1—12, and enum values through `valueOf` with a user-facing failure message.

Use these exact `schemaVersion=2` row contracts:

```text
search row.N = planId, majorId, majorName, planName, versionNo,
               yearFrom, yearTo, status, courseCount
detail plan   = planId, majorId, majorName, planName, versionNo,
                yearFrom, yearTo, status, createdAt, publishedBy, publishedAt
detail course.N = courseId, courseCode, courseName, credits,
                  requirementType, recommendedTermNumber
```

Timestamps use ISO-8601 instants; absent publisher/timestamp fields are empty strings. Include `page`, `pageSize`, `total`, `count` for search and `course.count` for detail.

- [ ] **Step 4: Add router cases and production construction**

```java
case Actions.ACADEMIC_CURRICULUM_SEARCH -> academicService.searchCurricula(request);
case Actions.ACADEMIC_CURRICULUM_GET -> academicService.getCurriculum(request);
case Actions.ACADEMIC_CURRICULUM_CREATE -> academicService.createCurriculum(request);
case Actions.ACADEMIC_CURRICULUM_UPDATE -> academicService.updateCurriculum(request);
case Actions.ACADEMIC_CURRICULUM_COPY -> academicService.copyCurriculum(request);
case Actions.ACADEMIC_CURRICULUM_PUBLISH -> academicService.publishCurriculum(request);
case Actions.ACADEMIC_CURRICULUM_ARCHIVE -> academicService.archiveCurriculum(request);
case Actions.ACADEMIC_CURRICULUM_COURSE_ADD -> academicService.addCurriculumCourse(request);
case Actions.ACADEMIC_CURRICULUM_COURSE_UPDATE -> academicService.updateCurriculumCourse(request);
case Actions.ACADEMIC_CURRICULUM_COURSE_REMOVE -> academicService.removeCurriculumCourse(request);
```

In `VCampusServer`, create `CurriculumRepository` with the same `ConnectionFactory` used by `AcademicRepository` and inject it through the three-argument `AcademicService` constructor. Schedule repository wiring is added when that class exists in Task 6.

- [ ] **Step 5: Run service, router and authentication tests**

Run: `mvn -pl vcampus-server -am -Dtest=AcademicCurriculumServiceTest,RequestRouterAcademicTest,AuthServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS; forced-password sessions remain unable to reach new academic actions.

- [ ] **Step 6: Commit curriculum service actions**

```powershell
git add -- vcampus-server/src/main/java/com/vcampus/server/service/AcademicService.java vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java vcampus-server/src/test/java/com/vcampus/server/service/AcademicCurriculumServiceTest.java vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterAcademicTest.java
git commit -m "feat(academic): expose curriculum actions"
```

### Task 5: Add Teaching-Section Targets and Curriculum-Aware Availability

**Files:**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/AcademicRepository.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/AcademicService.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/AcademicSectionTargetTest.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/service/AcademicAvailabilityContractTest.java`

**Interfaces:**
- Produces: `AcademicRepository.SectionTarget`, `getSectionTargets(long)`, `saveSectionTargets(long,List<SectionTarget>)`.
- Produces: `AcademicRepository.AvailableCourse` containing course identity, requirement type, recommended term and child `AvailableSection` rows.
- Produces: `availableCourseGroups(long userId,long termId)` while retaining the existing `availableSections(long userId,long termId)` flat result for Swing compatibility.
- Produces: `AcademicService.getSectionTargets(RequestMessage)` and `saveSectionTargets(RequestMessage)` routed from the two section-target action constants.

- [ ] **Step 1: Write failing target and availability tests**

```java
@Test void explicitTargetRequiresBothCurriculumAndRangeMatch() throws Exception {
    repository.saveSectionTargets(700, List.of(new SectionTarget(10, 2025, 2026)));
    assertTrue(repository.availableCourseGroups(studentUser(10, 2025), term).getFirst()
            .sections().stream().anyMatch(row -> row.sectionId() == 700));
    assertFalse(repository.availableCourseGroups(studentUser(10, 2027), term).stream()
            .flatMap(row -> row.sections().stream()).anyMatch(row -> row.sectionId() == 700));
}

@Test void responseKeepsLegacyRowsAndAddsCourseGroups() {
    ResponseMessage response = service.availableSections(studentRequest(term));
    assertNotNull(response.data().get("row.0"));
    assertEquals("2", response.data().get("catalogSchemaVersion"));
    assertNotNull(response.data().get("course.0"));
    assertNotNull(response.data().get("course.0.section.0"));
}
```

- [ ] **Step 2: Run the target tests and verify they fail**

Run: `mvn -pl vcampus-server -am -Dtest=AcademicSectionTargetTest,AcademicAvailabilityContractTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because target and grouped-availability APIs do not exist.

- [ ] **Step 3: Implement target replacement and grouped queries**

```java
public void saveSectionTargets(long sectionId, List<SectionTarget> targets) throws SQLException {
    try (Connection connection = connectionFactory.openConnection()) {
        connection.setAutoCommit(false);
        try {
            lockSection(connection, sectionId);
            targets.forEach(target -> CurriculumPlanPolicy.validateYears(
                    target.enrollmentYearStart(), target.enrollmentYearEnd()));
            deleteSectionTargets(connection, sectionId);
            insertSectionTargets(connection, sectionId, targets);
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            connection.rollback();
            throw failure;
        }
    }
}

public record AvailableCourse(long courseId, String courseCode, String courseName,
        BigDecimal credits, CourseRequirementType requirementType,
        int recommendedTermNumber, List<AvailableSection> sections) {}
```

The availability SQL must join the student's unique published curriculum and its plan-course row before returning a section. Apply explicit target ranges only when at least one target exists. Return current enrollment and conflict flags without trusting them for later mutation.

- [ ] **Step 4: Encode both versioned course groups and legacy flat rows**

Use `course.N` for course identity and requirement metadata, `course.N.section.count`, and `course.N.section.M` for section details. Keep current `count` and `row.N` keys unchanged for the Swing decoder through the compatibility period. Add both target action cases to `RequestRouter` and test student rejection plus academic-manager success.

- [ ] **Step 5: Run availability and existing academic tests**

Run: `mvn -pl vcampus-server -am -Dtest=AcademicSectionTargetTest,AcademicAvailabilityContractTest,AcademicNotificationTransactionTest,AcademicReferenceDatesTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS with both response formats present.

- [ ] **Step 6: Commit targets and availability**

```powershell
git add -- vcampus-server/src/main/java/com/vcampus/server/database/AcademicRepository.java vcampus-server/src/main/java/com/vcampus/server/service/AcademicService.java vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java vcampus-server/src/test/java/com/vcampus/server/database/AcademicSectionTargetTest.java vcampus-server/src/test/java/com/vcampus/server/service/AcademicAvailabilityContractTest.java
git commit -m "feat(academic): scope sections by curriculum"
```

### Task 6: Implement Draft and Published Schedule Revisions

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/ScheduleRevisionRepository.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/AcademicRepository.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/AcademicService.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/database/AcademicNotificationTransactionTest.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/ScheduleRevisionRepositoryTest.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/service/AcademicScheduleServiceTest.java`

**Interfaces:**
- Produces records: `ScheduleDraft`, `ScheduleRevision`, `ScheduleConflict`, `SaveScheduleDraft`, `PublishSchedule`.
- Produces: `getOrCreateDraft(long,long)`, `saveDraft(SaveScheduleDraft,long)`, `publish(PublishSchedule,long,String)`.
- Changes schedule reads to select only the current `PUBLISHED` revision.
- Consumes notification vocabulary `SCHEDULE_CHANGED` with target `ACADEMIC_SCHEDULE` from Task 1.

- [ ] **Step 1: Write failing revision, conflict and visibility tests**

```java
@Test void studentsKeepPublishedScheduleWhileAdminEditsDraft() throws Exception {
    long published = seedPublished(section, mondayMorning());
    ScheduleDraft draft = repository.getOrCreateDraft(section, admin);
    repository.saveDraft(new SaveScheduleDraft(section, draft.revisionId(),
            draft.revisionNo(), List.of(wednesdayAfternoon())), admin);
    assertEquals(mondayMorning(), academic.studentSchedule(student, term).getFirst().slot());
}

@Test void publishRejectsTeacherAndRoomConflicts() {
    ScheduleConflict conflict = assertThrows(ScheduleConflictException.class,
            () -> repository.publish(commandFor(overlappingTeacherSlot()), admin, "管理员"))
            .conflicts().getFirst();
    assertEquals(ConflictKind.TEACHER, conflict.kind());
}
```

Also test stale revision numbers, same-section internal overlaps, old published revision becoming `SUPERSEDED`, and notification rollback.

- [ ] **Step 2: Run schedule tests and verify they fail**

Run: `mvn -pl vcampus-server -am -Dtest=ScheduleRevisionRepositoryTest,AcademicScheduleServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because revision repository and actions do not exist.

- [ ] **Step 3: Implement revision storage and conflict queries**

```java
public ScheduleDraft saveDraft(SaveScheduleDraft command, long operatorId) throws SQLException {
    try (Connection connection = connectionFactory.openConnection()) {
        connection.setAutoCommit(false);
        try {
            LockedRevision draft = lockDraft(connection, command.revisionId());
            requireExpectedRevision(draft, command.expectedRevisionNo());
            validateSlots(command.slots());
            replaceSlots(connection, draft.sectionId(), draft.id(), command.slots());
            incrementRevisionNo(connection, draft.id());
            connection.commit();
            return loadDraft(connection, draft.sectionId());
        } catch (SQLException | RuntimeException failure) {
            connection.rollback();
            throw failure;
        }
    }
}

public ScheduleRevision publish(PublishSchedule command,
        long operatorId, String operatorName) throws SQLException {
    try (Connection connection = connectionFactory.openConnection()) {
        connection.setAutoCommit(false);
        try {
            LockedRevision draft = lockDraft(connection, command.revisionId());
            requireExpectedRevision(draft, command.expectedRevisionNo());
            List<ScheduleConflict> conflicts = findPublicationConflicts(connection, draft);
            if (!conflicts.isEmpty()) throw new ScheduleConflictException(conflicts);
            supersedeCurrentPublished(connection, draft.sectionId());
            markPublished(connection, draft.id(), operatorId);
            notifications.insertBatch(connection,
                    scheduleNotifications(connection, draft.sectionId(), operatorId, operatorName));
            connection.commit();
            return loadRevision(connection, draft.id());
        } catch (SQLException | RuntimeException failure) {
            connection.rollback();
            throw failure;
        }
    }
}
```

Conflict comparisons must use inclusive weekday, period and week intersections. Lock and publish in one connection. A failed notification write rolls back all schedule status changes.

- [ ] **Step 4: Route and encode schedule draft actions**

Use `scheduleRevisionId`, `expectedRevisionNo`, `slot.count`, and six-field `RowCodec` slots. Return conflict rows as kind, related entity ID, display name, weekday, start/end period and start/end week.

Expand `AcademicService` to its final constructor and update `VCampusServer` to pass the production repository:

```java
public AcademicService(AcademicRepository academic, CurriculumRepository curricula,
        ScheduleRevisionRepository schedules, SessionManager sessions) {
    this.academic = Objects.requireNonNull(academic);
    this.curricula = Objects.requireNonNull(curricula);
    this.schedules = Objects.requireNonNull(schedules);
    this.sessions = Objects.requireNonNull(sessions);
}
```

- [ ] **Step 5: Update existing schedule reads and section creation**

Creating a teaching section always creates its first schedule revision and slots. `academic.section.create` accepts optional `publishSchedule`: omission defaults to `true` for existing Swing clients and creates a published revision transactionally; the JavaFX gateway sends `false`, leaving a draft for the publication workflow. The server authorizes both modes identically and never infers client type.

- [ ] **Step 6: Run schedule, notification and protocol tests**

Run: `mvn -pl vcampus-server -am -Dtest=ScheduleRevisionRepositoryTest,AcademicScheduleServiceTest,AcademicNotificationTransactionTest,RequestRouterAcademicTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS; unpublished drafts never appear in student or teacher schedule queries.

- [ ] **Step 7: Commit schedule revisions**

```powershell
git add -- vcampus-server/src/main/java/com/vcampus/server/database/ScheduleRevisionRepository.java vcampus-server/src/main/java/com/vcampus/server/database/AcademicRepository.java vcampus-server/src/main/java/com/vcampus/server/service/AcademicService.java vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java vcampus-server/src/test/java/com/vcampus/server/database/ScheduleRevisionRepositoryTest.java vcampus-server/src/test/java/com/vcampus/server/service/AcademicScheduleServiceTest.java vcampus-server/src/test/java/com/vcampus/server/database/AcademicNotificationTransactionTest.java
git commit -m "feat(academic): publish versioned schedules"
```

### Task 7: Implement Atomic Teaching-Section Switching

**Files:**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/AcademicRepository.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/AcademicService.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/AcademicSectionSwitchTest.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterAcademicTest.java`

**Interfaces:**
- Produces: `AcademicRepository.switchSection(long userId,long fromSectionId,long toSectionId)`.
- Produces: `AcademicService.switchSection(RequestMessage)` routed from `Actions.ACADEMIC_ENROLLMENT_SWITCH_SECTION`.

- [ ] **Step 1: Write failing transaction tests**

```java
@Test void switchesSameCourseAndUpdatesBothCounts() throws Exception {
    repository.switchSection(studentUserId, oldSection, newSection);
    assertEquals(DROPPED, enrollmentStatus(studentId, oldSection));
    assertEquals(ENROLLED, enrollmentStatus(studentId, newSection));
    assertEquals(19, enrolledCount(oldSection));
    assertEquals(21, enrolledCount(newSection));
}

@Test void fullTargetKeepsOriginalEnrollment() {
    assertThrows(AcademicRuleException.class,
            () -> repository.switchSection(studentUserId, oldSection, fullSection));
    assertEquals(ENROLLED, enrollmentStatus(studentId, oldSection));
    assertEquals(20, enrolledCount(oldSection));
}
```

Also cover different course, different term, target scope mismatch, closed target, expired selection window, and a conflict that disappears only because the old section is excluded.

- [ ] **Step 2: Run switch tests and verify they fail**

Run: `mvn -pl vcampus-server -am -Dtest=AcademicSectionSwitchTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because `switchSection` is absent.

- [ ] **Step 3: Implement deterministic locking and rollback**

```java
public void switchSection(long userId, long fromSectionId, long toSectionId)
        throws SQLException {
    if (fromSectionId == toSectionId) throw new AcademicRuleException("请选择不同的教学班");
    try (Connection connection = connectionFactory.openConnection()) {
        connection.setAutoCommit(false);
        try {
            long first = Math.min(fromSectionId, toSectionId);
            long second = Math.max(fromSectionId, toSectionId);
            EnrollmentContext firstContext = lockEnrollmentContext(connection, first);
            EnrollmentContext secondContext = lockEnrollmentContext(connection, second);
            EnrollmentContext source = fromSectionId == first ? firstContext : secondContext;
            EnrollmentContext target = toSectionId == first ? firstContext : secondContext;
            requireSwitchablePair(source, target);
            long studentId = requireActiveEnrollment(connection, userId, fromSectionId);
            requireTargetEligible(connection, studentId, toSectionId, target);
            if (hasScheduleConflict(connection, studentId, toSectionId, fromSectionId))
                throw new AcademicRuleException("目标教学班与已选课程时间冲突");
            enrollTarget(connection, studentId, toSectionId);
            dropSource(connection, studentId, fromSectionId);
            updateEnrollmentCount(connection, fromSectionId, -1);
            updateEnrollmentCount(connection, toSectionId, 1);
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            connection.rollback();
            throw failure;
        }
    }
}
```

Never implement switch as calls to the public `drop` and `enroll` methods because they open separate connections.

- [ ] **Step 4: Add service parsing and router coverage**

Parse positive `fromSectionId` and `toSectionId`; require a student session. Return the message “换班成功” only after repository commit.

- [ ] **Step 5: Run switch, enrollment and router tests**

Run: `mvn -pl vcampus-server -am -Dtest=AcademicSectionSwitchTest,RequestRouterAcademicTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS with rollback assertions unchanged after every rejected switch.

- [ ] **Step 6: Commit atomic switching**

```powershell
git add -- vcampus-server/src/main/java/com/vcampus/server/database/AcademicRepository.java vcampus-server/src/main/java/com/vcampus/server/service/AcademicService.java vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java vcampus-server/src/test/java/com/vcampus/server/database/AcademicSectionSwitchTest.java vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterAcademicTest.java
git commit -m "feat(academic): switch teaching sections atomically"
```

### Task 8: Add Typed Client Commands, Data Decoders and Gateway

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicCommands.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicData.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicGateway.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/SocketAcademicGateway.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/academic/AcademicDataTest.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/academic/SocketAcademicGatewayTest.java`

**Interfaces:**
- Produces typed commands: `CourseDraft`, `CurriculumDraft`, `CurriculumCourseDraft`, `SectionDraft`, `ScheduleDraftCommand`, `SchedulePublishCommand`, `GradeDraft`.
- Produces typed data: `ReferenceData`, `CoursePage`, `CurriculumPage`, `CurriculumDetail`, `SectionPage`, `EnrollmentCatalog`, `ScheduleDraft`, `ScheduleEntry`, `Roster`, `GradeRow`.
- Produces synchronous `AcademicGateway`; callers must invoke it off the UI thread.

- [ ] **Step 1: Write strict decoder tests**

```java
@Test void groupsTeachingSectionsUnderTheirCourse() {
    EnrollmentCatalog catalog = AcademicData.enrollmentCatalog(responseWithTwoSections());
    AvailableCourse course = catalog.courses().getFirst();
    assertEquals(CourseRequirementType.REQUIRED, course.requirementType());
    assertEquals(2, course.sections().size());
}

@Test void rejectsUnknownSchemaAndMalformedCounts() {
    assertThrows(IllegalArgumentException.class,
            () -> AcademicData.enrollmentCatalog(success(Map.of("catalogSchemaVersion", "99"))));
}
```

Test immutable copied lists, negative capacities, enrolled counts above capacity, invalid enum values, missing rows and six-field schedule slots.

- [ ] **Step 2: Run client decoder tests and verify they fail**

Run: `mvn -pl vcampus-client -am -Dtest=AcademicDataTest,SocketAcademicGatewayTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because the new academic package is absent.

- [ ] **Step 3: Define typed commands and gateway signatures**

```java
public interface AcademicGateway {
    AcademicData.ReferenceData references() throws IOException;
    AcademicData.CoursePage courses(String keyword, int page) throws IOException;
    long createCourse(AcademicCommands.CourseDraft draft) throws IOException;
    void updateCourse(long courseId, AcademicCommands.CourseDraft draft, boolean enabled) throws IOException;
    AcademicData.CurriculumPage curricula(Long majorId, CurriculumPlanStatus status, int page) throws IOException;
    AcademicData.CurriculumDetail curriculum(long planId) throws IOException;
    long createCurriculum(AcademicCommands.CurriculumDraft draft) throws IOException;
    void updateCurriculum(long planId, AcademicCommands.CurriculumDraft draft) throws IOException;
    long copyCurriculum(long planId, String name, int yearFrom, int yearTo) throws IOException;
    void saveCurriculumCourse(long planId, AcademicCommands.CurriculumCourseDraft draft) throws IOException;
    void removeCurriculumCourse(long planId, long courseId) throws IOException;
    void publishCurriculum(long planId) throws IOException;
    void archiveCurriculum(long planId) throws IOException;
    AcademicData.SectionPage sections(long termId, String keyword, int page) throws IOException;
    long createSection(AcademicCommands.SectionDraft draft) throws IOException;
    List<AcademicData.SectionTarget> sectionTargets(long sectionId) throws IOException;
    void saveSectionTargets(long sectionId, List<AcademicData.SectionTarget> targets) throws IOException;
    AcademicData.ScheduleDraft scheduleDraft(long sectionId) throws IOException;
    AcademicData.ScheduleDraft saveSchedule(AcademicCommands.ScheduleDraftCommand command) throws IOException;
    void publishSchedule(AcademicCommands.SchedulePublishCommand command) throws IOException;
    AcademicData.EnrollmentCatalog enrollmentCatalog(long termId) throws IOException;
    void enroll(long sectionId) throws IOException;
    void drop(long sectionId) throws IOException;
    void switchSection(long fromSectionId, long toSectionId) throws IOException;
    List<AcademicData.ScheduleEntry> studentSchedule(long termId) throws IOException;
    List<AcademicData.ScheduleEntry> teacherSchedule(long termId, Long teacherUserId) throws IOException;
    List<AcademicData.TeachingSection> teacherSections(long termId) throws IOException;
    AcademicData.Roster roster(long sectionId) throws IOException;
    void saveGrade(long sectionId, long enrollmentId,
            AcademicCommands.GradeDraft draft) throws IOException;
    void publishGrades(long sectionId) throws IOException;
    List<AcademicData.GradeRow> myGrades() throws IOException;
}
```

- [ ] **Step 4: Implement response validation and Socket mapping**

```java
private ResponseMessage requireSuccess(ResponseMessage response) throws IOException {
    if (response == null) throw new IOException("服务器未返回数据");
    if (!response.success()) throw new IOException(response.message());
    return response;
}
```

`SocketAcademicGateway` stores only `VCampusClient` and session token. It maps command records to parameter maps and decodes responses; it must not own threads or JavaFX types.

- [ ] **Step 5: Run typed gateway and legacy decoder tests**

Run: `mvn -pl vcampus-client -am -Dtest=AcademicDataTest,SocketAcademicGatewayTest,AcademicModuleNavigationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS; the old Swing `AcademicViewData` decoder still accepts compatibility rows.

- [ ] **Step 6: Commit typed client boundary**

```powershell
git add -- vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicCommands.java vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicData.java vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicGateway.java vcampus-client/src/main/java/com/vcampus/client/fx/academic/SocketAcademicGateway.java vcampus-client/src/test/java/com/vcampus/client/fx/academic/AcademicDataTest.java vcampus-client/src/test/java/com/vcampus/client/fx/academic/SocketAcademicGatewayTest.java
git commit -m "feat(academic): add typed JavaFX gateway"
```

### Task 9: Build the JavaFX Academic Controller and Role-Aware Workspace

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicController.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicWorkspaceView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicAsync.java`
- Create: `vcampus-client/src/main/resources/com/vcampus/client/fx/academic/academic.css`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/academic/AcademicAsyncTest.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/academic/AcademicWorkspaceNavigationTest.java`

**Interfaces:**
- Consumes: `AcademicGateway`, user roles, executor, back callback and unread-refresh callback.
- Produces: `Parent view()`, `open(String route)`, `deactivate()`, `close()`.
- Produces: cancellable request generations that discard late results.

- [ ] **Step 1: Write failing lifecycle and navigation tests**

```java
@Test void staleRequestCannotReplaceNewerPage() {
    FakeExecutor executor = new FakeExecutor();
    AcademicAsync async = new AcademicAsync(executor, Platform::runLater);
    var first = async.submit(() -> "old", rendered::add, errors::add);
    async.submit(() -> "new", rendered::add, errors::add);
    first.complete();
    assertFalse(rendered.contains("old"));
}

@Test void studentDoesNotSeeAdministrationRoutes() {
    assertEquals(Set.of("enrollment", "my-courses", "student-schedule", "grades"),
            AcademicWorkspaceView.routesFor(Set.of(UserRole.STUDENT)));
}
```

- [ ] **Step 2: Run controller tests and verify they fail**

Run: `mvn -pl vcampus-client -am -Dtest=AcademicAsyncTest,AcademicWorkspaceNavigationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because controller, workspace and async helper are absent.

- [ ] **Step 3: Implement a session-scoped cancellable controller**

```java
public final class AcademicController implements AutoCloseable {
    public AcademicController(AcademicGateway gateway, Set<UserRole> roles,
            Executor executor, Runnable back, Runnable unreadRefresh) {
        this.gateway = Objects.requireNonNull(gateway);
        this.roles = Set.copyOf(roles);
        this.async = new AcademicAsync(executor, Platform::runLater);
        this.workspace = new AcademicWorkspaceView(this.roles, back, this::open);
        this.unreadRefresh = Objects.requireNonNull(unreadRefresh);
    }
    public Parent view() { return workspace; }
    public void open(String route) { requireFx(); if (!closed) workspace.open(route); }
    public void deactivate() { requireFx(); active = false; async.invalidate(); workspace.closeDialogs(); }
    @Override public void close() { requireFx(); closed = true; deactivate(); }
}
```

Use the same FX-thread guard and late-callback protections as `LibraryController`, but centralize them in `AcademicAsync` so every academic subview shares one lifecycle.

- [ ] **Step 4: Implement role-derived navigation and shared page states**

The workspace must expose loading, content, empty and retryable failure panes. Route resolution must reject unavailable pages without constructing their controls. Multi-role users see the union in this order: student, teacher, administrator. Install `academic.css` on the workspace root and use only academic-prefixed CSS classes so rules do not leak into the campus shell.

- [ ] **Step 5: Run workspace tests**

Run: `mvn -pl vcampus-client -am -Dtest=AcademicAsyncTest,AcademicWorkspaceNavigationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS; close and deactivate cancel work and close owned dialogs.

- [ ] **Step 6: Commit controller shell**

```powershell
git add -- vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicController.java vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicWorkspaceView.java vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicAsync.java vcampus-client/src/main/resources/com/vcampus/client/fx/academic/academic.css vcampus-client/src/test/java/com/vcampus/client/fx/academic/AcademicAsyncTest.java vcampus-client/src/test/java/com/vcampus/client/fx/academic/AcademicWorkspaceNavigationTest.java
git commit -m "feat(academic): add JavaFX workspace controller"
```

### Task 10: Build the JavaFX Course Catalog

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/CourseCatalogView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/CourseForm.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicController.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/academic/CourseFormPolicyTest.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/academic/CourseCatalogViewTest.java`

**Interfaces:**
- Produces listener actions `searchCourses(String,int)`, `createCourse(CourseDraft)`, `updateCourse(long,CourseDraft,boolean)`.
- Consumes `AcademicData.CoursePage` and renders pagination, enabled state and selection.

- [ ] **Step 1: Write failing form and view-state tests**

```java
@Test void courseCodeIsRequiredOnlyForCreation() {
    assertEquals(List.of("课程号必须为字母 C 加 6 位数字"),
            CourseForm.validateCreate("JAVA", "Java程序设计", "3.0", "48", ""));
    assertTrue(CourseForm.validateUpdate("Java程序设计", "3.0", "48", "").isEmpty());
}
```

- [ ] **Step 2: Run course UI tests and verify they fail**

Run: `mvn -pl vcampus-client -am -Dtest=CourseFormPolicyTest,CourseCatalogViewTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because catalog view and form policy are absent.

- [ ] **Step 3: Implement the catalog table and form dialog**

```java
public interface Listener {
    void search(String keyword, int page);
    void create(AcademicCommands.CourseDraft draft);
    void update(long courseId, AcademicCommands.CourseDraft draft, boolean enabled);
}
```

Use a `TableView` for code, name, credits, hours and status. Preserve search text across refresh, disable mutating buttons while a request is active, and keep the dialog open with entered values after server failure.

- [ ] **Step 4: Wire controller loads and mutations through `AcademicAsync`**

After successful create/update, show the server message and reload the current page. Do not mutate a locally cached row before server success.

- [ ] **Step 5: Run catalog and lifecycle tests**

Run: `mvn -pl vcampus-client -am -Dtest=CourseFormPolicyTest,CourseCatalogViewTest,AcademicAsyncTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 6: Commit course catalog UI**

```powershell
git add -- vcampus-client/src/main/java/com/vcampus/client/fx/academic/CourseCatalogView.java vcampus-client/src/main/java/com/vcampus/client/fx/academic/CourseForm.java vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicController.java vcampus-client/src/test/java/com/vcampus/client/fx/academic/CourseFormPolicyTest.java vcampus-client/src/test/java/com/vcampus/client/fx/academic/CourseCatalogViewTest.java
git commit -m "feat(academic): build JavaFX course catalog"
```

### Task 11: Build the JavaFX Curriculum Manager

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/CurriculumPlanView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/CurriculumPlanForm.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicController.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/academic/CurriculumPlanFormTest.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/academic/CurriculumPlanViewTest.java`

**Interfaces:**
- Produces search, open, create, copy, add/update/remove course, publish and archive events.
- Consumes `CurriculumPage`, `CurriculumDetail`, reference majors and enabled courses.

- [ ] **Step 1: Write failing state and batch-edit tests**

```java
@Test void publishedPlanDisablesEveryContentMutation() {
    var state = CurriculumPlanView.actionsFor(CurriculumPlanStatus.PUBLISHED, true);
    assertFalse(state.canEdit());
    assertFalse(state.canRemoveCourse());
    assertTrue(state.canCopy());
    assertTrue(state.canArchive());
}

@Test void batchRequirementChangePreservesSuggestedTerms() {
    var changed = CurriculumPlanForm.setRequirement(rows, selectedIds, ELECTIVE);
    assertEquals(rows.getFirst().recommendedTermNumber(), changed.getFirst().recommendedTermNumber());
}
```

- [ ] **Step 2: Run curriculum UI tests and verify they fail**

Run: `mvn -pl vcampus-client -am -Dtest=CurriculumPlanFormTest,CurriculumPlanViewTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because curriculum views are absent.

- [ ] **Step 3: Implement list/detail master layout**

Use a left filter/list pane and a right plan detail. The detail table shows course code, name, credits, required/elective and suggested term. Multi-selection enables batch requirement or suggested-term changes; submit one gateway mutation per selected row in a single controller job, then reload once.

- [ ] **Step 4: Implement copy and publication confirmations**

Copy dialog requires a new name and year range. Publication confirmation summarizes course count and year scope. On version conflict or overlap error, keep the detail loaded and show the server message inline.

- [ ] **Step 5: Run curriculum UI, decoder and controller tests**

Run: `mvn -pl vcampus-client -am -Dtest=CurriculumPlanFormTest,CurriculumPlanViewTest,AcademicDataTest,AcademicAsyncTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 6: Commit curriculum manager UI**

```powershell
git add -- vcampus-client/src/main/java/com/vcampus/client/fx/academic/CurriculumPlanView.java vcampus-client/src/main/java/com/vcampus/client/fx/academic/CurriculumPlanForm.java vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicController.java vcampus-client/src/test/java/com/vcampus/client/fx/academic/CurriculumPlanFormTest.java vcampus-client/src/test/java/com/vcampus/client/fx/academic/CurriculumPlanViewTest.java
git commit -m "feat(academic): build JavaFX curriculum manager"
```

### Task 12: Build Teaching-Section Management and Smart Scheduling

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/SectionManagementView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/SectionForm.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/ScheduleEditorView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/ScheduleGrid.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicController.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/academic/ScheduleGridTest.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/academic/ScheduleEditorPolicyTest.java`

**Interfaces:**
- Produces section search/create/status/target events.
- Produces schedule draft load/save/publish events using `ScheduleDraftCommand` and `SchedulePublishCommand`.
- Consumes teacher and room occupancy conflict rows and renders blocked cells.

- [ ] **Step 1: Write failing grid-selection and stale-version tests**

```java
@Test void dragCreatesOneContiguousSlotAndSkipsBlockedCells() {
    ScheduleGrid.Selection result = ScheduleGrid.select(
            cell(MONDAY, 1), cell(MONDAY, 4), Set.of(cell(MONDAY, 3)));
    assertEquals(List.of(slot(MONDAY, 1, 2)), result.slots());
}

@Test void staleDraftKeepsLocalEditsAndRequiresReload() {
    var state = ScheduleEditorPolicy.afterFailure(localDraft, "排课草稿已被其他管理员更新");
    assertEquals(localDraft, state.unsavedDraft());
    assertTrue(state.reloadRequired());
}
```

- [ ] **Step 2: Run scheduling UI tests and verify they fail**

Run: `mvn -pl vcampus-client -am -Dtest=ScheduleGridTest,ScheduleEditorPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because the scheduling controls are absent.

- [ ] **Step 3: Implement section list, form and target editor**

Section creation selects term, course, teacher, capacity and initial target ranges; it then opens the new section's schedule draft. Target rows validate major plus 2000—2100 year ranges before submission.

- [ ] **Step 4: Implement 7 × 12 drag schedule grid**

```java
grid.setOnMousePressed(event -> selection.start(cellAt(event)));
grid.setOnMouseDragged(event -> selection.extend(cellAt(event)));
grid.setOnMouseReleased(event -> listener.addSlot(
        selection.toSlot(activeWeekFrom, activeWeekTo, classroom.getText())));
```

Blocked teacher or room cells are visible and cannot be selected. Keyboard users can focus a cell, extend selection with Shift+arrow and commit with Enter. Every cell exposes weekday and period through accessible text.

- [ ] **Step 5: Wire draft save and publish**

Send the revision ID and expected revision number returned by the last successful load/save. Save failure leaves local slots intact. Publish displays server conflict rows grouped by teacher or room; success reloads the published summary and refreshes unread notifications.

- [ ] **Step 6: Run section and schedule UI tests**

Run: `mvn -pl vcampus-client -am -Dtest=ScheduleGridTest,ScheduleEditorPolicyTest,AcademicAsyncTest,AcademicDataTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS, including keyboard and stale-response behavior.

- [ ] **Step 7: Commit section and schedule UI**

```powershell
git add -- vcampus-client/src/main/java/com/vcampus/client/fx/academic/SectionManagementView.java vcampus-client/src/main/java/com/vcampus/client/fx/academic/SectionForm.java vcampus-client/src/main/java/com/vcampus/client/fx/academic/ScheduleEditorView.java vcampus-client/src/main/java/com/vcampus/client/fx/academic/ScheduleGrid.java vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicController.java vcampus-client/src/test/java/com/vcampus/client/fx/academic/ScheduleGridTest.java vcampus-client/src/test/java/com/vcampus/client/fx/academic/ScheduleEditorPolicyTest.java
git commit -m "feat(academic): build smart JavaFX scheduling"
```

### Task 13: Build Student Enrollment, Switching, Schedule and Grades

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/EnrollmentCenterView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/StudentScheduleView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/StudentGradesView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicController.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/academic/EnrollmentCenterPolicyTest.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/academic/StudentScheduleViewTest.java`

**Interfaces:**
- Consumes grouped `EnrollmentCatalog`, published schedule entries and grade rows.
- Produces enroll, drop and atomic switch events; no client-side mutation is final before gateway success.

- [ ] **Step 1: Write failing course-group and action tests**

```java
@Test void selectedCourseOffersSwitchOnlyToAnotherEligibleSection() {
    var actions = EnrollmentCenterView.actionsFor(selectedSection, candidateSection);
    assertFalse(actions.canEnroll());
    assertTrue(actions.canSwitch());
    assertFalse(actions.canDropCandidate());
}

@Test void fullOrConflictingSectionCannotBeSubmitted() {
    assertFalse(EnrollmentCenterView.actionsFor(null, fullSection).canEnroll());
    assertFalse(EnrollmentCenterView.actionsFor(null, conflictingSection).canEnroll());
}
```

- [ ] **Step 2: Run student UI tests and verify they fail**

Run: `mvn -pl vcampus-client -am -Dtest=EnrollmentCenterPolicyTest,StudentScheduleViewTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because student views are absent.

- [ ] **Step 3: Implement grouped course cards and section comparison**

Each course header shows requirement type, credits, suggested term and enrollment state. Expanded section rows show teacher, published schedule, classroom, weeks, capacity and remaining seats. Selecting a row overlays its schedule onto the side preview and highlights conflicts.

- [ ] **Step 4: Implement server-confirmed enroll, drop and switch flows**

Confirm dialogs state the selected section and schedule. A failed operation reloads the course group before enabling another submission, so an uncertain network result cannot be repeated blindly. Switch sends both IDs in one gateway call.

- [ ] **Step 5: Implement week-filtered schedule and read-only grades**

Use `ScheduleSlot.overlaps` semantics for week filtering and keep period labels as “第 N 节”; do not invent clock times. Only published entries returned by the server are rendered.

- [ ] **Step 6: Run student, decoder and lifecycle tests**

Run: `mvn -pl vcampus-client -am -Dtest=EnrollmentCenterPolicyTest,StudentScheduleViewTest,AcademicDataTest,AcademicAsyncTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS for required/elective badges, grouping, preview, switch and week filtering.

- [ ] **Step 7: Commit student JavaFX workflows**

```powershell
git add -- vcampus-client/src/main/java/com/vcampus/client/fx/academic/EnrollmentCenterView.java vcampus-client/src/main/java/com/vcampus/client/fx/academic/StudentScheduleView.java vcampus-client/src/main/java/com/vcampus/client/fx/academic/StudentGradesView.java vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicController.java vcampus-client/src/test/java/com/vcampus/client/fx/academic/EnrollmentCenterPolicyTest.java vcampus-client/src/test/java/com/vcampus/client/fx/academic/StudentScheduleViewTest.java
git commit -m "feat(academic): add JavaFX student enrollment"
```

### Task 14: Build Teacher Schedule, Roster and Grade Workflows

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/TeacherWorkspaceView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/GradeEditorView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicController.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/academic/TeacherWorkspacePolicyTest.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/academic/GradeEditorPolicyTest.java`

**Interfaces:**
- Consumes existing teacher sections, published teacher schedule, roster and grade actions through `AcademicGateway`.
- Produces section selection, roster load, grade save and publish events.

- [ ] **Step 1: Write failing permission and grade completeness tests**

```java
@Test void publishedSectionMakesEveryGradeCellReadOnly() {
    assertFalse(GradeEditorPolicy.forSection(true, roster).editable());
}

@Test void publishIsDisabledUntilEveryEnrolledStudentHasScore() {
    assertFalse(GradeEditorPolicy.forSection(false, rosterWithMissingScore).canPublish());
    assertTrue(GradeEditorPolicy.forSection(false, completeRoster).canPublish());
}
```

- [ ] **Step 2: Run teacher UI tests and verify they fail**

Run: `mvn -pl vcampus-client -am -Dtest=TeacherWorkspacePolicyTest,GradeEditorPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because teacher and grade views are absent.

- [ ] **Step 3: Implement teacher schedule and section list**

Render week view plus today's chronological list. Selecting a section opens summary, published schedule and roster. Teachers see only their sections; super administrators retain the existing demonstration access path enforced by the server.

- [ ] **Step 4: Implement grade editing and publication**

Score input is 0—100 and requires a reason on every save. Refresh the roster after server success to display calculated grade points. Publication confirmation names the section and enrolled-student count; published rows become read-only.

- [ ] **Step 5: Run teacher UI and existing grade tests**

Run: `mvn -pl vcampus-client -am -Dtest=TeacherWorkspacePolicyTest,GradeEditorPolicyTest,AcademicDataTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS; existing server grade permission and history tests remain green in the later full build.

- [ ] **Step 6: Commit teacher JavaFX workflows**

```powershell
git add -- vcampus-client/src/main/java/com/vcampus/client/fx/academic/TeacherWorkspaceView.java vcampus-client/src/main/java/com/vcampus/client/fx/academic/GradeEditorView.java vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicController.java vcampus-client/src/test/java/com/vcampus/client/fx/academic/TeacherWorkspacePolicyTest.java vcampus-client/src/test/java/com/vcampus/client/fx/academic/GradeEditorPolicyTest.java
git commit -m "feat(academic): add JavaFX teacher workspace"
```

### Task 15: Integrate Native Academic Routing, Notifications and Session Cleanup

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/CampusApplication.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/LegacyModuleBridge.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/NotificationDestination.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/NotificationPanel.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/NotificationDetailDialog.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/AcademicService.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/academic/AcademicCampusIntegrationTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/ui/LegacyModuleBridgeTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/ui/NotificationNavigationPolicyTest.java`

**Interfaces:**
- Produces native routes: `academic`, `academic-enrollment`, `academic-student-schedule`, `academic-teacher-schedule`, `academic-section/{id}`, `academic-grades`.
- Produces section-aware navigation callback from Swing notification UI to JavaFX.
- Removes the temporary two-argument `AcademicService` constructor after all callers use complete dependencies.

- [ ] **Step 1: Write failing routing and logout tests**

```java
@Test void academicRouteUsesNativeControllerInsteadOfSwingNode() {
    application.openRouteForTest("academic");
    assertInstanceOf(AcademicWorkspaceView.class, application.centerForTest());
    assertEquals("overview", application.academicControllerForTest().activeRoute());
}

@Test void logoutClosesAcademicControllerAndRejectsLateCallback() {
    application.openRouteForTest("academic-enrollment");
    application.logoutForTest();
    fakeGateway.completePending(catalog);
    assertNull(application.academicControllerForTest());
}
```

- [ ] **Step 2: Run integration tests and verify they fail**

Run: `mvn -pl vcampus-client -am -Dtest=AcademicCampusIntegrationTest,NotificationNavigationPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because academic routes still use `LegacyModuleBridge`.

- [ ] **Step 3: Cache and route one controller per session**

```java
private AcademicController showAcademic() {
    if (academic == null) {
        academicRequests = Executors.newFixedThreadPool(2, daemonFactory("vcampus-academic"));
        academic = new AcademicController(
                new SocketAcademicGateway(client, session.token()), session.roles(), academicRequests,
                () -> openRoute("workspace"), () -> refreshUnread(sessionGeneration));
    }
    shell.setCenter(academic.view());
    return academic;
}
```

Deactivate academic before leaving its view. On session clear, close it, null it and `shutdownNow()` its executor before clearing the client/session references.

- [ ] **Step 4: Route notification destinations to native subpages**

Teacher schedule notifications open `academic-teacher-schedule`; student grade notifications open `academic-grades`; schedule-change notifications with an entity ID open `academic-section/{id}` when authorized, otherwise the role's schedule page.

Extend `LegacyModuleBridge` with a callback only for notifications still rendered by Swing. The default `academic` workspace card must never call `legacy.open("academic")`.

- [ ] **Step 5: Remove temporary server constructor and update all fixtures**

Construct `AcademicService` everywhere with `AcademicRepository`, `CurriculumRepository`, `ScheduleRevisionRepository` and `SessionManager`. No production or test caller may pass null academic collaborators.

- [ ] **Step 6: Run integration, legacy compatibility and session tests**

Run: `mvn -pl vcampus-client -am -Dtest=AcademicCampusIntegrationTest,LegacyModuleBridgeTest,NotificationNavigationPolicyTest,ClientSessionTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS; normal JavaFX launch uses native academic pages, while `--swing` still reaches `AcademicModulePanel`.

- [ ] **Step 7: Commit route integration**

```powershell
git add -- vcampus-client/src/main/java/com/vcampus/client/fx/CampusApplication.java vcampus-client/src/main/java/com/vcampus/client/ui/LegacyModuleBridge.java vcampus-client/src/main/java/com/vcampus/client/ui/NotificationDestination.java vcampus-client/src/main/java/com/vcampus/client/ui/NotificationPanel.java vcampus-client/src/main/java/com/vcampus/client/ui/NotificationDetailDialog.java vcampus-server/src/main/java/com/vcampus/server/service/AcademicService.java vcampus-client/src/test/java/com/vcampus/client/fx/academic/AcademicCampusIntegrationTest.java vcampus-client/src/test/java/com/vcampus/client/ui/LegacyModuleBridgeTest.java vcampus-client/src/test/java/com/vcampus/client/ui/NotificationNavigationPolicyTest.java
git commit -m "feat(academic): integrate native JavaFX routes"
```

### Task 16: Complete Visual QA, Documentation and Full Verification

**Files:**
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/academic/AcademicScreenshotTest.java`
- Create: `docs/design/javafx-academic/design-qa.md`
- Create: `docs/design/javafx-academic/screenshots/` generated PNG files
- Modify: `docs/requirements.md`
- Modify: `docs/academic-management.md`
- Modify: `docs/architecture.md`
- Modify: `README.md`

**Interfaces:**
- Produces deterministic test-only screenshots at 1000, 1220 and 1440 pixel widths.
- Produces operator documentation for migration 014, role workflows, JavaFX launch and Swing fallback.

- [ ] **Step 1: Write the deterministic screenshot harness**

```java
@Test void rendersStudentAndAdministratorAcademicWorkspaces() throws Exception {
    snapshot(studentFixture(), 1000, 760, "student-enrollment-1000.png");
    snapshot(studentFixture(), 1220, 820, "student-schedule-1220.png");
    snapshot(adminFixture(), 1440, 900, "admin-schedule-1440.png");
}
```

Fixtures use in-memory typed gateway data and contain long names, a full section, a conflicting section, an empty result and a retryable failure. They must not introduce a production mock mode.

- [ ] **Step 2: Run all module tests before screenshots**

Run: `mvn -pl vcampus-common,vcampus-server,vcampus-client -am test`

Expected: PASS across protocol, database, permission, service, Swing compatibility and JavaFX tests.

- [ ] **Step 3: Render screenshots**

Run: `mvn -pl vcampus-client -Dtest=AcademicScreenshotTest test`

Expected: PNG files appear under `docs/design/javafx-academic/screenshots/` for student enrollment, student schedule, administrator curriculum, administrator scheduling and teacher grades.

- [ ] **Step 4: Inspect every screenshot and record findings**

Open each PNG with the image viewer. Record pass/fail for clipping, scrolling, alignment, contrast, keyboard focus, badge consistency, loading state, empty state and error state in `docs/design/javafx-academic/design-qa.md`. Correct any failed item, rerun the focused UI test and regenerate the affected screenshot.

- [ ] **Step 5: Update requirements and operating documentation**

Document the three-layer course/curriculum/section model, migration 014, plan copy/publish, schedule draft/publish, student switch transaction, role pages, notification deep links, JavaFX thread rules and `--swing` fallback. Mark only verified functionality complete.

- [ ] **Step 6: Run final clean verification**

Run: `mvn clean verify`

Expected: reactor summary reports `SUCCESS` for `vcampus-common`, `vcampus-server`, `vcampus-client` and the root project, with zero test failures.

- [ ] **Step 7: Review the exact final diff**

Run: `git status --short` and `git diff --check`

Expected: no whitespace errors; only academic upgrade files plus previously existing unrelated user changes are present. Do not stage unrelated changes.

- [ ] **Step 8: Commit verified documentation and visual evidence**

```powershell
git add -- vcampus-client/src/test/java/com/vcampus/client/fx/academic/AcademicScreenshotTest.java docs/design/javafx-academic docs/requirements.md docs/academic-management.md docs/architecture.md README.md
git commit -m "docs(academic): verify JavaFX academic workflows"
```

## Completion Criteria

- A course exists once globally and can be required for one plan and elective for another.
- Published plans are immutable, non-overlapping for one major and stable for their active cohorts.
- Students see course groups and choose one eligible teaching section.
- Failed switches preserve the original enrollment and both counts.
- Draft schedule changes remain invisible until conflict-checked publication.
- Normal startup uses native JavaFX for student, teacher and administrator academic workflows.
- Explicit `--swing` startup remains functional for compatibility.
- All Socket and JDBC work stays off the JavaFX Application Thread.
- `mvn clean verify` succeeds and JavaFX screenshots pass documented visual inspection.
