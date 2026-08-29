# VCampus Identity, Teacher Profile, and Workspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the approved base-identity rules, role-aware workspace cards, teacher self-profile, case-sensitive login, and readable query/refresh buttons.

**Architecture:** Keep identity validation and access rules in `vcampus-common`, enforce them again in `vcampus-server`, and let `vcampus-client` derive card presentation from authenticated roles. Teacher data flows only through the existing length-prefixed Socket protocol: Swing client → application server → JDBC repository → MySQL.

**Tech Stack:** Java 21, Maven, JUnit 5, Swing, Socket, `MessageCodec`, JDBC, MySQL 8.0.44.

**Spec:** `docs/superpowers/specs/2026-08-26-teacher-profile-role-login-ui-design.md`

## Global Constraints

- Preserve MySQL database → application server → Swing client; the client never connects to MySQL.
- Shared roles, access rules, module codes, and action constants belong in `vcampus-common`.
- Database and network work must stay off the Swing Event Dispatch Thread; Swing mutation returns to the EDT.
- Continue using `MessageCodec`; do not introduce Java object serialization.
- Keep usernames unique without regard to case, while login matching is case-sensitive.
- `STUDENT`, `TEACHER`, and `SUPER_ADMIN` are mutually exclusive base identities; every authenticated account has exactly one.
- Students may only add `LIBRARY_ADMIN` and `FORUM_ADMIN`; teachers may add all six business-admin roles; `SUPER_ADMIN` has no secondary roles.
- The current directory has no Git metadata, so task checkpoints use tests and file review instead of commit commands. Do not initialize Git without user authorization.

---

### Task 1: Centralize base-identity and role-combination rules

**Files:**
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/RoleCompositionPolicy.java`
- Create: `vcampus-common/src/test/java/com/vcampus/common/model/RoleCompositionPolicyTest.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/ModuleCode.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/AccessPolicy.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/StudentAccessPolicy.java`
- Modify: `vcampus-common/src/test/java/com/vcampus/common/model/AccessPolicyTest.java`
- Modify: `vcampus-common/src/test/java/com/vcampus/common/model/StudentPolicyTest.java`

**Interfaces:**
- Produces: `RoleCompositionPolicy.violation(Set<UserRole>): Optional<String>`.
- Produces: `RoleCompositionPolicy.requireValid(Set<UserRole>): void`, throwing `IllegalArgumentException` with the same violation text.
- Produces: `ModuleCode.PERSONAL_PROFILE` for student/teacher self-information and retains `ModuleCode.STUDENT_STATUS` for student administration.

- [ ] **Step 1: Write failing role-composition tests**

```java
@Test
void everyAccountMustHaveExactlyOneBaseIdentity() {
    assertTrue(RoleCompositionPolicy.violation(Set.of(UserRole.STUDENT)).isEmpty());
    assertTrue(RoleCompositionPolicy.violation(Set.of(UserRole.TEACHER)).isEmpty());
    assertTrue(RoleCompositionPolicy.violation(Set.of(UserRole.SUPER_ADMIN)).isEmpty());
    assertTrue(RoleCompositionPolicy.violation(Set.of()).isPresent());
    assertTrue(RoleCompositionPolicy.violation(Set.of(UserRole.STUDENT, UserRole.TEACHER)).isPresent());
}

@Test
void studentAndTeacherSecondaryRolesFollowApprovedMatrix() {
    assertTrue(RoleCompositionPolicy.violation(Set.of(
            UserRole.STUDENT, UserRole.LIBRARY_ADMIN, UserRole.FORUM_ADMIN)).isEmpty());
    assertTrue(RoleCompositionPolicy.violation(Set.of(
            UserRole.STUDENT, UserRole.ACADEMIC_ADMIN)).isPresent());
    assertTrue(RoleCompositionPolicy.violation(Set.of(
            UserRole.TEACHER, UserRole.STUDENT_ADMIN, UserRole.ACADEMIC_ADMIN,
            UserRole.LIBRARY_ADMIN, UserRole.SHOP_ADMIN, UserRole.BANK_ADMIN,
            UserRole.FORUM_ADMIN)).isEmpty());
    assertTrue(RoleCompositionPolicy.violation(Set.of(
            UserRole.SUPER_ADMIN, UserRole.FORUM_ADMIN)).isPresent());
}
```

- [ ] **Step 2: Run the common tests and confirm the new type is missing**

Run: `mvn -pl vcampus-common -Dtest=RoleCompositionPolicyTest test`

Expected: FAIL because `RoleCompositionPolicy` does not exist.

- [ ] **Step 3: Implement the policy with one source of truth**

```java
public final class RoleCompositionPolicy {
    private static final Set<UserRole> BASE = Set.of(
            UserRole.STUDENT, UserRole.TEACHER, UserRole.SUPER_ADMIN);
    private static final Set<UserRole> STUDENT_ALLOWED = Set.of(
            UserRole.STUDENT, UserRole.LIBRARY_ADMIN, UserRole.FORUM_ADMIN);

    public static Optional<String> violation(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        long baseCount = roles.stream().filter(BASE::contains).count();
        if (baseCount != 1) return Optional.of("账号必须且只能拥有一个基础身份");
        if (roles.contains(UserRole.SUPER_ADMIN) && roles.size() != 1)
            return Optional.of("超级管理员不能附加其他角色");
        if (roles.contains(UserRole.STUDENT) && !STUDENT_ALLOWED.containsAll(roles))
            return Optional.of("学生只能兼任图书管理员或论坛管理员");
        return Optional.empty();
    }

    public static void requireValid(Set<UserRole> roles) {
        violation(roles).ifPresent(message -> { throw new IllegalArgumentException(message); });
    }
}
```

- [ ] **Step 4: Split personal information from student administration in access policy**

Add `PERSONAL_PROFILE("个人信息")` before `STUDENT_STATUS`. Handle `AUTH` and `PERSONAL_PROFILE` before the super-administrator shortcut, then update the switch so:

```java
case PERSONAL_PROFILE -> containsAny(roles, UserRole.STUDENT, UserRole.TEACHER);
case STUDENT_STATUS -> containsAny(roles, UserRole.STUDENT_ADMIN);
```

Keep the `SUPER_ADMIN` shortcut for the six business modules, but do not let it grant `PERSONAL_PROFILE`. Change `StudentAccessPolicy.canUseSelfService` to require `STUDENT` only; `canManageStudents` continues to allow `STUDENT_ADMIN` or `SUPER_ADMIN`.

- [ ] **Step 5: Update access-policy tests to use valid complete role sets**

Replace admin-only sets such as `Set.of(UserRole.LIBRARY_ADMIN)` with `Set.of(UserRole.TEACHER, UserRole.LIBRARY_ADMIN)`. Assert that a plain student can access `PERSONAL_PROFILE` but not `STUDENT_STATUS`, a teacher/student administrator can access both, and a pure super administrator cannot access either student or teacher self-service.

- [ ] **Step 6: Run all common tests**

Run: `mvn -pl vcampus-common test`

Expected: PASS.

### Task 2: Build and test the role-aware workspace-card resolver

**Files:**
- Modify: `vcampus-client/pom.xml`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/WorkspaceCardSpec.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/WorkspaceCardResolver.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/ui/WorkspaceCardResolverTest.java`

**Interfaces:**
- Consumes: `RoleCompositionPolicy.requireValid(Set<UserRole>)` and `ModuleCode`.
- Produces: `WorkspaceCardSpec(ModuleCode module, String iconText, String title, String description)`.
- Produces: `WorkspaceCardResolver.resolve(Set<UserRole>): List<WorkspaceCardSpec>`.

- [ ] **Step 1: Add the managed JUnit dependency to the client module**

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Write failing tests for the three base identities**

```java
@Test
void studentCardsUseStudentCopyAndElevateLibraryAndForumTitles() {
    var cards = WorkspaceCardResolver.resolve(Set.of(
            UserRole.STUDENT, UserRole.LIBRARY_ADMIN, UserRole.FORUM_ADMIN));
    assertEquals(List.of("学", "教", "书", "商", "银", "论"), icons(cards));
    assertEquals(List.of("学籍信息", "课程安排", "图书管理", "商店购物", "线上银行", "论坛管理"), titles(cards));
}

@Test
void studentAdministratorTeacherGetsSevenCardsInApprovedOrder() {
    var cards = WorkspaceCardResolver.resolve(Set.of(UserRole.TEACHER, UserRole.STUDENT_ADMIN));
    assertEquals(List.of("学", "师", "教", "书", "商", "银", "论"), icons(cards));
    assertEquals("学籍管理", cards.getFirst().title());
    assertEquals("教师信息", cards.get(1).title());
}

@Test
void superAdministratorGetsSixManagementCardsOnly() {
    var cards = WorkspaceCardResolver.resolve(Set.of(UserRole.SUPER_ADMIN));
    assertEquals(List.of("学籍管理", "教务管理", "图书管理", "商店管理", "银行管理", "论坛管理"), titles(cards));
}
```

- [ ] **Step 3: Run the resolver test and verify failure**

Run: `mvn -pl vcampus-client -am -Dtest=WorkspaceCardResolverTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because the resolver types do not exist.

- [ ] **Step 4: Implement explicit ordered card construction**

Use small private methods `studentCards`, `teacherCards`, and `superAdminCards`. Call `RoleCompositionPolicy.requireValid(roles)` first. Do not derive user-visible names from `ModuleCode.displayName()`; build the approved titles explicitly so manager roles can replace the title without duplicating a card.

```java
private static WorkspaceCardSpec card(ModuleCode module, String icon, String title, String description) {
    return new WorkspaceCardSpec(module, icon, title, description);
}
```

- [ ] **Step 5: Run resolver and full client tests**

Run: `mvn -pl vcampus-client -am -Dtest=WorkspaceCardResolverTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

### Task 3: Enforce role validity and exact-case usernames at authentication boundaries

**Files:**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/UserRepository.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/AuthService.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/AdminBootstrapMain.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/StudentService.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/security/SessionManagerTest.java`

**Interfaces:**
- Consumes: `RoleCompositionPolicy.violation` and `requireValid`.
- Preserves: `UserRepository.findByUsername(String): Optional<UserAccount>`.

- [ ] **Step 1: Correct existing tests that construct an illegal super-admin session**

Change the `SessionManagerTest` account roles from `Set.of(SUPER_ADMIN, FORUM_ADMIN)` to `Set.of(SUPER_ADMIN)` and keep the test assertion that all roles are copied.

- [ ] **Step 2: Make the repository query case-sensitive without changing uniqueness**

Change only the lookup predicate:

```sql
WHERE BINARY u.username = BINARY ?
```

Keep the column and unique index unchanged, so `admin` and `ADMin` cannot both be created.

- [ ] **Step 3: Add defensive exact comparison and role validation in `AuthService.login`**

Immediately after loading the account:

```java
if (!account.username().equals(username)) {
    audit.record(account.id(), Actions.AUTH_LOGIN, "DENIED", clientAddress);
    return invalidCredentials(request);
}
```

After password and enabled checks but before creating a session:

```java
Optional<String> roleViolation = RoleCompositionPolicy.violation(account.roles());
if (roleViolation.isPresent()) {
    audit.record(account.id(), Actions.AUTH_LOGIN, "INVALID_ROLES", clientAddress);
    return ResponseMessage.failure(request.requestId(), "账号角色配置异常，请联系管理员");
}
```

- [ ] **Step 4: Validate roles at existing creation boundaries**

Call `RoleCompositionPolicy.requireValid(Set.of(UserRole.SUPER_ADMIN))` in `AdminBootstrapMain` before repository creation and `RoleCompositionPolicy.requireValid(Set.of(UserRole.STUDENT))` in `StudentService.create` before hashing or inserting. These are deliberate integration points for the later account-management service.

- [ ] **Step 5: Run common and server tests**

Run: `mvn -pl vcampus-server -am test`

Expected: PASS.

### Task 4: Add teacher-profile schema, protocol, repository, and service

**Files:**
- Modify: `database/schema.sql`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/model/TeacherProfile.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/TeacherProfileStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/TeacherRepository.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/service/TeacherProfileService.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/service/TeacherProfileServiceTest.java`

**Interfaces:**
- Produces: `Actions.TEACHER_PROFILE_GET_SELF = "profile.teacher.getSelf"`.
- Produces: `Actions.TEACHER_PROFILE_UPDATE_CONTACT = "profile.teacher.updateContact"`.
- Produces: `TeacherProfileStore.findByUserId(long): Optional<TeacherProfile>`.
- Produces: `TeacherProfileStore.updateContact(long, String, String): boolean`.
- Produces: `TeacherProfileService.getSelf(RequestMessage)` and `updateContact(RequestMessage)`.

- [ ] **Step 1: Add the idempotent teacher table to `schema.sql`**

```sql
CREATE TABLE IF NOT EXISTS teacher_profiles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL UNIQUE,
    teacher_number VARCHAR(32) NOT NULL UNIQUE,
    full_name VARCHAR(100) NOT NULL,
    department_id BIGINT NOT NULL,
    professional_title VARCHAR(100) NOT NULL,
    phone VARCHAR(32) NULL,
    email VARCHAR(128) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_teacher_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_teacher_department FOREIGN KEY (department_id) REFERENCES departments(id)
);
```

- [ ] **Step 2: Write failing service tests with an in-memory fake store**

Create sessions for a teacher and a student. Test that the teacher receives keys `teacherNumber`, `fullName`, `departmentName`, `professionalTitle`, `phone`, and `email`; the student gets “无权执行此操作”; update passes only phone/email to the fake store; invalid email is rejected.

```java
RequestMessage request = RequestMessage.create(Actions.TEACHER_PROFILE_GET_SELF,
        Map.of("sessionToken", teacherSession.token()));
ResponseMessage response = service.getSelf(request);
assertTrue(response.success());
assertEquals("T20260001", response.data().get("teacherNumber"));
```

- [ ] **Step 3: Run the service test and verify failure**

Run: `mvn -pl vcampus-server -am -Dtest=TeacherProfileServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because the teacher profile types and actions do not exist.

- [ ] **Step 4: Implement the profile record and JDBC repository**

Use a join from `teacher_profiles` to `departments` and select only by `user_id`. Implement contact update as:

```sql
UPDATE teacher_profiles SET phone = ?, email = ? WHERE user_id = ?
```

Use `trimToNull`; accept blank phone/email as `NULL`; reject phone longer than 32 characters and email longer than 128 or missing `@`.

- [ ] **Step 5: Implement service authorization and response mapping**

Resolve the session token, require `UserRole.TEACHER`, use only `session.userId()`, and never accept a target user id from request parameters. Return “未找到教师档案，请联系管理员” when the teacher role has no profile.

- [ ] **Step 6: Run teacher service and all server tests**

Run: `mvn -pl vcampus-server -am test`

Expected: PASS.

### Task 5: Wire teacher profile through server, client, and Swing UI

**Files:**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/TeacherProfileFrame.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/MainFrame.java`

**Interfaces:**
- Consumes: `TeacherProfileService`, `WorkspaceCardResolver.resolve`, and the two teacher-profile actions.
- Produces: `VCampusClient.getMyTeacherProfile(String)`.
- Produces: `VCampusClient.updateTeacherContact(String, String, String)`.

- [ ] **Step 1: Wire the service into the server composition root and router**

Construct `TeacherRepository`, then `TeacherProfileService`, pass it into `RequestRouter`, and add both action cases:

```java
case Actions.TEACHER_PROFILE_GET_SELF -> teacherProfileService.getSelf(request);
case Actions.TEACHER_PROFILE_UPDATE_CONTACT -> teacherProfileService.updateContact(request);
```

- [ ] **Step 2: Add client network methods**

```java
public ResponseMessage getMyTeacherProfile(String token) throws IOException {
    return sendAuthorized(Actions.TEACHER_PROFILE_GET_SELF, token, Map.of());
}

public ResponseMessage updateTeacherContact(String token, String phone, String email) throws IOException {
    return sendAuthorized(Actions.TEACHER_PROFILE_UPDATE_CONTACT, token,
            Map.of("phone", phone, "email", email));
}
```

- [ ] **Step 3: Create the teacher information window**

Build a white `JFrame` with six labeled values. Load through `CompletableFuture.supplyAsync`, update labels in `SwingUtilities.invokeLater`, and show one “修改联系方式” button. The edit dialog contains only phone and email fields; success uses `UiDialogs.showSuccess` with title “操作成功”.

- [ ] **Step 4: Replace enum streaming in `MainFrame` with resolved card specs**

Render `WorkspaceCardResolver.resolve(roles)`. The card uses `spec.iconText()`, `spec.title()`, and `spec.description()`. Open targets as follows:

```java
case PERSONAL_PROFILE -> {
    if (roles.contains(UserRole.TEACHER)) new TeacherProfileFrame(client, sessionToken).setVisible(true);
    else new StudentFrame(client, sessionToken, roles).setVisible(true);
}
case STUDENT_STATUS -> new StudentFrame(client, sessionToken, roles).setVisible(true);
case ACADEMIC -> new AcademicFrame(client, sessionToken, roles).setVisible(true);
```

- [ ] **Step 5: Add scrolling without shrinking cards**

Use a three-column `GridLayout(0, 3, 16, 16)` inside a `JScrollPane`, set the viewport background to white, remove the scroll-pane border, and increment the card panel preferred height when seven cards are present.

- [ ] **Step 6: Compile all modules and run resolver tests**

Run: `mvn -pl vcampus-client -am test`

Expected: PASS.

### Task 6: Make every query and refresh command readable

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/Theme.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/AcademicPanel.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/StudentFrame.java`
- Review: every file reported by `rg -n 'primaryButton\("(查询|刷新)|new JButton\("(查询|刷新)' vcampus-client/src/main/java`

**Interfaces:**
- Produces: `Theme.styleCommandButton(JButton): void`.

- [ ] **Step 1: Add the dedicated command-button style**

```java
static void styleCommandButton(JButton button) {
    button.setForeground(TEXT);
    button.setBackground(SECONDARY);
    button.setFont(button.getFont().deriveFont(Font.BOLD));
    button.setOpaque(true);
    button.setContentAreaFilled(true);
    button.setFocusPainted(false);
    button.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ACCENT),
            BorderFactory.createEmptyBorder(10, 20, 10, 20)));
    button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
}
```

- [ ] **Step 2: Route academic query/refresh buttons through the new style**

Change `AcademicPanel.primaryButton` to call `Theme.styleCommandButton`. It is used only for current query/refresh commands; retain `actionButton` for create/edit/save actions.

- [ ] **Step 3: Update the student search button and audit all matches**

Replace `Theme.stylePrimaryButton(search)` with `Theme.styleCommandButton(search)` in `StudentFrame`. Run the `rg` command from the Files section and verify every query/refresh match reaches `styleCommandButton` through either `AcademicPanel.primaryButton` or direct use.

- [ ] **Step 4: Compile the client**

Run: `mvn -pl vcampus-client -am test`

Expected: PASS.

### Task 7: Update documentation and perform full verification

**Files:**
- Modify: `docs/requirements.md`
- Modify: `docs/authentication.md`
- Modify: `docs/student-management.md`
- Modify: `docs/academic-management.md`
- Create: `docs/teacher-profile.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: all behavior implemented in Tasks 1–6.
- Produces: operator instructions for rerunning `database/schema.sql` and manually checking each base identity.

- [ ] **Step 1: Document the final identity matrix and workspace labels**

Copy the approved matrix exactly: student → library/forum admins only; teacher → all six business admins; super administrator → no secondary roles. Document that all-six teacher remains unable to manage accounts and roles.

- [ ] **Step 2: Document teacher profile setup and verification**

Explain that rerunning `database/schema.sql` creates `teacher_profiles` idempotently. Provide parameterized example values rather than real personal data, and state that the future account-management phase will create user, role, and teacher profile atomically.

- [ ] **Step 3: Run focused and full test suites**

Run:

```powershell
mvn -pl vcampus-common test
mvn -pl vcampus-server -am test
mvn -pl vcampus-client -am test
mvn clean verify
```

Expected: all commands finish with `BUILD SUCCESS` and no failed tests.

- [ ] **Step 4: Perform source audits**

Run:

```powershell
rg -n 'ObjectInputStream|ObjectOutputStream' vcampus-common vcampus-server vcampus-client
rg -n 'DriverManager|getConnection' vcampus-client
rg -n 'primaryButton\("(查询|刷新)|new JButton\("(查询|刷新)' vcampus-client/src/main/java
```

Expected: no Java native serialization; no client JDBC; every query/refresh result maps to the command-button style.

- [ ] **Step 5: Manual Eclipse acceptance check**

Rerun `database/schema.sql`, start `ServerMain`, then `ClientMain`. Verify exact-case login; the six student cards; the six teacher cards; seven scrollable cards for teacher + `STUDENT_ADMIN`; six management cards for super administrator; teacher profile read/update; and dark bold query/refresh text.

- [ ] **Step 6: Record the checkpoint**

List changed files and the four Maven results in the final handoff. Explicitly state that account-and-role management remains the next phase and was not silently added to this phase.
