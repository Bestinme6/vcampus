# Student and Teacher Embedded Workspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Embed student status and teacher profile pages in the main workspace while preserving permissions, dialogs, and notification navigation.

**Architecture:** Extract the content and behavior of the two existing frame classes into reusable panels, retain thin frame wrappers, and extend the tested main-module route. Use separate lazy content keys for student self-service, student management, and teacher profile.

**Tech Stack:** Java 21, Swing, JUnit 5, Maven

**Spec:** `docs/superpowers/specs/2026-08-27-student-teacher-embedded-design.md`

## Global Constraints

- Client-only change; preserve the three-tier architecture and binary protocol.
- Network work stays off EDT; Swing updates stay on EDT.
- Existing role-derived student self/admin behavior remains unchanged.
- Existing confirmation, edit, history, success, and error dialogs remain modal.

---

### Task 1: Extend embedded module routing

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/MainModuleRoute.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/ui/MainModuleRouteTest.java`

**Interfaces:**
- Produces: `PERSONAL_PROFILE -> personal-profile` and `STUDENT_STATUS -> student-status` while preserving `LIBRARY -> library`.

- [x] Write route assertions first and run the targeted test to observe failure.
- [x] Implement only the two route mappings.
- [x] Re-run `MainModuleRouteTest` and observe success.

### Task 2: Extract reusable student and teacher panels

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/StudentFrame.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/TeacherProfileFrame.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/ui/ProfileModuleKindTest.java`

**Interfaces:**
- Produces: `StudentModulePanel(VCampusClient, String, Set<UserRole>, Runnable)`.
- Produces: `TeacherProfileModulePanel(VCampusClient, String, Runnable)`.
- Produces: `ProfileModuleKind.forRoles(Set<UserRole>)`, returning `STUDENT` or `TEACHER` for the personal-profile route.

- [x] Write failing role-selection tests for student and teacher identities.
- [x] Extract `StudentModulePanel`; make `StudentFrame` a thin wrapper.
- [x] Extract `TeacherProfileModulePanel`; make `TeacherProfileFrame` a thin wrapper.
- [x] Add header back buttons only when a callback is supplied.
- [x] Run the role-selection test and existing student/teacher client tests.

### Task 3: Wire workspace and notification routes

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/MainFrame.java`
- Modify: `docs/requirements.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: the two module panels and `ProfileModuleKind`.
- Produces: lazy `student-profile`, `student-management`, and `teacher-profile` content pages.

- [x] Add `showPersonalProfile`, `showStudentProfile`, `showStudentManagement`, and `showTeacherProfile` helpers.
- [x] Replace the corresponding frame construction in workspace and `STUDENT_PROFILE` notification branches.
- [x] Confirm source search finds no `new StudentFrame` or `new TeacherProfileFrame` in `MainFrame`.
- [x] Update the single-window UI documentation.
- [x] Run `mvn clean verify`; expected result is zero failures across all modules.
- [ ] Manually verify student, teacher/student-admin, and notification navigation.
