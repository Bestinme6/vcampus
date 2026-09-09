# JavaFX Profile and Account Workspaces Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the embedded Swing pages for student records, personal student records, account management, and teacher information with polished native JavaFX workspaces.

**Architecture:** Add a focused `com.vcampus.client.fx.identity` package with typed response models, gateway interfaces around the existing socket client, one controller/view pair per workspace, shared JavaFX components, and one stylesheet. `CampusApplication` owns and routes to the controllers while the legacy bridge remains available only for compatibility startup and unmigrated pages.

**Tech Stack:** Java 21, JavaFX Controls/CSS, `CompletableFuture`/`ExecutorService`, existing `VCampusClient` socket protocol, JUnit 5, Maven Surefire.

**Spec:** `docs/superpowers/specs/2026-09-09-javafx-profile-account-design.md`

## Global Constraints

- Preserve MySQL database -> application server -> client; the client never connects directly to MySQL.
- Reuse the existing `account.*`, `student.*`, and teacher-profile actions and the length-prefixed `MessageCodec`; do not change the database schema or protocol.
- Run socket work off the JavaFX Application Thread and update controls only on that thread.
- Keep existing role and action policies as the source of truth; the server performs final authorization.
- Retain existing Swing pages for compatibility startup, while the JavaFX portal routes the four target pages natively.
- Work in the current checkout without stashing, resetting, or overwriting unrelated academic, library, bank, server, documentation, or screenshot changes already present.
- Use test-first steps and finish with `mvn clean verify` on Java 21.

## File Map

Create these focused production files under `vcampus-client/src/main/java/com/vcampus/client/fx/identity/`:

- `StudentData.java`: immutable student profile, list page, reference item, and status-history records plus response parsers.
- `StudentGateway.java`, `SocketStudentGateway.java`: student query/mutation boundary and socket adapter.
- `StudentProfileView.java`, `StudentProfileController.java`: personal student cards, contact dialog, history timeline, and async orchestration.
- `StudentAdminView.java`, `StudentAdminController.java`, `StudentDialogs.java`: management list/detail page and edit/status/history dialogs.
- `TeacherData.java`, `TeacherGateway.java`, `SocketTeacherGateway.java`: teacher response model and socket boundary.
- `TeacherProfileView.java`, `TeacherProfileController.java`: teacher cards and contact editor.
- `AccountData.java`, `AccountGateway.java`, `SocketAccountGateway.java`: account page/reference model, commands, and socket adapter.
- `AccountAdminView.java`, `AccountAdminController.java`, `AccountDialogs.java`: account management list/detail page and all account dialogs.
- `IdentityUi.java`: shared cards, detail rows, status chips, alerts, formatting, and stylesheet installation.
- `RequestGate.java`: monotonically increasing request generation used to reject stale results.

Create `vcampus-client/src/main/resources/com/vcampus/client/fx/identity/identity.css` for module-specific styles.

Create matching tests under `vcampus-client/src/test/java/com/vcampus/client/fx/identity/`. Modify `CampusApplication.java`, `LegacyModuleBridge.java`, its integration tests, `README.md`, `docs/requirements.md`, `docs/student-management.md`, `docs/account-management.md`, and `docs/teacher-profile.md`. Add visual outputs under `docs/design/javafx-identity/screenshots/`.

---

### Task 1: Typed data and stale-request foundation

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/RequestGate.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/StudentData.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/TeacherData.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/AccountData.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/identity/RequestGateTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/identity/StudentDataTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/identity/TeacherDataTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/identity/AccountDataTest.java`

**Interfaces:**
- Produces: `RequestGate.next()`, `RequestGate.accepts(long)`, and `RequestGate.close()`.
- Produces: `StudentData.profile(ResponseMessage)`, `page(ResponseMessage)`, `history(ResponseMessage)`, and `references(ResponseMessage)`.
- Produces: `TeacherData.from(ResponseMessage)`.
- Produces: `AccountData.page(ResponseMessage)` and `references(ResponseMessage)`.

- [ ] **Step 1: Write failing parser and request-generation tests**

Use real protocol keys and `RowCodec` payloads. The minimum assertions are:

```java
@Test void newerRequestInvalidatesOlderResult() {
    RequestGate gate = new RequestGate();
    long old = gate.next();
    long current = gate.next();
    assertFalse(gate.accepts(old));
    assertTrue(gate.accepts(current));
    gate.close();
    assertFalse(gate.accepts(current));
}

@Test void parsesStudentProfileAndStatusHistory() {
    StudentData.Profile profile = StudentData.profile(success(Map.ofEntries(
        entry("id", "8"), entry("studentNumber", "2026000008"),
        entry("fullName", "林同学"), entry("gender", "FEMALE"),
        entry("birthDate", "2008-05-01"), entry("departmentId", "10"),
        entry("departmentName", "计算机学院"), entry("majorId", "20"),
        entry("majorName", "软件工程"), entry("classId", "30"),
        entry("className", "软工2601"), entry("enrollmentYear", "2026"),
        entry("status", "ENROLLED"), entry("phone", ""),
        entry("email", "student@vcampus.edu"), entry("address", ""))));
    assertEquals(8L, profile.id());
    assertEquals(StudentStatus.ENROLLED, profile.status());
}
```

Also assert readable Chinese exceptions for missing required numeric/enumerated fields, optional contact fields becoming empty strings, account roles parsing from `RowCodec`, and reference parent/year parsing.

- [ ] **Step 2: Run the focused tests and confirm RED**

Run:

```powershell
mvn -pl vcampus-client -am -Dtest=RequestGateTest,StudentDataTest,TeacherDataTest,AccountDataTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation failure because the four production types do not exist.

- [ ] **Step 3: Implement immutable records and strict parsers**

Use these signatures so later tasks share one vocabulary:

```java
public final class RequestGate {
    private long generation;
    private boolean closed;
    public synchronized long next() { return ++generation; }
    public synchronized boolean accepts(long value) { return !closed && value == generation; }
    public synchronized void close() { closed = true; generation++; }
}

public final class StudentData {
    public record Profile(long id, String studentNumber, String fullName, Gender gender,
        String birthDate, long departmentId, String departmentName, long majorId,
        String majorName, long classId, String className, int enrollmentYear,
        StudentStatus status, String phone, String email, String address) {}
    public record Summary(long id, String studentNumber, String fullName, Gender gender,
        String departmentName, String majorName, String className, int enrollmentYear,
        StudentStatus status, String phone, String email) {}
    public record Page(List<Summary> rows, int page, int pageSize, int total) {}
    public record History(String oldStatus, StudentStatus newStatus, String reason,
        String operator, String changedAt) {}
    public record Reference(long id, long parentId, String code, String name, int year) {}
    public record References(List<Reference> departments, List<Reference> majors,
        List<Reference> classes) {}
}

public record TeacherData(String teacherNumber, String fullName, String departmentName,
    String professionalTitle, String phone, String email) {}

public final class AccountData {
    public record Account(long userId, String username, String displayName,
        UserRole baseIdentity, Set<UserRole> administrativeRoles, boolean enabled,
        boolean forcePasswordChange, String lastLoginAt) {}
    public record Page(List<Account> rows, int page, int pageSize, int total) {}
    public record Reference(long id, long parentId, String code, String name, int year) {}
    public record References(List<Reference> departments, List<Reference> majors,
        List<Reference> classes) {}
}
```

Copy collections defensively. Preserve blank `oldStatus` for the initial status entry and reject malformed booleans, roles, row widths, numbers, genders, and statuses with domain-specific Chinese error messages.

- [ ] **Step 4: Run focused tests and confirm GREEN**

Run the command from Step 2. Expected: all four test classes pass.

- [ ] **Step 5: Commit the foundation**

```powershell
git add -- vcampus-client/src/main/java/com/vcampus/client/fx/identity/RequestGate.java vcampus-client/src/main/java/com/vcampus/client/fx/identity/StudentData.java vcampus-client/src/main/java/com/vcampus/client/fx/identity/TeacherData.java vcampus-client/src/main/java/com/vcampus/client/fx/identity/AccountData.java vcampus-client/src/test/java/com/vcampus/client/fx/identity/RequestGateTest.java vcampus-client/src/test/java/com/vcampus/client/fx/identity/StudentDataTest.java vcampus-client/src/test/java/com/vcampus/client/fx/identity/TeacherDataTest.java vcampus-client/src/test/java/com/vcampus/client/fx/identity/AccountDataTest.java
git commit -m "feat(identity): add typed JavaFX workspace data"
```

### Task 2: Socket gateways

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/StudentGateway.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/SocketStudentGateway.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/TeacherGateway.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/SocketTeacherGateway.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/AccountGateway.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/SocketAccountGateway.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/identity/SocketIdentityGatewayTest.java`

**Interfaces:**
- Consumes: Task 1 records and existing `VCampusClient` methods.
- Produces: blocking gateways used only by controllers on background executors.

- [ ] **Step 1: Write failing gateway delegation tests**

Create a recording `VCampusClient` test double and assert representative argument forwarding:

```java
@Test void studentSearchForwardsFilterAndParsesPage() throws Exception {
    RecordingClient client = new RecordingClient();
    client.response = studentPageResponse();
    StudentGateway gateway = new SocketStudentGateway(client, "token");
    gateway.search("林", StudentStatus.ENROLLED, 2);
    assertEquals(List.of("token", "林", "ENROLLED", "2"), client.studentSearchArgs);
}
```

Cover contact maps, student status change, account role sets, account password char arrays, and teacher contact forwarding.

- [ ] **Step 2: Run the gateway test and confirm RED**

```powershell
mvn -pl vcampus-client -am -Dtest=SocketIdentityGatewayTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation failure for the missing gateways.

- [ ] **Step 3: Implement the exact gateway contracts**

```java
public interface StudentGateway {
    StudentData.Profile self() throws IOException;
    StudentData.Page search(String keyword, StudentStatus status, int page) throws IOException;
    StudentData.Profile get(long studentId) throws IOException;
    StudentData.References references() throws IOException;
    List<StudentData.History> history(Long studentId) throws IOException;
    void updateProfile(Map<String,String> values) throws IOException;
    void updateContact(long studentId, String phone, String email, String address) throws IOException;
    void updateOwnContact(String phone, String email, String address) throws IOException;
    void changeStatus(long studentId, StudentStatus status, String reason) throws IOException;
}

public interface TeacherGateway {
    TeacherData self() throws IOException;
    void updateContact(String phone, String email) throws IOException;
}

public interface AccountGateway {
    AccountData.Page search(String keyword, UserRole identity, Boolean enabled, int page) throws IOException;
    AccountData.References references() throws IOException;
    void create(Map<String,String> values) throws IOException;
    void updateRoles(long userId, Set<UserRole> roles) throws IOException;
    void setEnabled(long userId, boolean enabled) throws IOException;
    void resetPassword(long userId, char[] password) throws IOException;
}
```

Every adapter must call `requireSuccess(response)` before parsing and throw `IllegalStateException(response.message())` on a failed response. Password copies must be cleared in `finally` after delegation.

- [ ] **Step 4: Run gateway and parser tests**

Run the Step 2 command plus Task 1 tests. Expected: PASS.

- [ ] **Step 5: Commit gateway adapters**

```powershell
git add -- vcampus-client/src/main/java/com/vcampus/client/fx/identity/*Gateway.java vcampus-client/src/test/java/com/vcampus/client/fx/identity/SocketIdentityGatewayTest.java
git commit -m "feat(identity): add socket gateways"
```

### Task 3: Shared JavaFX identity components

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/IdentityUi.java`
- Create: `vcampus-client/src/main/resources/com/vcampus/client/fx/identity/identity.css`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/identity/IdentityUiTest.java`

**Interfaces:**
- Produces: shared `card`, `detailRow`, `statusChip`, `pageHeader`, `emptyState`, `showError`, `showSuccess`, and stylesheet helpers.

- [ ] **Step 1: Write a failing JavaFX component test**

```java
@Test void statusChipCarriesSemanticClassAndAccessibleText() throws Exception {
    Label chip = onFx(() -> IdentityUi.statusChip("在籍", "positive"));
    assertEquals("在籍", chip.getText());
    assertTrue(chip.getStyleClass().contains("identity-status-positive"));
    assertEquals("学籍状态：在籍", chip.getAccessibleText());
}
```

Also assert that cards and empty states expose stable CSS classes and stylesheet installation is idempotent.

- [ ] **Step 2: Run the component test and confirm RED**

```powershell
mvn -pl vcampus-client -am -Dtest=IdentityUiTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: Implement shared controls and CSS tokens**

The helper API is:

```java
final class IdentityUi {
    static VBox card(Node... children);
    static HBox detailRow(String label, String value);
    static Label statusChip(String text, String semantic);
    static HBox pageHeader(String title, String subtitle, Runnable back);
    static VBox emptyState(String title, String detail, Runnable retry);
    static void install(Parent root);
    static void showError(Node owner, String message);
    static void showSuccess(Node owner, String message);
}
```

Define `.identity-page`, `.identity-header`, `.identity-card`, `.identity-toolbar`, `.identity-split`, `.identity-table`, `.identity-detail-row`, `.identity-status-positive`, `.identity-status-warning`, `.identity-status-negative`, `.identity-empty`, and dialog form classes. Use existing campus colors (`#2468d8`, `#f4f7fb`, `#17233c`, `#6b7890`) and 14–20 px card radii.

- [ ] **Step 4: Run `IdentityUiTest` and existing `FxUiTest`**

```powershell
mvn -pl vcampus-client -am -Dtest=IdentityUiTest,FxUiTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS with the existing portal stylesheet unaffected.

- [ ] **Step 5: Commit the shared UI**

```powershell
git add -- vcampus-client/src/main/java/com/vcampus/client/fx/identity/IdentityUi.java vcampus-client/src/main/resources/com/vcampus/client/fx/identity/identity.css vcampus-client/src/test/java/com/vcampus/client/fx/identity/IdentityUiTest.java
git commit -m "feat(identity): add shared JavaFX profile styling"
```

### Task 4: Personal student and teacher profile workspaces

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/StudentProfileView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/StudentProfileController.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/TeacherProfileView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/TeacherProfileController.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/identity/PersonalProfileControllerTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/identity/PersonalProfileViewTest.java`

**Interfaces:**
- Consumes: gateways, data records, `RequestGate`, `IdentityUi`.
- Produces: `view()`, `activate()`, `deactivate()`, and `close()` on both controllers.

- [ ] **Step 1: Write failing controller and view tests**

```java
@Test void studentContactSaveReloadsProfileWithoutChangingRoute() {
    FakeStudentGateway gateway = new FakeStudentGateway(profile("旧电话"));
    StudentProfileController controller = controller(gateway);
    controller.activate();
    awaitFx();
    controller.saveContact("13900000000", "student@vcampus.edu", "1号楼");
    awaitFx();
    assertEquals(2, gateway.selfCalls);
    assertEquals("13900000000", controller.view().phoneText());
}
```

Assert grouped card titles, student status chip, teacher identity fields, history empty state, failure input preservation, and ignored completion after `close()`.

- [ ] **Step 2: Run personal-profile tests and confirm RED**

```powershell
mvn -pl vcampus-client -am -Dtest=PersonalProfileControllerTest,PersonalProfileViewTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: Implement the student profile vertical slice**

`StudentProfileView` exposes listener callbacks for reload, contact edit, and history. Render an identity header plus “基本信息”“学业信息”“联系方式” cards and a vertical history section. `StudentProfileController.activate()` loads profile and history concurrently, applies each only when its request token is current, and reloads the profile after contact save.

- [ ] **Step 4: Implement the teacher profile vertical slice**

Render identity, “任职信息”, and “联系方式” cards. `TeacherProfileController.saveContact(phone,email)` calls the gateway off-thread, preserves form values on error, and reloads `self()` after success.

- [ ] **Step 5: Run personal-profile, data, gateway, and shared-UI tests**

Run the Step 2 command and Tasks 1–3 focused tests. Expected: PASS.

- [ ] **Step 6: Commit personal profile pages**

```powershell
git add -- vcampus-client/src/main/java/com/vcampus/client/fx/identity/StudentProfileView.java vcampus-client/src/main/java/com/vcampus/client/fx/identity/StudentProfileController.java vcampus-client/src/main/java/com/vcampus/client/fx/identity/TeacherProfileView.java vcampus-client/src/main/java/com/vcampus/client/fx/identity/TeacherProfileController.java vcampus-client/src/test/java/com/vcampus/client/fx/identity/PersonalProfileControllerTest.java vcampus-client/src/test/java/com/vcampus/client/fx/identity/PersonalProfileViewTest.java
git commit -m "feat(identity): add JavaFX personal profiles"
```

### Task 5: Student-record management workspace

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/StudentAdminView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/StudentAdminController.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/StudentDialogs.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/identity/StudentAdminControllerTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/identity/StudentAdminViewTest.java`

**Interfaces:**
- Consumes: `StudentGateway`, `StudentData`, `StudentManagementActionPolicy`, `IdentityUi`.
- Produces: native `student-status` page with search, page, selection, details, dialogs, and refresh.

- [ ] **Step 1: Write failing management workflow tests**

```java
@Test void laterSearchWinsAndSelectingRowLoadsFullDetails() {
    ControlledStudentGateway gateway = new ControlledStudentGateway();
    StudentAdminController controller = controller(gateway);
    controller.search("旧", null, 1);
    controller.search("新", StudentStatus.ENROLLED, 1);
    gateway.completeSecond(page(summary(2, "新同学")));
    gateway.completeFirst(page(summary(1, "旧同学")));
    awaitFx();
    assertEquals(List.of("新同学"), controller.view().visibleNames());
    controller.select(2);
    awaitFx();
    assertEquals("2026000002", controller.view().detailStudentNumber());
}
```

Also cover status-filter pagination reset, reference cascading, edit refresh preserving selection, selected row leaving the filter, history rendering, and no-selection action state.

- [ ] **Step 2: Run student-admin tests and confirm RED**

```powershell
mvn -pl vcampus-client -am -Dtest=StudentAdminControllerTest,StudentAdminViewTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: Implement the compact table/detail view**

Use a `SplitPane` or responsive `HBox` with a `TableView<StudentData.Summary>` and a fixed-minimum-width detail card. Bind table columns to typed fields, expose selected IDs rather than row indexes, keep horizontal table scrolling available, and display page/total counts.

- [ ] **Step 4: Implement controller workflows and JavaFX dialogs**

`StudentAdminController` owns current filters, page, selected student ID, references, and request gates. `StudentDialogs` returns typed submissions:

```java
record ProfileSubmission(String fullName, Gender gender, String birthDate,
    long departmentId, long majorId, long classId, int enrollmentYear,
    String phone, String email, String address) {}
record ContactSubmission(String phone, String email, String address) {}
record StatusSubmission(StudentStatus status, String reason) {}
```

Build the `Map<String,String>` for `updateProfile` in the controller, including `studentId`. After mutation, reload the selected detail and current page; if the ID disappears from the filtered page, clear selection and show “记录已更新，当前筛选下不再显示”。

- [ ] **Step 5: Run student-admin and shared identity tests**

Run the Step 2 command plus Task 1–3 tests. Expected: PASS.

- [ ] **Step 6: Commit the management page**

```powershell
git add -- vcampus-client/src/main/java/com/vcampus/client/fx/identity/StudentAdminView.java vcampus-client/src/main/java/com/vcampus/client/fx/identity/StudentAdminController.java vcampus-client/src/main/java/com/vcampus/client/fx/identity/StudentDialogs.java vcampus-client/src/test/java/com/vcampus/client/fx/identity/StudentAdminControllerTest.java vcampus-client/src/test/java/com/vcampus/client/fx/identity/StudentAdminViewTest.java
git commit -m "feat(identity): add JavaFX student management"
```

### Task 6: Account management workspace

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/AccountAdminView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/AccountAdminController.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/identity/AccountDialogs.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/identity/AccountAdminControllerTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/identity/AccountAdminViewTest.java`

**Interfaces:**
- Consumes: `AccountGateway`, `AccountData`, existing `AccountFormPolicy` rules, `RequestGate`, and `IdentityUi`.
- Produces: native `accounts` workspace.

- [ ] **Step 1: Write failing account workflow tests**

```java
@Test void successfulCreateClearsFiltersAndHighlightsReturnedUsername() {
    FakeAccountGateway gateway = new FakeAccountGateway();
    AccountAdminController controller = controller(gateway);
    controller.search("旧条件", UserRole.TEACHER, false, 3);
    controller.create(studentSubmission("2026000042"));
    awaitFx();
    assertEquals("", controller.keyword());
    assertEquals(1, controller.page());
    assertEquals("2026000042", controller.highlightedUsername());
}
```

Also cover role options by base identity, no super-admin rows/actions, enable confirmation target, password length/match, password buffer clearing, reference cascade, stale searches, and action buttons disabled without a selected account.

- [ ] **Step 2: Run account tests and confirm RED**

```powershell
mvn -pl vcampus-client -am -Dtest=AccountAdminControllerTest,AccountAdminViewTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: Implement the account list/detail view**

Create a typed `TableView<AccountData.Account>` with filters and pagination on the left and account identity, status, role chips, last-login value, and contextual actions on the right. Keep “创建账号” visible in the toolbar even with no row selected.

- [ ] **Step 4: Implement dialogs and controller mutations**

`AccountDialogs` returns `Map<String,String>` for creation, `Set<UserRole>` for role edits, and a copied `char[]` for password reset. Student/teacher create forms show their exact existing fields and allowed role sets. `AccountAdminController` reloads the current list after mutations; after creation it clears filters, moves to page 1, saves `highlightedUsername`, and shows a visible success banner even if the new account does not fall on the first server page.

- [ ] **Step 5: Run account tests and existing account-policy tests**

```powershell
mvn -pl vcampus-client -am -Dtest=AccountAdminControllerTest,AccountAdminViewTest,AccountFormPolicyTest,AccountViewDataTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS; legacy tests remain green.

- [ ] **Step 6: Commit account management**

```powershell
git add -- vcampus-client/src/main/java/com/vcampus/client/fx/identity/AccountAdminView.java vcampus-client/src/main/java/com/vcampus/client/fx/identity/AccountAdminController.java vcampus-client/src/main/java/com/vcampus/client/fx/identity/AccountDialogs.java vcampus-client/src/test/java/com/vcampus/client/fx/identity/AccountAdminControllerTest.java vcampus-client/src/test/java/com/vcampus/client/fx/identity/AccountAdminViewTest.java
git commit -m "feat(identity): add JavaFX account management"
```

### Task 7: Portal routing, notification deep link, and lifecycle

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/CampusApplication.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/LegacyModuleBridge.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/CampusFlowTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/FxUiTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/ui/LegacyModuleBridgeTest.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/IdentityCampusIntegrationTest.java`

**Interfaces:**
- Consumes: all four controllers.
- Produces: native routing for `personal-profile`, `student-status`, `accounts`, and student-profile notifications.

- [ ] **Step 1: Write failing route and lifecycle tests**

Assert that route resolution selects student versus teacher personal controllers, only a super-admin may open accounts, student admins may open student management, and student-profile notifications invoke the external route callback with `personal-profile`.

```java
@Test void studentProfileNotificationLeavesLegacyBridgeThroughNativeRoute() {
    bridge.navigate(notification(NotificationTarget.STUDENT_PROFILE));
    assertEquals(List.of("personal-profile"), externalRoutes);
}
```

Add a source-level integration assertion that the three target route names are handled before `shell.setCenter(legacyNode)`.

- [ ] **Step 2: Run integration tests and confirm RED**

```powershell
mvn -pl vcampus-client -am -Dtest=IdentityCampusIntegrationTest,CampusFlowTest,FxUiTest,LegacyModuleBridgeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: Wire controller ownership into `CampusApplication`**

Add nullable fields for the four controllers and one fixed-size identity executor. Implement `showStudentProfile()`, `showStudentAdmin()`, `showTeacherProfile()`, and `showAccounts()` with role checks. In `openRoute`, dispatch these routes before the legacy fallback and set the correct sidebar selection. Deactivate the current identity controller when leaving it.

- [ ] **Step 4: Route student-profile notifications natively**

Rename the final legacy bridge callback internally from `externalAcademicRoute` to `externalRoute`, retain all constructor signatures, and have `openStudentProfileFromNotification()` call `externalRoute.accept("personal-profile")` after its student permission check. Keep the old Swing fallback when the callback is null.

- [ ] **Step 5: Close all identity resources on logout**

Call each controller’s `close()`, null its field, shut down the identity executor, and ensure late completions fail their `RequestGate` checks before touching controls.

- [ ] **Step 6: Run integration and full client tests**

```powershell
mvn -pl vcampus-client -am test
```

Expected: all common and client tests pass.

- [ ] **Step 7: Commit routing integration**

```powershell
git add -- vcampus-client/src/main/java/com/vcampus/client/fx/CampusApplication.java vcampus-client/src/main/java/com/vcampus/client/ui/LegacyModuleBridge.java vcampus-client/src/test/java/com/vcampus/client/fx/CampusFlowTest.java vcampus-client/src/test/java/com/vcampus/client/fx/FxUiTest.java vcampus-client/src/test/java/com/vcampus/client/ui/LegacyModuleBridgeTest.java vcampus-client/src/test/java/com/vcampus/client/fx/IdentityCampusIntegrationTest.java
git commit -m "feat(identity): route portal to native JavaFX pages"
```

### Task 8: Responsive visual verification and documentation

**Files:**
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/identity/IdentityScreenshotTest.java`
- Create: `docs/design/javafx-identity/screenshots/student-admin-1000.png`
- Create: `docs/design/javafx-identity/screenshots/student-profile-1220.png`
- Create: `docs/design/javafx-identity/screenshots/account-admin-1440.png`
- Create: `docs/design/javafx-identity/screenshots/teacher-profile-1220.png`
- Modify: `README.md`
- Modify: `docs/requirements.md`
- Modify: `docs/student-management.md`
- Modify: `docs/account-management.md`
- Modify: `docs/teacher-profile.md`

**Interfaces:**
- Consumes: complete native identity workspaces.
- Produces: reviewable screenshots, updated feature status, and final verification evidence.

- [ ] **Step 1: Write the screenshot harness and layout assertions**

Build deterministic fake gateways and render each page in a `Scene`. For management pages assert that at 1000 px the toolbar controls stay within page bounds and the detail pane retains its minimum width; for 1220/1440 assert both panes are visible without vertical overlap.

```java
assertTrue(searchButton.localToScene(searchButton.getBoundsInLocal()).getMaxX() <= scene.getWidth());
assertTrue(detailPane.getBoundsInParent().getWidth() >= 300);
```

- [ ] **Step 2: Run the visual test and inspect generated PNGs**

```powershell
mvn -pl vcampus-client -am -Dtest=IdentityScreenshotTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Open all four PNGs and check clipped text, overlapping controls, excessive empty space, status contrast, table density, and dialog consistency. Adjust CSS and layout constants, rerun once, and retain the accepted images.

- [ ] **Step 3: Update product documentation**

Change README and requirements wording from Swing-compatible student/teacher/account pages to native JavaFX. In each module document, record the new list/detail or card layout, native deep link, async behavior, and that protocol/server/database behavior is unchanged.

- [ ] **Step 4: Run whitespace and focused regression checks**

```powershell
git diff --check
mvn -pl vcampus-client -am test
```

Expected: no whitespace errors and all common/client tests pass.

- [ ] **Step 5: Run the required full build**

```powershell
mvn clean verify
```

Expected: reactor build success with zero test failures; record totals and skipped tests from the fresh output.

- [ ] **Step 6: Confirm the staged scope before the final commit**

Use explicit paths and inspect `git diff --cached --name-status`. Do not stage unrelated pre-existing changes. Include only identity implementation/tests/screenshots and the exact documentation hunks that describe this migration.

- [ ] **Step 7: Commit documentation and visual evidence**

```powershell
git add -- vcampus-client/src/test/java/com/vcampus/client/fx/identity/IdentityScreenshotTest.java docs/design/javafx-identity/screenshots README.md docs/requirements.md docs/student-management.md docs/account-management.md docs/teacher-profile.md
git commit -m "docs: verify JavaFX identity workspaces"
```

- [ ] **Step 8: Final acceptance review**

Confirm every requirement in `docs/superpowers/specs/2026-09-09-javafx-profile-account-design.md` maps to implemented code or test evidence. Report changed behavior, test totals, screenshot paths, and any remaining MySQL/manual multi-client acceptance limits without claiming they were exercised.
