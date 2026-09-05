# Library JavaFX Upgrade Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development to implement the independent deliverables, with root-owned Maven builds and final review.

**Goal:** Deliver the approved JavaFX library with student return reminders and globally sorted catalog pages.

**Architecture:** JavaFX reader/admin views use existing VCampusClient Socket operations. Additive catalog metadata preserves legacy rows. Reservation writes and availability notifications live in server JDBC transactions.

**Tech Stack:** Java 21, JavaFX 21.0.11, MySQL 8, MessageCodec, Maven/JUnit/H2.

**Spec:** docs/superpowers/specs/2026-08-31-library-javafx-design.md

## Global constraints

No client JDBC, no object serialization, no unrelated forum changes. UI updates only on its UI thread; requests off UI threads. Existing feature branch is codex/javafx-campus-portal and contains prior accepted uncommitted work: retain all of it. Do not apply migrations or commit unrelated work. No extra approval needed for the approved implementation.

## Task 1: Library server contract and transactional reminders

Files: common LibrarySort/notification enums/Actions; LibraryCatalogStore/Repository, LibraryLoanRepository, LibraryService, reservation store/repository; router and client network additive methods; database schema and migration; focused server tests.

Interfaces: `searchLibraryCatalog(token,keyword,category,page,includeDisabled,LibrarySort sort)`; `createLibraryReservation(token,bookId)`; `cancelLibraryReservation(token,reservationId)`; `myLibraryReservations(token,status,page)` all return ResponseMessage. Sort enum values and row metadata exactly follow spec. Reservation response row has 7 fields; create returns reservationId.

- [x] Add domain regression tests first, including unavailable vs ON_LOAN, duplicate/ownership, normal vs damaged return, history count, sort ties and pagination.
- [x] Implement whitelist ordering and aggregate loan count without modifying legacy row encoding.
- [x] Implement book-scoped serialization for reservation/return races and atomic notification/status writes; preserve rollback on notification failure.
- [x] Add non-destructive migration and protocol APIs; root runs tests and reviews.

## Task 2: JavaFX administration

Files: fx/library/LibraryAdminView.java and supporting admin-only classes/tests. Constructor `(VCampusClient client,String token,Executor executor,Runnable onChanged)`; public `activate(String tab)`, `close()`. Extends BorderPane. Tabs: inventory, circulation, loans.

- [x] Implement styled catalog/copy tables, category and sort selectors, book/copy forms, read-only state labels and guarded mutations.
- [x] Preserve circulation preview and normal/damaged/lost returns; retain loan filtering/pagination.
- [x] Guard async completion and modal continuation on close; add UI interaction tests. No Maven by agents.

## Task 3: JavaFX reader pages and integration

Files: fx/library/LibraryController.java, LibraryReaderView.java, LibraryData.java, library.css; CampusApplication and legacy notification routing; UI/Socket tests.

Interfaces: public controller `(VCampusClient client,String token,Set<UserRole> roles,Executor executor,Runnable back,Runnable unreadRefresh)`; `view()`, `open(String route)`, `openBook(long bookId)`, `close()`.

- [x] Build approved blue/white search and book cards with typographic cover, full metadata, state badges and real borrow/reserve buttons.
- [x] Build loans/reservations tabs, cancellation and confirmation flows with failure/loading feedback.
- [x] Parse additive metadata strictly; old servers show unknown counts and no enabled reservation action.
- [x] Wire workspace library/library-loans and LIBRARY_CATALOG notification links into JavaFX; preserve --swing compatibility.
- [x] Test selected actions, permission-specific entry points, real protocol parsing and logout isolation; render snapshots.

## Task 4: Delivery

- [x] Review server and UI changes, address concrete findings.
- [x] Run root Maven clean verify; inspect generated screenshots and fix visible issues.
- [x] Update requirements, library docs, migration/start instructions and screenshot QA with explicit MySQL acceptance limits.

## Rulings

Use student-only reminders as requested, without adding queue/hold behavior. Existing uncommitted forum files are outside this task and must not be overwritten. Root owns shared UI routing and builds; server agent adds only library-related shared protocol lines. Administration and reader view work run independently against the documented contract.
