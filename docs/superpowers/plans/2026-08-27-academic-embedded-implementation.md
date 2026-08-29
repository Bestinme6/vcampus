# Academic Embedded Workspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Embed the academic module in the main workspace and reuse it for teacher-schedule and student-grades notification deep links.

**Architecture:** Extract the frame content into `AcademicModulePanel`, keep a thin compatibility frame, and extend the existing lazy main-content route. Isolate title-to-index lookup in a tested navigation helper.

**Tech Stack:** Java 21, Swing, JUnit 5, Maven

**Spec:** `docs/superpowers/specs/2026-08-27-academic-embedded-design.md`

## Global Constraints

- Client-only change with no protocol, server, or database mutation.
- Preserve role-derived academic tabs and background request behavior.
- Preserve section-reference refresh on activation and modal operation dialogs.

---

### Task 1: Academic route and deep-link lookup

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/MainModuleRoute.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/ui/MainModuleRouteTest.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/AcademicModuleNavigation.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/ui/AcademicModuleNavigationTest.java`

- [x] Add failing assertions for `ACADEMIC -> academic`.
- [x] Add failing tests that locate “教师课表” and “我的成绩” by title and return `-1` when unavailable.
- [x] Implement the minimal mappings and lookup helper.
- [x] Re-run both targeted tests and observe success.

### Task 2: Extract the reusable academic panel

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/AcademicFrame.java`

**Interfaces:**
- Produces: `AcademicModulePanel(VCampusClient, String, Set<UserRole>, Runnable)`.
- Produces: `openTeacherSchedule()` and `openStudentGrades()`.

- [x] Move the heading, permission-derived tabs and tab-change listener into `AcademicModulePanel`.
- [x] Add “返回工作台” only when a callback is supplied.
- [x] Make `AcademicFrame` a thin wrapper around the panel.
- [x] Run academic navigation and refresh-policy tests.

### Task 3: Main-window and notification integration

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/MainFrame.java`
- Modify: `docs/requirements.md`
- Modify: `README.md`

- [x] Add lazy `showAcademic`, `showTeacherSchedule`, and `showStudentGrades` helpers.
- [x] Route the workspace card and both academic notification targets through those helpers.
- [x] Confirm `MainFrame` contains no `new AcademicFrame` call.
- [x] Update single-window documentation.
- [x] Run `mvn clean verify` with zero failures.
- [ ] Manually verify student, teacher, academic-administrator and notification flows.
