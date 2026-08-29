# Library Embedded Workspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the library open inside `MainFrame`'s right-hand content area and reuse the same embedded instance for workspace and notification navigation.

**Architecture:** Introduce a small `MainContentHost` around Swing `CardLayout`, extract the library window content into `LibraryModulePanel`, and route both module-card clicks and notification deep links through the host. Keep `LibraryFrame` only as a compatibility wrapper.

**Tech Stack:** Java 21, Swing, JUnit 5, Maven multi-module build

**Spec:** `docs/superpowers/specs/2026-08-27-single-window-module-embedding-design.md`

## Global Constraints

- Preserve MySQL -> application server -> Swing client; this plan makes no server or database changes.
- Keep network work off the Swing EDT and component updates on the EDT.
- Preserve the existing library permission-derived tabs and modal dialogs.
- Do not create a new top-level window from workspace or notification library navigation.

---

### Task 1: Lazy main-content host

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/MainContentHost.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/ui/MainContentHostTest.java`

**Interfaces:**
- Produces: `register(String, JComponent)`, `show(String)`, `showLazy(String, Supplier<? extends JComponent>)`, `currentName()`, and `registeredCount()`.

- [x] **Step 1: Write the failing test**

```java
@Test
void lazyPageIsCreatedOnceAndReused() {
    MainContentHost host = new MainContentHost();
    AtomicInteger creations = new AtomicInteger();
    JPanel first = host.showLazy("library", () -> {
        creations.incrementAndGet();
        return new JPanel();
    });
    JPanel second = host.showLazy("library", () -> {
        creations.incrementAndGet();
        return new JPanel();
    });
    assertSame(first, second);
    assertEquals(1, creations.get());
    assertEquals("library", host.currentName());
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `.tools/apache-maven-3.9.11/bin/mvn.cmd -pl vcampus-client -am -Dtest=MainContentHostTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation failure because `MainContentHost` does not exist.

- [x] **Step 3: Write minimal implementation**

Create a package-private `JPanel` with a `CardLayout` and `LinkedHashMap<String, JComponent>`. Reject blank names, duplicate explicit registration, missing `show` targets, and null factories/components. `showLazy` must return the registered component and invoke the supplier only on first access.

- [x] **Step 4: Run test to verify it passes**

Run the Task 1 Maven command again. Expected: PASS.

### Task 2: Reusable library module panel

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/LibraryModulePanel.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/LibraryFrame.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/ui/LibraryModuleNavigationTest.java`

**Interfaces:**
- Consumes: existing library child panels and `LibraryAccessPolicy`.
- Produces: `LibraryModulePanel(VCampusClient, String, Set<UserRole>, Runnable)` and `void openMyLoans()`.

- [x] **Step 1: Write the failing test**

Test a dependency-free tab-selection seam, `LibraryModuleNavigation`, with literal tab titles: `openMyLoansIndex(List.of("图书检索", "我的借阅"))` returns `1`, and a management-only list returns `-1`.

- [x] **Step 2: Run test to verify it fails**

Run the client targeted test command. Expected: compilation failure because `LibraryModuleNavigation` does not exist.

- [x] **Step 3: Write minimal implementation and panel extraction**

Create `LibraryModuleNavigation`; create `LibraryModulePanel` by moving the root panel and tabs from `LibraryFrame`; add a visible “返回工作台” button wired to the supplied callback. Make `LibraryFrame` attach a `LibraryModulePanel` with an empty back callback.

- [x] **Step 4: Run targeted tests**

Expected: `LibraryModuleNavigationTest` and `LibraryTabPolicyTest` pass.

### Task 3: Route workspace and notification library navigation

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/MainFrame.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/ui/NotificationLibraryUiTest.java`

**Interfaces:**
- Consumes: `MainContentHost` and `LibraryModulePanel`.
- Produces: one lazy embedded library instance for `ModuleCode.LIBRARY` and `NotificationTarget.LIBRARY_LOANS`.

- [x] **Step 1: Write the failing routing test**

Add a pure routing policy test asserting `MainModuleRoute.route(ModuleCode.LIBRARY)` is `"library"` and unimplemented modules have no route. This catches accidental return to popup-only routing without starting network requests.

- [x] **Step 2: Run test to verify it fails**

Expected: compilation failure because `MainModuleRoute` does not exist.

- [x] **Step 3: Implement the route and wire MainFrame**

Replace `contentCards` with `MainContentHost`. Store the workspace navigation button. Add `showWorkspace()`, `showLibrary()`, and `showLibraryLoans()` helpers. Change the library card and library notification branches to call these helpers; no branch may instantiate `LibraryFrame`.

- [x] **Step 4: Run client tests**

Run: `.tools/apache-maven-3.9.11/bin/mvn.cmd -pl vcampus-client -am test`

Expected: all common and client tests pass.

### Task 4: Documentation and full verification

**Files:**
- Modify: `docs/requirements.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: completed embedded library behavior.
- Produces: documented single-window UI baseline and acceptance instructions.

- [x] **Step 1: Update documentation**

Record that the library is the first embedded business module and that modal dialogs remain for focused actions and feedback.

- [x] **Step 2: Run full verification**

Run: `.tools/apache-maven-3.9.11/bin/mvn.cmd clean verify`

Expected: all modules build and all tests pass with zero failures.

- [ ] **Step 3: Manual acceptance**

Start the current server and client, click “书”, verify the title bar remains `VCampus 虚拟校园系统`, exercise return-to-workspace, reopen the library, and use a library notification to reach “我的借阅”.
