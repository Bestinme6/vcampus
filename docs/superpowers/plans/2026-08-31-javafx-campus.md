# JavaFX Campus Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or executing-plans for the bounded deliverables below. Asset production may run independently of source changes.

**Goal:** Ship the approved login and campus overview as a JavaFX desktop client while retaining access to every existing business module.

**Architecture:** JavaFX owns the window and session lifecycle. Existing Java Socket client feeds a pure-Java dashboard loader. An EDT-confined bridge lazily hosts Swing modules through SwingNode.

**Tech Stack:** Java 21, JavaFX 21.0.11, Maven, JUnit, existing MessageCodec and MySQL services.

**Spec:** docs/superpowers/specs/2026-08-31-javafx-campus-design.md

## Global Constraints

- No direct client MySQL access; no new serialization format.
- No fictional data or authentication bypass in production UI.
- Preserve original term.N row format; add separate date metadata keys.
- JavaFX updates on FX thread; Swing updates on EDT; requests on background threads.
- No schema change. Keep old Swing launch via --swing.

### Task 1: Native runtime and selected illustration

Files: vcampus-client/pom.xml; resources/com/vcampus/client/fx/seu-auditorium.png; docs/design/javafx-campus.

- [x] Generate a portrait illustration from the approved composite and official reference; no embedded UI text. Save asset with its source/prompt metadata.
- [x] Add controls and swing JavaFX dependencies at 21.0.11 and copy runtime dependencies at package time.

```xml
<dependency><groupId>org.openjfx</groupId><artifactId>javafx-controls</artifactId><version>21.0.11</version></dependency>
<dependency><groupId>org.openjfx</groupId><artifactId>javafx-swing</artifactId><version>21.0.11</version></dependency>
```

- [x] Build client to verify Java 21 compatibility and runtime resolution.

### Task 2: Real personal overview data

Files: ui/CampusDashboardData.java, ui/CampusDashboardLoader.java; AcademicRepository.java; AcademicService.java; associated tests.

Interface: `CampusDashboardLoader(VCampusClient client, String token, Set<UserRole> roles, String displayName)` and `CampusDashboardData load(LocalDate date)` (blocking; caller schedules it). Public data record contains `displayName`, `identity`, `profileLine`, `date`, `courses`, `tasks`, `loanCount`, `orderCount`, `balance`, `notices`. Nested Course has `title`, `location`, `periods`, `detail`; Task has `title`, `detail`, `route` (library-loans/shop-orders), `urgent`. Counts and balance are display strings, with unavailable explicitly shown.

- [x] Write date/role/data failure tests before implementation. For 2026-08-31 with term beginning 2026-08-31, include Monday week-1 course, exclude Tuesday and week-2 course. For 2026-09-07 do the reverse for week range. Before/after term show no matching course.
- [x] Check real socket requests with a loopback test server using MessageCodec, including forced-change/session failure and malformed rows. No business queries for super admin.
- [x] Implement additive date metadata (`term.N.startDate`, `term.N.endDate`) without modifying old rows. Test both row compatibility and supplied dates.
- [x] Use existing parser classes in ui package for loans, bank and orders. Aggregate PAID plus SHIPPED totals; waiting receipt only SHIPPED. Handle failures without fake zeros. Never expose another account's data.
- [x] Run focused client/server tests.

### Task 3: JavaFX shell and Swing bridge

Files: fx/CampusApplication.java, fx/FxLoginView.java, fx/FxCampusView.java, fx/FxStyles.java, fx/ClientSession.java, ui/LegacyModuleBridge.java, ClientMain.java; CSS resource.

Interfaces: `LegacyModuleBridge(VCampusClient, String, Set<UserRole>, Runnable onWorkspace, Runnable onUnreadRefresh, Runnable onModule)` owns an EDT-only JPanel. `content()` returns JPanel; `open(String route)` activates cached modules. Routes: accounts, notifications, personal-profile, student-status, academic, student-schedule, teacher-schedule, library, library-loans, shop, shop-orders, bank, forum.

- [x] Write session/login validation and UI interaction tests. Missing credentials must not send login; forced-change response must not open home. Validate port 1..65535 before request.
- [x] Implement login, connection settings, asynchronous error handling, required password change and logout. Clear password fields and session references.
- [x] Implement layout from selected target, date strip (real containing week), data loading/error/empty states and business deep links.
- [x] Implement workspace using existing WorkspaceCardResolver and admin-only account entry; bridge business modules on EDT and cache per session.
- [x] Guard callbacks against old session/date generations. Stop unread poller on close/logout. Verify all active controls work through UI tests.

### Task 4: Visual and regression verification

Files: test-only JavaFX snapshot/interaction harness, docs/design/javafx-campus/design-qa.md, docs/requirements.md, README.md, scripts/run-client.ps1.

- [x] Run root `mvn clean verify` with project local Maven settings, require exit 0.
- [x] Render JavaFX login and home at 1440x1024 plus minimum desktop size. Use generated asset and test-only deterministic fixtures, not production mock mode.
- [x] Inspect screenshots against approved image. Check overflowing text, date alignment, role navigation and blank/error states; fix visible issues.
- [x] Document first-phase JavaFX coverage, Swing compatibility modules, startup/classpath and same-version server date metadata. Do not claim all modules migrated or real MySQL acceptance completed without evidence.

## Plan self-review

Data task provides exactly the public record consumed by FX view. Asset task only writes its PNG. Root owns pom/runtime, bridge and FX files, so those changes do not race with data task. Server change adds map keys, not schema or row format. Class periods replace illustrative times because real data has no clock timetable.
