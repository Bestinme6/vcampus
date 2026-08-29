# VCampus Forum Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect forum comments and moderation actions to the existing message center with transactionally consistent notifications and direct navigation to the related post.

**Architecture:** Extend the shared notification vocabulary and existing `notifications` constraints, then inject the existing `NotificationWriter` into `ForumRepository` so forum mutations, moderation logs, counters, and notifications commit in one MySQL transaction. On the client, carry both `NotificationTarget` and `relatedEntityId` through a typed destination object so a forum notification can reuse `ForumModulePanel.openPost(long)`.

**Tech Stack:** Java 21, Swing, Java Socket, JDBC, MySQL 8.0.44, H2 MySQL mode, JUnit 5, Maven.

**Spec:** `docs/superpowers/specs/2026-08-29-forum-notifications-design.md`

## Global Constraints

- Preserve MySQL database -> application server -> Swing client; the client must never import JDBC or connect to MySQL.
- Keep notification creation in the same JDBC transaction as the forum change that caused it.
- Reuse the existing `notifications` table and notification Socket actions; do not add a forum-specific notification endpoint.
- Do not notify when sender and recipient are the same user.
- Do not notify for failed, unchanged, forbidden, missing, or conflicting forum operations.
- One successful comment creates at most one notification; do not aggregate comments.
- All eight successful post moderation actions notify the post author.
- Comment `HIDE` and `RESTORE` notify the comment author.
- `related_entity_id` for every forum notification is the post ID.
- Keep Swing mutations on the Event Dispatch Thread and network/database work off it.
- Update `database/schema.sql`, add numbered migration `006_forum_notifications.sql`, and update `docs/requirements.md` with implementation status.
- The current directory has no Git metadata. Each task includes a suggested commit, but execution must skip commit commands unless Git metadata has been restored.

Before running Maven commands in the current Windows workspace, define:

```powershell
$mvn = 'C:\Users\Bestinme\Documents\Codex\2026-08-25\wo\work\tools\apache-maven-3.9.16\bin\mvn.cmd'
$mavenRepoArg = '-Dmaven.repo.local=C:\Users\Bestinme\Documents\Codex\2026-08-25\wo\work\tools\m2-repository'
```

---

## File Structure

### New files

- `database/migrations/006_forum_notifications.sql` — upgrades the three notification check constraints without deleting notification rows.
- `vcampus-server/src/main/java/com/vcampus/server/database/ForumNotificationFactory.java` — creates bounded, localized `NotificationDraft` values and suppresses self-notifications.
- `vcampus-server/src/test/java/com/vcampus/server/database/ForumNotificationFactoryTest.java` — verifies text, target IDs, self-notification suppression, and field limits.
- `vcampus-client/src/main/java/com/vcampus/client/ui/NotificationDestination.java` — carries `NotificationTarget` plus nullable related entity ID and decides whether a destination is navigable.
- `vcampus-client/src/main/java/com/vcampus/client/ui/NotificationNavigationPolicy.java` — validates and extracts the post ID for a forum deep link.
- `vcampus-client/src/test/java/com/vcampus/client/ui/NotificationForumUiTest.java` — verifies forum filtering, detail action, and preservation of the post ID.

### Modified files

- `vcampus-common/src/main/java/com/vcampus/common/model/NotificationType.java`
- `vcampus-common/src/main/java/com/vcampus/common/model/NotificationSource.java`
- `vcampus-common/src/main/java/com/vcampus/common/model/NotificationTarget.java`
- `vcampus-common/src/test/java/com/vcampus/common/model/NotificationVocabularyTest.java`
- `database/schema.sql`
- `vcampus-server/src/main/java/com/vcampus/server/database/ForumRepository.java`
- `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`
- `vcampus-server/src/test/java/com/vcampus/server/database/ForumMigrationTest.java`
- `vcampus-server/src/test/java/com/vcampus/server/database/ForumRepositoryTest.java`
- `vcampus-client/src/main/java/com/vcampus/client/ui/NotificationPanel.java`
- `vcampus-client/src/main/java/com/vcampus/client/ui/NotificationDetailDialog.java`
- `vcampus-client/src/main/java/com/vcampus/client/ui/MainFrame.java`
- `vcampus-client/src/test/java/com/vcampus/client/ui/NotificationLibraryUiTest.java`
- `vcampus-client/src/test/java/com/vcampus/client/ui/NotificationViewDataTest.java`
- `docs/requirements.md`
- `docs/message-center.md`
- `docs/superpowers/specs/2026-08-29-forum-notifications-design.md`

---

### Task 1: Shared forum notification vocabulary and MySQL migration

**Files:**
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/NotificationType.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/NotificationSource.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/NotificationTarget.java`
- Modify: `vcampus-common/src/test/java/com/vcampus/common/model/NotificationVocabularyTest.java`
- Modify: `database/schema.sql`
- Create: `database/migrations/006_forum_notifications.sql`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/database/ForumMigrationTest.java`

**Interfaces:**
- Produces: `NotificationType.FORUM_POST_COMMENTED`, `FORUM_POST_MODERATED`, `FORUM_COMMENT_MODERATED`.
- Produces: `NotificationSource.FORUM`.
- Produces: `NotificationTarget.FORUM_POST`.
- Produces: fresh-install and upgrade constraints that accept those exact enum names.

- [ ] **Step 1: Add failing shared vocabulary assertions**

Add to `NotificationVocabularyTest`:

```java
@Test
void forumNotificationVocabularyIsStable() {
    assertEquals("FORUM_POST_COMMENTED",
            NotificationType.FORUM_POST_COMMENTED.name());
    assertEquals("FORUM_POST_MODERATED",
            NotificationType.FORUM_POST_MODERATED.name());
    assertEquals("FORUM_COMMENT_MODERATED",
            NotificationType.FORUM_COMMENT_MODERATED.name());
    assertEquals("FORUM", NotificationSource.FORUM.name());
    assertEquals("FORUM_POST", NotificationTarget.FORUM_POST.name());
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-common' '-Dtest=NotificationVocabularyTest' test
```

Expected: test compilation fails because the five forum notification enum values do not exist.

- [ ] **Step 3: Add the five enum values**

Append the values without renaming existing constants:

```java
public enum NotificationType {
    // existing values unchanged
    FORUM_POST_COMMENTED,
    FORUM_POST_MODERATED,
    FORUM_COMMENT_MODERATED
}

public enum NotificationSource {
    ACADEMIC, STUDENT_STATUS, ACCOUNT_SECURITY, LIBRARY, FORUM
}

public enum NotificationTarget {
    TEACHER_SCHEDULE, STUDENT_GRADES, STUDENT_PROFILE,
    LIBRARY_LOANS, FORUM_POST, NONE
}
```

- [ ] **Step 4: Run the shared test and verify GREEN**

Run the Step 2 command. Expected: `NotificationVocabularyTest` passes.

- [ ] **Step 5: Add failing schema/migration assertions**

Extend `ForumMigrationTest` to read both files and require every enum literal:

```java
@Test
void forumNotificationMigrationExtendsAllNotificationConstraints() throws Exception {
    String schema = Files.readString(Path.of("..", "database", "schema.sql"));
    String migration = Files.readString(Path.of(
            "..", "database", "migrations", "006_forum_notifications.sql"));
    for (String value : List.of(
            "FORUM_POST_COMMENTED", "FORUM_POST_MODERATED",
            "FORUM_COMMENT_MODERATED", "FORUM", "FORUM_POST")) {
        assertTrue(schema.contains("'" + value + "'"));
        assertTrue(migration.contains("'" + value + "'"));
    }
    assertTrue(migration.contains("DROP CHECK chk_notification_type"));
    assertTrue(migration.contains("DROP CHECK chk_notification_source"));
    assertTrue(migration.contains("DROP CHECK chk_notification_target"));
}
```

- [ ] **Step 6: Run the migration test and verify RED**

Run:

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-server' '-am' '-Dtest=ForumMigrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: failure because migration `006_forum_notifications.sql` and the new schema literals are absent.

- [ ] **Step 7: Update the fresh schema and create migration 006**

In `database/schema.sql`, recreate the allowed lists with all existing values plus the new values. Create `006_forum_notifications.sql`:

```sql
USE vcampus;

ALTER TABLE notifications
    DROP CHECK chk_notification_type,
    DROP CHECK chk_notification_source,
    DROP CHECK chk_notification_target;

ALTER TABLE notifications
    ADD CONSTRAINT chk_notification_type CHECK (notification_type IN (
        'SCHEDULE_ASSIGNED', 'GRADE_PUBLISHED', 'STUDENT_STATUS_CHANGED',
        'ROLES_CHANGED', 'ACCOUNT_ENABLED', 'ACCOUNT_DISABLED', 'PASSWORD_RESET',
        'LIBRARY_BORROWED', 'LIBRARY_RENEWED', 'LIBRARY_RETURNED', 'LIBRARY_LOST',
        'LIBRARY_DUE_SOON', 'LIBRARY_OVERDUE',
        'FORUM_POST_COMMENTED', 'FORUM_POST_MODERATED',
        'FORUM_COMMENT_MODERATED')),
    ADD CONSTRAINT chk_notification_source CHECK (source_module IN (
        'ACADEMIC', 'STUDENT_STATUS', 'ACCOUNT_SECURITY', 'LIBRARY', 'FORUM')),
    ADD CONSTRAINT chk_notification_target CHECK (target IN (
        'TEACHER_SCHEDULE', 'STUDENT_GRADES', 'STUDENT_PROFILE',
        'LIBRARY_LOANS', 'FORUM_POST', 'NONE'));
```

- [ ] **Step 8: Run Task 1 tests and verify GREEN**

Run the commands from Steps 2 and 6. Expected: both pass.

- [ ] **Step 9: Suggested commit if Git is restored**

```bash
git add vcampus-common database vcampus-server/src/test/java/com/vcampus/server/database/ForumMigrationTest.java
git commit -m "feat(notifications): add forum notification vocabulary"
```

---

### Task 2: Bounded forum notification draft factory

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/ForumNotificationFactory.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/ForumNotificationFactoryTest.java`

**Interfaces:**
- Consumes: the Task 1 enum values and `NotificationWriter.NotificationDraft`.
- Produces: `Optional<NotificationDraft> commentCreated(long postAuthorId, long commenterId, String commenterName, long postId, String postTitle, String comment)`.
- Produces: `Optional<NotificationDraft> postModerated(long postAuthorId, long operatorId, String operatorName, long postId, String postTitle, ForumModerationAction action, String reason)`.
- Produces: `Optional<NotificationDraft> commentModerated(long commentAuthorId, long operatorId, String operatorName, long postId, String postTitle, ForumModerationAction action, String reason)`.

- [ ] **Step 1: Write failing factory behavior tests**

Create tests using literal expected text and real drafts:

```java
private final ForumNotificationFactory factory = new ForumNotificationFactory();

@Test
void commentDraftTargetsPostAuthorAndPost() {
    var draft = factory.commentCreated(
            1L, 2L, "李老师", 41L, "校园活动建议", "我支持这个建议").orElseThrow();
    assertEquals(1L, draft.recipientUserId());
    assertEquals(2L, draft.senderUserId());
    assertEquals(NotificationType.FORUM_POST_COMMENTED, draft.type());
    assertEquals(NotificationSource.FORUM, draft.source());
    assertEquals(NotificationTarget.FORUM_POST, draft.target());
    assertEquals(41L, draft.relatedEntityId());
    assertEquals("您的帖子收到一条新评论", draft.title());
    assertEquals("李老师评论了您的帖子《校园活动建议》：我支持这个建议", draft.content());
}

@Test
void selfActionsDoNotCreateNotifications() {
    assertTrue(factory.commentCreated(
            1L, 1L, "张同学", 41L, "标题", "评论").isEmpty());
    assertTrue(factory.postModerated(
            3L, 3L, "管理员", 41L, "标题",
            ForumModerationAction.LOCK, "管理员调整内容状态").isEmpty());
}

@Test
void generatedFieldsNeverExceedNotificationColumns() {
    String longText = "长".repeat(2_000);
    var draft = factory.commentCreated(
            1L, 2L, longText, 41L, longText, longText).orElseThrow();
    assertTrue(draft.title().length() <= 160);
    assertTrue(draft.content().length() <= 1_000);
}
```

Add literal action-label coverage:

```java
@Test
void everyPostModerationActionUsesItsChineseLabel() {
    Map<ForumModerationAction, String> labels = Map.of(
            ForumModerationAction.HIDE, "隐藏",
            ForumModerationAction.RESTORE, "恢复",
            ForumModerationAction.LOCK, "锁定",
            ForumModerationAction.UNLOCK, "解锁",
            ForumModerationAction.PIN, "置顶",
            ForumModerationAction.UNPIN, "取消置顶",
            ForumModerationAction.FEATURE, "设为精华",
            ForumModerationAction.UNFEATURE, "取消精华");
    labels.forEach((action, label) -> assertTrue(factory.postModerated(
            1L, 3L, "论坛管理员", 41L, "标题", action, "审核原因")
            .orElseThrow().content().contains(label)));
}

@Test
void commentModerationIncludesReasonAndTargetsParentPost() {
    for (ForumModerationAction action : List.of(
            ForumModerationAction.HIDE, ForumModerationAction.RESTORE)) {
        var draft = factory.commentModerated(
                2L, 3L, "论坛管理员", 41L, "标题", action, "审核原因")
                .orElseThrow();
        assertEquals(NotificationType.FORUM_COMMENT_MODERATED, draft.type());
        assertEquals(41L, draft.relatedEntityId());
        assertTrue(draft.content().contains("审核原因"));
    }
}
```

- [ ] **Step 2: Run factory tests and verify RED**

Run:

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-server' '-am' '-Dtest=ForumNotificationFactoryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: test compilation fails because `ForumNotificationFactory` is missing.

- [ ] **Step 3: Implement the minimal factory**

Use a package-private final class with these constants and helpers:

```java
final class ForumNotificationFactory {
    private static final int TITLE_LIMIT = 160;
    private static final int CONTENT_LIMIT = 1_000;

    Optional<NotificationDraft> commentCreated(
            long postAuthorId, long commenterId, String commenterName,
            long postId, String postTitle, String comment) {
        if (postAuthorId == commenterId) return Optional.empty();
        return Optional.of(new NotificationDraft(
                postAuthorId, commenterId, NotificationType.FORUM_POST_COMMENTED,
                NotificationSource.FORUM, "您的帖子收到一条新评论",
                bounded(commenterName + "评论了您的帖子《" + postTitle + "》：" + comment,
                        CONTENT_LIMIT),
                NotificationTarget.FORUM_POST, postId));
    }

    Optional<NotificationDraft> postModerated(
            long postAuthorId, long operatorId, String operatorName,
            long postId, String postTitle,
            ForumModerationAction action, String reason) {
        if (postAuthorId == operatorId) return Optional.empty();
        String content = operatorName + "已将您的帖子《" + postTitle + "》"
                + actionLabel(action) + "。";
        if (action == ForumModerationAction.HIDE
                || action == ForumModerationAction.RESTORE) {
            content += "管理原因：" + reason;
        }
        return Optional.of(new NotificationDraft(
                postAuthorId, operatorId, NotificationType.FORUM_POST_MODERATED,
                NotificationSource.FORUM, "您的帖子状态已更新",
                bounded(content, CONTENT_LIMIT),
                NotificationTarget.FORUM_POST, postId));
    }

    Optional<NotificationDraft> commentModerated(
            long commentAuthorId, long operatorId, String operatorName,
            long postId, String postTitle,
            ForumModerationAction action, String reason) {
        if (commentAuthorId == operatorId) return Optional.empty();
        if (action != ForumModerationAction.HIDE
                && action != ForumModerationAction.RESTORE) {
            throw new IllegalArgumentException("评论审核动作无效");
        }
        String content = operatorName + "已" + actionLabel(action)
                + "您在帖子《" + postTitle + "》中的评论。管理原因：" + reason;
        return Optional.of(new NotificationDraft(
                commentAuthorId, operatorId,
                NotificationType.FORUM_COMMENT_MODERATED,
                NotificationSource.FORUM, "您的评论状态已更新",
                bounded(content, CONTENT_LIMIT),
                NotificationTarget.FORUM_POST, postId));
    }

    private String actionLabel(ForumModerationAction action) {
        return switch (action) {
            case HIDE -> "隐藏";
            case RESTORE -> "恢复";
            case LOCK -> "锁定";
            case UNLOCK -> "解锁";
            case PIN -> "置顶";
            case UNPIN -> "取消置顶";
            case FEATURE -> "设为精华";
            case UNFEATURE -> "取消精华";
            default -> throw new IllegalArgumentException("不支持的论坛通知动作");
        };
    }

    private String bounded(String value, int limit) {
        if (value.length() <= limit) return value;
        return value.substring(0, limit - 1) + "…";
    }
}
```

- [ ] **Step 4: Run factory tests and verify GREEN**

Run the Step 2 command. Expected: all factory tests pass.

- [ ] **Step 5: Suggested commit if Git is restored**

```bash
git add vcampus-server/src/main/java/com/vcampus/server/database/ForumNotificationFactory.java vcampus-server/src/test/java/com/vcampus/server/database/ForumNotificationFactoryTest.java
git commit -m "feat(forum): define forum notification drafts"
```

---

### Task 3: Transactional notifications for new comments

**Files:**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/ForumRepository.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/database/ForumRepositoryTest.java`

**Interfaces:**
- Consumes: `ForumNotificationFactory.commentCreated(...)` and `NotificationWriter.insert(Connection, NotificationDraft)`.
- Produces: `ForumRepository(ConnectionFactory, NotificationWriter)` for controlled testing and production injection.
- Preserves: `ForumRepository(ConnectionFactory)` as a convenience constructor backed by `NotificationRepository`.

- [ ] **Step 1: Extend the H2 fixture with notifications and captured drafts**

Add a minimal `notifications` table to `createSchema()`:

```java
statement.execute("CREATE TABLE notifications ("
        + "id BIGINT AUTO_INCREMENT PRIMARY KEY, recipient_user_id BIGINT NOT NULL, "
        + "sender_user_id BIGINT, notification_type VARCHAR(40) NOT NULL, "
        + "source_module VARCHAR(40) NOT NULL, title VARCHAR(160) NOT NULL, "
        + "content VARCHAR(1000) NOT NULL, target VARCHAR(40) NOT NULL, "
        + "related_entity_id BIGINT, is_read BOOLEAN DEFAULT FALSE, "
        + "read_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
```

Then replace the repository assignment in `setUp()` with:

```java
repository = new ForumRepository(
        connections, new NotificationRepository(connections));
```

- [ ] **Step 2: Add failing comment notification tests**

```java
@Test
void commentByAnotherUserCreatesForumNotification() throws SQLException {
    long postId = repository.createPost(1L,
            new CreatePost(1L, "课程交流帖", "讨论课程安排。"));
    repository.createComment(postId, 2L, "第一条评论");

    assertEquals(1, scalarInt("SELECT COUNT(*) FROM notifications"));
    assertEquals("FORUM_POST_COMMENTED", scalarString(
            "SELECT notification_type FROM notifications"));
    assertEquals(postId, scalarLong(
            "SELECT related_entity_id FROM notifications"));
}

@Test
void commentingOnOwnPostDoesNotNotifySelf() throws SQLException {
    long postId = repository.createPost(1L,
            new CreatePost(1L, "自己的帖子", "正文"));
    repository.createComment(postId, 1L, "补充说明");
    assertEquals(0, scalarInt("SELECT COUNT(*) FROM notifications"));
}

private int scalarInt(String sql) throws SQLException {
    return Math.toIntExact(scalarLong(sql));
}

private long scalarLong(String sql) throws SQLException {
    try (Connection connection = connections.openConnection();
         Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(sql)) {
        result.next();
        return result.getLong(1);
    }
}

private void executeUpdate(String sql) throws SQLException {
    try (Connection connection = connections.openConnection();
         Statement statement = connection.createStatement()) {
        statement.executeUpdate(sql);
    }
}
```

- [ ] **Step 3: Run the comment tests and verify RED**

Run:

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-server' '-am' '-Dtest=ForumRepositoryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: the notification count assertion fails because `createComment` does not write notifications.

- [ ] **Step 4: Inject dependencies and notify within createComment**

Add fields and constructors:

```java
private final NotificationWriter notifications;
private final ForumNotificationFactory notificationFactory = new ForumNotificationFactory();

public ForumRepository(ConnectionFactory connections) {
    this(connections, new NotificationRepository(connections));
}

public ForumRepository(ConnectionFactory connections, NotificationWriter notifications) {
    this.connectionFactory = Objects.requireNonNull(connections);
    this.notifications = Objects.requireNonNull(notifications);
}
```

Replace the post validation query with one that also reads `p.author_user_id` and `p.title`:

```sql
SELECT p.status, p.locked, p.author_user_id, p.title, s.enabled
FROM forum_posts p
JOIN forum_sections s ON s.id = p.section_id
WHERE p.id = ?
FOR UPDATE
```

Read the commenter display name with `SELECT display_name FROM users WHERE id = ?` using a prepared statement. After refreshing stats and before commit, create the draft.

Do not use `Optional.ifPresent` with a checked exception. Use an explicit branch so `SQLException` reaches the existing rollback handler:

```java
Optional<NotificationDraft> draft = notificationFactory.commentCreated(
        postAuthorId, authorUserId, commenterName, postId, postTitle, content);
if (draft.isPresent()) {
    notifications.insert(connection, draft.orElseThrow());
}
```

- [ ] **Step 5: Run comment tests and verify GREEN**

Run the Step 3 command. Expected: existing comment counter tests and new notification tests pass.

- [ ] **Step 6: Add a failing rollback test**

Create a reusable failing writer, create a post with the normal repository, then call `createComment` through a repository using the failing writer:

```java
private NotificationWriter failingNotificationWriter() {
    return new NotificationWriter() {
        @Override
        public void insert(Connection connection, NotificationDraft draft)
                throws SQLException {
            throw new SQLException("notification failed");
        }

        @Override
        public void insertBatch(Connection connection, List<NotificationDraft> drafts)
                throws SQLException {
            throw new SQLException("notification failed");
        }
    };
}

@Test
void notificationFailureRollsBackCommentAndCounter() throws SQLException {
    long postId = repository.createPost(1L,
            new CreatePost(1L, "事务测试", "正文"));
    ForumRepository failingRepository = new ForumRepository(
            connections, failingNotificationWriter());

    assertThrows(SQLException.class,
            () -> failingRepository.createComment(postId, 2L, "不会提交"));
    assertEquals(0, scalarInt("SELECT COUNT(*) FROM forum_comments"));
    assertEquals(0, scalarInt(
            "SELECT comment_count FROM forum_posts WHERE id = " + postId));
}
```

- [ ] **Step 7: Run rollback test and verify GREEN**

No new production change should be required if insertion occurs before `commit()`. Run `ForumRepositoryTest`; expected: pass and the rollback assertions remain zero.

- [ ] **Step 8: Suggested commit if Git is restored**

```bash
git add vcampus-server/src/main/java/com/vcampus/server/database/ForumRepository.java vcampus-server/src/test/java/com/vcampus/server/database/ForumRepositoryTest.java
git commit -m "feat(forum): notify authors about new comments"
```

---

### Task 4: Transactional notifications for all moderation actions

**Files:**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/ForumRepository.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/database/ForumRepositoryTest.java`

**Interfaces:**
- Consumes: `ForumNotificationFactory.postModerated(...)` and `commentModerated(...)`.
- Produces: moderation transactions that update state, write audit log, write at most one notification, and commit atomically.

- [ ] **Step 1: Add failing parameterized post moderation test**

For each action, prepare the required current state, apply the action as user 3, and assert one notification to author 1:

```java
@ParameterizedTest
@EnumSource(value = ForumModerationAction.class, names = {
        "HIDE", "RESTORE", "LOCK", "UNLOCK",
        "PIN", "UNPIN", "FEATURE", "UNFEATURE"})
void everySuccessfulPostModerationNotifiesAuthor(ForumModerationAction action)
        throws SQLException {
    long postId = preparePostFor(action);
    assertEquals(MutationResult.CHANGED,
            repository.moderatePost(postId, action, "审核原因", 3L));
    assertEquals("FORUM_POST_MODERATED", scalarString(
            "SELECT notification_type FROM notifications ORDER BY id DESC LIMIT 1"));
    assertEquals(postId, scalarLong(
            "SELECT related_entity_id FROM notifications ORDER BY id DESC LIMIT 1"));
}

private long preparePostFor(ForumModerationAction action) throws SQLException {
    long postId = repository.createPost(1L,
            new CreatePost(1L, "需要审核的帖子", "正文"));
    switch (action) {
        case RESTORE -> repository.moderatePost(
                postId, ForumModerationAction.HIDE, "准备隐藏状态", 3L);
        case UNLOCK -> repository.moderatePost(
                postId, ForumModerationAction.LOCK, "准备锁定状态", 3L);
        case UNPIN -> repository.moderatePost(
                postId, ForumModerationAction.PIN, "准备置顶状态", 3L);
        case UNFEATURE -> repository.moderatePost(
                postId, ForumModerationAction.FEATURE, "准备精华状态", 3L);
        default -> { }
    }
    executeUpdate("DELETE FROM notifications");
    return postId;
}
```

Add separate tests proving self-moderation and `UNCHANGED` create zero notifications.

- [ ] **Step 2: Add failing comment moderation tests**

Create a post by user 1 and comment by user 2, then hide and restore as user 3. Assert two `FORUM_COMMENT_MODERATED` notifications to user 2, both targeting the post ID. Add a self-moderation case with comment author 3 and expect zero.

- [ ] **Step 3: Run repository tests and verify RED**

Run `ForumRepositoryTest`. Expected: notification assertions fail because moderation only updates state and writes logs.

- [ ] **Step 4: Enrich locked state queries**

Change `PostModerationState` to include `authorUserId` and `title`. Change `CommentState` to include the comment author, post ID, post title, and status. Make their `SELECT ... FOR UPDATE` queries join `forum_posts` as needed. Add a `displayName(Connection,long)` helper for the operator.

- [ ] **Step 5: Write notifications before each moderation commit**

After state update and `insertLog(...)`, call the factory and writer:

```java
notificationFactory.postModerated(
        state.authorUserId(), operatorUserId, operatorName,
        postId, state.title(), action, reason)
        .ifPresent(draft -> insertNotification(connection, draft));
```

For comments, pass `state.postId()` as the related entity. Keep the existing early rollback returns before notification creation.

- [ ] **Step 6: Add and pass moderation rollback test**

Use a throwing `NotificationWriter`; after a moderation call fails, assert the post/comment status and moderation log count are unchanged. Run `ForumRepositoryTest`; expected: all tests pass.

- [ ] **Step 7: Suggested commit if Git is restored**

```bash
git add vcampus-server/src/main/java/com/vcampus/server/database/ForumRepository.java vcampus-server/src/test/java/com/vcampus/server/database/ForumRepositoryTest.java
git commit -m "feat(forum): notify authors about moderation"
```

---

### Task 5: Production dependency wiring and server regression

**Files:**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`

**Interfaces:**
- Consumes: `ForumRepository(ConnectionFactory, NotificationWriter)`.
- Produces: production forum repository using the same `NotificationRepository` instance as the message center.

- [ ] **Step 1: Add explicit production injection**

Replace:

```java
new ForumRepository(connections)
```

with:

```java
new ForumRepository(connections, notificationRepository)
```

- [ ] **Step 2: Compile and run server tests**

Run:

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-server' '-am' test
```

Expected: all common and server tests pass. Direct repository tests inject either `NotificationRepository` or an explicitly throwing writer; do not add a no-op production default.

- [ ] **Step 3: Suggested commit if Git is restored**

```bash
git add vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java
git commit -m "refactor(server): wire forum notification writer"
```

---

### Task 6: Forum source filter and typed notification destination

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/NotificationDestination.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/ui/NotificationForumUiTest.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/NotificationPanel.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/NotificationDetailDialog.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/ui/NotificationLibraryUiTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/ui/NotificationViewDataTest.java`

**Interfaces:**
- Produces: `record NotificationDestination(NotificationTarget target, Long relatedEntityId)`.
- Produces: `static NotificationDestination from(NotificationDetail detail)`.
- Produces: `boolean navigable()` where `FORUM_POST` requires a positive related ID, `NONE` is false, and existing targets remain true.
- Changes: notification UI callbacks to `Consumer<NotificationDestination>`.

- [ ] **Step 1: Add failing destination and forum UI tests**

```java
@Test
void forumDestinationPreservesPostId() {
    var destination = new NotificationDestination(
            NotificationTarget.FORUM_POST, 41L);
    assertTrue(destination.navigable());
    assertEquals(41L, destination.relatedEntityId());
}

@Test
void forumDestinationWithoutPostIdIsNotNavigable() {
    assertFalse(new NotificationDestination(
            NotificationTarget.FORUM_POST, null).navigable());
}

@Test
void messageCenterOffersForumSourceFilter() {
    NotificationPanel panel = new NotificationPanel(
            new VCampusClient("localhost", 1), "token", destination -> { }, () -> { });
    assertTrue(buttonLabels(panel).contains("论坛通知"));
}
```

Create a `NotificationDetail` with source `FORUM`, target `FORUM_POST`, related ID 41; build `NotificationDetailDialog`; assert the button label contains “查看帖子”.

- [ ] **Step 2: Run client tests and verify RED**

Run:

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-client' '-am' '-Dtest=NotificationForumUiTest,NotificationLibraryUiTest,NotificationViewDataTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: compilation failure because `NotificationDestination` and new enum switch branches are missing.

- [ ] **Step 3: Implement NotificationDestination**

```java
record NotificationDestination(NotificationTarget target, Long relatedEntityId) {
    NotificationDestination {
        Objects.requireNonNull(target, "target");
    }

    static NotificationDestination from(NotificationDetail detail) {
        return new NotificationDestination(detail.target(), detail.relatedEntityId());
    }

    boolean navigable() {
        if (target == NotificationTarget.NONE) return false;
        return target != NotificationTarget.FORUM_POST
                || relatedEntityId != null && relatedEntityId > 0;
    }
}
```

- [ ] **Step 4: Update message center filtering and labels**

Add:

```java
addSourceButton(sources, new JButton("论坛通知"), NotificationSource.FORUM);
```

Add `case FORUM -> "校园论坛"` in the detail source label switch. Add `case FORUM_POST -> "查看帖子"` in the action label switch, but only render the action when `NotificationDestination.from(detail).navigable()`.

- [ ] **Step 5: Change UI callback types without losing IDs**

Change both `NotificationPanel` and `NotificationDetailDialog` from `Consumer<NotificationTarget>` to `Consumer<NotificationDestination>`. On click:

```java
NotificationDestination destination = NotificationDestination.from(detail);
targetNavigator.accept(destination);
```

Update existing library UI tests to accept `destination -> { }` and assert `LIBRARY_LOANS` remains navigable.

- [ ] **Step 6: Run client notification tests and verify GREEN**

Run the Step 2 command. Expected: all selected tests pass.

- [ ] **Step 7: Suggested commit if Git is restored**

```bash
git add vcampus-client/src/main/java/com/vcampus/client/ui/NotificationDestination.java vcampus-client/src/main/java/com/vcampus/client/ui/NotificationPanel.java vcampus-client/src/main/java/com/vcampus/client/ui/NotificationDetailDialog.java vcampus-client/src/test/java/com/vcampus/client/ui
git commit -m "feat(notifications): add forum source and post destination"
```

---

### Task 7: Main-window forum deep link, docs, and full verification

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/MainFrame.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/ForumModulePanel.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/ForumPostDetailPanel.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/NotificationNavigationPolicy.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/ui/NotificationNavigationPolicyTest.java`
- Modify: `docs/requirements.md`
- Modify: `docs/message-center.md`
- Modify: `docs/superpowers/specs/2026-08-29-forum-notifications-design.md`

**Interfaces:**
- Consumes: `NotificationDestination` and existing `ForumModulePanel.openPost(long)`.
- Produces: `navigateFromNotification(NotificationDestination destination)` with a `FORUM_POST` branch.
- Produces: a readable inaccessible-post fallback that returns to forum home.

- [ ] **Step 1: Add failing navigation policy test**

Extract only the decision that is practical to test without constructing a `JFrame`:

```java
@Test
void forumNotificationRequiresPositivePostId() {
    assertEquals(41L, NotificationNavigationPolicy.forumPostId(
            new NotificationDestination(NotificationTarget.FORUM_POST, 41L)));
    assertThrows(IllegalArgumentException.class,
            () -> NotificationNavigationPolicy.forumPostId(
                    new NotificationDestination(NotificationTarget.FORUM_POST, null)));
}
```

Expected production API:

```java
final class NotificationNavigationPolicy {
    private NotificationNavigationPolicy() {
    }

    static long forumPostId(NotificationDestination destination) {
        if (destination.target() != NotificationTarget.FORUM_POST
                || destination.relatedEntityId() == null
                || destination.relatedEntityId() <= 0) {
            throw new IllegalArgumentException("论坛通知缺少帖子编号");
        }
        return destination.relatedEntityId();
    }
}
```

- [ ] **Step 2: Run navigation test and verify RED**

Run:

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-client' '-am' '-Dtest=NotificationNavigationPolicyTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: compilation failure because the policy is missing.

- [ ] **Step 3: Implement policy and update MainFrame callback**

Change notification panel construction to pass `this::navigateFromNotification` with the typed destination. Switch on `destination.target()` and preserve all existing branches. Add:

```java
case FORUM_POST -> {
    long postId = NotificationNavigationPolicy.forumPostId(destination);
    showForum().openPost(postId);
}
```

For the existing targets, ignore `relatedEntityId` exactly as before. `NONE` remains a no-op.

- [ ] **Step 4: Make inaccessible forum deep links readable**

In `ForumPostDetailPanel`, replace the generic failed-detail warning with “该帖子当前不可访问” and invoke the existing back action so the user lands on forum home. Both ordinary list navigation and notification navigation continue to call `ForumModulePanel.openPost(long)`; no second navigation path or client-side permission bypass is added.

- [ ] **Step 5: Run the complete client suite**

Run:

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-client' '-am' test
```

Expected: all common and client tests pass, including existing academic, student, library, account, and notification navigation tests.

- [ ] **Step 6: Update documentation status**

In `docs/requirements.md`, extend both the message-center and forum scope with forum notification linkage while retaining “待本机 MySQL 双客户端验收” until manual acceptance. In `docs/message-center.md`, document the three forum types, forum filter, post deep link, self-notification suppression, and migration 006. Change the design document status to “已实现，待本机 MySQL 双客户端验收” only after Step 7 succeeds.

- [ ] **Step 7: Run full verification and architecture checks**

Run:

```powershell
& $mvn $mavenRepoArg clean verify
rg -n "java\.sql|DriverManager|ConnectionFactory" vcampus-client/src
rg -n "FORUM_POST_COMMENTED|FORUM_POST_MODERATED|FORUM_COMMENT_MODERATED|FORUM_POST" database vcampus-common/src vcampus-server/src vcampus-client/src
```

Expected:

- Maven prints `BUILD SUCCESS` with zero failures.
- The client JDBC search returns no matches.
- Every forum notification enum appears in shared vocabulary, schema/migration, server production code, and tests.

- [ ] **Step 8: Manual MySQL dual-client acceptance**

Apply `database/migrations/006_forum_notifications.sql` and restart server/client processes. Use user A as post author, user B as commenter, and an administrator:

1. B comments on A's post; A receives one unread forum notification and “查看帖子” opens the correct post.
2. A comments on A's own post; no notification is created.
3. Administrator performs all eight post actions; A receives one notification per successful state change.
4. Administrator hides and restores B's comment; B receives both notifications with reasons.
5. Administrator operates their own content; no self-notification appears.
6. Repeat an unchanged action; no notification appears.
7. Hide/delete a post, then use an ordinary user's old notification; the client reports that the post is unavailable and returns to forum home.
8. Confirm source filtering, details, unread count, read state, sender, reason, and timestamps.

- [ ] **Step 9: Suggested final commit if Git is restored**

```bash
git add vcampus-client docs database vcampus-common vcampus-server
git commit -m "feat: connect forum activity to message center"
```
