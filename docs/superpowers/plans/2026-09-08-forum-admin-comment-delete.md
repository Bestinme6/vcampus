# Forum Administrator Comment Delete Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let forum administrators soft-delete any selected normal comment from post detail or content administration with an audit reason.

**Architecture:** Keep author self-delete on `forum.comment.delete`. Add `DELETE` to administrative moderation and route administrator UI actions through `forum.admin.comment.moderate`, which updates comment state, post counters, and moderation logs transactionally.

**Tech Stack:** Java 21, JavaFX 21, Socket `MessageCodec`, JDBC, MySQL/H2, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-08-forum-images-reports-design.md`

## Global Constraints

- Preserve MySQL → application server → JavaFX client; the client never connects to MySQL.
- Use `forum.` actions and the existing length-prefixed protocol.
- Use `DELETED` soft deletion and require a 2–255 character reason for administrator deletion.
- Keep network/database work off the JavaFX thread and UI changes on it.

---

### Task 1: Repository and service permission path

**Files:**
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/ForumModerationAction.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/ForumRepository.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/ForumService.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/ForumRepositoryTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/service/ForumServiceTest.java`

**Interfaces:**
- Produces: `ForumModerationAction.DELETE` accepted by `ForumStore.moderateComment(...)`.
- Produces: administrator deletion sets `status='DELETED'`, sets `deleted_at`, refreshes post counts, and inserts a moderation log.

- [ ] **Step 1: Write failing repository tests**

Add tests that create a comment owned by user 2 and assert:

```java
assertEquals(MutationResult.CHANGED, repository.deleteComment(commentId, 3L, true));
assertEquals(MutationResult.CHANGED, repository.moderateComment(
        anotherCommentId, ForumModerationAction.DELETE, "违反社区规则", 3L));
assertEquals(0, repository.listComments(new CommentQuery(postId, 3L, true, 1, 20)).total());
assertEquals(0, scalarInt("SELECT comment_count FROM forum_posts WHERE id=" + postId));
assertEquals(1, scalarInt("SELECT COUNT(*) FROM forum_moderation_logs"
        + " WHERE target_type='COMMENT' AND target_id=" + anotherCommentId
        + " AND action='DELETE'"));
```

- [ ] **Step 2: Run the repository tests and confirm the expected failures**

Run:

```powershell
& './.tools/apache-maven-3.9.11/bin/mvn.cmd' -s './.tools/maven-settings.xml' -pl vcampus-server -am '-Dtest=ForumRepositoryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: the cross-user administrator delete returns `FORBIDDEN`, and `DELETE` moderation is not implemented.

- [ ] **Step 3: Implement transaction-safe deletion**

Add `DELETE` to the enum. In `deleteComment`, allow `state.authorUserId() == actorUserId || administrator`; when the administrator deletes another user's comment, insert an audit log with reason `管理员删除评论`. In `moderateComment`, handle `DELETE` only from `NORMAL`, set `deleted_at`, refresh statistics, and insert the supplied reason. Keep `RESTORE` unable to restore `DELETED` comments.

- [ ] **Step 4: Add and run service permission tests**

Add a test that sends `forum.admin.comment.moderate` with `action=DELETE` and a forum administrator session, plus a non-manager rejection. Assert the store receives `DELETE`, the reason, and the administrator user ID. Run `ForumServiceTest` and `ForumRepositoryTest`; expected result is zero failures.

- [ ] **Step 5: Commit the server change**

```powershell
git add vcampus-common/src/main/java/com/vcampus/common/model/ForumModerationAction.java vcampus-server/src/main/java/com/vcampus/server/database/ForumRepository.java vcampus-server/src/main/java/com/vcampus/server/service/ForumService.java vcampus-server/src/test/java/com/vcampus/server/database/ForumRepositoryTest.java vcampus-server/src/test/java/com/vcampus/server/service/ForumServiceTest.java
git commit -m "fix(forum): allow audited administrator comment deletion"
```

### Task 2: JavaFX administrator controls

**Files:**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/ForumRepository.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumAdminView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumController.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumUiTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumControllerTest.java`

**Interfaces:**
- Consumes: `ForumModerationAction.DELETE` and `ForumGateway.moderateComment(...)`.
- Produces: `CommentRow.canDelete()` is true for a normal comment when the viewer is its author or a forum manager.

- [ ] **Step 1: Write failing UI and controller tests**

Construct a normal comment owned by another user with `canDelete=true`, render `ForumDetailView`, and assert its delete button invokes the supplied callback. Render a selected comment in `ForumAdminView`, assert `#forum-admin-delete` becomes enabled, and fire it to record `ForumModerationAction.DELETE`. In `ForumControllerTest`, assert manager deletion calls `gateway.moderateComment(id, DELETE, reason)` while ordinary self-delete calls `gateway.deleteComment(id)`.

- [ ] **Step 2: Run the focused client tests and confirm missing controls**

```powershell
& './.tools/apache-maven-3.9.11/bin/mvn.cmd' -s './.tools/maven-settings.xml' -pl vcampus-client -am '-Dtest=ForumUiTest,ForumControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: no administrative Delete action exists and the manager path uses self-delete.

- [ ] **Step 3: Implement controls and manager-aware DTO permission**

Set comment deletion permission to:

```java
status == ForumContentStatus.NORMAL
        && (author == query.viewerUserId() || query.administrator())
```

Add a Delete button to `ForumAdminView` and allow it only for `COMMENT/NORMAL`. In `ForumController`, request a 2–255 character reason for manager deletion and call `moderateComment(..., DELETE, reason)`; retain the confirmation-only self-delete path for non-managers.

- [ ] **Step 4: Run focused server and client tests**

Run `ForumRepositoryTest`, `ForumUiTest`, and `ForumControllerTest`. Expected: administrator and author paths both pass, and ordinary cross-user deletion remains forbidden.

- [ ] **Step 5: Commit the client change**

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/database/ForumRepository.java vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumAdminView.java vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumController.java vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumUiTest.java vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumControllerTest.java
git commit -m "fix(forum): expose administrator comment delete controls"
```

