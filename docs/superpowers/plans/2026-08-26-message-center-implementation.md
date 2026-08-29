# VCampus Message Center Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为所有正常登录用户增加可检索、可分页、可标记已读的站内消息中心，并让排课、成绩发布、学籍状态及账号安全变更在同一数据库事务中生成通知。

**Architecture:** 在 `vcampus-common` 固定消息枚举和五个协议动作；在 `vcampus-server` 由 `NotificationRepository` 同时承担用户消息查询与事务内通知写入。业务仓储接收同一个 `Connection` 上的 `NotificationWriter`，保证业务记录和通知一起提交或一起回滚；Swing 客户端通过后台请求加载消息，并由不可重入的 10 秒轮询器维护侧栏角标。

**Tech Stack:** Java 21、Swing、Socket、`MessageCodec`/`RowCodec`、JDBC、MySQL 8.0.44、H2 测试、JUnit 5、Maven。

**Spec:** `docs/superpowers/specs/2026-08-26-message-center-design.md`

## Global Constraints

- 保持 `MySQL -> 应用服务器 -> Swing 客户端` 三层结构，客户端不得连接数据库。
- 协议继续使用 `MessageCodec` 的长度前缀二进制格式，不使用 Java 原生序列化。
- 公共动作统一使用 `notification.` 前缀；读取方用户 ID 只能来自有效会话。
- 强制改密会话不得访问消息中心。
- 业务变更与通知写入必须共用同一 JDBC 事务；通知失败必须回滚业务。
- 首版每页固定 10 条，排序为 `created_at DESC, id DESC`。
- Swing 组件只在 EDT 更新，网络和数据库操作不得阻塞 EDT。
- 未读角标登录后立即读取，此后每 10 秒读取；请求不得重叠，窗口退出后停止。
- 首版不实现推送、私聊、删除、归档、订阅设置、附件或任意 URL 跳转。
- 当前目录没有 Git 元数据；以下“保存检查点”只记录建议提交内容，不执行 `git commit`。若之后初始化 Git，可使用给出的提交信息。

---

### Task 1: Database schema and common notification vocabulary

**Files:**
- Modify: `database/schema.sql`
- Create: `database/migrations/002_notifications.sql`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/NotificationType.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/NotificationSource.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/NotificationTarget.java`
- Create: `vcampus-common/src/test/java/com/vcampus/common/model/NotificationVocabularyTest.java`

**Interfaces:**
- Produces: `NotificationType`, `NotificationSource`, `NotificationTarget` enums using the exact names from the spec.
- Produces: `Actions.NOTIFICATION_SEARCH`, `NOTIFICATION_GET`, `NOTIFICATION_UNREAD_COUNT`, `NOTIFICATION_MARK_READ`, `NOTIFICATION_MARK_ALL_READ`.
- Produces: MySQL table `notifications` and index `idx_notification_recipient_unread_created`.

- [ ] **Step 1: Write the failing vocabulary test**

```java
class NotificationVocabularyTest {
    @Test
    void exposesStableProtocolValues() {
        assertEquals("GRADE_PUBLISHED", NotificationType.GRADE_PUBLISHED.name());
        assertEquals("ACCOUNT_SECURITY", NotificationSource.ACCOUNT_SECURITY.name());
        assertEquals("STUDENT_GRADES", NotificationTarget.STUDENT_GRADES.name());
        assertEquals("notification.search", Actions.NOTIFICATION_SEARCH);
        assertEquals("notification.markAllRead", Actions.NOTIFICATION_MARK_ALL_READ);
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails to compile**

Run: `mvn -pl vcampus-common -am -Dtest=NotificationVocabularyTest test`

Expected: compilation fails because the notification enums/constants do not exist.

- [ ] **Step 3: Add exact enum values and action constants**

```java
public enum NotificationType {
    SCHEDULE_ASSIGNED, GRADE_PUBLISHED, STUDENT_STATUS_CHANGED,
    ROLES_CHANGED, ACCOUNT_ENABLED, ACCOUNT_DISABLED, PASSWORD_RESET
}

public enum NotificationSource {
    ACADEMIC, STUDENT_STATUS, ACCOUNT_SECURITY
}

public enum NotificationTarget {
    TEACHER_SCHEDULE, STUDENT_GRADES, STUDENT_PROFILE, NONE
}
```

Add the five action strings to `Actions` without changing existing values.

- [ ] **Step 4: Add the schema and idempotent migration**

Add this table after `users` exists and before seed data depends on it:

```sql
CREATE TABLE IF NOT EXISTS notifications (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    recipient_user_id BIGINT NOT NULL,
    sender_user_id BIGINT NULL,
    notification_type VARCHAR(40) NOT NULL,
    source_module VARCHAR(40) NOT NULL,
    title VARCHAR(160) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    target VARCHAR(40) NOT NULL DEFAULT 'NONE',
    related_entity_id BIGINT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_notification_recipient_unread_created
        (recipient_user_id, is_read, created_at),
    CONSTRAINT fk_notification_recipient
        FOREIGN KEY (recipient_user_id) REFERENCES users(id),
    CONSTRAINT fk_notification_sender
        FOREIGN KEY (sender_user_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_notification_type CHECK (notification_type IN
        ('SCHEDULE_ASSIGNED','GRADE_PUBLISHED','STUDENT_STATUS_CHANGED',
         'ROLES_CHANGED','ACCOUNT_ENABLED','ACCOUNT_DISABLED','PASSWORD_RESET')),
    CONSTRAINT chk_notification_source CHECK (source_module IN
        ('ACADEMIC','STUDENT_STATUS','ACCOUNT_SECURITY')),
    CONSTRAINT chk_notification_target CHECK (target IN
        ('TEACHER_SCHEDULE','STUDENT_GRADES','STUDENT_PROFILE','NONE'))
);
```

`002_notifications.sql` contains the same `CREATE TABLE IF NOT EXISTS` statement so an existing course database can upgrade without re-running all schema creation.

- [ ] **Step 5: Run common tests**

Run: `mvn -pl vcampus-common -am test`

Expected: all common tests pass.

- [ ] **Step 6: Save checkpoint**

Suggested commit: `feat(notification): add schema and shared vocabulary`

---

### Task 2: Notification repository, ownership rules, filtering, and transactional writer

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/model/NotificationRecord.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/NotificationStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/NotificationWriter.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/NotificationRepository.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/NotificationRepositoryTest.java`

**Interfaces:**
- Produces: `NotificationWriter.insert(Connection, NotificationDraft)` and `insertBatch(Connection, List<NotificationDraft>)`.
- Produces: `NotificationStore.search(long, NotificationQuery)`, `findOwned(long,long)`, `unreadCount(long)`, `markRead(long,long)`, and `markAllRead(long)`.
- Produces: immutable records `NotificationDraft`, `NotificationQuery`, and `NotificationPage` nested in their owning interfaces.

- [ ] **Step 1: Write failing H2 repository tests**

Create an H2 MySQL-mode schema containing `users` and `notifications`, seed users 1 and 2, then test:

```java
@Test
void isolatesRecipientsAndUsesStableNewestFirstPaging() throws Exception {
    insert(1L, "第一条", NotificationSource.ACADEMIC, false, "2026-08-26 08:00:00");
    insert(2L, "他人的", NotificationSource.ACADEMIC, false, "2026-08-26 09:00:00");
    insert(1L, "第二条", NotificationSource.ACCOUNT_SECURITY, true, "2026-08-26 10:00:00");

    NotificationPage page = repository.search(1L,
            new NotificationQuery("条", null, null, 1, 10));

    assertEquals(2, page.total());
    assertEquals(List.of("第二条", "第一条"),
            page.rows().stream().map(NotificationRecord::title).toList());
}

@Test
void cannotReadOrMarkAnotherUsersMessage() throws Exception {
    long id = insertForUser(2L);
    assertTrue(repository.findOwned(1L, id).isEmpty());
    assertFalse(repository.markRead(1L, id));
    assertEquals(1, repository.unreadCount(2L));
}

@Test
void readOperationsAreIdempotent() throws Exception {
    long id = insertForUser(1L);
    assertTrue(repository.markRead(1L, id));
    assertTrue(repository.markRead(1L, id));
    assertEquals(0, repository.unreadCount(1L));
    assertEquals(0, repository.markAllRead(1L));
}
```

Also cover keyword escaping, source filter, read filter, page minimum validation, and identical timestamps ordered by descending ID.

- [ ] **Step 2: Run repository tests and confirm failure**

Run: `mvn -pl vcampus-server -am -Dtest=NotificationRepositoryTest test`

Expected: compilation fails because notification repository types do not exist.

- [ ] **Step 3: Define focused records and interfaces**

```java
public record NotificationRecord(
        long id, long recipientUserId, Long senderUserId,
        NotificationType type, NotificationSource source,
        String title, String content, NotificationTarget target,
        Long relatedEntityId, boolean read, Instant readAt, Instant createdAt) {}

public interface NotificationWriter {
    void insert(Connection connection, NotificationDraft draft) throws SQLException;
    void insertBatch(Connection connection, List<NotificationDraft> drafts) throws SQLException;

    record NotificationDraft(
            long recipientUserId, Long senderUserId, NotificationType type,
            NotificationSource source, String title, String content,
            NotificationTarget target, Long relatedEntityId) {}
}

public interface NotificationStore {
    NotificationPage search(long recipientUserId, NotificationQuery query) throws SQLException;
    Optional<NotificationRecord> findOwned(long recipientUserId, long notificationId) throws SQLException;
    int unreadCount(long recipientUserId) throws SQLException;
    boolean markRead(long recipientUserId, long notificationId) throws SQLException;
    int markAllRead(long recipientUserId) throws SQLException;

    record NotificationQuery(String keyword, NotificationSource source,
            Boolean read, int page, int pageSize) {}
    record NotificationPage(List<NotificationRecord> rows,
            int page, int pageSize, int total) {
        public NotificationPage { rows = List.copyOf(rows); }
    }
}
```

- [ ] **Step 4: Implement parameterized JDBC queries**

`NotificationRepository` implements both interfaces. The write SQL must use the caller-provided `Connection` and must never commit it. Search SQL always starts with `recipient_user_id = ?`, appends parameterized filters, uses `LIKE ? ESCAPE '\\'`, then `ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?`.

`markRead` first verifies ownership in one `UPDATE`:

```sql
UPDATE notifications
SET is_read = TRUE, read_at = COALESCE(read_at, CURRENT_TIMESTAMP)
WHERE id = ? AND recipient_user_id = ?
```

It returns true when the row exists even if already read; use a following ownership query when the driver reports zero affected rows.

- [ ] **Step 5: Run repository tests**

Run: `mvn -pl vcampus-server -am -Dtest=NotificationRepositoryTest test`

Expected: all repository tests pass.

- [ ] **Step 6: Save checkpoint**

Suggested commit: `feat(notification): add secure notification repository`

---

### Task 3: Notification service, protocol routing, and forced-password gate

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/service/NotificationService.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/service/NotificationServiceTest.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterTest.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`

**Interfaces:**
- Consumes: `NotificationStore` from Task 2 and current `SessionManager.UserSession.userId()`.
- Produces: service methods `search`, `get`, `unreadCount`, `markRead`, `markAllRead`, each accepting `RequestMessage` and returning `ResponseMessage`.
- Produces: list row encoding order `[id,type,source,title,summary,target,relatedEntityId,isRead,createdAt]`.

- [ ] **Step 1: Write failing service permission and encoding tests**

Use a fake `NotificationStore` that records its recipient argument:

```java
@Test
void alwaysUsesSessionUserAndNeverAcceptsRecipientParameter() {
    ResponseMessage response = service.search(request(user1Token,
            Map.of("recipientUserId", "2", "page", "1")));
    assertTrue(response.success());
    assertEquals(1L, store.lastRecipientUserId);
}

@Test
void hidesForeignAndMissingMessagesBehindSameResponse() {
    ResponseMessage response = service.get(request(user1Token,
            Map.of("notificationId", "99")));
    assertFalse(response.success());
    assertEquals("消息不存在", response.message());
}
```

Also verify page size is always 10, invalid enum/page/ID input yields readable failure, detail returns full content, and repeated mark-read succeeds.

- [ ] **Step 2: Run service tests and confirm failure**

Run: `mvn -pl vcampus-server -am -Dtest=NotificationServiceTest test`

Expected: compilation fails because `NotificationService` does not exist.

- [ ] **Step 3: Implement session-bound service methods**

`NotificationService` validates the token with `sessions.find(token)`, derives `userId` exclusively from the returned session, parses optional `source` and `read`, and encodes rows with `RowCodec`. A list summary is produced server-side with a maximum of 80 Unicode code units plus `…`; details return `content`, `target`, and `relatedEntityId` separately.

- [ ] **Step 4: Route the five actions**

Extend the router constructor with `NotificationService notificationService`, then add:

```java
case Actions.NOTIFICATION_SEARCH -> notificationService.search(request);
case Actions.NOTIFICATION_GET -> notificationService.get(request);
case Actions.NOTIFICATION_UNREAD_COUNT -> notificationService.unreadCount(request);
case Actions.NOTIFICATION_MARK_READ -> notificationService.markRead(request);
case Actions.NOTIFICATION_MARK_ALL_READ -> notificationService.markAllRead(request);
```

Do not add notification actions to `ForcedPasswordAccessPolicy`; the existing pre-switch gate must reject them. Update router tests to construct a fake notification store and verify a forced-password session receives `请先修改初始密码` before the notification service is called.

- [ ] **Step 5: Wire one repository instance into the server**

```java
NotificationRepository notifications = new NotificationRepository(connections);
NotificationService notificationService = new NotificationService(notifications, sessionManager);
```

Pass the same `notifications` instance to later business repositories as `NotificationWriter`, and pass `notificationService` to `RequestRouter`.

- [ ] **Step 6: Run focused server tests**

Run: `mvn -pl vcampus-server -am -Dtest=NotificationServiceTest,RequestRouterTest test`

Expected: all focused tests pass.

- [ ] **Step 7: Save checkpoint**

Suggested commit: `feat(notification): expose session-scoped notification API`

---

### Task 4: Academic notifications in the existing section and grade transactions

**Files:**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/AcademicRepository.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/AcademicService.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/AcademicNotificationTransactionTest.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`

**Interfaces:**
- Consumes: `NotificationWriter` and `NotificationDraft` from Task 2.
- Changes: `AcademicRepository(ConnectionFactory, NotificationWriter)`.
- Changes: `createSection(CreateSection, long operatorUserId, String operatorDisplayName)` and `publishGrades(long sectionId, long operatorUserId)`.
- Produces: one `SCHEDULE_ASSIGNED` draft per successful section creation and one `GRADE_PUBLISHED` draft per `ENROLLED` student during first publication.

- [ ] **Step 1: Write failing transaction tests**

Build the minimal H2 academic schema and a `RecordingNotificationWriter`. Verify:

```java
@Test
void sectionCreationNotifiesAssignedTeacherBeforeCommit() throws Exception {
    long id = repository.createSection(command(), 1L, "系统管理员");
    NotificationDraft draft = writer.single();
    assertEquals(teacherUserId, draft.recipientUserId());
    assertEquals(NotificationType.SCHEDULE_ASSIGNED, draft.type());
    assertEquals(NotificationTarget.TEACHER_SCHEDULE, draft.target());
    assertEquals(id, draft.relatedEntityId());
    assertTrue(draft.content().contains("系统管理员"));
}

@Test
void notificationFailureRollsBackSectionAndSchedules() {
    writer.failWith(new SQLException("notification failed"));
    assertThrows(SQLException.class,
            () -> repository.createSection(command(), 1L, "系统管理员"));
    assertEquals(0, count("course_sections"));
    assertEquals(0, count("class_schedules"));
}
```

For grade publication seed two `ENROLLED` students and one `DROPPED` student. Assert two drafts, correct teacher/course content and `STUDENT_GRADES` target; a second publication must throw the existing business-rule error and insert nothing. Also force writer failure and assert `grades_published` and `published_at` remain unchanged.

- [ ] **Step 2: Run the academic notification tests and confirm failure**

Run: `mvn -pl vcampus-server -am -Dtest=AcademicNotificationTransactionTest test`

Expected: tests fail because the repository does not accept a writer/operator context.

- [ ] **Step 3: Insert schedule notification inside `createSection` transaction**

After the section and all schedule rows are inserted, before `connection.commit()`, call:

```java
notifications.insert(connection, new NotificationDraft(
        command.teacherUserId(), operatorUserId,
        NotificationType.SCHEDULE_ASSIGNED, NotificationSource.ACADEMIC,
        "课表安排通知",
        operatorDisplayName + "已为您安排《" + courseName + "》教学班，请查看教师课表。",
        NotificationTarget.TEACHER_SCHEDULE, sectionId));
```

Load `courseName` with the same transaction connection. Preserve current schedule conflict validation and rollback behavior.

- [ ] **Step 4: Batch grade notifications inside `publishGrades` transaction**

After grades receive `published_at` and the section becomes completed, query course name, teacher display name, and all `course_enrollments.status = 'ENROLLED'` student `user_id` values. Create immutable drafts and call `insertBatch(connection, drafts)` once before commit. Do not notify on `saveGrade`.

- [ ] **Step 5: Pass operator context from `AcademicService`**

Use the authenticated session already checked by the service:

```java
repository.createSection(command, session.userId(), session.displayName());
repository.publishGrades(sectionId, session.userId());
```

Update existing tests and server construction for the new constructor/signatures.

- [ ] **Step 6: Run academic and full server tests**

Run: `mvn -pl vcampus-server -am -Dtest=AcademicNotificationTransactionTest test`

Run: `mvn -pl vcampus-server -am test`

Expected: all server tests pass.

- [ ] **Step 7: Save checkpoint**

Suggested commit: `feat(academic): notify teachers and students transactionally`

---

### Task 5: Student-status notification in the existing status transaction

**Files:**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/StudentRepository.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/StudentService.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/StudentStatusNotificationTest.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`

**Interfaces:**
- Changes: `StudentRepository(ConnectionFactory, NotificationWriter)`.
- Changes: `changeStatus(long studentId, StudentStatus newStatus, String reason, long operatorUserId, String operatorDisplayName)`.
- Produces: one `STUDENT_STATUS_CHANGED` notification targeted at `STUDENT_PROFILE` only when old and new status differ.

- [ ] **Step 1: Write failing status notification tests**

```java
@Test
void realStatusChangeIncludesOldNewReasonAndRecipient() throws Exception {
    StudentStatus old = repository.changeStatus(
            studentId, StudentStatus.SUSPENDED, "个人申请", 900L, "李老师");
    assertEquals(StudentStatus.ENROLLED, old);
    NotificationDraft draft = writer.single();
    assertEquals(studentUserId, draft.recipientUserId());
    assertEquals(NotificationType.STUDENT_STATUS_CHANGED, draft.type());
    assertEquals(NotificationTarget.STUDENT_PROFILE, draft.target());
    assertTrue(draft.content().contains("在读"));
    assertTrue(draft.content().contains("休学"));
    assertTrue(draft.content().contains("个人申请"));
}
```

Add tests proving an unchanged status generates no history and no notification, and writer failure rolls back both `student_profiles.status` and `student_status_history`.

- [ ] **Step 2: Run the tests and confirm failure**

Run: `mvn -pl vcampus-server -am -Dtest=StudentStatusNotificationTest test`

Expected: failure because no transactional writer is connected.

- [ ] **Step 3: Write the notification before commit**

Lock the student row, read both `status` and `user_id`, return the old status without updates when unchanged, otherwise update status, insert history, and insert this notification on the same connection:

```java
new NotificationDraft(studentUserId, operatorUserId,
        NotificationType.STUDENT_STATUS_CHANGED,
        NotificationSource.STUDENT_STATUS,
        "学籍状态变更",
        operatorDisplayName + "已将您的学籍状态由“" + oldStatus.displayName()
                + "”变更为“" + newStatus.displayName() + "”。原因：" + reason + "。",
        NotificationTarget.STUDENT_PROFILE, studentId)
```

If `StudentStatus` has no `displayName()`, add a private exhaustive switch in `StudentRepository`; do not expose database enum codes in Chinese messages.

- [ ] **Step 4: Pass operator display name from the service and wire repository**

Update `StudentService.changeStatus` to use `session.userId()` and `session.displayName()`. Update server construction and focused fakes.

- [ ] **Step 5: Run status and server tests**

Run: `mvn -pl vcampus-server -am -Dtest=StudentStatusNotificationTest test`

Run: `mvn -pl vcampus-server -am test`

Expected: all tests pass.

- [ ] **Step 6: Save checkpoint**

Suggested commit: `feat(student): notify students of status changes`

---

### Task 6: Account security notifications and no-op-safe mutations

**Files:**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/AccountStore.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/AccountRepository.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/AccountService.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/database/AccountRepositoryTest.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/AccountServiceTest.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterTest.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`

**Interfaces:**
- Adds: `AccountStore.MutationResult { NOT_FOUND, UNCHANGED, CHANGED }`.
- Changes: account mutation methods receive `operatorUserId` and `operatorDisplayName` and return `MutationResult`.
- Produces: `ROLES_CHANGED`, `ACCOUNT_ENABLED`, `ACCOUNT_DISABLED`, and `PASSWORD_RESET` notifications with target `NONE`.

- [ ] **Step 1: Extend failing account repository tests**

Update the H2 schema to include `notifications`, construct the repository with a real or recording writer, then verify:

```java
assertEquals(MutationResult.CHANGED,
        repository.replaceAdministrativeRoles(userId, UserRole.TEACHER,
                Set.of(UserRole.BANK_ADMIN), 900L, "系统管理员"));
assertEquals("ROLES_CHANGED", lastNotificationType(userId));
assertTrue(lastNotificationContent(userId).contains("新增：银行管理员"));

assertEquals(MutationResult.UNCHANGED,
        repository.replaceAdministrativeRoles(userId, UserRole.TEACHER,
                Set.of(UserRole.BANK_ADMIN), 900L, "系统管理员"));
assertEquals(1, notificationCount(userId));
```

Test enabled -> disabled, repeated disabled, disabled -> enabled, password reset without any password/hash/salt in content, missing account, and writer failure rolling back each account mutation.

- [ ] **Step 2: Run account tests and confirm failure**

Run: `mvn -pl vcampus-server -am -Dtest=AccountRepositoryTest,AccountServiceTest test`

Expected: compilation/test failures until the interface and implementation change together.

- [ ] **Step 3: Implement atomic mutation results and notification copy**

For roles, lock the target account and current roles, calculate sorted `added` and `removed` sets, return `UNCHANGED` when both are empty, otherwise replace roles and insert a `ROLES_CHANGED` notification before commit. Use Chinese role labels from an exhaustive helper.

For enablement, lock/read current `enabled`, return `UNCHANGED` for the same value, otherwise update and insert either `ACCOUNT_ENABLED` or `ACCOUNT_DISABLED`.

For reset, update hash/salt/forced-change and always insert `PASSWORD_RESET`; content must only state that an administrator reset the password and that a temporary password must be changed after login.

- [ ] **Step 4: Preserve service semantics and session invalidation**

`AccountService` maps `NOT_FOUND` to the current “账号不存在” failure. Both `UNCHANGED` and `CHANGED` return success, but invalidate target sessions only for `CHANGED`; reset always returns `CHANGED`. This prevents unnecessary logout and duplicate notifications for no-op role/state requests.

- [ ] **Step 5: Run focused and full server tests**

Run: `mvn -pl vcampus-server -am -Dtest=AccountRepositoryTest,AccountServiceTest,RequestRouterTest test`

Run: `mvn -pl vcampus-server -am test`

Expected: all tests pass; no response contains password material.

- [ ] **Step 6: Save checkpoint**

Suggested commit: `feat(account): add transactional security notifications`

---

### Task 7: Client protocol adapter and parsing model

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/NotificationViewData.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/UnreadBadgeFormatter.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/ui/NotificationViewDataTest.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/ui/UnreadBadgeFormatterTest.java`

**Interfaces:**
- Produces client calls: `searchNotifications`, `getNotification`, `unreadNotificationCount`, `markNotificationRead`, `markAllNotificationsRead`.
- Produces: `NotificationViewData.NotificationPage.parse(ResponseMessage)` and `NotificationDetail.parse(ResponseMessage)`.
- Produces: `UnreadBadgeFormatter.format(int)` returning `""`, exact `1..99`, or `"99+"`.

- [ ] **Step 1: Write failing parser and badge tests**

```java
@ParameterizedTest
@CsvSource({"0,''", "1,1", "99,99", "100,99+", "999,99+"})
void formatsUnreadBadge(int count, String expected) {
    assertEquals(expected, UnreadBadgeFormatter.format(count));
}

@Test
void parsesListRowsAndRejectsMalformedRows() {
    ResponseMessage response = success(Map.of(
            "page", "1", "pageSize", "10", "total", "1", "count", "1",
            "row.0", RowCodec.encode(List.of("7", "GRADE_PUBLISHED", "ACADEMIC",
                    "成绩发布", "最终成绩已发布", "STUDENT_GRADES", "31",
                    "false", "2026-08-26T10:00:00Z"))));
    assertEquals(7L, NotificationPage.parse(response).rows().getFirst().id());
    assertThrows(IllegalArgumentException.class,
            () -> NotificationPage.parse(success(Map.of("count", "1", "row.0", "broken"))));
}
```

- [ ] **Step 2: Run client tests and confirm failure**

Run: `mvn -pl vcampus-client -am -Dtest=NotificationViewDataTest,UnreadBadgeFormatterTest test`

Expected: compilation fails because parser/formatter do not exist.

- [ ] **Step 3: Add authorized client calls**

```java
public ResponseMessage searchNotifications(String token, String keyword,
        NotificationSource source, Boolean read, int page) throws IOException
public ResponseMessage getNotification(String token, long notificationId) throws IOException
public ResponseMessage unreadNotificationCount(String token) throws IOException
public ResponseMessage markNotificationRead(String token, long notificationId) throws IOException
public ResponseMessage markAllNotificationsRead(String token) throws IOException
```

Each delegates to `sendAuthorized`; omit optional filters rather than sending the string `null`.

- [ ] **Step 4: Implement strict immutable parsing**

Use enum `valueOf`, `Long.parseLong`, `Boolean.parseBoolean`, and `Instant.parse`. Convert blank `relatedEntityId` to `null`. Reject missing rows, wrong column count, negative counts, and unsuccessful responses with a readable `IllegalArgumentException(response.message())`.

- [ ] **Step 5: Run all client tests**

Run: `mvn -pl vcampus-client -am test`

Expected: all client tests pass.

- [ ] **Step 6: Save checkpoint**

Suggested commit: `feat(client): add notification protocol models`

---

### Task 8: Desktop message list, modal detail, and fixed-target navigation

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/NotificationPanel.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/NotificationCard.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/NotificationDetailDialog.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/NotificationFilterPolicy.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/ui/NotificationFilterPolicyTest.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/AcademicFrame.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/AcademicPanel.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/MainFrame.java`

**Interfaces:**
- Produces: `NotificationPanel(VCampusClient, String, Consumer<NotificationTarget>, Runnable unreadChanged)`.
- Produces: `activate()` to load page 1 and `refreshCurrentPage()` to preserve filters.
- Produces: `AcademicFrame.openTeacherSchedule()` and `openStudentGrades()` as fixed internal navigation entry points.

- [ ] **Step 1: Write failing pure UI policy tests**

```java
@Test
void resetsToFirstPageWhenSearchOrFilterChanges() {
    NotificationFilterPolicy policy = new NotificationFilterPolicy();
    policy.goToPage(4);
    policy.changeKeyword("成绩");
    assertEquals(1, policy.page());
}

@Test
void clampsNavigationToAvailablePages() {
    NotificationFilterPolicy policy = new NotificationFilterPolicy();
    policy.applyTotal(0, 10);
    assertFalse(policy.canGoPrevious());
    assertFalse(policy.canGoNext());
}
```

- [ ] **Step 2: Run policy test and confirm failure**

Run: `mvn -pl vcampus-client -am -Dtest=NotificationFilterPolicyTest test`

Expected: compilation fails because the policy does not exist.

- [ ] **Step 3: Implement the filter/page policy and list panel**

The panel layout is:

```text
全部消息                         [搜索框] [已读筛选] [查询]
[全部消息] [教务通知] [学籍通知] [账号安全]       [一键已读]
----------------------------------------------------------------
[未读点] [来源/标题] [正文摘要]                         [时间]
...最多 10 张纵向卡片，放入 JScrollPane...
----------------------------------------------------------------
[上一页] 第 N 页 / 共 M 页 [下一页]
```

All data requests run with `CompletableFuture.supplyAsync`. Disable only the initiating control while loading; marshal success/error UI changes through `SwingUtilities.invokeLater`. Search/filter changes reset page to 1. Empty results display `暂无消息`.

- [ ] **Step 4: Implement card and detail behavior**

`NotificationCard` renders an unread red dot, source label, bold title, 80-character summary, and local-zone time. Clicking calls `getNotification`, opens `NotificationDetailDialog`, then calls `markNotificationRead`. On mark success, update the card and invoke `unreadChanged.run()`; on failure retain unread state and show a readable message.

The modal dialog displays full content and only one optional action button chosen by exhaustive target switch:

```java
case TEACHER_SCHEDULE -> "查看教师课表";
case STUDENT_GRADES -> "查看我的成绩";
case STUDENT_PROFILE -> "查看学籍信息";
case NONE -> null;
```

No server-provided path, class name, or URL is executed.

- [ ] **Step 5: Implement mark-all-read and fixed navigation**

After successful `markAllNotificationsRead`, refresh the current page and badge immediately. Add explicit `AcademicPanel` tab-selection methods and expose them through `AcademicFrame`; `MainFrame` maps `TEACHER_SCHEDULE` and `STUDENT_GRADES` to a new `AcademicFrame`, while `STUDENT_PROFILE` opens `StudentFrame`. If the current role cannot open a target, show `该页面当前不可用` without losing the message detail.

- [ ] **Step 6: Add the message card to `MainFrame` CardLayout**

Create one `NotificationPanel`, add it as `"notifications"`, give the existing “消息中心” button a real listener, and call `notificationPanel.activate()` when selected. Preserve existing workspace and account navigation styling.

- [ ] **Step 7: Run client tests and manual Swing smoke test**

Run: `mvn -pl vcampus-client -am test`

Manual check: open the client, enter message center, search/filter/page, open a detail, verify the modal and fixed target button, then use 一键已读. Confirm no UI freeze while the server is stopped.

- [ ] **Step 8: Save checkpoint**

Suggested commit: `feat(ui): add desktop message center and detail dialog`

---

### Task 9: Non-overlapping 10-second unread polling and lifecycle cleanup

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/UnreadNotificationPoller.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/ui/UnreadNotificationPollerTest.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/MainFrame.java`

**Interfaces:**
- Produces: `UnreadNotificationPoller(Supplier<CompletableFuture<Integer>>, IntConsumer, Consumer<Throwable>, Duration)`.
- Produces lifecycle methods `start()`, `refreshNow()`, and `close()`.
- Guarantees at most one request in flight and no callback after close.

- [ ] **Step 1: Write failing deterministic poller tests**

Inject a controllable `ScheduledExecutorService` or package-private scheduler constructor. Test:

```java
@Test
void skipsTickWhileRequestIsStillRunning() {
    CompletableFuture<Integer> blocked = new CompletableFuture<>();
    FakeUnreadSource source = new FakeUnreadSource(blocked);
    poller.start();
    scheduler.runNext();
    scheduler.runNext();
    assertEquals(1, source.calls());
}

@Test
void closeStopsFutureRequestsAndCallbacks() {
    poller.start();
    poller.close();
    scheduler.runAll();
    assertEquals(0, source.calls());
}
```

Also verify failure leaves the last displayed count untouched and schedules a retry, and `refreshNow()` coalesces with an in-flight request.

- [ ] **Step 2: Run poller tests and confirm failure**

Run: `mvn -pl vcampus-client -am -Dtest=UnreadNotificationPollerTest test`

Expected: compilation fails because the poller does not exist.

- [ ] **Step 3: Implement the poller**

Use a single-thread scheduled executor and an `AtomicBoolean inFlight`. The supplier starts the client request on the existing background executor and returns its `CompletableFuture<Integer>`; completion clears `inFlight`. `start()` invokes `refreshNow()` immediately and schedules fixed-delay checks every `Duration.ofSeconds(10)`. Poll errors call the supplied error consumer, which `MainFrame` configures as silent logging only. `close()` sets a closed flag and calls `shutdownNow()`.

- [ ] **Step 4: Render the sidebar badge**

Keep a reference to the message navigation button and place its label plus a small opaque red badge in a dedicated transparent navigation component. Apply `UnreadBadgeFormatter`; hide for zero, display `1..99`, and `99+` above 99. Update it only on EDT.

- [ ] **Step 5: Connect lifecycle events**

Start the poller after `MainFrame` construction. Call `refreshNow()` after single/all-read operations. Call `close()` before logout disposal and from a `WindowListener.windowClosed` hook so clicking the title-bar close also stops polling. A failed poll must neither show a dialog nor replace the previous count.

- [ ] **Step 6: Run tests and a timed manual check**

Run: `mvn -pl vcampus-client -am test`

Manual check: create an unread database message from another client, wait at most 10 seconds, verify the badge changes, then close/logout and verify no more network requests are logged.

- [ ] **Step 7: Save checkpoint**

Suggested commit: `feat(ui): poll and display unread notification badge`

---

### Task 10: Documentation, migration verification, and end-to-end acceptance

**Files:**
- Modify: `docs/requirements.md`
- Create: `docs/message-center.md`
- Modify: `README.md`
- Verify: `database/schema.sql`
- Verify: `database/migrations/002_notifications.sql`

**Interfaces:**
- Consumes all previous tasks.
- Produces operator/developer instructions for schema upgrade, server/client launch, behavior matrix, and future module integration through `NotificationWriter`.

- [ ] **Step 1: Update requirement status and operating guide**

Mark the message-center foundation complete in `docs/requirements.md`. In `docs/message-center.md`, document:

```text
事件 -> 接收者 -> 类型 -> 跳转目标
教学班创建 -> 任课教师 -> SCHEDULE_ASSIGNED -> TEACHER_SCHEDULE
最终成绩发布 -> 在选学生 -> GRADE_PUBLISHED -> STUDENT_GRADES
学籍状态变更 -> 对应学生 -> STUDENT_STATUS_CHANGED -> STUDENT_PROFILE
角色/启停/重置 -> 目标账号 -> 对应安全类型 -> NONE
```

Include the 10-second polling behavior, no-delete limitation, privacy boundary, migration command/process, and the rule that future library/shop/bank/forum code inserts notifications inside its own transaction.

- [ ] **Step 2: Run the full clean build**

Run: `mvn clean verify`

Expected: reactor reports `BUILD SUCCESS` for parent, common, server, and client.

- [ ] **Step 3: Apply migration to a disposable MySQL database**

Run the project’s normal MySQL 8.0.44 import flow against a disposable schema, first with `schema.sql`, then separately test upgrading an existing schema with `002_notifications.sql`.

Expected: table, foreign keys, check constraints, and `idx_notification_recipient_unread_created` exist; running the migration a second time does not destroy data.

- [ ] **Step 4: Perform two-client acceptance flow**

1. Start `com.vcampus.server.ServerMain`.
2. Start two `com.vcampus.client.ClientMain` instances.
3. Super admin creates a teaching section; assigned teacher sees a badge within 10 seconds and opens teacher schedule from detail.
4. Teacher publishes final grades; every `ENROLLED` student receives exactly one message and a `DROPPED` student receives none.
5. Student administrator changes status; student sees old/new status and reason and opens profile.
6. Super admin changes roles, disables/re-enables, and resets a password; target sees the security messages after valid login, with no secret included.
7. Verify keyword/source/read filtering, stable pagination, single read, mark all read, `99+`, silent retry when server stops, and poll stop at logout.
8. Attempt a handcrafted `notification.get` for another user’s ID; expect `消息不存在`.

- [ ] **Step 5: Inspect secrets and generated artifacts**

Run: `rg -n "temporaryPassword|password_hash|password_salt|jdbc:mysql://.*:[^/]+@" docs vcampus-* database`

Expected: only field names, validation code, and safe documentation examples appear; no real password, hash, salt, token, or personal data is introduced. Do not add `target/` artifacts to delivery changes.

- [ ] **Step 6: Save final checkpoint**

Suggested commit: `docs(notification): document message center and acceptance`
