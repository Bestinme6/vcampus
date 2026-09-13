# Critical Operation Audit Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record stable audit summaries for critical bank, shop, academic, and forum mutations, and replace the audit detail text area with a two-row horizontal field layout that never needs an internal scrollbar.

**Architecture:** Keep audit decisions in each business service, inject the existing server-owned `AuditStore`, and preserve domain repositories as the source of truth. Each audited response path records the authenticated actor, the request's existing action code, and one stable result code; read-only methods remain untouched. The JavaFX client renders the existing `AuditLogEntry` data in eight labels inside a responsive `GridPane`, so the wire protocol and database schema do not change.

**Tech Stack:** Java 21, Maven, JUnit 5, H2 test databases, JavaFX, CSS, existing Socket/`MessageCodec` protocol, MySQL-backed `AuditRepository` in production.

**Spec:** `docs/superpowers/specs/2026-09-13-critical-operation-audit-design.md`

## Global Constraints

- Work only in the `codex/audit-critical-operations` isolated worktree; do not modify or overwrite the user's uncommitted login-view changes in the primary checkout.
- Follow strict red-green-refactor: add the named test, run it and observe the expected assertion failure, then write the smallest production change and rerun it.
- Keep existing two-argument/four-argument service constructors as compatibility overloads for tests and callers. They delegate to the new audit-aware constructor with a no-op `AuditStore`; production must always pass the real shared `AuditRepository`.
- Audit only the action constants listed in the approved spec. Never audit searches, getters, previews, refreshes, cart edits, likes, bookmarks, report creation, or notification read state.
- Do not change `Actions`, `MessageCodec`, `audit_logs`, or any public request/response payload.
- An invalid/expired session has no reliable business actor and creates no new business audit row. A valid actor who lacks permission records `DENIED`.
- `AuditRepository.record` is already best-effort. Do not allow audit failure to alter a completed business response or transaction.
- Use these result mappings consistently: successful/idempotent completion → `SUCCESS`; valid session without permission → `DENIED`; request parsing/validation → `INVALID`; explicit missing-target result → `NOT_FOUND`; state, duplicate, balance, stock, capacity, or revision conflict → `CONFLICT`; `SQLException` → `DATABASE_ERROR`.

---

## Task 1: Add a reusable audit recorder and cover bank mutations

**Files:**

- Create: `vcampus-server/src/test/java/com/vcampus/server/support/RecordingAuditStore.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/BankServiceTest.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/BankService.java`

- [ ] **Step 1: Create the test-only recorder**

```java
package com.vcampus.server.support;

import com.vcampus.server.database.AuditStore;
import java.util.ArrayList;
import java.util.List;

public final class RecordingAuditStore implements AuditStore {
    public record Entry(Long userId, String action, String result, String clientAddress) { }

    private final List<Entry> entries = new ArrayList<>();

    @Override
    public void record(Long userId, String action, String result, String clientAddress) {
        entries.add(new Entry(userId, action, result, clientAddress));
    }

    public List<Entry> entries() { return List.copyOf(entries); }
    public Entry last() { return entries.getLast(); }
}
```

- [ ] **Step 2: Write failing bank audit tests**

Change `BankServiceTest.setUp()` to keep a `RecordingAuditStore audit` field and construct `new BankService(store, sessions, audit)`. Make the request helper accept a real action code instead of the current hard-coded `bank.test` value. Add assertions for:

```java
assertEquals(new RecordingAuditStore.Entry(
        11L, Actions.BANK_TRANSFER_CREATE, "SUCCESS", null), audit.last());
```

Cover these cases in separate tests:

- successful transfer records `SUCCESS`;
- malformed amount/operation ID records `INVALID`;
- `BankRuleException` from transfer records `CONFLICT`;
- a valid non-bank-admin calling `bank.admin.topup` records `DENIED`;
- `SQLException` from top-up records `DATABASE_ERROR`;
- `searchLedger` leaves `audit.entries()` empty.

- [ ] **Step 3: Run the bank test and confirm the red state**

Run:

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -Dtest=BankServiceTest test
```

Expected: compilation fails because the three-argument `BankService` constructor does not exist, or the new audit assertions fail because no rows are recorded.

- [ ] **Step 4: Inject and record bank audit outcomes**

Add the dependency without breaking current callers:

```java
private static final AuditStore NO_AUDIT = (userId, action, result, address) -> { };
private final AuditStore audit;

public BankService(BankStore bank, SessionManager sessions) {
    this(bank, sessions, NO_AUDIT);
}

public BankService(BankStore bank, SessionManager sessions, AuditStore audit) {
    this.bank = Objects.requireNonNull(bank);
    this.sessions = Objects.requireNonNull(sessions);
    this.audit = Objects.requireNonNull(audit);
}

private ResponseMessage audited(UserSession actor, RequestMessage request,
                                String result, ResponseMessage response) {
    audit.record(actor.userId(), request.action(), result, null);
    return response;
}
```

Use `audited(...)` on every exit from `transfer`, `topUp`, `freeze`, `unfreeze`, and their shared `changeStatus` path. Resolve the session once so permission failures can distinguish expired sessions from a valid unauthorized actor. Record only the four bank action constants in the spec. Split `IllegalArgumentException`, `BankRuleException`, and `SQLException` catches so they map to `INVALID`, `CONFLICT`, and `DATABASE_ERROR` respectively.

- [ ] **Step 5: Rerun the bank test and regression slice**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -Dtest=BankServiceTest,RequestRouterBankTest test
```

Expected: both test classes pass and read-only requests still produce no audit entry.

- [ ] **Step 6: Commit the bank slice**

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/service/BankService.java vcampus-server/src/test/java/com/vcampus/server/service/BankServiceTest.java vcampus-server/src/test/java/com/vcampus/server/support/RecordingAuditStore.java
git commit -m "feat: audit critical bank operations"
```

---

## Task 2: Cover shop order and administration mutations

**Files:**

- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/ShopServiceTest.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/ShopService.java`

- [ ] **Step 1: Write failing shop audit tests**

Add a `RecordingAuditStore` to the fixture and construct `new ShopService(store, sessions, audit)`. Add tests for:

- checkout success → `SUCCESS` with `Actions.SHOP_CHECKOUT` and student actor;
- buy-now validation failure → `INVALID`;
- checkout `ShopRuleException` or `BankRuleException` → `CONFLICT`;
- student calling product save → `DENIED`;
- repository `SQLException` during shipment → `DATABASE_ERROR`;
- `searchProducts`, `getProduct`, `cart`, `searchOrders`, and admin order search do not append entries.

Also add a parameterized or table-driven success test asserting the exact action is retained for:

```java
Set.of(Actions.SHOP_CHECKOUT, Actions.SHOP_BUY_NOW,
       Actions.SHOP_ORDER_CANCEL, Actions.SHOP_ORDER_CONFIRM,
       Actions.SHOP_ADMIN_PRODUCT_SAVE, Actions.SHOP_ADMIN_PRODUCT_SET_ENABLED,
       Actions.SHOP_ADMIN_INVENTORY_ADJUST, Actions.SHOP_ADMIN_ORDER_SHIP)
```

- [ ] **Step 2: Run the shop test and confirm the red state**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -Dtest=ShopServiceTest test
```

Expected: the new constructor or audit expectations fail.

- [ ] **Step 3: Add the audit-aware constructor and wrapper**

Use the same constructor shape and `audited(UserSession, RequestMessage, String, ResponseMessage)` helper as Task 1. Extend the existing `Work` helper so the authenticated `UserSession` is still available to the response/error mapping; do not infer actors from request parameters.

Audit exactly these methods: `checkout`, `buyNow`, `cancelOrder`, `confirmOrder`, `saveProduct`, `setProductEnabled`, `adjustInventory`, and `shipOrder`. For their existing exception paths:

- request parsing and `IllegalArgumentException` → `INVALID`;
- `ShopRuleException` and `BankRuleException` → `CONFLICT`;
- explicit missing order/product results → `NOT_FOUND`;
- valid-role failure → `DENIED`;
- `SQLException` → `DATABASE_ERROR`.

Do not add auditing to cart mutations or image upload/commit operations because they are outside the approved action list.

- [ ] **Step 4: Rerun shop tests**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -Dtest=ShopServiceTest,RequestRouterShopTest test
```

Expected: all shop and router tests pass.

- [ ] **Step 5: Commit the shop slice**

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/service/ShopService.java vcampus-server/src/test/java/com/vcampus/server/service/ShopServiceTest.java
git commit -m "feat: audit critical shop operations"
```

---

## Task 3: Cover forum deletion, moderation, and report resolution

**Files:**

- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/ForumServiceTest.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/ForumReportServiceTest.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/ForumService.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/ForumReportService.java`

- [ ] **Step 1: Write failing forum audit tests**

Inject one `RecordingAuditStore` into each fixture. In `ForumServiceTest`, test exact action/result pairs for successful post deletion, unauthorized comment deletion, invalid section save, missing moderation target, conflicting moderation, and database failure. Assert that feed/search/get/create/like/bookmark paths leave the recorder empty.

In `ForumReportServiceTest`, assert:

```java
assertEquals("SUCCESS", audit.last().result());       // CHANGED
assertEquals("CONFLICT", audit.last().result());      // UNCHANGED or CONFLICT
assertEquals("NOT_FOUND", audit.last().result());     // NOT_FOUND
assertEquals("DENIED", audit.last().result());        // FORBIDDEN or valid non-manager
assertEquals("INVALID", audit.last().result());       // invalid reportId/resolution
assertEquals("DATABASE_ERROR", audit.last().result());// SQLException
```

Verify report creation, report state, report search, and moderation-log search do not audit.

- [ ] **Step 2: Run the tests and confirm the red state**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -Dtest=ForumServiceTest,ForumReportServiceTest test
```

Expected: audit-aware constructor compilation or audit assertions fail.

- [ ] **Step 3: Add audit dependencies while preserving all ForumService overloads**

Keep these compatibility forms working:

```java
public ForumService(ForumStore forum, SessionManager sessions)
public ForumService(ForumStore forum, SessionManager sessions,
                    ForumCommunityStore communityStore)
public ForumService(ForumStore forum, SessionManager sessions,
                    ForumCommunityStore communityStore, ForumImageService imageService)
```

Add an audit-aware full constructor whose last argument is `AuditStore`, and delegate all existing overloads through `NO_AUDIT`. Add `ForumReportService(ForumReportStore, SessionManager, AuditStore)` with its current constructor delegating to `NO_AUDIT`.

Audit exactly:

- `deletePost`, `deleteComment`;
- `saveSection`, `setSectionEnabled`;
- `moderatePost`, `moderateComment`;
- `ForumReportService.resolve`.

Map `ForumStore.MutationResult` explicitly: `CHANGED` → `SUCCESS`, `NOT_FOUND` → `NOT_FOUND`, `FORBIDDEN` → `DENIED`, and `UNCHANGED`/`CONFLICT` → `CONFLICT`. Parsing errors map to `INVALID`; SQL failures map to `DATABASE_ERROR`.

- [ ] **Step 4: Rerun forum and router tests**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -Dtest=ForumServiceTest,ForumReportServiceTest,RequestRouterForumTest test
```

Expected: all selected tests pass with no audits from read-only methods.

- [ ] **Step 5: Commit the forum slice**

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/service/ForumService.java vcampus-server/src/main/java/com/vcampus/server/service/ForumReportService.java vcampus-server/src/test/java/com/vcampus/server/service/ForumServiceTest.java vcampus-server/src/test/java/com/vcampus/server/service/ForumReportServiceTest.java
git commit -m "feat: audit critical forum operations"
```

---

## Task 4: Cover academic administration, enrollment, schedule, and grade mutations

**Files:**

- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/AcademicCurriculumServiceTest.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/AcademicScheduleServiceTest.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/AcademicAvailabilityContractTest.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/service/AcademicAuditCoverageTest.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/AcademicService.java`

- [ ] **Step 1: Add failing representative outcome tests**

Update existing fixtures to pass a `RecordingAuditStore`. Assert:

- curriculum creation success → `SUCCESS`;
- student curriculum creation → `DENIED`;
- duplicate curriculum version → `CONFLICT`;
- malformed curriculum/course fields → `INVALID`;
- schedule save/publish/reset success → `SUCCESS` with their exact request actions;
- stale schedule revision → `CONFLICT`;
- repository SQL failure → `DATABASE_ERROR`;
- course/curriculum/schedule getters and searches do not audit.

In `AcademicAuditCoverageTest`, use the existing H2 schema builders from `AcademicCurriculumServiceTest` and `ScheduleRevisionRepositoryTest` to exercise the remaining mutation families. Assert one exact entry per invoked request and cover:

```java
Set.of(
    Actions.ACADEMIC_CURRICULUM_CREATE,
    Actions.ACADEMIC_CURRICULUM_UPDATE,
    Actions.ACADEMIC_CURRICULUM_COPY,
    Actions.ACADEMIC_CURRICULUM_PUBLISH,
    Actions.ACADEMIC_CURRICULUM_ARCHIVE,
    Actions.ACADEMIC_CURRICULUM_COURSE_ADD,
    Actions.ACADEMIC_CURRICULUM_COURSE_UPDATE,
    Actions.ACADEMIC_CURRICULUM_COURSE_REMOVE,
    Actions.ACADEMIC_COURSE_CREATE,
    Actions.ACADEMIC_COURSE_UPDATE,
    Actions.ACADEMIC_SECTION_CREATE,
    Actions.ACADEMIC_SECTION_SET_STATUS,
    Actions.ACADEMIC_SECTION_DELETE,
    Actions.ACADEMIC_SECTION_TARGETS_SAVE,
    Actions.ACADEMIC_SCHEDULE_DRAFT_SAVE,
    Actions.ACADEMIC_SCHEDULE_DRAFT_RESET,
    Actions.ACADEMIC_SCHEDULE_PUBLISH,
    Actions.ACADEMIC_ENROLLMENT_ENROLL,
    Actions.ACADEMIC_ENROLLMENT_DROP,
    Actions.ACADEMIC_ENROLLMENT_SWITCH_SECTION,
    Actions.ACADEMIC_GRADE_SAVE,
    Actions.ACADEMIC_GRADE_PUBLISH)
```

The coverage test may invoke a mix of successful and deliberately invalid requests, but every action above must be observed once and no read action may appear.

- [ ] **Step 2: Run academic tests and confirm the red state**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -Dtest=AcademicCurriculumServiceTest,AcademicScheduleServiceTest,AcademicAvailabilityContractTest,AcademicAuditCoverageTest test
```

Expected: the five-argument constructor or audit assertions fail.

- [ ] **Step 3: Add the audit-aware AcademicService constructor**

```java
public AcademicService(AcademicRepository academic, CurriculumRepository curricula,
        ScheduleRevisionRepository schedules, SessionManager sessions) {
    this(academic, curricula, schedules, sessions, NO_AUDIT);
}

public AcademicService(AcademicRepository academic, CurriculumRepository curricula,
        ScheduleRevisionRepository schedules, SessionManager sessions, AuditStore audit) {
    this.academic = Objects.requireNonNull(academic);
    this.curricula = Objects.requireNonNull(curricula);
    this.schedules = Objects.requireNonNull(schedules);
    this.sessions = Objects.requireNonNull(sessions);
    this.audit = Objects.requireNonNull(audit);
}
```

Add the same response-returning `audited(...)` helper used in the smaller services. Apply it only to the 22 methods represented by the action set above, including shared helpers such as `mutateCurriculum` and `mutateCurriculumCourse`.

For authorization, resolve `sessions.find(token)` before applying the academic role filter. Expired sessions return the existing response without audit; valid unauthorized actors return the same message plus `DENIED`. Split combined catches where needed:

- `IllegalArgumentException` → `INVALID`;
- `SQLIntegrityConstraintViolationException`, `AcademicRuleException`, `ScheduleConflictException`, and optimistic-revision/state failures → `CONFLICT`;
- explicit false/missing repository result → `NOT_FOUND`;
- other `SQLException` → `DATABASE_ERROR`.

Preserve every existing Chinese response message and response data map.

- [ ] **Step 4: Rerun all academic service and router tests**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -Dtest=AcademicCurriculumServiceTest,AcademicScheduleServiceTest,AcademicAvailabilityContractTest,AcademicAuditCoverageTest,RequestRouterAcademicTest test
```

Expected: all selected tests pass and each request produces at most one audit row.

- [ ] **Step 5: Commit the academic slice**

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/service/AcademicService.java vcampus-server/src/test/java/com/vcampus/server/service/AcademicCurriculumServiceTest.java vcampus-server/src/test/java/com/vcampus/server/service/AcademicScheduleServiceTest.java vcampus-server/src/test/java/com/vcampus/server/service/AcademicAvailabilityContractTest.java vcampus-server/src/test/java/com/vcampus/server/service/AcademicAuditCoverageTest.java
git commit -m "feat: audit critical academic operations"
```

---

## Task 5: Wire one production AuditRepository into every audited service

**Files:**

- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterBankTest.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterShopTest.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterForumTest.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterAcademicTest.java`

- [ ] **Step 1: Add routing-level audit assertions**

In each existing router fixture, construct its service with one `RecordingAuditStore`, dispatch one critical action through `RequestRouter`, and assert the recorder sees the same action and actor as the routed request while the response remains unchanged. The four tests must cover bank transfer, shop checkout, forum moderation or deletion, and academic enrollment or schedule mutation.

- [ ] **Step 2: Run router tests and confirm current fixtures expose the missing injection**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -Dtest=RequestRouterBankTest,RequestRouterShopTest,RequestRouterForumTest,RequestRouterAcademicTest test
```

Expected before updating the fixtures/wiring: constructor or audit assertions fail; after the test fixture injection they pass without router changes.

- [ ] **Step 3: Pass the single production repository to all services**

Change only constructor arguments in `VCampusServer`:

```java
new AcademicService(..., sessionManager, auditRepository)
new ForumService(..., sessionManager, communityStore, forumImageService, auditRepository)
new ForumReportService(..., sessionManager, auditRepository)
new BankService(bankRepository, sessionManager, auditRepository)
new ShopService(shopRepository, sessionManager, auditRepository)
```

Retain the already shared `auditRepository` used by authentication, accounts, library, and system management. Do not create per-service repositories.

- [ ] **Step 4: Run the server module**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server test
```

Expected: server module passes; router responses and protocol actions are unchanged.

- [ ] **Step 5: Commit production wiring**

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterBankTest.java vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterShopTest.java vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterForumTest.java vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterAcademicTest.java
git commit -m "feat: wire shared audit store to business services"
```

---

## Task 6: Replace the scrolling audit detail text area with a horizontal grid

**Files:**

- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/system/SystemManagementViewTest.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/system/SystemManagementView.java`
- Modify: `vcampus-client/src/main/resources/com/vcampus/client/fx/system/system.css`

- [ ] **Step 1: Replace the old UI test with failing layout assertions**

Remove the `TextArea` cast/import. After selecting the existing sample row, assert:

```java
Parent details = (Parent) view.lookup("#audit-details");
assertInstanceOf(GridPane.class, details);
assertNull(details.lookup(".scroll-pane"));
assertEquals("17", label(view, "#audit-detail-id").getText());
assertEquals("9", label(view, "#audit-detail-user-id").getText());
assertEquals("admin", label(view, "#audit-detail-username").getText());
assertEquals("系统管理员", label(view, "#audit-detail-display-name").getText());
assertEquals("system.config.updatePeriods", label(view, "#audit-detail-action").getText());
assertEquals("SUCCESS", label(view, "#audit-detail-result").getText());
assertEquals("127.0.0.1", label(view, "#audit-detail-client-address").getText());
assertEquals("2026-09-12T08:00:00Z", label(view, "#audit-detail-created-at").getText());

table.getSelectionModel().clearSelection();
assertEquals("—", label(view, "#audit-detail-action").getText());
```

Add `private static Label label(Parent root, String selector)` for the casts. Retain the table resize-policy assertion.

- [ ] **Step 2: Run the JavaFX test and confirm the red state**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-client -Dtest=SystemManagementViewTest test
```

Expected: `#audit-details` is still a `TextArea`, so the `GridPane` assertion fails.

- [ ] **Step 3: Build the two-row, four-column detail panel**

Replace the `TextArea` field with eight persistent value labels and a `GridPane`:

```java
private final Label auditDetailId = detailValue("audit-detail-id");
private final Label auditDetailUserId = detailValue("audit-detail-user-id");
private final Label auditDetailUsername = detailValue("audit-detail-username");
private final Label auditDetailDisplayName = detailValue("audit-detail-display-name");
private final Label auditDetailAction = detailValue("audit-detail-action");
private final Label auditDetailResult = detailValue("audit-detail-result");
private final Label auditDetailClientAddress = detailValue("audit-detail-client-address");
private final Label auditDetailCreatedAt = detailValue("audit-detail-created-at");
private final GridPane auditDetails = new GridPane();
```

Create each field as a small `VBox` containing a caption label and value label. Put ID/user ID/username/display name in row 0 and action/result/address/time in row 1. Add four `ColumnConstraints` with `percentWidth = 25`, `hgrow = Priority.ALWAYS`, and a sensible minimum width. Set value labels to wrap and fill width. Set a tooltip on the action label and update it with the selected action.

`showAuditDetails(null)` must set all eight values to `—`; a row must set each raw value, using `—` for null/blank fields. The root has ID `audit-details` and contains no `TextArea` or `ScrollPane`.

- [ ] **Step 4: Style the grid**

Add focused CSS rules:

```css
.audit-detail-grid { -fx-hgap: 16; -fx-vgap: 12; }
.audit-detail-item { -fx-spacing: 4; -fx-min-width: 0; }
.audit-detail-caption { -fx-font-size: 11px; -fx-text-fill: -vc-text-muted; }
.audit-detail-value { -fx-font-size: 13px; -fx-text-fill: -vc-text; }
```

Use the actual text color variables already declared in `system.css`; do not introduce an undefined CSS variable.

- [ ] **Step 5: Run focused and complete client tests**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-client -Dtest=SystemManagementViewTest test
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-client test
```

Expected: both commands pass, the selected row populates all fields, and clearing selection restores `—`.

- [ ] **Step 6: Commit the client slice**

```powershell
git add vcampus-client/src/main/java/com/vcampus/client/fx/system/SystemManagementView.java vcampus-client/src/main/resources/com/vcampus/client/fx/system/system.css vcampus-client/src/test/java/com/vcampus/client/fx/system/SystemManagementViewTest.java
git commit -m "feat: show audit details in horizontal grid"
```

---

## Task 7: Update documentation and complete verification

**Files:**

- Modify: `docs/system-management.md`
- Modify: `docs/requirements.md`
- Modify: `docs/testing.md`
- Modify: `docs/milestones.md`

- [ ] **Step 1: Update user-facing system management documentation**

Document the four new audit domains and their critical-operation-only boundary. Explain that bank ledgers, order detail, academic records, and forum moderation logs remain the detailed business sources. Describe the read-only two-row detail grid and the `—` empty state.

- [ ] **Step 2: Update delivery and test status**

Mark cross-module critical-operation audit coverage complete in `requirements.md` and `milestones.md`. In `testing.md`, add the new service outcome cases, no-audit read cases, routing injection coverage, and JavaFX no-scroll layout assertions. After the final Maven run, replace the documented test counts with the exact Surefire totals from that run.

- [ ] **Step 3: Check the audit whitelist mechanically**

```powershell
rg -n "audit\.record|audited\(" vcampus-server/src/main/java/com/vcampus/server/service/BankService.java vcampus-server/src/main/java/com/vcampus/server/service/ShopService.java vcampus-server/src/main/java/com/vcampus/server/service/AcademicService.java vcampus-server/src/main/java/com/vcampus/server/service/ForumService.java vcampus-server/src/main/java/com/vcampus/server/service/ForumReportService.java
rg -n "auditDetails|TextArea" vcampus-client/src/main/java/com/vcampus/client/fx/system/SystemManagementView.java
```

Expected: only approved mutation methods call the helper; `SystemManagementView` has no audit-detail `TextArea` reference.

- [ ] **Step 4: Run the complete project verification**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" clean verify
```

Expected: reactor `BUILD SUCCESS`, with zero failures and zero errors across common, server, and client modules.

- [ ] **Step 5: Inspect the final diff and workspace state**

```powershell
git diff --check
git status --short
git log --oneline --decorate -8
```

Expected: `git diff --check` is silent; only the four documentation files are uncommitted at this point; the feature commits are present on `codex/audit-critical-operations`.

- [ ] **Step 6: Commit documentation**

```powershell
git add docs/system-management.md docs/requirements.md docs/testing.md docs/milestones.md
git commit -m "docs: describe critical operation audit coverage"
```

- [ ] **Step 7: Perform the completion check**

Run `git status --short` once more. Expected: no output. Report the exact test totals and note that the isolated branch is ready for review/integration; do not merge it into the primary checkout until the user's separate uncommitted login-view changes have been preserved.
