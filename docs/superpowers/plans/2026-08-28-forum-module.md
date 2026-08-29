# VCampus Forum Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a real three-tier campus forum with searchable posts, comments, author soft deletion, administrator moderation, and three embedded Swing views.

**Execution status (2026-08-28):** Implemented and automated verification passed; local MySQL dual-client acceptance remains pending.

**Architecture:** Shared enums, access policy, and `forum.*` action names live in `vcampus-common`; `ForumService` validates sessions and permissions before delegating transactional work to `ForumStore`/`ForumRepository` in `vcampus-server`; `ForumModulePanel` and focused child panels call the server asynchronously through `VCampusClient`. MySQL owns all durable state, and list payloads use the existing `RowCodec` convention.

**Tech Stack:** Java 21, Maven, Swing, Java Socket, `MessageCodec`, JDBC, MySQL 8.0.44, JUnit 5, H2 test database.

**Spec:** `docs/superpowers/specs/2026-08-28-forum-module-design.md`

## Global Constraints

- Preserve `MySQL database -> application server -> Swing client`; the client never connects to MySQL.
- Use the length-prefixed `MessageCodec` protocol; never use Java native object serialization.
- Put every forum action behind the exact `forum.` prefix.
- Keep database/network work off the Swing Event Dispatch Thread and UI mutations on it.
- Use parameterized SQL and soft-delete content; moderation logs are append-only.
- Ordinary users may delete only their own content; `FORUM_ADMIN` and `SUPER_ADMIN` may moderate all content.
- Update `database/schema.sql`, `database/seed.sql`, the numbered migration, and `docs/requirements.md` with the implementation.
- Root verification command is `mvn clean verify`.
- This checkout currently has no `.git` directory. Commit steps are conditional: run them only after Git metadata is restored; otherwise record the verification checkpoint in the task notes.

---

## File Map

**Shared vocabulary and protocol**

- Modify `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`: forum request names.
- Create `vcampus-common/src/main/java/com/vcampus/common/model/ForumContentStatus.java`: `NORMAL`, `DELETED`, `HIDDEN`.
- Create `vcampus-common/src/main/java/com/vcampus/common/model/ForumSort.java`: `LATEST_REPLY`, `LATEST_CREATED`.
- Create `vcampus-common/src/main/java/com/vcampus/common/model/ForumTargetType.java`: moderation target vocabulary.
- Create `vcampus-common/src/main/java/com/vcampus/common/model/ForumModerationAction.java`: legal moderation transitions.
- Create `vcampus-common/src/main/java/com/vcampus/common/model/ForumAccessPolicy.java`: access and administrator checks.

**Database and server**

- Modify `database/schema.sql`: four forum tables, constraints, indexes, and foreign keys.
- Create `database/migrations/005_forum.sql`: repeatable upgrade for an existing database.
- Modify `database/seed.sql`: idempotent default board rows.
- Create `vcampus-server/src/main/java/com/vcampus/server/database/ForumStore.java`: service-facing records, commands, pages, and mutation outcomes.
- Create `vcampus-server/src/main/java/com/vcampus/server/database/ForumRepository.java`: parameterized JDBC and transactions.
- Create `vcampus-server/src/main/java/com/vcampus/server/service/ForumService.java`: session, validation, permission, state transition, and response encoding.
- Modify `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`: `forum.*` dispatch.
- Modify `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`: production wiring.

**Client**

- Modify `vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java`: forum requests.
- Create `vcampus-client/src/main/java/com/vcampus/client/ui/ForumViewData.java`: strict response decoding.
- Create `vcampus-client/src/main/java/com/vcampus/client/ui/ForumModulePanel.java`: internal card navigation and shared state.
- Create `vcampus-client/src/main/java/com/vcampus/client/ui/ForumNavigation.java`: immutable home-query/back-navigation state.
- Create `vcampus-client/src/main/java/com/vcampus/client/ui/ForumHomePanel.java`: search, board filter, sorting, paging, and publishing.
- Create `vcampus-client/src/main/java/com/vcampus/client/ui/ForumPostDetailPanel.java`: post detail, comment timeline, commenting, author deletion.
- Create `vcampus-client/src/main/java/com/vcampus/client/ui/ForumAdminPanel.java`: boards, content moderation, and audit logs.
- Create `vcampus-client/src/main/java/com/vcampus/client/ui/ForumAdminTabPolicy.java`: administrator-tab visibility.
- Create `vcampus-client/src/main/java/com/vcampus/client/ui/ForumAsync.java`: background request/EDT completion helper with stale-response generation checks.
- Modify `vcampus-client/src/main/java/com/vcampus/client/ui/MainModuleRoute.java`: embedded `forum` route.
- Modify `vcampus-client/src/main/java/com/vcampus/client/ui/MainFrame.java`: lazy-load forum module.

**Tests and documentation**

- Create common policy/vocabulary tests, server service/repository tests, and client decoder/navigation tests listed in the tasks below.
- Modify `docs/requirements.md`: forum delivery state after verification.

---

### Task 1: Shared forum vocabulary, actions, and access policy

**Files:**
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/ForumContentStatus.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/ForumSort.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/ForumTargetType.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/ForumModerationAction.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/ForumAccessPolicy.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Test: `vcampus-common/src/test/java/com/vcampus/common/model/ForumPolicyTest.java`
- Test: `vcampus-common/src/test/java/com/vcampus/common/protocol/ForumActionsTest.java`

**Interfaces:**
- Consumes: existing `UserRole` and `Actions` conventions.
- Produces: `ForumAccessPolicy.canUse(Set<UserRole>)`, `ForumAccessPolicy.canManage(Set<UserRole>)`, forum enums, and fourteen action constants from the design spec.

- [ ] **Step 1: Write failing vocabulary and permission tests**

```java
@Test
void studentsTeachersAndAdministratorsCanUseForum() {
    assertTrue(ForumAccessPolicy.canUse(Set.of(UserRole.STUDENT)));
    assertTrue(ForumAccessPolicy.canUse(Set.of(UserRole.TEACHER)));
    assertTrue(ForumAccessPolicy.canUse(Set.of(UserRole.SUPER_ADMIN)));
}

@Test
void onlyForumAndSuperAdministratorsCanModerate() {
    assertFalse(ForumAccessPolicy.canManage(Set.of(UserRole.STUDENT)));
    assertTrue(ForumAccessPolicy.canManage(Set.of(UserRole.STUDENT, UserRole.FORUM_ADMIN)));
    assertTrue(ForumAccessPolicy.canManage(Set.of(UserRole.SUPER_ADMIN)));
}

@Test
void forumActionsHaveRequiredPrefix() {
    assertTrue(Actions.FORUM_POST_SEARCH.startsWith("forum."));
    assertEquals("forum.admin.post.moderate", Actions.FORUM_ADMIN_POST_MODERATE);
}
```

- [ ] **Step 2: Run the focused tests and confirm they fail because the new types/constants do not exist**

Run: `mvn -pl vcampus-common -Dtest=ForumPolicyTest,ForumActionsTest test`  
Expected: compilation failure naming `ForumAccessPolicy` or `FORUM_POST_SEARCH`.

- [ ] **Step 3: Add the minimal enums, policy, and exact action constants**

```java
public final class ForumAccessPolicy {
    private ForumAccessPolicy() {}
    public static boolean canUse(Set<UserRole> roles) {
        return roles.contains(UserRole.STUDENT)
                || roles.contains(UserRole.TEACHER)
                || roles.contains(UserRole.SUPER_ADMIN);
    }
    public static boolean canManage(Set<UserRole> roles) {
        return roles.contains(UserRole.FORUM_ADMIN)
                || roles.contains(UserRole.SUPER_ADMIN);
    }
}
```

Add constants matching every action in spec section 4, including `FORUM_SECTION_LIST`, `FORUM_POST_SEARCH`, `FORUM_POST_GET`, `FORUM_POST_CREATE`, `FORUM_POST_DELETE`, `FORUM_COMMENT_LIST`, `FORUM_COMMENT_CREATE`, `FORUM_COMMENT_DELETE`, and all six `FORUM_ADMIN_*` actions.

- [ ] **Step 4: Run common tests**

Run: `mvn -pl vcampus-common test`  
Expected: all common tests pass.

- [ ] **Step 5: Create the checkpoint commit when Git is available**

```powershell
git add vcampus-common
git commit -m "feat(forum): add shared protocol vocabulary"
```

---

### Task 2: Forum schema, seed data, and store contract

**Files:**
- Modify: `database/schema.sql`
- Create: `database/migrations/005_forum.sql`
- Modify: `database/seed.sql`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/ForumStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/ForumRepository.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/ForumMigrationTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/ForumRepositoryTest.java`

**Interfaces:**
- Consumes: Task 1 enums, `ConnectionFactory`, MySQL schema conventions, H2 test setup conventions.
- Produces: `ForumStore` with section/post/comment/admin/log queries and transactional mutations; `ForumRepository(ConnectionFactory)`.

- [ ] **Step 1: Define the store interface and immutable records**

```java
public interface ForumStore {
    List<SectionRecord> listSections(boolean includeDisabled) throws SQLException;
    PostPage searchPosts(PostQuery query) throws SQLException;
    Optional<PostDetail> findPost(long postId, long viewerUserId, boolean administrator)
            throws SQLException;
    long createPost(long authorUserId, CreatePost command) throws SQLException;
    MutationResult deletePost(long postId, long actorUserId, boolean administrator)
            throws SQLException;
    CommentPage listComments(long postId, CommentQuery query, boolean administrator)
            throws SQLException;
    long createComment(long postId, long authorUserId, String content) throws SQLException;
    MutationResult deleteComment(long commentId, long actorUserId, boolean administrator)
            throws SQLException;
    long saveSection(long operatorUserId, SaveSection command) throws SQLException;
    MutationResult setSectionEnabled(long sectionId, boolean enabled, long operatorUserId)
            throws SQLException;
    AdminContentPage searchAdminContent(AdminContentQuery query) throws SQLException;
    MutationResult moderatePost(long postId, ForumModerationAction action,
                                String reason, long operatorUserId) throws SQLException;
    MutationResult moderateComment(long commentId, ForumModerationAction action,
                                   String reason, long operatorUserId) throws SQLException;
    ModerationLogPage searchModerationLogs(int page, int pageSize) throws SQLException;

    enum MutationResult { NOT_FOUND, UNCHANGED, CHANGED, CONFLICT, FORBIDDEN }
}
```

Define `SectionRecord`, `PostRow`, `PostDetail`, `CommentRow`, `ModerationLogRow`, their page records, and query/command records with the field names from the design spec. Record constructors copy lists with `List.copyOf`.

- [ ] **Step 2: Write failing migration and repository tests**

```java
@Test
void schemaCreatesAllForumTablesAndIndexes() throws Exception {
    applyMigration("database/migrations/005_forum.sql");
    assertTableExists("FORUM_SECTIONS");
    assertTableExists("FORUM_POSTS");
    assertTableExists("FORUM_COMMENTS");
    assertTableExists("FORUM_MODERATION_LOGS");
}

@Test
void deletingOwnPostIsSoftAndHiddenFromOrdinarySearch() throws Exception {
    long postId = repository.createPost(studentId, new CreatePost(sectionId, "测试标题", "正文"));
    assertEquals(MutationResult.CHANGED,
            repository.deletePost(postId, studentId, false));
    assertTrue(repository.searchPosts(normalQuery()).rows().isEmpty());
}

@Test
void commentCreationUpdatesPostCountersInSameTransaction() throws Exception {
    repository.createComment(postId, teacherId, "第一条评论");
    PostDetail detail = repository.findPost(postId, teacherId, false).orElseThrow();
    assertEquals(1, detail.commentCount());
    assertNotNull(detail.lastCommentedAt());
}
```

- [ ] **Step 3: Run repository tests and verify failure**

Run: `mvn -pl vcampus-server -am -Dtest=ForumMigrationTest,ForumRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`  
Expected: tests fail because forum DDL and repository do not yet exist.

- [ ] **Step 4: Add the four tables and default sections**

Use constraints from the spec, indexes for `(section_id, status, pinned, last_commented_at)`, `(post_id, status, created_at)`, and `(target_type, target_id, created_at)`, plus non-negative checks for counts. Add identical DDL to `schema.sql` and `005_forum.sql`. Add idempotent seed rows:

```sql
INSERT INTO forum_sections (code, name, description, sort_order, enabled)
VALUES ('CAMPUS', '校园生活', '校园见闻与生活交流', 10, TRUE),
       ('STUDY', '学习广角', '课程、竞赛与学习经验', 20, TRUE),
       ('ACTIVITY', '场馆运动', '社团、活动与运动', 30, TRUE),
       ('CAREER', '生涯发展', '实习、就业与成长', 40, TRUE),
       ('MARKET', '交换认领', '闲置交换与失物招领', 50, TRUE)
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description);
```

- [ ] **Step 5: Implement `ForumRepository` with parameterized SQL and transactions**

For comment creation, lock the visible post and update counters in one transaction:

```java
connection.setAutoCommit(false);
PostState post = lockPost(connection, postId);
if (post == null || post.status() != ForumContentStatus.NORMAL || post.locked()) {
    connection.rollback();
    throw new IllegalStateException("帖子不可评论");
}
long commentId = insertComment(connection, postId, authorUserId, content);
updatePostCommentStats(connection, postId);
connection.commit();
return commentId;
```

Moderation methods must update with an expected-current-state predicate and append `forum_moderation_logs` before committing. Escape `!`, `%`, and `_` in keyword searches and use `LIKE ? ESCAPE '!'`.

- [ ] **Step 6: Run database tests**

Run: `mvn -pl vcampus-server -am -Dtest=ForumMigrationTest,ForumRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`  
Expected: both test classes pass, including rollback and concurrent-state cases.

- [ ] **Step 7: Create the checkpoint commit when Git is available**

```powershell
git add database vcampus-server/src/main/java/com/vcampus/server/database vcampus-server/src/test/java/com/vcampus/server/database
git commit -m "feat(forum): add forum persistence"
```

---

### Task 3: Forum service validation and permissions

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/service/ForumService.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/service/ForumServiceTest.java`

**Interfaces:**
- Consumes: `ForumStore`, `SessionManager`, Task 1 policies/enums, `RequestMessage`, `ResponseMessage`, and `RowCodec`.
- Produces: one public method per forum action: `listSections`, `searchPosts`, `getPost`, `createPost`, `deletePost`, `listComments`, `createComment`, `deleteComment`, `saveSection`, `setSectionEnabled`, `searchAdminContent`, `moderatePost`, `moderateComment`, `searchModerationLogs`.

- [ ] **Step 1: Write a fake store and failing service behavior tests**

```java
@Test
void ordinaryUserCannotModeratePost() {
    ResponseMessage response = service.moderatePost(request(studentToken, Map.of(
            "postId", "12", "action", "HIDE", "reason", "违规内容")));
    assertFalse(response.success());
    assertEquals("无权管理论坛内容", response.message());
    assertEquals(0, store.moderatePostCalls);
}

@Test
void authorIdentityComesFromSessionNotRequest() {
    ResponseMessage response = service.createPost(request(studentToken, Map.of(
            "sectionId", "2", "title", "课程资料交流", "content", "正文",
            "authorUserId", "999")));
    assertTrue(response.success());
    assertEquals(studentUserId, store.lastAuthorUserId);
}

@Test
void rejectsCommentOnLockedPostWithReadableMessage() {
    store.createCommentFailure = new IllegalStateException("帖子不可评论");
    ResponseMessage response = service.createComment(request(studentToken,
            Map.of("postId", "9", "content", "回复")));
    assertFalse(response.success());
    assertEquals("帖子已锁定或不可访问", response.message());
}
```

- [ ] **Step 2: Run the service tests and verify failure**

Run: `mvn -pl vcampus-server -am -Dtest=ForumServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`  
Expected: compilation failure because `ForumService` does not exist.

- [ ] **Step 3: Implement session, permission, and input helpers**

```java
private Optional<UserSession> session(RequestMessage request) {
    return sessions.find(request.parameters().get("sessionToken"));
}

private ResponseMessage requireManager(RequestMessage request,
                                       Function<UserSession, ResponseMessage> operation) {
    Optional<UserSession> session = session(request);
    if (session.isEmpty()) return expired(request);
    if (!ForumAccessPolicy.canManage(session.get().roles())) {
        return ResponseMessage.failure(request.requestId(), "无权管理论坛内容");
    }
    return operation.apply(session.get());
}
```

Validate title `4..160`, post body `1..10000`, comment `1..2000`, and moderation reason `2..255` after trimming outer whitespace without changing internal newlines.

- [ ] **Step 4: Implement all public handlers and stable response rows**

Encode post rows in this exact order:

```java
RowCodec.encode(id, sectionId, sectionName, authorUserId, authorDisplayName,
        title, summary, status, locked, pinned, featured,
        viewCount, commentCount, createdAt, lastCommentedAt)
```

Encode comment rows as `id, postId, authorUserId, authorDisplayName, content, status, createdAt, canDelete`; return post detail fields by key, including a server-computed `canDelete`. Map `SQLException` to the existing generic database failure message and domain conflicts to user-readable forum messages.

- [ ] **Step 5: Run service tests**

Run: `mvn -pl vcampus-server -am -Dtest=ForumServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`  
Expected: all forum service tests pass.

- [ ] **Step 6: Create the checkpoint commit when Git is available**

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/service/ForumService.java vcampus-server/src/test/java/com/vcampus/server/service/ForumServiceTest.java
git commit -m "feat(forum): add forum service rules"
```

---

### Task 4: Server routing and production wiring

**Files:**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterTest.java`

**Interfaces:**
- Consumes: `ForumService` public handlers from Task 3 and `ForumRepository(ConnectionFactory)` from Task 2.
- Produces: reachable `forum.*` server endpoints while preserving forced-password gating.

- [ ] **Step 1: Add a failing router test**

```java
@Test
void routesForumSectionListToForumService() {
    ResponseMessage response = router.route(
            authorized(Actions.FORUM_SECTION_LIST, student.token()), "127.0.0.1");
    assertTrue(response.success());
    assertEquals(1, forumStore.listSectionsCalls);
}
```

Update existing router construction in tests with the new final `ForumService` constructor argument before `SessionManager`.

- [ ] **Step 2: Run the router test and confirm failure**

Run: `mvn -pl vcampus-server -am -Dtest=RequestRouterTest -Dsurefire.failIfNoSpecifiedTests=false test`  
Expected: failure because the router does not dispatch forum actions.

- [ ] **Step 3: Add exact switch branches and production construction**

```java
case Actions.FORUM_SECTION_LIST -> forumService.listSections(request);
case Actions.FORUM_POST_SEARCH -> forumService.searchPosts(request);
case Actions.FORUM_ADMIN_POST_MODERATE -> forumService.moderatePost(request);
```

Add the remaining branches from Task 3. In `VCampusServer`, construct `ForumService forumService = new ForumService(new ForumRepository(connections), sessionManager);` and pass it into `RequestRouter`.

- [ ] **Step 4: Run all server tests**

Run: `mvn -pl vcampus-server -am test`  
Expected: all common and server tests pass.

- [ ] **Step 5: Create the checkpoint commit when Git is available**

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterTest.java
git commit -m "feat(forum): route forum requests"
```

---

### Task 5: Client networking and strict response decoding

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/ForumViewData.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/ui/ForumViewDataTest.java`

**Interfaces:**
- Consumes: Task 1 actions and Task 3 field order.
- Produces: typed client methods and records `SectionRow`, `PostRow`, `PostDetail`, `CommentRow`, `ModerationLogRow`, plus their page records.

- [ ] **Step 1: Write failing decoder tests for Chinese and multiline content**

```java
@Test
void decodesPostRowsWithoutBreakingChineseOrNewlines() {
    String encoded = RowCodec.encode("7", "2", "校园生活", "11", "张同学",
            "食堂窗口建议", "第一行\n第二行", "NORMAL", "false", "true",
            "false", "25", "3", "2026-08-28T08:00:00Z", "2026-08-28T09:00:00Z");
    ResponseMessage response = ResponseMessage.success("r1", "查询成功", Map.of(
            "page", "1", "pageSize", "10", "total", "1", "count", "1", "row.0", encoded));
    ForumViewData.PostPage page = ForumViewData.postPage(response);
    assertEquals("第一行\n第二行", page.rows().getFirst().summary());
}

@Test
void rejectsRowsWithUnexpectedFieldCount() {
    assertThrows(IllegalArgumentException.class,
            () -> ForumViewData.postPage(malformedResponse()));
}
```

- [ ] **Step 2: Run decoder tests and verify failure**

Run: `mvn -pl vcampus-client -am -Dtest=ForumViewDataTest -Dsurefire.failIfNoSpecifiedTests=false test`  
Expected: compilation failure because `ForumViewData` does not exist.

- [ ] **Step 3: Add typed request methods**

```java
public ResponseMessage searchForumPosts(String token, Long sectionId, String keyword,
                                        ForumSort sort, int page) throws IOException
public ResponseMessage getForumPost(String token, long postId) throws IOException
public ResponseMessage createForumPost(String token, long sectionId,
                                       String title, String content) throws IOException
public ResponseMessage createForumComment(String token, long postId, String content)
        throws IOException
public ResponseMessage moderateForumPost(String token, long postId,
        ForumModerationAction action, String reason) throws IOException
```

Add the remaining public/admin methods one-to-one with the action list. Each calls the existing `sendAuthorized` helper and transmits only server-recognized fields.

- [ ] **Step 4: Implement strict response decoders**

Use exact field counts from Task 3 and parse `Instant`, booleans, integers, statuses, and nullable timestamps explicitly. Wrap malformed data in `IllegalArgumentException("服务器返回的论坛数据格式不正确", cause)`.

- [ ] **Step 5: Run client decoder tests**

Run: `mvn -pl vcampus-client -am -Dtest=ForumViewDataTest -Dsurefire.failIfNoSpecifiedTests=false test`  
Expected: all decoder tests pass.

- [ ] **Step 6: Create the checkpoint commit when Git is available**

```powershell
git add vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java vcampus-client/src/main/java/com/vcampus/client/ui/ForumViewData.java vcampus-client/src/test/java/com/vcampus/client/ui/ForumViewDataTest.java
git commit -m "feat(forum): add client protocol adapter"
```

---

### Task 6: Swing forum home and post detail

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/ForumAsync.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/ForumModulePanel.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/ForumNavigation.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/ForumHomePanel.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/ForumPostDetailPanel.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/ui/ForumNavigationTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/ui/ForumAsyncTest.java`

**Interfaces:**
- Consumes: Task 5 client methods/records and existing `Theme`, `RoundedPanel`, `MainContentHost` patterns.
- Produces: `ForumModulePanel.openPost(long)`, `ForumModulePanel.openHome()`, `ForumNavigation.HomeQuery`, and refreshable home/detail panels.

- [ ] **Step 1: Write failing internal navigation and EDT tests**

```java
@Test
void openingPostAndReturningPreservesHomeQuery() {
    ForumNavigation navigation = new ForumNavigation();
    navigation.rememberHome(new ForumNavigation.HomeQuery(
            2L, "食堂", ForumSort.LATEST_REPLY, 3));
    navigation.openPost(18L);
    assertEquals(18L, navigation.currentPostId());
    assertEquals(3, navigation.backHome().page());
}

@Test
void asyncCompletionRunsOnEventDispatchThread() throws Exception {
    AtomicBoolean onEdt = new AtomicBoolean();
    ForumAsync.run(() -> "ok", value -> onEdt.set(SwingUtilities.isEventDispatchThread()),
            error -> fail(error));
    awaitCondition(onEdt::get);
}
```

- [ ] **Step 2: Run focused UI tests and verify failure**

Run: `mvn -pl vcampus-client -am -Dtest=ForumNavigationTest,ForumAsyncTest -Dsurefire.failIfNoSpecifiedTests=false test`  
Expected: compilation failure for missing forum UI helpers.

- [ ] **Step 3: Implement stale-safe asynchronous execution**

```java
long generation = requestGeneration.incrementAndGet();
ForumAsync.run(request,
        value -> { if (generation == requestGeneration.get()) render(value); },
        error -> { if (generation == requestGeneration.get()) showError(error); });
```

`ForumAsync` uses `CompletableFuture.supplyAsync`, unwraps `CompletionException`, and invokes both callbacks with `SwingUtilities.invokeLater`.

- [ ] **Step 4: Implement the module card host and forum home**

Use an internal `CardLayout` with names `home`, `detail`, and conditionally `admin`. The home toolbar contains keyword, board selector, sort selector, search, publish, and refresh. The center list renders pinned/featured badges and post metadata; the footer owns previous/next buttons and page text. Publishing validates locally, disables submit during the request, and refreshes page 1 after success.

- [ ] **Step 5: Implement post detail and comments**

Render server-owned author/status data, load comments separately, and disable comment submission when `locked` or non-`NORMAL`. Show delete buttons only when the server-computed `canDelete` value is true; the server still rechecks authorship on mutation. After mutations, refresh detail and comment page.

- [ ] **Step 6: Run forum UI tests and the full client suite**

Run: `mvn -pl vcampus-client -am test`  
Expected: all common and client tests pass in headless mode.

- [ ] **Step 7: Create the checkpoint commit when Git is available**

```powershell
git add vcampus-client/src/main/java/com/vcampus/client/ui/ForumAsync.java vcampus-client/src/main/java/com/vcampus/client/ui/ForumModulePanel.java vcampus-client/src/main/java/com/vcampus/client/ui/ForumNavigation.java vcampus-client/src/main/java/com/vcampus/client/ui/ForumHomePanel.java vcampus-client/src/main/java/com/vcampus/client/ui/ForumPostDetailPanel.java vcampus-client/src/test/java/com/vcampus/client/ui/ForumNavigationTest.java vcampus-client/src/test/java/com/vcampus/client/ui/ForumAsyncTest.java
git commit -m "feat(forum): add forum browsing and discussion UI"
```

---

### Task 7: Forum administration UI and workbench integration

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/ForumAdminPanel.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/ForumAdminTabPolicy.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/ForumModulePanel.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/MainModuleRoute.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/MainFrame.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/ui/MainModuleRouteTest.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/ui/ForumAdminTabPolicyTest.java`

**Interfaces:**
- Consumes: Task 1 `ForumAccessPolicy`, Task 5 admin client calls, Task 6 module navigation.
- Produces: accessible workbench route and administrator tabs for boards, posts, comments, and logs.

- [ ] **Step 1: Write failing route and admin-tab tests**

```java
@Test
void forumUsesEmbeddedRoute() {
    assertEquals("forum", MainModuleRoute.route(ModuleCode.FORUM).orElseThrow());
}

@Test
void adminTabIsVisibleOnlyToForumOrSuperAdmin() {
    assertFalse(ForumAdminTabPolicy.visible(Set.of(UserRole.STUDENT)));
    assertTrue(ForumAdminTabPolicy.visible(Set.of(UserRole.TEACHER, UserRole.FORUM_ADMIN)));
    assertTrue(ForumAdminTabPolicy.visible(Set.of(UserRole.SUPER_ADMIN)));
}
```

- [ ] **Step 2: Run focused tests and verify failure**

Run: `mvn -pl vcampus-client -am -Dtest=MainModuleRouteTest,ForumAdminTabPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`  
Expected: forum route assertion fails and admin policy type is missing.

- [ ] **Step 3: Implement administrator tabs and confirmations**

Create four sub-tabs: boards, posts, comments, logs. Hide/restore operations require a reason of `2..255`; lock/unlock, pin/unpin, and feature/unfeature use explicit confirmation. Each successful mutation refreshes only the active table. Render `DELETED` as read-only audit data and offer no restore action.

- [ ] **Step 4: Wire the embedded route into `MainFrame`**

```java
private ForumModulePanel showForum() {
    ForumModulePanel panel = contentHost.showLazy("forum",
            () -> new ForumModulePanel(client, sessionToken, roles, this::showWorkspace));
    selectNavigation(workspaceNavigation);
    return panel;
}
```

Add `case FORUM -> Optional.of("forum")` in `MainModuleRoute` and `case "forum" -> showForum()` in `ModuleCard.openModule()`.

- [ ] **Step 5: Run client tests**

Run: `mvn -pl vcampus-client -am test`  
Expected: all client tests pass, and the old assertion that forum is unavailable has been replaced.

- [ ] **Step 6: Create the checkpoint commit when Git is available**

```powershell
git add vcampus-client
git commit -m "feat(forum): add moderation UI and workbench route"
```

---

### Task 8: Documentation, full verification, and local acceptance

**Files:**
- Modify: `docs/requirements.md`
- Modify: `docs/superpowers/specs/2026-08-28-forum-module-design.md`
- Modify: `docs/superpowers/plans/2026-08-28-forum-module.md`

**Interfaces:**
- Consumes: every completed task.
- Produces: verified repository state and accurate delivery status.

- [ ] **Step 1: Run the complete automated build**

Run: `mvn clean verify`  
Expected: `BUILD SUCCESS` with all three modules and all forum tests passing.

- [ ] **Step 2: Check protocol and threading invariants mechanically**

Run: `rg -n "forum\.|Forum" vcampus-common/src/main vcampus-server/src/main vcampus-client/src/main`  
Expected: actions are in common, JDBC imports occur only in server code, and UI calls use `ForumAsync` rather than blocking event listeners.

Run: `rg -n "java\.sql|DriverManager|ConnectionFactory" vcampus-client/src`  
Expected: no matches.

- [ ] **Step 3: Apply schema and seed to the configured local MySQL database**

Run with credentials supplied by the operator's existing MySQL option file:

```powershell
mysql --default-character-set=utf8mb4 vcampus --execute="source database/schema.sql"
mysql --default-character-set=utf8mb4 vcampus --execute="source database/seed.sql"
```

Expected: both scripts complete without errors and can be safely rerun. Do not write the local account or password into repository files.

- [ ] **Step 4: Perform two-client acceptance**

Start `com.vcampus.server.ServerMain`, then two instances of `com.vcampus.client.ClientMain`. In client A, sign in as a normal student or teacher, create a post and comment, and confirm author deletion removes content from ordinary search. In client B, sign in with `FORUM_ADMIN` or `SUPER_ADMIN`, hide/restore and lock the post, then verify client A cannot view hidden content or comment on the locked post after refresh. Confirm the moderation log contains operator, target, action, reason, and time.

- [ ] **Step 5: Update truthful requirement and spec status**

If only automated verification completed, set the forum row to `开发完成，待本机 MySQL 双客户端验收`. If Step 4 completed, set it to `开发完成，本机 MySQL 与双客户端验收通过`. Change the design status from `待用户评审` to `已实现` only after the corresponding verification succeeds.

- [ ] **Step 6: Run the final build after documentation edits**

Run: `mvn clean verify`  
Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Create the final checkpoint commit when Git is available**

```powershell
git add docs database vcampus-common vcampus-server vcampus-client
git commit -m "feat: complete campus forum module"
```
