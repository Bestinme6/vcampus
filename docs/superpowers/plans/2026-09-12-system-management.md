# System Management and Audit Query Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a super-administrator-only JavaFX system-management workspace for editing existing term settings, maintaining twelve optional class-period times, and searching immutable audit logs.

**Architecture:** Add typed shared contracts under `vcampus-common`, structured persistence and query repositories plus a permission-enforcing service under `vcampus-server`, and an asynchronous gateway/controller/view stack under `vcampus-client`. Reuse `academic_terms`, add `class_period_times`, expose period times to normal academic reference-data consumers, and keep operational secrets in environment variables.

**Tech Stack:** Java 21, Maven, JavaFX 21, Java Socket with `MessageCodec`/`RowCodec`, JDBC, MySQL 8, H2 tests, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-12-system-management-design.md`

## Global Constraints

- Preserve MySQL database → application server → JavaFX/Swing client; the client never connects directly to MySQL.
- All four new actions use the `system.` prefix and the existing length-prefixed binary protocol.
- Every system-management operation requires an exact `{SUPER_ADMIN}` role set on the server.
- Do not expose or edit database passwords, Socket ports, thread counts, or attachment paths in the UI.
- Network and database work stays off the JavaFX Application Thread; UI changes return to that thread.
- Update `database/schema.sql`, `database/seed.sql`, and a new migration together.
- Preserve the user's existing uncommitted login-view changes and the current exam-scope documentation edits.
- Implement each task test-first and commit only that task's files.

---

## File Structure

### Shared contracts

- `vcampus-common/src/main/java/com/vcampus/common/model/SystemAccessPolicy.java`: exact super-admin authorization rule.
- `vcampus-common/src/main/java/com/vcampus/common/model/SystemTermConfig.java`: immutable editable term projection with row encoding.
- `vcampus-common/src/main/java/com/vcampus/common/model/ClassPeriodTime.java`: nullable start/end time pair with validation and row encoding.
- `vcampus-common/src/main/java/com/vcampus/common/model/AuditLogEntry.java`: immutable audit projection with row encoding.
- `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`: four system actions.

### Server

- `vcampus-server/src/main/java/com/vcampus/server/database/SystemManagementStore.java`: configuration read/write port.
- `vcampus-server/src/main/java/com/vcampus/server/database/SystemManagementRepository.java`: JDBC configuration persistence.
- `vcampus-server/src/main/java/com/vcampus/server/database/AuditQueryStore.java`: typed paginated audit query port.
- `vcampus-server/src/main/java/com/vcampus/server/database/AuditQueryRepository.java`: parameterized audit query implementation.
- `vcampus-server/src/main/java/com/vcampus/server/service/SystemManagementService.java`: session, permission, validation, response, and mutation auditing.
- `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`: route the four actions.
- `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`: production dependency wiring.
- `vcampus-server/src/main/java/com/vcampus/server/database/AcademicRepository.java`: include period rows in academic reference data.
- `vcampus-server/src/main/java/com/vcampus/server/service/AcademicService.java`: encode period rows for normal academic consumers.

### Client

- `vcampus-client/src/main/java/com/vcampus/client/fx/system/SystemData.java`: strict response parsing and form validation.
- `vcampus-client/src/main/java/com/vcampus/client/fx/system/SystemGateway.java`: blocking client port.
- `vcampus-client/src/main/java/com/vcampus/client/fx/system/SocketSystemGateway.java`: Socket implementation.
- `vcampus-client/src/main/java/com/vcampus/client/fx/system/SystemManagementController.java`: asynchronous lifecycle and stale-response protection.
- `vcampus-client/src/main/java/com/vcampus/client/fx/system/SystemManagementView.java`: two-tab JavaFX workspace.
- `vcampus-client/src/main/resources/com/vcampus/client/fx/system/system.css`: module styling.
- `vcampus-client/src/main/java/com/vcampus/client/ui/ClassPeriodFormatter.java`: shared configured-time formatting.
- `vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicData.java`: decode period reference rows.
- `vcampus-client/src/main/java/com/vcampus/client/ui/CampusDashboardLoader.java`: enrich dashboard period text.
- `vcampus-client/src/main/java/com/vcampus/client/fx/academic/StudentScheduleView.java`: enrich student schedule period text.
- `vcampus-client/src/main/java/com/vcampus/client/fx/academic/TeacherWorkspaceView.java`: enrich teacher schedule period text.
- `vcampus-client/src/main/java/com/vcampus/client/fx/CampusApplication.java`: navigation, routing, and controller lifecycle.

### Database and documentation

- `database/migrations/018_system_management.sql`: upgrade existing databases.
- `database/schema.sql`: canonical `class_period_times` structure.
- `database/seed.sql`: twelve empty period rows and non-overwriting term seed.
- `docs/system-management.md` and current delivery documents: behavior, coverage, testing, and demo flow.

---

### Task 1: Shared Protocol and Domain Contracts

**Files:**
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/SystemAccessPolicy.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/SystemTermConfig.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/ClassPeriodTime.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/AuditLogEntry.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Create: `vcampus-common/src/test/java/com/vcampus/common/model/SystemAccessPolicyTest.java`
- Create: `vcampus-common/src/test/java/com/vcampus/common/model/SystemManagementModelTest.java`
- Create: `vcampus-common/src/test/java/com/vcampus/common/protocol/SystemActionsTest.java`

**Interfaces:**
- Produces: `SystemAccessPolicy.canManage(Set<UserRole>)`.
- Produces: `SystemTermConfig.encode()` and `SystemTermConfig.decode(String)`.
- Produces: `ClassPeriodTime.encode()` and `ClassPeriodTime.decode(String)`.
- Produces: `AuditLogEntry.encode()` and `AuditLogEntry.decode(String)`.
- Produces: `Actions.SYSTEM_CONFIG_GET`, `SYSTEM_CONFIG_UPDATE_TERM`, `SYSTEM_CONFIG_UPDATE_PERIODS`, `SYSTEM_AUDIT_SEARCH`.

- [ ] **Step 1: Write failing action and permission tests**

```java
@Test void systemActionsUseStablePrefix() {
    assertEquals("system.config.get", Actions.SYSTEM_CONFIG_GET);
    assertEquals("system.config.updateTerm", Actions.SYSTEM_CONFIG_UPDATE_TERM);
    assertEquals("system.config.updatePeriods", Actions.SYSTEM_CONFIG_UPDATE_PERIODS);
    assertEquals("system.audit.search", Actions.SYSTEM_AUDIT_SEARCH);
}

@Test void onlyExactSuperAdministratorSetCanManage() {
    assertTrue(SystemAccessPolicy.canManage(Set.of(UserRole.SUPER_ADMIN)));
    assertFalse(SystemAccessPolicy.canManage(Set.of(UserRole.TEACHER)));
    assertFalse(SystemAccessPolicy.canManage(Set.of(UserRole.SUPER_ADMIN, UserRole.FORUM_ADMIN)));
}
```

- [ ] **Step 2: Run the focused tests and observe the missing-symbol failure**

Run: `mvn -pl vcampus-common -Dtest=SystemActionsTest,SystemAccessPolicyTest test`

Expected: test compilation fails because the new actions and `SystemAccessPolicy` do not exist.

- [ ] **Step 3: Add the action constants and exact-role policy**

```java
public static final String SYSTEM_CONFIG_GET = "system.config.get";
public static final String SYSTEM_CONFIG_UPDATE_TERM = "system.config.updateTerm";
public static final String SYSTEM_CONFIG_UPDATE_PERIODS = "system.config.updatePeriods";
public static final String SYSTEM_AUDIT_SEARCH = "system.audit.search";

public static boolean canManage(Set<UserRole> roles) {
    Objects.requireNonNull(roles, "roles");
    return Set.of(UserRole.SUPER_ADMIN).equals(Set.copyOf(roles));
}
```

- [ ] **Step 4: Write failing round-trip and validation tests**

```java
@Test void termPeriodAndAuditRowsRoundTrip() {
    var term = new SystemTermConfig(7, "2026-2027", 1, "第一学期",
            LocalDate.parse("2026-09-01"), LocalDate.parse("2027-01-15"),
            LocalDateTime.parse("2026-08-20T00:00:00"),
            LocalDateTime.parse("2026-09-10T23:59:59"),
            LocalDateTime.parse("2026-09-20T23:59:59"), AcademicTermStatus.SELECTION);
    assertEquals(term, SystemTermConfig.decode(term.encode()));

    var period = new ClassPeriodTime(1, LocalTime.parse("08:00"), LocalTime.parse("08:45"));
    assertEquals(period, ClassPeriodTime.decode(period.encode()));

    var audit = new AuditLogEntry(9, 1L, "admin", "系统管理员",
            Actions.SYSTEM_CONFIG_UPDATE_TERM, "SUCCESS", "127.0.0.1",
            Instant.parse("2026-09-12T08:00:00Z"));
    assertEquals(audit, AuditLogEntry.decode(audit.encode()));
}

@Test void periodRequiresBothTimesAndIncreasingOrder() {
    assertThrows(IllegalArgumentException.class,
            () -> new ClassPeriodTime(1, LocalTime.NOON, null));
    assertThrows(IllegalArgumentException.class,
            () -> new ClassPeriodTime(1, LocalTime.NOON, LocalTime.NOON));
}
```

- [ ] **Step 5: Implement immutable models with strict field counts**

```java
public record ClassPeriodTime(int periodNumber, LocalTime startTime, LocalTime endTime) {
    public ClassPeriodTime {
        if (periodNumber < 1 || periodNumber > 12) throw new IllegalArgumentException("节次必须为 1—12");
        if ((startTime == null) != (endTime == null)) throw new IllegalArgumentException("开始和结束时间必须同时填写");
        if (startTime != null && !startTime.isBefore(endTime)) throw new IllegalArgumentException("开始时间必须早于结束时间");
    }
    public String encode() { return RowCodec.encode(Integer.toString(periodNumber), text(startTime), text(endTime)); }
}
```

Implement the other records with the exact fields in the Interfaces block, `RowCodec`, ISO date/time parsing, null-to-empty encoding only for nullable fields, and a fixed decoded-field count.

- [ ] **Step 6: Run all common tests**

Run: `mvn -pl vcampus-common test`

Expected: all common tests pass with zero failures and zero errors.

- [ ] **Step 7: Commit Task 1**

```powershell
git add vcampus-common/src/main/java/com/vcampus/common/model vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java vcampus-common/src/test/java/com/vcampus/common
git commit -m "feat(system): add shared management contracts"
```

### Task 2: Canonical Schema, Migration, and Non-Overwriting Seed

**Files:**
- Modify: `database/schema.sql`
- Modify: `database/seed.sql`
- Create: `database/migrations/018_system_management.sql`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/database/DatabaseScriptsTest.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/SystemManagementMigrationTest.java`

**Interfaces:**
- Produces: `class_period_times(period_number, start_time, end_time, updated_by_user_id, updated_at)`.
- Preserves: existing mutable `academic_terms` values on repeated seed application.

- [ ] **Step 1: Add failing database-script assertions**

```java
@Test void systemManagementSchemaAndSeedArePackagedSafely() throws Exception {
    String schema = script("database/schema.sql");
    String seed = script("database/seed.sql");
    assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS class_period_times"));
    assertTrue(seed.contains("INSERT INTO class_period_times"));
    assertFalse(seed.matches("(?s).*INSERT INTO academic_terms.*ON DUPLICATE KEY UPDATE\\s+term_name.*"));
}
```

- [ ] **Step 2: Run the script test and observe failure**

Run: `mvn -pl vcampus-server -am -Dtest=DatabaseScriptsTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: failure because the table is absent and the current term seed overwrites mutable fields.

- [ ] **Step 3: Add the canonical table and safe seed**

```sql
CREATE TABLE IF NOT EXISTS class_period_times (
    period_number TINYINT PRIMARY KEY,
    start_time TIME NULL,
    end_time TIME NULL,
    updated_by_user_id BIGINT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_period_time_updater FOREIGN KEY (updated_by_user_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_period_number CHECK (period_number BETWEEN 1 AND 12),
    CONSTRAINT chk_period_time_pair CHECK ((start_time IS NULL AND end_time IS NULL) OR (start_time IS NOT NULL AND end_time IS NOT NULL AND start_time < end_time))
);
```

Insert twelve rows using a recursive CTE or twelve explicit values with `ON DUPLICATE KEY UPDATE period_number = VALUES(period_number)`. Change the `academic_terms` duplicate clause to `id = LAST_INSERT_ID(id)` so repeated startup does not overwrite settings.

- [ ] **Step 4: Write a failing H2 migration test**

```java
@Test void migrationCreatesTwelveEmptyPeriodRowsWithoutChangingTerm() throws Exception {
    applyBaseSchema();
    execute("INSERT INTO academic_terms(id,academic_year,term_number,term_name,start_date,end_date,selection_start,selection_end,drop_deadline,status) VALUES (1,'2026-2027',1,'初始名称','2026-09-01','2027-01-15','2026-08-20 00:00:00','2026-09-10 23:59:59','2026-09-20 23:59:59','SELECTION')");
    updateTermName("管理员配置");
    applyMigration("018_system_management.sql");
    assertEquals(12, scalarInt("SELECT COUNT(*) FROM class_period_times"));
    assertEquals("管理员配置", scalarString("SELECT term_name FROM academic_terms WHERE id=1"));
}
```

- [ ] **Step 5: Add idempotent migration SQL and make the test pass**

```sql
CREATE TABLE IF NOT EXISTS class_period_times (...same columns and constraints as schema.sql...);
INSERT INTO class_period_times(period_number)
VALUES (1),(2),(3),(4),(5),(6),(7),(8),(9),(10),(11),(12)
ON DUPLICATE KEY UPDATE period_number = VALUES(period_number);
```

- [ ] **Step 6: Run database script and migration tests**

Run: `mvn -pl vcampus-server -am -Dtest=DatabaseScriptsTest,SystemManagementMigrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: both test classes pass.

- [ ] **Step 7: Commit Task 2**

```powershell
git add database/schema.sql database/seed.sql database/migrations/018_system_management.sql vcampus-server/src/test/java/com/vcampus/server/database
git commit -m "feat(system): add term and period persistence schema"
```

### Task 3: Configuration and Audit Query Repositories

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/SystemManagementStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/SystemManagementRepository.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/AuditQueryStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/AuditQueryRepository.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/SystemManagementRepositoryTest.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/AuditQueryRepositoryTest.java`

**Interfaces:**
- Produces: `SystemManagementStore.Snapshot configuration()`.
- Produces: `boolean updateTerm(SystemTermConfig term, long operatorUserId)`.
- Produces: `void updatePeriods(List<ClassPeriodTime> periods, long operatorUserId)`.
- Produces: `AuditQueryStore.AuditPage search(AuditQueryStore.AuditQuery query)`.

- [ ] **Step 1: Define ports and write failing repository tests**

```java
interface SystemManagementStore {
    record Snapshot(List<SystemTermConfig> terms, List<ClassPeriodTime> periods) {}
    Snapshot configuration() throws SQLException;
    boolean updateTerm(SystemTermConfig term, long operatorUserId) throws SQLException;
    void updatePeriods(List<ClassPeriodTime> periods, long operatorUserId) throws SQLException;
}

interface AuditQueryStore {
    record AuditQuery(String userKeyword, String actionPrefix, String resultCode,
                      LocalDate fromDate, LocalDate toDate, int page, int pageSize) {}
    record AuditPage(List<AuditLogEntry> rows, int page, int pageSize, int total) {}
    AuditPage search(AuditQuery query) throws SQLException;
}
```

Test that configuration returns all terms plus periods 1—12, an unknown term returns `false`, period update stores the operator, and a forced failure after row six rolls back every row.

- [ ] **Step 2: Run focused tests and observe missing implementations**

Run: `mvn -pl vcampus-server -am -Dtest=SystemManagementRepositoryTest,AuditQueryRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: test compilation fails because the ports and repositories do not exist.

- [ ] **Step 3: Implement configuration reads and writes**

```java
try (PreparedStatement statement = connection.prepareStatement("""
        UPDATE academic_terms
           SET term_name=?, start_date=?, end_date=?, selection_start=?, selection_end=?, drop_deadline=?, status=?
         WHERE id=? AND academic_year=? AND term_number=?
        """)) {
    // Bind typed values from SystemTermConfig; return executeUpdate() == 1.
}
```

Implement `updatePeriods` using one connection, `setAutoCommit(false)`, twelve parameterized `UPDATE` statements, commit on success, rollback on failure, and restore the original auto-commit state in `finally`.

- [ ] **Step 4: Implement dynamic but parameterized audit filtering**

```java
String order = " ORDER BY a.created_at DESC, a.id DESC LIMIT ? OFFSET ?";
String userClause = " AND (LOWER(u.username) LIKE ? OR LOWER(u.display_name) LIKE ?)";
String actionClause = " AND a.action_code LIKE ?";
String resultClause = " AND a.result_code = ?";
String fromClause = " AND a.created_at >= ?";
String toClause = " AND a.created_at < ?";
```

Build the `WHERE` clause only from these fixed fragments; bind `%lowercase-keyword%`, `prefix + "%"`, the result, `fromDate.atStartOfDay()`, and `toDate.plusDays(1).atStartOfDay()`. Run a matching `COUNT(*)`, then the page query.

- [ ] **Step 5: Run repository tests**

Run: `mvn -pl vcampus-server -am -Dtest=SystemManagementRepositoryTest,AuditQueryRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: all repository tests pass, including rollback, null actor, date boundary, stable ordering, and page count cases.

- [ ] **Step 6: Commit Task 3**

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/database/SystemManagementStore.java vcampus-server/src/main/java/com/vcampus/server/database/SystemManagementRepository.java vcampus-server/src/main/java/com/vcampus/server/database/AuditQueryStore.java vcampus-server/src/main/java/com/vcampus/server/database/AuditQueryRepository.java vcampus-server/src/test/java/com/vcampus/server/database/SystemManagementRepositoryTest.java vcampus-server/src/test/java/com/vcampus/server/database/AuditQueryRepositoryTest.java
git commit -m "feat(system): persist settings and query audit logs"
```

### Task 4: Permission-Enforcing System Management Service

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/service/SystemManagementService.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/service/SystemManagementServiceTest.java`

**Interfaces:**
- Consumes: the Task 1 models and Task 3 stores.
- Produces: `getConfig(RequestMessage)`, `updateTerm(RequestMessage,String)`, `updatePeriods(RequestMessage,String)`, and `searchAudit(RequestMessage)`.

- [ ] **Step 1: Write failing permission, validation, and audit tests**

```java
@Test void onlySuperAdministratorCanReadConfiguration() {
    assertTrue(service.getConfig(request(superAdminToken())).success());
    assertEquals("没有系统管理权限", service.getConfig(request(teacherToken())).message());
}

@Test void updatePeriodsRejectsOverlapBeforeCallingStore() {
    RequestMessage request = periodRequest(superAdminToken(),
            period(1,"08:00","09:00"), period(2,"08:45","09:30"));
    ResponseMessage response = service.updatePeriods(request, "127.0.0.1");
    assertFalse(response.success());
    assertEquals(0, configurationStore.updatePeriodCalls);
}

@Test void successfulTermUpdateRecordsExactAuditAction() {
    assertTrue(service.updateTerm(validTermRequest(superAdminToken()), "127.0.0.1").success());
    assertEquals(Actions.SYSTEM_CONFIG_UPDATE_TERM, audit.action);
    assertEquals("SUCCESS", audit.result);
}
```

- [ ] **Step 2: Run the service test and observe missing service failure**

Run: `mvn -pl vcampus-server -am -Dtest=SystemManagementServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: test compilation fails because `SystemManagementService` does not exist.

- [ ] **Step 3: Implement authentication and exact authorization once**

```java
private Optional<UserSession> administrator(RequestMessage request) {
    Optional<UserSession> session = sessions.find(request.parameters().get("sessionToken"));
    return session.filter(value -> SystemAccessPolicy.canManage(value.roles()));
}
```

Return “会话已失效，请重新登录” for no session and “没有系统管理权限” for a valid non-super-admin session; do not collapse these states into one message.

- [ ] **Step 4: Implement strict request parsing and responses**

```java
for (int index = 0; index < 12; index++) {
    ClassPeriodTime period = ClassPeriodTime.decode(required(data, "period." + index));
    if (period.periodNumber() != index + 1) throw new IllegalArgumentException("必须提交第 1—12 节完整配置");
    if (previousEnd != null && period.startTime() != null && period.startTime().isBefore(previousEnd))
        throw new IllegalArgumentException("相邻节次时间不能重叠");
    if (period.endTime() != null) previousEnd = period.endTime();
}
```

Encode config as `term.count`, `term.N`, `period.count`, `period.N`; encode audit pages as `row.count`, `row.N`, `page`, `pageSize`, `total`. Bound text to schema lengths, page to at least one, and dates to ISO values.

- [ ] **Step 5: Run service tests**

Run: `mvn -pl vcampus-server -am -Dtest=SystemManagementServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: all service tests pass with no store calls on denied or invalid requests.

- [ ] **Step 6: Commit Task 4**

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/service/SystemManagementService.java vcampus-server/src/test/java/com/vcampus/server/service/SystemManagementServiceTest.java
git commit -m "feat(system): enforce management permissions and validation"
```

### Task 5: Server Routing, Wiring, and Client Transport

**Files:**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterTest.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/system/SystemGateway.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/system/SocketSystemGateway.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/system/SocketSystemGatewayTest.java`

**Interfaces:**
- Produces: four routed server operations and four client methods.
- Produces: `SystemGateway.config()`, `updateTerm(SystemTermConfig)`, `updatePeriods(List<ClassPeriodTime>)`, and `search(SystemData.AuditQuery)`.

- [ ] **Step 1: Add failing router tests**

```java
@Test void routesSystemConfigurationToSystemService() {
    RequestRouter router = routerWith(systemService);
    ResponseMessage response = router.route(authorized(Actions.SYSTEM_CONFIG_GET, admin.token()), "127.0.0.1");
    assertTrue(response.success());
    assertEquals(1, systemStore.configurationCalls);
}
```

Also assert that a constructor with no system service returns “系统管理暂不可用” rather than throwing `NullPointerException`.

- [ ] **Step 2: Run router tests and observe failure**

Run: `mvn -pl vcampus-server -am -Dtest=RequestRouterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: test compilation fails because `RequestRouter` cannot accept or route the service.

- [ ] **Step 3: Extend the canonical router constructor and switch**

```java
case Actions.SYSTEM_CONFIG_GET -> systemService == null
        ? ResponseMessage.failure(request.requestId(), "系统管理暂不可用")
        : systemService.getConfig(request);
case Actions.SYSTEM_CONFIG_UPDATE_TERM -> systemService == null
        ? ResponseMessage.failure(request.requestId(), "系统管理暂不可用")
        : systemService.updateTerm(request, clientAddress);
case Actions.SYSTEM_CONFIG_UPDATE_PERIODS -> systemService == null
        ? ResponseMessage.failure(request.requestId(), "系统管理暂不可用")
        : systemService.updatePeriods(request, clientAddress);
case Actions.SYSTEM_AUDIT_SEARCH -> systemService == null
        ? ResponseMessage.failure(request.requestId(), "系统管理暂不可用")
        : systemService.searchAudit(request);
```

Keep existing overloads source-compatible by delegating with `null`; wire repositories and service in `VCampusServer`.

- [ ] **Step 4: Write failing Socket gateway tests**

```java
@Test void gatewaySendsTokenAndEncodedPeriodSnapshot() throws Exception {
    gateway.updatePeriods(periods());
    assertEquals(Actions.SYSTEM_CONFIG_UPDATE_PERIODS, server.request().action());
    assertEquals(token, server.request().parameters().get("sessionToken"));
    assertEquals("12", server.request().parameters().get("period.count"));
}
```

- [ ] **Step 5: Add typed methods to `VCampusClient` and implement gateway**

```java
public ResponseMessage systemConfig(String token) throws IOException {
    return sendAuthorized(Actions.SYSTEM_CONFIG_GET, token, Map.of());
}
public ResponseMessage updateSystemTerm(String token, SystemTermConfig term) throws IOException {
    return sendAuthorized(Actions.SYSTEM_CONFIG_UPDATE_TERM, token, Map.of("term", term.encode()));
}
```

Use a `LinkedHashMap` for the twelve period rows and audit filters; never send an empty optional filter as the literal string `null`.

- [ ] **Step 6: Run routing and transport tests**

Run: `mvn -pl vcampus-server -am -Dtest=RequestRouterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Run: `mvn -pl vcampus-client -am -Dtest=SocketSystemGatewayTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: both commands pass.

- [ ] **Step 7: Commit Task 5**

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterTest.java vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java vcampus-client/src/main/java/com/vcampus/client/fx/system vcampus-client/src/test/java/com/vcampus/client/fx/system/SocketSystemGatewayTest.java
git commit -m "feat(system): route management requests over sockets"
```

### Task 6: Client Data Parsing, Validation, and Controller

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/system/SystemData.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/system/SystemManagementController.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/system/SystemDataTest.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/system/SystemManagementControllerTest.java`

**Interfaces:**
- Produces: `SystemData.Config`, `SystemData.AuditQuery`, and `SystemData.AuditPage`.
- Produces: controller methods `activate()`, `deactivate()`, `close()`, `saveTerm(...)`, `savePeriods(...)`, and `searchAudit(...)`.

- [ ] **Step 1: Write failing strict parser and validation tests**

```java
@Test void malformedPeriodCountIsRejectedWithStableMessage() {
    ResponseMessage response = success(Map.of("term.count","0","period.count","11"));
    var error = assertThrows(IllegalArgumentException.class, () -> SystemData.config(response));
    assertEquals("服务器返回的系统配置格式不正确", error.getMessage());
}

@Test void auditQueryRejectsReverseDateRange() {
    assertThrows(IllegalArgumentException.class, () ->
            new SystemData.AuditQuery("", "", "", LocalDate.parse("2026-09-12"), LocalDate.parse("2026-09-11"), 1));
}
```

- [ ] **Step 2: Run parser tests and observe missing class failure**

Run: `mvn -pl vcampus-client -am -Dtest=SystemDataTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: test compilation fails because `SystemData` does not exist.

- [ ] **Step 3: Implement immutable parsing and form validation**

```java
public record Config(List<SystemTermConfig> terms, List<ClassPeriodTime> periods) {
    public Config {
        terms = List.copyOf(terms);
        periods = List.copyOf(periods);
        if (periods.size() != 12) throw new IllegalArgumentException("必须包含第 1—12 节配置");
    }
}
```

Wrap malformed server values in the stable Chinese messages used by tests; preserve service error messages in the gateway.

- [ ] **Step 4: Write failing controller lifecycle tests**

```java
@Test void staleAuditResponseCannotReplaceNewerFilter() {
    controller.activate();
    controller.searchAudit(query("old"));
    controller.searchAudit(query("new"));
    gateway.complete("new", page("new"));
    gateway.complete("old", page("old"));
    assertEquals(List.of("new"), controller.typedView().visibleActions());
}

@Test void failedSaveKeepsEditedValues() {
    view.editTerm(changedTerm());
    gateway.failNext("数据库暂不可用");
    controller.saveTerm(changedTerm());
    assertEquals(changedTerm().termName(), view.termName());
}
```

- [ ] **Step 5: Implement controller with independent request generations**

```java
private final RequestGate configGate = new RequestGate();
private final RequestGate auditGate = new RequestGate();

public void deactivate() {
    active = false;
    configGate.next();
    auditGate.next();
}
```

Use `CompletableFuture.supplyAsync(..., executor)` and `Platform.runLater(...)`. Disable only the operation being submitted; refresh after success; do not clear form fields on failure.

- [ ] **Step 6: Run data and controller tests**

Run: `mvn -pl vcampus-client -am -Dtest=SystemDataTest,SystemManagementControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: all tests pass.

- [ ] **Step 7: Commit Task 6**

```powershell
git add vcampus-client/src/main/java/com/vcampus/client/fx/system/SystemData.java vcampus-client/src/main/java/com/vcampus/client/fx/system/SystemManagementController.java vcampus-client/src/test/java/com/vcampus/client/fx/system/SystemDataTest.java vcampus-client/src/test/java/com/vcampus/client/fx/system/SystemManagementControllerTest.java
git commit -m "feat(system): add client data and async controller"
```

### Task 7: Native JavaFX System Management Workspace and Navigation

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/system/SystemManagementView.java`
- Create: `vcampus-client/src/main/resources/com/vcampus/client/fx/system/system.css`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/CampusApplication.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/system/SystemManagementViewTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/CampusFlowTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/ui/MainModuleRouteTest.java`

**Interfaces:**
- Consumes: Task 6 controller and data.
- Produces: a two-tab view with stable node ids used by tests.

- [ ] **Step 1: Write failing view and navigation tests**

```java
@Test void superAdministratorSeesSystemManagementButTeacherDoesNot() {
    assertTrue(CampusApplication.canOpenSystemManagement(Set.of(UserRole.SUPER_ADMIN)));
    assertFalse(CampusApplication.canOpenSystemManagement(Set.of(UserRole.TEACHER)));
}

@Test void workspaceHasConfigurationAndAuditTabs() {
    SystemManagementView view = new SystemManagementView(() -> {});
    assertNotNull(view.lookup("#system-config-tab"));
    assertNotNull(view.lookup("#system-audit-tab"));
    assertNull(view.lookup("#audit-delete"));
}
```

- [ ] **Step 2: Run UI tests and observe missing view/route failure**

Run: `mvn -pl vcampus-client -am -Dtest=SystemManagementViewTest,CampusFlowTest,MainModuleRouteTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: tests fail because the view and navigation entry are absent.

- [ ] **Step 3: Implement the two-tab view**

```java
Tab configuration = new Tab("基础配置", configurationPane());
configuration.setId("system-config-tab");
Tab audit = new Tab("审计日志", auditPane());
audit.setId("system-audit-tab");
tabs.getTabs().setAll(configuration, audit);
tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
```

Use `DatePicker`, validated time text fields with `HH:mm`, an `AcademicTermStatus` combo box, one save button per configuration area, and a read-only `TableView<AuditLogEntry>`. Add filter reset and paging but no mutation or export control.

- [ ] **Step 4: Add styling and responsive layout**

```css
.system-page { -fx-background-color: #f5f8fd; -fx-padding: 22; }
.system-card { -fx-background-color: white; -fx-background-radius: 12; -fx-padding: 18; }
.system-primary { -fx-background-color: #dbeafe; -fx-text-fill: #0f172a; -fx-font-weight: bold; }
.system-error { -fx-text-fill: #b91c1c; }
```

Make long forms scroll vertically and keep audit-table columns horizontally accessible at the 1000×720 minimum window.

- [ ] **Step 5: Wire sidebar, route, executor, and lifecycle**

```java
if (session.identity() == UserRole.SUPER_ADMIN)
    sidebar.getChildren().add(nav("system-management", "系统管理", Feather.SETTINGS));

case "system-management" -> showSystemManagement();

static boolean canOpenSystemManagement(Set<UserRole> roles) {
    return SystemAccessPolicy.canManage(roles);
}
```

Create the controller lazily with `new SocketSystemGateway(client, session.token())`; deactivate it when leaving the route and close it on logout/application shutdown. Reject the route locally when the role set is not exact super admin.

- [ ] **Step 6: Run UI/navigation tests**

Run: `mvn -pl vcampus-client -am -Dtest=SystemManagementViewTest,CampusFlowTest,MainModuleRouteTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: all selected tests pass on the JavaFX Application Thread.

- [ ] **Step 7: Commit Task 7**

```powershell
git add vcampus-client/src/main/java/com/vcampus/client/fx/system/SystemManagementView.java vcampus-client/src/main/resources/com/vcampus/client/fx/system/system.css vcampus-client/src/main/java/com/vcampus/client/fx/CampusApplication.java vcampus-client/src/test/java/com/vcampus/client/fx/system/SystemManagementViewTest.java vcampus-client/src/test/java/com/vcampus/client/fx/CampusFlowTest.java vcampus-client/src/test/java/com/vcampus/client/ui/MainModuleRouteTest.java
git commit -m "feat(system): add JavaFX management workspace"
```

### Task 8: Publish Configured Period Times to Academic Consumers

**Files:**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/AcademicRepository.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/AcademicService.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/AcademicData.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/ClassPeriodFormatter.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/CampusDashboardLoader.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/StudentScheduleView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/academic/TeacherWorkspaceView.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterAcademicTest.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/ui/ClassPeriodFormatterTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/ui/CampusDashboardLoaderTest.java`
- Modify: existing student/teacher schedule view tests.

**Interfaces:**
- Produces: academic reference response keys `period.count` and `period.N` for authenticated academic users.
- Produces: `ClassPeriodFormatter.format(int start, int end, List<ClassPeriodTime> periods)`.

- [ ] **Step 1: Write failing formatter and reference-data tests**

```java
@Test void formatsConfiguredRangeAndFallsBackWhenEndpointMissing() {
    List<ClassPeriodTime> periods = List.of(
            new ClassPeriodTime(1, LocalTime.parse("08:00"), LocalTime.parse("08:45")),
            new ClassPeriodTime(2, LocalTime.parse("08:50"), LocalTime.parse("09:35")));
    assertEquals("第 1—2 节 08:00—09:35", ClassPeriodFormatter.format(1, 2, periods));
    assertEquals("第 1—3 节", ClassPeriodFormatter.format(1, 3, periods));
}
```

Add a service assertion that `academic.referenceData` contains exactly twelve period rows and remains accessible to student, teacher, academic administrator, and super administrator sessions.

- [ ] **Step 2: Run focused tests and observe missing formatter/data failure**

Run: `mvn -pl vcampus-client -am -Dtest=ClassPeriodFormatterTest,CampusDashboardLoaderTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: test compilation or assertion failure because configured times are not available.

- [ ] **Step 3: Extend academic reference persistence and protocol data**

```java
try (PreparedStatement statement = connection.prepareStatement(
        "SELECT period_number,start_time,end_time FROM class_period_times ORDER BY period_number")) {
    // Map null TIME values to ClassPeriodTime(period, null, null).
}
```

Append `period.count` and encoded `period.N` values in `AcademicService.referenceData`; extend the client `AcademicData.ReferenceData` record and parser without changing existing term row encoding.

- [ ] **Step 4: Implement one shared formatter and use it in three views**

```java
public static String format(int start, int end, List<ClassPeriodTime> periods) {
    String base = "第 " + start + (start == end ? "" : "—" + end) + " 节";
    ClassPeriodTime first = find(periods, start);
    ClassPeriodTime last = find(periods, end);
    return first.configured() && last.configured()
            ? base + " " + first.startTime() + "—" + last.endTime()
            : base;
}
```

Pass reference periods into dashboard and schedule rendering; if the reference request fails or rows are incomplete, supply `List.of()` and preserve current behavior.

- [ ] **Step 5: Run academic and presentation tests**

Run: `mvn -pl vcampus-server -am -Dtest=RequestRouterAcademicTest -Dsurefire.failIfNoSpecifiedTests=false test`

Run: `mvn -pl vcampus-client -am -Dtest=ClassPeriodFormatterTest,CampusDashboardLoaderTest,StudentScheduleViewTest,TeacherWorkspaceNavigationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: all tests pass for configured and unconfigured period data.

- [ ] **Step 6: Commit Task 8**

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/database vcampus-server/src/main/java/com/vcampus/server/service/AcademicService.java vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterAcademicTest.java vcampus-client/src/main/java/com/vcampus/client/fx/academic vcampus-client/src/main/java/com/vcampus/client/ui/ClassPeriodFormatter.java vcampus-client/src/main/java/com/vcampus/client/ui/CampusDashboardLoader.java vcampus-client/src/test/java/com/vcampus/client
git commit -m "feat(system): show configured class times in schedules"
```

### Task 9: Documentation, Visual-Test Hygiene, and Full Verification

**Files:**
- Create: `docs/system-management.md`
- Modify: `README.md`
- Modify: `docs/README.md`
- Modify: `docs/requirements.md`
- Modify: `docs/milestones.md`
- Modify: `docs/testing.md`
- Modify: `docs/demo-guide.md`
- Modify: screenshot-generating tests that currently target `docs/design/**/screenshots`.

**Interfaces:**
- Produces: current user-facing scope, permission, audit-coverage, test, and demo documentation.
- Produces: ordinary Maven verification output only under ignored `target/` directories.

- [ ] **Step 1: Add a failing workspace-hygiene check**

Record the current tracked-file status in memory, run the screenshot test suite, and assert no new tracked files change. Use this repeatable command during development:

```powershell
$before = @(git diff --name-only)
mvn -pl vcampus-client -am -Dtest=AcademicScreenshotTest,BankVisualTest,IdentityScreenshotTest,NotificationScreenshotTest,ShopVisualTest -Dsurefire.failIfNoSpecifiedTests=false test
$after = @(git diff --name-only)
Compare-Object $before $after
```

Expected before the fix: `docs/design/javafx-bank/screenshots/uncertain.png` or another generated baseline can appear as an added difference.

- [ ] **Step 2: Redirect ordinary screenshot output to `target/`**

```java
private static final Path OUTPUT = Path.of("target", "visual-snapshots", "bank");
```

Apply the same policy to screenshot tests that write directly under `docs/design`. Preserve committed reference images; updating references remains a separate explicit manual action.

- [ ] **Step 3: Write current system-management documentation**

```markdown
## 审计覆盖

统一审计页读取 `audit_logs`，当前覆盖登录、账号管理、图书馆流通、论文维护和系统配置变更。
银行流水、商店库存流水和论坛审核日志仍在各业务模块中查询，不会混入统一审计结果。
```

Add an acceptance sequence: super administrator edits one period, student refreshes a schedule, super administrator filters `system.config.updatePeriods`, and a non-super-admin receives a permission denial.

- [ ] **Step 4: Update requirement and milestone status**

Add “系统管理” to the requirements status table as implemented only after its full test suite passes. Update the test baseline count from fresh output, not an estimate. Keep online classroom and examinations out of scope.

- [ ] **Step 5: Run the complete build**

Run: `mvn clean verify`

Expected: reactor `BUILD SUCCESS`; zero failures and zero errors. Record the exact test and skip counts from Surefire summaries.

- [ ] **Step 6: Verify repository hygiene and scope**

Run: `git diff --check`

Run: `git status --short`

Run: `rg -n "(考试|试卷)" README.md docs -g "*.md" -g "!docs/superpowers/**" -g "!docs/design/**"`

Expected: no whitespace errors; no build-generated tracked-file changes; only intended feature files plus pre-existing user changes remain; no current documentation advertises examinations.

- [ ] **Step 7: Commit Task 9**

```powershell
git add README.md docs/README.md docs/requirements.md docs/milestones.md docs/testing.md docs/demo-guide.md docs/system-management.md vcampus-client/src/test/java/com/vcampus/client/fx
git commit -m "docs(system): document management and audit workflows"
```

- [ ] **Step 8: Review final branch diff**

Run: `git diff HEAD~9 --stat`

Run: `git log -9 --oneline`

Confirm every acceptance criterion in the spec maps to a passing test or the documented final-MySQL manual acceptance sequence.
