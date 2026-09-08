# Forum Reports Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow users to report posts and comments once and give forum administrators an audited, race-safe report queue.

**Architecture:** A focused `ForumReportRepository` owns report persistence and transactional resolution. `ForumReportService` exposes user submission/state and manager search/resolution through new `forum.report.*` actions; JavaFX adds report dialogs and a Reports administration tab.

**Tech Stack:** Java 21, JavaFX 21, Socket `MessageCodec`, JDBC, MySQL/H2, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-08-forum-images-reports-design.md`

## Global Constraints

- Posts and comments are reportable; self-report and inaccessible-content report are forbidden.
- Reasons are fixed enums; optional details are at most 300 characters.
- A user can report a given target once; reports never auto-hide content.
- Resolution is transactional and requires a 2–255 character administrator note.

---

### Task 1: Shared report contract and schema

**Files:**
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/ForumReportReason.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/ForumReportStatus.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/ForumReportResolution.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Modify: `database/schema.sql`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/ForumMigrationTest.java`

**Interfaces:**
- Produces: reasons `SPAM`, `HARASSMENT`, `MISLEADING`, `PRIVACY`, `INAPPROPRIATE`, `OTHER`.
- Produces: statuses `PENDING`, `DISMISSED`, `ACTIONED`; resolutions `DISMISS`, `HIDE`, `DELETE`.
- Produces actions: `forum.report.create`, `forum.report.state`, `forum.admin.report.search`, `forum.admin.report.resolve`.

- [ ] **Step 1: Write the failing migration assertion**

Assert `forum_reports` includes reporter, target type/ID, reason, detail, status, resolution fields, handler, timestamps, and a unique key over `(reporter_user_id, target_type, target_id)`.

- [ ] **Step 2: Run migration test and confirm the table is absent**

Run `ForumMigrationTest`; expected failure names `forum_reports`.

- [ ] **Step 3: Add enums, actions, and rerunnable MySQL schema**

Use `VARCHAR` checks for the enum values, foreign keys for reporter and handler, `UNIQUE (reporter_user_id, target_type, target_id)`, and indexes `(status, created_at, id)` and `(target_type, target_id, status)`.

- [ ] **Step 4: Run common compilation and migration tests**

Expected: schema and enum compilation pass.

- [ ] **Step 5: Commit the contract**

```powershell
git add vcampus-common/src/main/java/com/vcampus/common/model/ForumReportReason.java vcampus-common/src/main/java/com/vcampus/common/model/ForumReportStatus.java vcampus-common/src/main/java/com/vcampus/common/model/ForumReportResolution.java vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java database/schema.sql vcampus-server/src/test/java/com/vcampus/server/database/ForumMigrationTest.java
git commit -m "feat(forum): define report contract and schema"
```

### Task 2: Report repository and transactional resolution

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/ForumReportStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/ForumReportRepository.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/ForumReportRepositoryTest.java`

**Interfaces:**
- Produces: `create(long reporterId, ForumTargetType targetType, long targetId, ForumReportReason reason, String detail)`.
- Produces: `boolean reportedBy(long userId, ForumTargetType targetType, long targetId)`.
- Produces: `ReportPage search(ReportQuery query)`.
- Produces: `MutationResult resolve(long reportId, ForumReportResolution action, String note, long operatorId)`.

- [ ] **Step 1: Write failing repository tests**

Create post and comment fixtures and assert successful reports, duplicate rejection, self-report rejection, hidden/deleted rejection, filters, and report row snapshots. Add a two-thread resolution test using a latch; exactly one call must return `CHANGED`, and the other must return `CONFLICT` or `UNCHANGED`.

- [ ] **Step 2: Run `ForumReportRepositoryTest` and confirm missing types**

Expected: test compilation fails because the repository contract is absent.

- [ ] **Step 3: Implement repository queries and transactions**

Use a target query that joins the appropriate post/author for `POST` or comment/post/author for `COMMENT`. Insert reports only for `NORMAL` content whose author differs from reporter. Convert duplicate-key violations into an `IllegalArgumentException("该内容已举报")`.

For resolution, lock the report and target. `DISMISS` updates only the selected report. `HIDE` or `DELETE` updates the target, refreshes post comment stats when the target is a comment, marks all pending reports for the target `ACTIONED`, and inserts a moderation log before commit.

- [ ] **Step 4: Run repository tests**

Expected: all report persistence, permission, and race tests pass.

- [ ] **Step 5: Commit repository behavior**

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/database/ForumReportStore.java vcampus-server/src/main/java/com/vcampus/server/database/ForumReportRepository.java vcampus-server/src/test/java/com/vcampus/server/database/ForumReportRepositoryTest.java
git commit -m "feat(forum): persist and resolve reports"
```

### Task 3: Socket services and client gateway

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/service/ForumReportService.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumGateway.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/SocketForumGateway.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumData.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/service/ForumReportServiceTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterForumTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumGatewayTest.java`

**Interfaces:**
- Consumes the repository methods from Task 2.
- Produces gateway methods `report(...)`, `reported(...)`, `reports(...)`, and `resolveReport(...)`.

- [ ] **Step 1: Write failing service and gateway tests**

Assert ordinary sessions can create and query their report state, managers can search/resolve, ordinary users cannot use manager actions, invalid target/reason/detail inputs fail, and response rows round-trip through `RowCodec`.

- [ ] **Step 2: Run focused tests and confirm actions are unrouted**

Expected: action constants exist but router and gateway methods are missing.

- [ ] **Step 3: Implement service, routing, client requests, and data parsing**

Return stable keys: `reported`, page metadata, and report rows containing report ID, target identity, snapshot, reporter, reason, detail, status, resolution, handler, created and handled timestamps. Keep all authorization in `ForumReportService`, not the client.

- [ ] **Step 4: Run service, router, codec, and gateway tests**

Expected: zero failures and no protocol field-count mismatch.

- [ ] **Step 5: Commit network integration**

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/service/ForumReportService.java vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumGateway.java vcampus-client/src/main/java/com/vcampus/client/fx/forum/SocketForumGateway.java vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumData.java vcampus-server/src/test/java/com/vcampus/server/service/ForumReportServiceTest.java vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterForumTest.java vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumGatewayTest.java
git commit -m "feat(forum): expose report protocol"
```

### Task 4: JavaFX report and moderation flows

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumReportDialog.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumReportsView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumDetailView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumAdminView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumController.java`
- Modify: `vcampus-client/src/main/resources/com/vcampus/client/fx/forum/forum.css`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumUiTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumControllerTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumVisualTest.java`

**Interfaces:**
- Consumes gateway report methods from Task 3.
- Produces Report actions for a post and each eligible comment, plus an administrator Reports tab.

- [ ] **Step 1: Write failing JavaFX tests**

Assert self-authored targets omit Report, eligible targets open a reason dialog, detail validation enforces 300 characters, successful submission displays `已举报`, and the admin queue enables resolution only for `PENDING`. Assert resolving refreshes both report and content tables.

- [ ] **Step 2: Run focused JavaFX tests and confirm controls are absent**

Expected: report selectors and admin tab do not exist.

- [ ] **Step 3: Implement the dialog, queue, controller orchestration, and styles**

Use a combo box with the six Chinese reason labels and an optional text area. Give stable IDs `forum-report-post`, `forum-report-comment-<id>`, `forum-report-submit`, `forum-admin-reports`, and `forum-report-resolve-*`. Run gateway calls via `ForumAsync`; render dialogs and update nodes on the JavaFX thread.

- [ ] **Step 4: Run UI tests and visual capture**

Expected: interaction tests pass and report dialog/queue remain readable at 780, 1060, and 1220 pixel content widths.

- [ ] **Step 5: Commit the UI**

```powershell
git add vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumReportDialog.java vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumReportsView.java vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumDetailView.java vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumAdminView.java vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumController.java vcampus-client/src/main/resources/com/vcampus/client/fx/forum/forum.css vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumUiTest.java vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumControllerTest.java vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumVisualTest.java
git commit -m "feat(forum): add report submission and review UI"
```

