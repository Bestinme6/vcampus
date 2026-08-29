# VCampus Account Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a super-administrator-only account management page that transactionally creates student and teacher accounts, manages roles and account state, resets passwords, and enforces password change before business access.

**Architecture:** Shared actions and policies remain in `vcampus-common`; a new server-side `AccountService` delegates atomic JDBC work to `AccountRepository`; Swing account management uses the existing Socket protocol and never accesses MySQL. Session invalidation and a router-level forced-password gate make role, state, and password changes effective immediately.

**Tech Stack:** Java 21, Maven, Swing, Java Socket/I/O, MySQL 8.0.44, PBKDF2-HMAC-SHA256, JUnit 5, H2 2.3.232 in MySQL compatibility mode for repository transaction tests.

**Spec:** `docs/superpowers/specs/2026-08-26-account-management-design.md`

## Global Constraints

- Preserve MySQL database -> application server -> Swing client; the client must never import JDBC or connect to MySQL.
- Use the length-prefixed `MessageCodec` protocol and `account.` / `auth.` action prefixes; do not use Java native serialization.
- Every normal account has exactly one immutable base identity: `STUDENT` or `TEACHER`; account management cannot create or modify `SUPER_ADMIN`.
- Student administrator roles are limited to `LIBRARY_ADMIN` and `FORUM_ADMIN`; teachers may receive any of the six business administrator roles.
- Student login names equal 10-digit student numbers; teacher login names equal `T` plus 7 digits; these values are immutable.
- Passwords are 8–128 characters, PBKDF2-hashed with random salt, never logged, and zeroed from temporary character arrays.
- All network/database work stays off the Swing Event Dispatch Thread; all Swing mutations return to it.
- Creation is transactional and must not leave a user, role, profile, or initial status history partially written.
- The project directory is not a Git repository, so this plan deliberately uses verified test checkpoints instead of commit steps.

---

### Task 1: Shared account policies and protocol actions

**Files:**
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/RoleCompositionPolicy.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/AccountAccessPolicy.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/ForcedPasswordAccessPolicy.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Modify: `vcampus-common/src/test/java/com/vcampus/common/model/RoleCompositionPolicyTest.java`
- Create: `vcampus-common/src/test/java/com/vcampus/common/model/AccountAccessPolicyTest.java`
- Create: `vcampus-common/src/test/java/com/vcampus/common/model/ForcedPasswordAccessPolicyTest.java`

**Interfaces:**
- Produces: `RoleCompositionPolicy.baseIdentity(Set<UserRole>)`, `allowedAdministrativeRoles(UserRole)`, and `administrativeRoles(Set<UserRole>)`.
- Produces: `AccountAccessPolicy.canManageAccounts(Set<UserRole>)`.
- Produces: `ForcedPasswordAccessPolicy.isAllowed(String action)`.
- Produces: `Actions.AUTH_CHANGE_PASSWORD` and six `Actions.ACCOUNT_*` constants.

- [ ] **Step 1: Write failing role-helper tests**

```java
@Test
void exposesBaseIdentityAndAllowedAdministrativeRoles() {
    assertEquals(UserRole.STUDENT,
            RoleCompositionPolicy.baseIdentity(Set.of(UserRole.STUDENT, UserRole.FORUM_ADMIN)));
    assertEquals(Set.of(UserRole.LIBRARY_ADMIN, UserRole.FORUM_ADMIN),
            RoleCompositionPolicy.allowedAdministrativeRoles(UserRole.STUDENT));
    assertTrue(RoleCompositionPolicy.allowedAdministrativeRoles(UserRole.TEACHER)
            .contains(UserRole.ACADEMIC_ADMIN));
    assertEquals(Set.of(UserRole.FORUM_ADMIN),
            RoleCompositionPolicy.administrativeRoles(Set.of(UserRole.TEACHER, UserRole.FORUM_ADMIN)));
}
```

- [ ] **Step 2: Write failing access-policy tests**

```java
@Test
void onlyStandaloneSuperAdministratorManagesAccounts() {
    assertTrue(AccountAccessPolicy.canManageAccounts(Set.of(UserRole.SUPER_ADMIN)));
    assertFalse(AccountAccessPolicy.canManageAccounts(Set.of(UserRole.TEACHER, UserRole.ACADEMIC_ADMIN)));
    assertFalse(AccountAccessPolicy.canManageAccounts(Set.of(UserRole.STUDENT)));
}

@Test
void forcedPasswordSessionOnlyUsesAuthenticationActions() {
    assertTrue(ForcedPasswordAccessPolicy.isAllowed(Actions.AUTH_CHANGE_PASSWORD));
    assertTrue(ForcedPasswordAccessPolicy.isAllowed(Actions.AUTH_LOGOUT));
    assertTrue(ForcedPasswordAccessPolicy.isAllowed(Actions.AUTH_SESSION));
    assertFalse(ForcedPasswordAccessPolicy.isAllowed(Actions.STUDENT_GET_SELF));
    assertFalse(ForcedPasswordAccessPolicy.isAllowed(Actions.ACCOUNT_SEARCH));
}
```

- [ ] **Step 3: Run tests and verify RED**

Run:

```text
mvn -pl vcampus-common -Dtest=RoleCompositionPolicyTest,AccountAccessPolicyTest,ForcedPasswordAccessPolicyTest test
```

Expected: test compilation fails because the new policies, helpers, and actions do not exist.

- [ ] **Step 4: Implement the policies and actions**

Add action values exactly as follows:

```java
public static final String AUTH_CHANGE_PASSWORD = "auth.changePassword";
public static final String ACCOUNT_SEARCH = "account.search";
public static final String ACCOUNT_REFERENCE_DATA = "account.referenceData";
public static final String ACCOUNT_CREATE = "account.create";
public static final String ACCOUNT_UPDATE_ROLES = "account.updateRoles";
public static final String ACCOUNT_SET_ENABLED = "account.setEnabled";
public static final String ACCOUNT_RESET_PASSWORD = "account.resetPassword";
```

Implement account authorization and the forced-password allowlist as pure functions:

```java
public static boolean canManageAccounts(Set<UserRole> roles) {
    return Set.of(UserRole.SUPER_ADMIN).equals(Set.copyOf(roles));
}

private static final Set<String> ALLOWED = Set.of(
        Actions.AUTH_CHANGE_PASSWORD, Actions.AUTH_LOGOUT, Actions.AUTH_SESSION);

public static boolean isAllowed(String action) {
    return ALLOWED.contains(action);
}
```

`baseIdentity` must call `requireValid` first. `allowedAdministrativeRoles(STUDENT)` returns library/forum; `TEACHER` returns all six business roles; `SUPER_ADMIN` returns an empty set. Return immutable sets.

- [ ] **Step 5: Run common tests and verify GREEN**

Run: `mvn -pl vcampus-common test`

Expected: all common tests pass with zero failures.

---

### Task 2: Testable authentication persistence and session lifecycle

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/UserAccountStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/AuditStore.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/UserRepository.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/AuditRepository.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/security/SessionManager.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/AuthService.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/security/SessionManagerTest.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/service/AuthServiceTest.java`

**Interfaces:**
- Produces: `UserAccountStore.findByUsername`, `updateLastLogin`, and `updatePassword`.
- Produces: `AuditStore.record(Long, String, String, String)`.
- Produces: `SessionManager.invalidateUser(long)`, `completePasswordChange(String)`, and `requiresPasswordChange(String)`.
- Produces: `AuthService.changePassword(RequestMessage, String)`.

- [ ] **Step 1: Define persistence interfaces and adapt existing repositories**

```java
public interface UserAccountStore {
    Optional<UserAccount> findByUsername(String username) throws SQLException;
    void updateLastLogin(long userId) throws SQLException;
    boolean updatePassword(long userId, PasswordHash password, boolean forcePasswordChange)
            throws SQLException;
}

public interface AuditStore {
    void record(Long userId, String action, String result, String clientAddress);
}
```

Make `UserRepository implements UserAccountStore` and `AuditRepository implements AuditStore`. Add this parameterized update:

```sql
UPDATE users
   SET password_hash = ?, password_salt = ?, force_password_change = ?
 WHERE id = ?
```

Change `AuthService` constructor fields to these interfaces without changing existing login behavior.

- [ ] **Step 2: Write failing session lifecycle tests**

```java
@Test
void invalidatesEverySessionForUser() {
    UserSession first = sessions.create(account(21L, true));
    UserSession second = sessions.create(account(21L, true));
    sessions.invalidateUser(21L);
    assertTrue(sessions.find(first.token()).isEmpty());
    assertTrue(sessions.find(second.token()).isEmpty());
}

@Test
void completesPasswordChangeForCurrentSession() {
    UserSession session = sessions.create(account(21L, true));
    assertTrue(sessions.requiresPasswordChange(session.token()));
    assertTrue(sessions.completePasswordChange(session.token()));
    assertFalse(sessions.requiresPasswordChange(session.token()));
}
```

- [ ] **Step 3: Run the session test and verify RED**

Run: `mvn -pl vcampus-server -am -Dtest=SessionManagerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails for the three missing session methods.

- [ ] **Step 4: Implement session lifecycle methods**

`invalidateUser` removes every map entry whose session has the target `userId`. `requiresPasswordChange` returns `false` for absent tokens. `completePasswordChange` replaces the immutable `UserSession` under the same token with identical values except `forcePasswordChange=false`.

```java
public void invalidateUser(long userId) {
    sessions.entrySet().removeIf(entry -> entry.getValue().userId() == userId);
}
```

- [ ] **Step 5: Write failing password-change service tests with fake stores**

Cover correct current password, wrong current password, same new password, invalid length, expired session, repository failure, password flag clearing, session update, and audit result. The success assertion must include:

```java
ResponseMessage response = service.changePassword(request(
        session.token(), "temporary-123", "new-password-456"), "127.0.0.1");
assertTrue(response.success());
assertFalse(sessions.requiresPasswordChange(session.token()));
assertFalse(hasher.verify("temporary-123".toCharArray(), store.hash, store.salt));
assertTrue(hasher.verify("new-password-456".toCharArray(), store.hash, store.salt));
```

- [ ] **Step 6: Run the auth test and verify RED**

Run: `mvn -pl vcampus-server -am -Dtest=AuthServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because `AuthService.changePassword` does not exist.

- [ ] **Step 7: Implement password change**

Read `sessionToken`, `currentPassword`, and `newPassword`; verify the session, reload the account by the session username, verify the current password, reject an identical new password, hash the new password, update the row with `forcePasswordChange=false`, update the session, and audit `SUCCESS` or the appropriate failure. Clear both password arrays in `finally`.

- [ ] **Step 8: Run server tests and verify GREEN**

Run: `mvn -pl vcampus-server -am test`

Expected: all common and server tests pass.

---

### Task 3: Account repository and atomic creation

**Files:**
- Modify: `pom.xml`
- Modify: `vcampus-server/pom.xml`
- Create: `vcampus-server/src/main/java/com/vcampus/server/model/AccountSummary.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/AccountStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/AccountRepository.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/AccountRepositoryTest.java`

**Interfaces:**
- Produces: immutable `AccountSummary` and `AccountStore.AccountPage`.
- Produces: `AccountStore.CreateStudentAccount` and `CreateTeacherAccount` commands.
- Produces: search, reference data, create student/teacher, replace roles, set enabled, and reset password persistence methods.

- [ ] **Step 1: Add H2 test dependency**

Add `<h2.version>2.3.232</h2.version>` to the parent and dependency management entry for `com.h2database:h2`. Add it with `test` scope to `vcampus-server/pom.xml`. Production remains MySQL-only.

- [ ] **Step 2: Define focused account records and store interface**

```java
public record AccountSummary(
        long userId, String username, String displayName, UserRole baseIdentity,
        Set<UserRole> administrativeRoles, boolean enabled,
        boolean forcePasswordChange, Instant lastLoginAt) {
    public AccountSummary { administrativeRoles = Set.copyOf(administrativeRoles); }
}
```

`AccountStore` must expose these exact methods:

```java
AccountPage search(String keyword, UserRole identity, Boolean enabled, int page, int pageSize)
        throws SQLException;
AccountReferences referenceData() throws SQLException;
long createStudent(CreateStudentAccount command, PasswordHash password, long operatorUserId)
        throws SQLException;
long createTeacher(CreateTeacherAccount command, PasswordHash password) throws SQLException;
Optional<AccountSummary> findManageableById(long userId) throws SQLException;
boolean replaceAdministrativeRoles(long userId, UserRole baseIdentity, Set<UserRole> roles)
        throws SQLException;
boolean setEnabled(long userId, boolean enabled) throws SQLException;
boolean resetPassword(long userId, PasswordHash password) throws SQLException;
```

The student command carries student number, profile fields, and the complete validated role set. The teacher command carries teacher number, profile fields, and the complete validated role set. Reference records carry IDs, parent IDs, codes, names, and enrollment year.

- [ ] **Step 3: Write H2 schema fixture and failing repository tests**

Use an in-memory URL unique per test:

```java
DatabaseConfig config = new DatabaseConfig(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
ConnectionFactory connections = new ConnectionFactory(config);
```

Create minimal `users`, `roles`, `user_roles`, `departments`, `majors`, `administrative_classes`, `student_profiles`, `teacher_profiles`, and `student_status_history` tables in `@BeforeEach`, then seed all `UserRole` codes and one valid academic hierarchy.

Tests must prove:

```java
@Test void createsStudentUserRolesProfileAndInitialHistoryInOneTransaction() { ... }
@Test void rollsBackStudentCreationWhenClassDoesNotMatchMajor() { ... }
@Test void createsTeacherUserRolesAndProfileInOneTransaction() { ... }
@Test void rollsBackTeacherCreationWhenDepartmentIsMissing() { ... }
@Test void searchExcludesSuperAdministrators() { ... }
@Test void replacesOnlyAdministrativeRolesAndPreservesBaseIdentity() { ... }
@Test void updatesEnabledStateAndTemporaryPasswordFlag() { ... }
```

For rollback assertions, query all affected tables and assert no row remains for the failed username.

- [ ] **Step 4: Run repository tests and verify RED**

Run: `mvn -pl vcampus-server -am -Dtest=AccountRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because account persistence types do not exist.

- [ ] **Step 5: Implement parameterized JDBC and transactions**

Use one connection with `autoCommit=false` in each create/role-replacement method. Validate the student hierarchy before insert:

```sql
SELECT 1
  FROM departments d
  JOIN majors m ON m.department_id = d.id
  JOIN administrative_classes c ON c.major_id = m.id
 WHERE d.id = ? AND m.id = ? AND c.id = ? AND c.enrollment_year = ?
```

Look up every role ID from seeded `roles`; missing roles fail the transaction. Search derives the base identity and aggregates role rows in Java to avoid database-specific `GROUP_CONCAT` behavior. Filter SQL must exclude users carrying `SUPER_ADMIN`.

- [ ] **Step 6: Run repository tests and verify GREEN**

Run: `mvn -pl vcampus-server -am -Dtest=AccountRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: all repository tests pass, including rollback checks.

---

### Task 4: Super-administrator account business service

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/service/AccountService.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/service/AccountServiceTest.java`

**Interfaces:**
- Consumes: `AccountStore`, `AuditStore`, `PasswordHasher`, `SessionManager`, `RoleCompositionPolicy`, and `RowCodec`.
- Produces: one public method per `account.*` action: `search`, `referenceData`, `create`, `updateRoles`, `setEnabled`, and `resetPassword`.

- [ ] **Step 1: Write a fake `AccountStore` and failing authorization tests**

Create sessions for standalone super administrator, student, and teacher. For every account method assert expired sessions receive “登录已过期，请重新登录” and non-super sessions receive “无权执行此操作”. Confirm the fake store is untouched.

- [ ] **Step 2: Write failing creation and maintenance tests**

Cover these exact scenarios:

```java
@Test void superAdministratorCreatesStudentWithOnlyLibraryAndForumRoles() { ... }
@Test void superAdministratorCreatesTeacherWithAllSixAdministrativeRoles() { ... }
@Test void rejectsStudentWithAcademicAdministratorRole() { ... }
@Test void rejectsSuperAdministratorAsCreatedIdentity() { ... }
@Test void rejectsMismatchedLoginNumberAndInvalidPassword() { ... }
@Test void updatesOnlyAdministrativeRolesThenInvalidatesTargetSessions() { ... }
@Test void disablingTargetInvalidatesTargetSessions() { ... }
@Test void resetHashesTemporaryPasswordAndInvalidatesTargetSessions() { ... }
@Test void searchEncodesRowsAndNeverReturnsPasswordMaterial() { ... }
```

Assert `RoleCompositionPolicy.requireValid` receives the complete base-plus-administrator set, not just checked boxes.

- [ ] **Step 3: Run service tests and verify RED**

Run: `mvn -pl vcampus-server -am -Dtest=AccountServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because `AccountService` is missing.

- [ ] **Step 4: Implement validation and response encoding**

Centralize authorization in:

```java
private Optional<UserSession> administrator(RequestMessage request) {
    return sessions.find(request.parameters().get("sessionToken"))
            .filter(session -> AccountAccessPolicy.canManageAccounts(session.roles()));
}
```

Differentiate expired from forbidden before calling it. Parse `identity` only as `STUDENT` or `TEACHER`; build the complete role set; enforce exact number patterns; validate required profile fields, email, phone, dates, IDs, and password length. Hash passwords immediately before repository calls and clear arrays in `finally`.

Encode each search row with `RowCodec` fields:

```text
userId, username, displayName, baseIdentity, administrativeRoles,
enabled, forcePasswordChange, lastLoginAt
```

Return `rows`, `page`, `pageSize`, and `total`. Reference data uses the existing indexed-key convention used by `StudentService`.

- [ ] **Step 5: Run service and server tests and verify GREEN**

Run: `mvn -pl vcampus-server -am test`

Expected: all server tests pass.

---

### Task 5: Route enforcement and server wiring

**Files:**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterTest.java`

**Interfaces:**
- Consumes: `AuthService.changePassword`, all `AccountService` methods, `SessionManager.requiresPasswordChange`, and `ForcedPasswordAccessPolicy`.
- Produces: fully routed `auth.changePassword` and `account.*` actions plus centralized forced-password protection.

- [ ] **Step 1: Write failing router gate tests**

Construct a forced-password session and assert a request for `student.getSelf` is rejected before `StudentService` is invoked:

```java
ResponseMessage response = router.route(
        authorized(Actions.STUDENT_GET_SELF, forcedSession.token()), "127.0.0.1");
assertFalse(response.success());
assertEquals("请先修改初始密码", response.message());
```

Also assert `auth.changePassword`, `auth.logout`, and `auth.session` pass the gate, and an absent/expired token continues to the normal service so existing error semantics remain intact.

- [ ] **Step 2: Run the router test and verify RED**

Run: `mvn -pl vcampus-server -am -Dtest=RequestRouterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: constructor/action compilation failures because the gate and account service are not wired.

- [ ] **Step 3: Add gate and action routing**

Before the switch, for actions other than login and ping, read `sessionToken`. If `sessions.requiresPasswordChange(token)` and `!ForcedPasswordAccessPolicy.isAllowed(action)`, return the required failure. Add switch cases for all seven new actions and remove the `Actions.STUDENT_CREATE` case.

- [ ] **Step 4: Wire production dependencies once**

In `VCampusServer`, create one shared `UserRepository`, `AuditRepository`, `SessionManager`, and `PasswordHasher`; pass them to both authentication and account services. Construct `AccountRepository(connections)`, then pass `AccountService` and `SessionManager` into the router.

- [ ] **Step 5: Run all server tests and verify GREEN**

Run: `mvn -pl vcampus-server -am test`

Expected: common and server modules compile and all tests pass.

---

### Task 6: Client account protocol adapter and form model

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/AccountViewData.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/ui/AccountViewDataTest.java`

**Interfaces:**
- Produces: client methods `searchAccounts`, `getAccountReferenceData`, `createAccount`, `updateAccountRoles`, `setAccountEnabled`, `resetAccountPassword`, and `changePassword`.
- Produces: `AccountViewData.AccountRow`, `AccountPage`, `ReferenceItem`, and `ReferenceData` parsing helpers.

- [ ] **Step 1: Write failing parsing tests**

Use a `ResponseMessage` whose `rows` field contains two `RowCodec` rows. Assert parsing of identity, comma-separated administrator roles, booleans, empty last-login timestamp, page metadata, and hierarchical references. Include malformed numeric and role values and assert a readable `IllegalArgumentException`.

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -pl vcampus-client -am -Dtest=AccountViewDataTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because `AccountViewData` is missing.

- [ ] **Step 3: Implement immutable parsing records**

```java
record AccountRow(long userId, String username, String displayName,
        UserRole baseIdentity, Set<UserRole> administrativeRoles,
        boolean enabled, boolean forcePasswordChange, String lastLoginAt) {}

record AccountPage(List<AccountRow> rows, int page, int pageSize, int total) {}
```

Return defensive immutable collections and translate corrupt protocol data into “服务器返回的账号数据格式不正确”.

- [ ] **Step 4: Add Socket client methods**

Each method calls `sendAuthorized` with the exact action and parameter keys from Tasks 1 and 4. Password methods accept `char[]`, construct the request as late as possible, and never store passwords in object fields.

- [ ] **Step 5: Run client tests and verify GREEN**

Run: `mvn -pl vcampus-client -am test`

Expected: all common and client tests pass.

---

### Task 7: Account management Swing page and super-admin navigation

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/AccountManagementPanel.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/AccountCreateDialog.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/AccountRolesDialog.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/AccountFormPolicy.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/ui/AccountFormPolicyTest.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/MainFrame.java`

**Interfaces:**
- Consumes: `AccountViewData`, client account methods, `AccountAccessPolicy`, and `RoleCompositionPolicy.allowedAdministrativeRoles`.
- Produces: one embedded account-management content panel and deterministic identity-specific form behavior.

- [ ] **Step 1: Write failing form-policy tests**

```java
@Test
void studentFormUsesStudentFieldsAndOnlyTwoRoles() {
    assertEquals("2026000001", AccountFormPolicy.username(UserRole.STUDENT, "2026000001"));
    assertEquals(Set.of(UserRole.LIBRARY_ADMIN, UserRole.FORUM_ADMIN),
            AccountFormPolicy.allowedRoles(UserRole.STUDENT));
    assertTrue(AccountFormPolicy.showsAcademicClassFields(UserRole.STUDENT));
}

@Test
void teacherFormUsesTeacherNumberAndSixRoles() {
    assertEquals("T0000001", AccountFormPolicy.username(UserRole.TEACHER, "T0000001"));
    assertFalse(AccountFormPolicy.showsAcademicClassFields(UserRole.TEACHER));
    assertEquals(6, AccountFormPolicy.allowedRoles(UserRole.TEACHER).size());
}
```

Also reject `SUPER_ADMIN` and invalid number formats.

- [ ] **Step 2: Run form-policy tests and verify RED**

Run: `mvn -pl vcampus-client -am -Dtest=AccountFormPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because `AccountFormPolicy` is missing.

- [ ] **Step 3: Implement form policy and dialogs**

`AccountCreateDialog` switches student/teacher cards with `CardLayout`. It filters majors by selected department and classes by selected major, derives the immutable username preview from number input, validates required fields and password confirmation, and exposes one immutable submission map only after validation.

`AccountRolesDialog` displays the base identity read-only and only the permitted administrator checkboxes. Both dialogs use white backgrounds, dark labels, `Theme.styleCommandButton`, and clear password arrays after closing.

- [ ] **Step 4: Build the account list panel**

Use a `JTable` with non-editable model columns from the specification. Execute search, reference, create, role update, enable/disable, and reset calls with `CompletableFuture.supplyAsync`; disable the initiating button until completion; call `UiDialogs.showSuccess` then reload the current page.

Reset password uses two `JPasswordField` values, rejects mismatch locally, confirms before sending, and clears both arrays in `finally`. Enable/disable confirms the target username and desired state. No delete button is created.

- [ ] **Step 5: Refactor `MainFrame` to switch embedded content**

Introduce a center `CardLayout` containing `WORKSPACE` and `ACCOUNT_MANAGEMENT`. Store the workbench and account navigation buttons, update their foreground/background selected state on switch, and add the account button directly below workbench only when:

```java
AccountAccessPolicy.canManageAccounts(roles)
```

Keep student/teacher navigation unchanged and preserve logout behavior.

- [ ] **Step 6: Run client tests and compile**

Run: `mvn -pl vcampus-client -am test`

Expected: all client tests pass and every Swing class compiles under Java 21.

---

### Task 8: Forced-password client flow

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/PasswordChangeDialog.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/PasswordChangeForm.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/ui/PasswordChangeFormTest.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/LoginFrame.java`

**Interfaces:**
- Consumes: login response `forcePasswordChange`, session token, and `VCampusClient.changePassword`.
- Produces: a modal, non-bypassable password-change flow before `MainFrame` is constructed.

- [ ] **Step 1: Write failing password-form tests**

```java
@Test void acceptsDistinctMatchingPasswordOfValidLength() { ... }
@Test void rejectsWrongConfirmation() { ... }
@Test void rejectsShortOrLongNewPassword() { ... }
@Test void rejectsNewPasswordEqualToCurrentPassword() { ... }
```

The validator returns a specific Chinese validation message and never converts stored `char[]` fields to long-lived strings.

- [ ] **Step 2: Run the test and verify RED**

Run: `mvn -pl vcampus-client -am -Dtest=PasswordChangeFormTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because `PasswordChangeForm` is missing.

- [ ] **Step 3: Implement the modal dialog**

Display current temporary password, new password, and confirmation fields. The confirm action validates locally, performs the request asynchronously, shows “操作成功”, marks the dialog completed, and closes. Closing/cancelling without success calls a supplied logout callback and returns to the login screen. Clear all password arrays after every attempt and on dispose.

- [ ] **Step 4: Integrate with successful login**

After parsing roles and session metadata, branch before creating `MainFrame`:

```java
if (Boolean.parseBoolean(response.data().getOrDefault("forcePasswordChange", "false"))) {
    openRequiredPasswordChange(sessionToken, displayName, roles);
} else {
    openMainFrame(sessionToken, displayName, roles);
}
```

On successful change, open `MainFrame` with the same token; on cancel, invalidate it and redisplay a clean `LoginFrame`.

- [ ] **Step 5: Run client tests and verify GREEN**

Run: `mvn -pl vcampus-client -am test`

Expected: all client tests pass.

---

### Task 9: Remove duplicate student-account creation

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/StudentFrame.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/StudentService.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/StudentRepository.java`
- Modify: affected tests in `vcampus-common/src/test` and `vcampus-server/src/test`

**Interfaces:**
- Removes: `Actions.STUDENT_CREATE`, `VCampusClient.createStudent`, `StudentService.create`, and account-creation JDBC methods from `StudentRepository`.
- Preserves: all student search, view, edit, contact, status, history, and reference-data interfaces.

- [ ] **Step 1: Add/adjust regression assertions before deletion**

Add a UI policy or component-level assertion that an administrator student frame exposes edit/contact/status actions but no create action. Preserve existing `StudentAccessPolicyTest` assertions for management permission.

- [ ] **Step 2: Remove the duplicate UI and protocol path**

Delete the “新增学生” button, its dialog method, password handling, client network method, action constant, router branch (already removed in Task 5), service method, and repository-only creation helpers. Do not remove student update or reference-data code used by account creation dialogs.

- [ ] **Step 3: Search for stale symbols**

Run:

```text
rg -n "STUDENT_CREATE|client\.createStudent|新增学生" vcampus-common/src vcampus-server/src vcampus-client/src docs
```

Expected: no production-code hits; documentation hits are corrected in Task 10.

- [ ] **Step 4: Run all module tests**

Run: `mvn test`

Expected: all modules pass without stale references.

---

### Task 10: Documentation, database compatibility, and full verification

**Files:**
- Modify: `docs/requirements.md`
- Modify: `docs/authentication.md`
- Modify: `docs/student-management.md`
- Modify: `docs/teacher-profile.md`
- Create: `docs/account-management.md`
- Modify: `README.md`
- Review/modify only if required: `database/schema.sql`

**Interfaces:**
- Produces: Eclipse/MySQL setup and acceptance instructions matching the implemented feature.

- [ ] **Step 1: Update documentation with exact behavior**

Document super-admin-only navigation, account search/create/roles/state/reset, immutable login identifiers, permitted role matrix, no deletion, creation transaction, session invalidation, and forced password change. Remove every statement saying the account management UI is a future phase and remove instructions that create students through the academic-record window.

- [ ] **Step 2: Confirm schema compatibility**

Verify `users.force_password_change`, `users.enabled`, unique usernames, role links, both profile tables, and status history already exist. Do not change `schema.sql` unless an implementation test proves a missing constraint or column; if changed, use non-destructive `CREATE TABLE IF NOT EXISTS`/index migration-compatible SQL.

- [ ] **Step 3: Run architecture scans**

Run:

```text
rg -n "ObjectInputStream|ObjectOutputStream" vcampus-common/src vcampus-client/src vcampus-server/src
rg -n "java\.sql|jdbc:mysql" vcampus-client/src vcampus-common/src
rg -n "STUDENT_CREATE|client\.createStudent|新增学生" vcampus-common/src vcampus-server/src vcampus-client/src docs
```

Expected: no native serialization; no client/common JDBC; no obsolete student-account creation references.

- [ ] **Step 4: Run the complete clean build**

Run: `mvn clean verify`

Expected: reactor success for parent, common, server, and client with zero test failures.

- [ ] **Step 5: Perform manual Eclipse/MySQL acceptance**

1. Re-run the current `database/schema.sql`, Maven Update Project, and Project Clean.
2. Start `ServerMain`, then `ClientMain`.
3. Confirm account navigation appears only for super administrator.
4. Create one student with library administrator and one teacher with all six business administrator roles.
5. Confirm each is forced to change the temporary password and then sees the correct dynamic workbench.
6. Change roles and verify the old session is rejected and the next login shows updated cards.
7. Disable an account and verify login is rejected; re-enable it and verify login works.
8. Reset a password and verify the previous password fails, the temporary password works, and forced change runs again.
9. Confirm学籍管理 no longer contains“新增学生” while its search/edit/status functions still work.
