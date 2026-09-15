# Forum Bank Boost Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an atomic paid “助力推荐” flow in which eligible non-admin users spend 5/10/15 yuan to add decaying heat to another user’s forum post, with every successful charge recorded in the bank ledger and the message center’s bank area.

**Architecture:** Keep forum policy and orchestration in the server forum layer, expose only fixed tiers through shared protocol types, and reuse `BankRepository` through a caller-owned JDBC transaction. Persist the boost, debit ledger, and bank notification on the same connection; read active boost aggregates in forum feed queries while keeping `LATEST` chronological and admin pins dominant.

**Tech Stack:** Java 21, Maven multi-module build, JUnit 5, H2 in MySQL mode, JDBC/MySQL, length-prefixed `MessageCodec`, JavaFX client.

**Spec:** `docs/superpowers/specs/2026-09-15-forum-bank-boost-design.md`

## Global Constraints

- Preserve `MySQL -> application server -> JavaFX client`; no client-side JDBC.
- Follow strict red-green-refactor: add one behavior-focused test, run it and confirm the expected failure, implement the minimum behavior, then rerun it.
- Treat all `*_ADMIN` roles and `SUPER_ADMIN` as administrators for this feature, even if they also hold `STUDENT` or `TEACHER`.
- Accept only `postId`, server-defined `tier`, and UUID `operationId` from the client. Never accept amount, heat, duration, or expiry from the client.
- Use `Asia/Shanghai` natural-day boundaries and an injected `Clock` in service/query code.
- Keep the same UUID when retrying an uncertain payment. A new click after a definite result gets a new UUID.
- Do not change `forumVersion=2`; add optional `boost.<index>` payload entries so existing clients can still parse feed rows.
- Keep JavaFX state mutations on the Application Thread and socket/JDBC work off it.
- Commit after every green task using the commit message shown below; do not bundle unrelated changes.

---

### Task 1: Add shared boost vocabulary and administrator policy

**Files:**

- Create: `vcampus-common/src/main/java/com/vcampus/common/model/ForumBoostTier.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/ForumAccessPolicy.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/BankLedgerType.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/NotificationType.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Create: `vcampus-common/src/test/java/com/vcampus/common/model/ForumBoostTierTest.java`
- Modify: `vcampus-common/src/test/java/com/vcampus/common/model/ForumPolicyTest.java`
- Modify: `vcampus-common/src/test/java/com/vcampus/common/protocol/ForumActionsTest.java`
- Modify: `vcampus-common/src/test/java/com/vcampus/common/model/BankVocabularyTest.java`
- Modify: `vcampus-common/src/test/java/com/vcampus/common/model/NotificationVocabularyTest.java`

**Interfaces:**

- Produces `ForumBoostTier.FIVE/TEN/FIFTEEN`, each with exact `BigDecimal amount()` and `int initialHeat()`.
- Produces `ForumAccessPolicy.canBoost(Set<UserRole>)` for use by the server and client.
- Produces action constants `FORUM_BOOST_GET` and `FORUM_BOOST_CREATE`.
- Produces `BankLedgerType.FORUM_BOOST` and `NotificationType.BANK_FORUM_BOOST_PAID`.

- [x] Add failing tier and role-policy tests. Cover exact amount scale, heat, rejected unknown value, ordinary student/teacher acceptance, and rejection when any admin role is mixed with an ordinary role.

```java
@Test
void tiersOwnAllServerTrustedPaymentValues() {
    assertAll(
            () -> assertEquals(new BigDecimal("5.00"), ForumBoostTier.FIVE.amount()),
            () -> assertEquals(50, ForumBoostTier.FIVE.initialHeat()),
            () -> assertEquals(new BigDecimal("10.00"), ForumBoostTier.TEN.amount()),
            () -> assertEquals(100, ForumBoostTier.TEN.initialHeat()),
            () -> assertEquals(new BigDecimal("15.00"), ForumBoostTier.FIFTEEN.amount()),
            () -> assertEquals(150, ForumBoostTier.FIFTEEN.initialHeat()));
}

@Test
void anyAdministratorRoleDisablesBoosting() {
    assertTrue(ForumAccessPolicy.canBoost(Set.of(UserRole.STUDENT)));
    assertTrue(ForumAccessPolicy.canBoost(Set.of(UserRole.TEACHER)));
    assertFalse(ForumAccessPolicy.canBoost(Set.of(UserRole.STUDENT, UserRole.BANK_ADMIN)));
    assertFalse(ForumAccessPolicy.canBoost(Set.of(UserRole.TEACHER, UserRole.FORUM_ADMIN)));
    assertFalse(ForumAccessPolicy.canBoost(Set.of(UserRole.SUPER_ADMIN)));
}
```

- [x] Run the focused common tests and confirm compilation fails because the new enum, method, actions, and vocabulary do not exist.

```powershell
mvn "-Dmaven.repo.local=.m2-codex" -pl vcampus-common -Dtest=ForumBoostTierTest,ForumPolicyTest,ForumActionsTest,BankVocabularyTest,NotificationVocabularyTest test
```

- [x] Implement the enum with no mutable configuration and add the role helper. The role test must iterate every `UserRole` whose value is `SUPER_ADMIN` or whose name ends in `_ADMIN`, so future admin roles remain covered.

```java
public enum ForumBoostTier {
    FIVE("5.00", 50), TEN("10.00", 100), FIFTEEN("15.00", 150);

    private final BigDecimal amount;
    private final int initialHeat;

    ForumBoostTier(String amount, int initialHeat) {
        this.amount = new BigDecimal(amount);
        this.initialHeat = initialHeat;
    }

    public BigDecimal amount() { return amount; }
    public int initialHeat() { return initialHeat; }
}
```

```java
public static boolean canBoost(Set<UserRole> roles) {
    Objects.requireNonNull(roles, "roles");
    boolean administrator = roles.stream().anyMatch(role ->
            role == UserRole.SUPER_ADMIN || role.name().endsWith("_ADMIN"));
    return canUse(roles) && !administrator;
}
```

- [x] Add both action values to the exact-value/count test and add codec round-trip coverage for the new ledger and notification enum values.
- [x] Rerun the focused tests and then the entire common module; both commands must be green.

```powershell
mvn "-Dmaven.repo.local=.m2-codex" -pl vcampus-common test
```

- [x] Commit the green task.

```powershell
git add vcampus-common
git commit -m "feat: add forum boost protocol vocabulary"
```

---

### Task 2: Add the boost table and upgrade-safe constraints

**Files:**

- Create: `database/migrations/023_forum_bank_boost.sql`
- Modify: `database/schema.sql`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/ForumBoostMigrationTest.java`

**Interfaces:**

- Produces `forum_post_boosts` with foreign keys to `forum_posts`, `users`, and `bank_ledger_entries`.
- Extends both fresh-schema and migration paths to accept `FORUM_BOOST` and `BANK_FORUM_BOOST_PAID`.
- Guarantees one boost per `(sponsor_user_id, operation_id)` and one boost per `bank_ledger_id`.

- [x] Add an H2 MySQL-mode migration test that creates the prerequisite minimal tables, executes the migration using the same script runner used by database bootstrap tests, and verifies columns, foreign keys, unique indexes, legal tier rows, and rejected mismatched tier/amount/heat combinations.

```java
@Test
void migrationCreatesConstrainedBoostStorageAndExtendsVocabularies() throws Exception {
    runMigration("023_forum_bank_boost.sql");
    execute("INSERT INTO forum_post_boosts(post_id,sponsor_user_id,tier,amount,initial_heat," +
            "operation_id,bank_ledger_id,created_at,expires_at) VALUES " +
            "(11,2,'FIVE',5.00,50,'550e8400-e29b-41d4-a716-446655440000',31," +
            "TIMESTAMP '2026-09-15 02:00:00',TIMESTAMP '2026-09-17 02:00:00')");
    assertThrows(SQLException.class, () -> execute(
            "INSERT INTO forum_post_boosts(post_id,sponsor_user_id,tier,amount,initial_heat," +
            "operation_id,bank_ledger_id,created_at,expires_at) VALUES " +
            "(11,2,'FIVE',10.00,50,'550e8400-e29b-41d4-a716-446655440001',32," +
            "CURRENT_TIMESTAMP,DATEADD('HOUR',48,CURRENT_TIMESTAMP))"));
    assertUniqueViolation("sponsor_user_id, operation_id");
    assertUniqueViolation("bank_ledger_id");
}
```

- [x] Run the test and confirm it fails because migration 023 and the table are missing.

```powershell
mvn "-Dmaven.repo.local=.m2-codex" -pl vcampus-server -am -Dtest=ForumBoostMigrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] Add the migration and mirror it in `schema.sql`. Use these required table semantics:

```sql
CREATE TABLE forum_post_boosts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    sponsor_user_id BIGINT NOT NULL,
    tier VARCHAR(16) NOT NULL,
    amount DECIMAL(15,2) NOT NULL,
    initial_heat INT NOT NULL,
    operation_id CHAR(36) NOT NULL,
    bank_ledger_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_forum_post_boost_tier CHECK (
        (tier='FIVE' AND amount=5.00 AND initial_heat=50) OR
        (tier='TEN' AND amount=10.00 AND initial_heat=100) OR
        (tier='FIFTEEN' AND amount=15.00 AND initial_heat=150)),
    CONSTRAINT uk_forum_post_boost_operation UNIQUE (sponsor_user_id, operation_id),
    CONSTRAINT uk_forum_post_boost_ledger UNIQUE (bank_ledger_id),
    CONSTRAINT fk_forum_post_boost_post FOREIGN KEY (post_id) REFERENCES forum_posts(id),
    CONSTRAINT fk_forum_post_boost_sponsor FOREIGN KEY (sponsor_user_id) REFERENCES users(id),
    CONSTRAINT fk_forum_post_boost_ledger FOREIGN KEY (bank_ledger_id) REFERENCES bank_ledger_entries(id)
);
CREATE INDEX idx_forum_post_boost_active
    ON forum_post_boosts(post_id, expires_at, created_at);
CREATE INDEX idx_forum_post_boost_sponsor_day
    ON forum_post_boosts(sponsor_user_id, post_id, created_at);
```

- [x] Extend bank-ledger and notification check constraints in the migration with the project’s existing drop/recreate pattern. Ensure migration 023 succeeds both after migration 022 and against the test fixture representing an older installation.
- [x] Rerun the focused migration test plus existing schema/bootstrap/migration tests.

```powershell
mvn "-Dmaven.repo.local=.m2-codex" -pl vcampus-server -am -Dtest=ForumBoostMigrationTest,ForumMigrationTest,DatabaseScriptsTest,DatabaseBootstrapperMysqlTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] Commit the green task.

```powershell
git add database vcampus-server/src/test/java/com/vcampus/server/database/ForumBoostMigrationTest.java
git commit -m "feat: add forum boost database schema"
```

---

### Task 3: Expose a transaction-scoped forum debit in the bank layer

**Files:**

- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/BankPaymentPort.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/BankRepository.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/database/BankRepositoryTest.java`

**Interfaces:**

- Produces `ForumPaymentResult debitForForum(Connection, long, BigDecimal, String, String)`.
- The caller owns commit/rollback; the bank implementation writes one `FORUM_BOOST` debit ledger and returns account, ledger, balance, and duplicate status.
- Does not create the forum notification; that remains in the forum transaction orchestrator so its content can include the post and heat.

- [x] Add a failing bank repository test proving exact debit values, ledger type/direction/reference/description, duplicate behavior, caller-owned rollback, frozen-account rejection, and insufficient-funds rejection.

```java
@Test
void forumDebitUsesCallerTransactionAndDedicatedLedgerType() throws Exception {
    repository.topUp(9L, "student", new BigDecimal("50.00"), UUID.randomUUID().toString());
    try (Connection connection = connections.openConnection()) {
        connection.setAutoCommit(false);
        var result = repository.debitForForum(connection, 1L, new BigDecimal("10.00"),
                "FB-550e8400-e29b-41d4-a716-446655440000",
                "论坛助力推荐：帖子 #11");
        assertEquals(new BigDecimal("40.00"), result.balanceAfter());
        assertEquals("FORUM_BOOST", scalarString(connection,
                "SELECT entry_type FROM bank_ledger_entries WHERE id=" + result.ledgerId()));
        assertEquals("DEBIT", scalarString(connection,
                "SELECT direction FROM bank_ledger_entries WHERE id=" + result.ledgerId()));
        assertTrue(repository.debitForForum(connection, 1L, new BigDecimal("10.00"),
                "FB-550e8400-e29b-41d4-a716-446655440000",
                "论坛助力推荐：帖子 #11").duplicate());
        connection.rollback();
    }
    assertEquals(new BigDecimal("50.00"), repository.account(1L).balance());
}
```

- [x] Run `BankRepositoryTest` and confirm the expected missing-method compilation failure.

```powershell
mvn "-Dmaven.repo.local=.m2-codex" -pl vcampus-server -am -Dtest=BankRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] Add a dedicated `ForumPaymentResult` record to `BankPaymentPort` and implement `debitForForum` as a thin adapter over `writeMovement(..., BankLedgerType.FORUM_BOOST, false)`. Do not duplicate balance locking, frozen checks, idempotency, or amount validation.
- [x] Rerun `BankRepositoryTest` and all bank service/payment transaction tests.

```powershell
mvn "-Dmaven.repo.local=.m2-codex" -pl vcampus-server -am -Dtest=*Bank*,*PaymentTransactionTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] Commit the green task.

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/database/BankPaymentPort.java vcampus-server/src/main/java/com/vcampus/server/database/BankRepository.java vcampus-server/src/test/java/com/vcampus/server/database/BankRepositoryTest.java
git commit -m "feat: add transaction scoped forum debit"
```

---

### Task 4: Implement atomic boost persistence, eligibility, limits, and idempotency

**Files:**

- Create: `vcampus-server/src/main/java/com/vcampus/server/database/ForumBoostStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/ForumBoostRepository.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/ForumBoostRuleException.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/ForumBoostRepositoryTest.java`

**Interfaces:**

```java
public interface ForumBoostStore {
    record BoostState(boolean eligible, String reason, int activeBoostCount, int activeHeat,
                      int userOperationsUsed, BigDecimal userAmountUsed,
                      BigDecimal postAmountUsed, BigDecimal balance,
                      List<ForumBoostTier> availableTiers) { }
    record BoostReceipt(long boostId, long bankLedgerId, String bankReference,
                        BigDecimal amount, int initialHeat, BigDecimal balanceAfter,
                        int activeBoostCount, int activeHeat, boolean duplicate) { }

    BoostState state(long postId, long actorUserId, Instant dayStart,
                     Instant dayEnd, Instant now) throws SQLException;
    BoostReceipt create(long postId, long actorUserId, ForumBoostTier tier,
                        UUID operationId, Instant dayStart, Instant dayEnd,
                        Instant now) throws SQLException;
}
```

- Consumes `BankPaymentPort` and `NotificationWriter` using the repository’s single JDBC connection.
- Produces stable business failures for missing/ineligible post, self-boost, daily limits, frozen account, insufficient balance, and idempotency conflict.

- [x] Create the H2 integration fixture with users, sections, posts, bank tables, notifications, and `forum_post_boosts`; use real `BankRepository` and `NotificationRepository` rather than mocks for the success path.
- [x] Add and run a first failing test for one successful 5-yuan boost. Assert all four atomic results: balance is 45.00, one `FORUM_BOOST` debit ledger exists, one boost row exists with 50 heat/48-hour expiry, and one `BANK_FORUM_BOOST_PAID` notification exists with source `BANK`, target `BANK_LEDGER`, account ID, amount, heat, balance, and `FB-<uuid>` in its content.

```java
@Test
void createAtomicallyDebitsPersistsBoostAndSendsBankNotification() throws Exception {
    fund(2L, "50.00");
    UUID operationId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    var receipt = repository.create(11L, 2L, ForumBoostTier.FIVE, operationId,
            shanghaiDayStart, shanghaiDayEnd, now);

    assertAll(
            () -> assertEquals(new BigDecimal("5.00"), receipt.amount()),
            () -> assertEquals(50, receipt.initialHeat()),
            () -> assertEquals(new BigDecimal("45.00"), receipt.balanceAfter()),
            () -> assertEquals(1, count("bank_ledger_entries", "entry_type='FORUM_BOOST'")),
            () -> assertEquals(1, count("forum_post_boosts", "post_id=11")),
            () -> assertEquals(1, count("notifications",
                    "notification_type='BANK_FORUM_BOOST_PAID' AND source_module='BANK'")));
}
```

- [x] Implement the smallest success path: open transaction, lock the target post joined to section, reject self/inactive/hidden/pinned/announcement states, debit with reference `FB-<uuid>`, insert boost, insert notification, and commit. Roll back on every checked/runtime exception and restore auto-commit before closing.
- [x] Add failing idempotency tests: same actor/UUID/post/tier returns the original IDs and `duplicate=true`; same actor/UUID with a different post or tier raises `ForumBoostRuleException`; neither path creates another ledger, boost, or notification.
- [x] Implement idempotency lookup before limit checks. On a duplicate, read the current balance and current aggregate, but return the original amount/heat/ledger IDs. Handle the unique-key race by re-reading the row rather than leaking SQL details.
- [x] Add failing boundary tests for exactly 3 operations, exactly 30.00 user/post/day, exactly 200.00 post/day, and one request beyond each boundary. Seed separate sponsors where necessary so each limit is isolated.
- [x] Implement limits with `[dayStart, dayEnd)` predicates. Hold the target post `FOR UPDATE` before idempotency/limit aggregation so concurrent transactions serialize per post. Compute available tiers from the remaining user operation count, user amount, post amount, account state, and balance.
- [x] Add failing eligibility tests for author, deleted/hidden post, disabled section, pinned post, announcement, missing post, frozen account, and insufficient balance. Assert zero new rows in all affected tables.
- [x] Add a notification-failure test using a `NotificationWriter` that throws, and a boost-insert failure test using a deliberately violated constraint. Assert balance, ledger, boost, and notification all roll back.
- [x] Add a two-thread test with separate connections and a start barrier. Seed 195.00 post-day usage, submit two 5.00 boosts, and assert exactly one succeeds and the post total is 200.00. Add the analogous personal-operation limit race if the post-lock test does not naturally cover it.
- [x] Run the repository test after every red/green slice; then run all database tests.

```powershell
mvn "-Dmaven.repo.local=.m2-codex" -pl vcampus-server -am -Dtest=ForumBoostRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn "-Dmaven.repo.local=.m2-codex" -pl vcampus-server -am -Dtest=*RepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] Commit the green task.

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/database/ForumBoostStore.java vcampus-server/src/main/java/com/vcampus/server/database/ForumBoostRepository.java vcampus-server/src/main/java/com/vcampus/server/database/ForumBoostRuleException.java vcampus-server/src/test/java/com/vcampus/server/database/ForumBoostRepositoryTest.java
git commit -m "feat: persist atomic forum boosts"
```

---

### Task 5: Add boost service actions, request validation, routing, and production wiring

**Files:**

- Create: `vcampus-server/src/main/java/com/vcampus/server/service/ForumBoostService.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/ForumService.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/service/ForumBoostServiceTest.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterForumTest.java`

**Interfaces:**

- `forum.boost.get` requires `sessionToken` and positive `postId`.
- `forum.boost.create` requires `sessionToken`, positive `postId`, exact tier enum name, and UUID operation ID.
- Both actions use the authenticated session user; any request `userId`, `amount`, `heat`, `expiresAt`, or `createdAt` is ignored and never passed to the store.
- Responses use scalar protocol fields; tier availability is a comma-delimited enum list and amounts use two decimal places.

- [x] Add service tests with a recording `ForumBoostStore` and fixed clock. Prove Beijing boundaries around UTC 16:00, session identity, all-admin rejection before store access, malformed input rejection, and exact receipt/state serialization.

```java
@Test
void createUsesSessionIdentityTrustedTierUuidAndShanghaiDay() {
    var response = service.handle(RequestMessage.create(Actions.FORUM_BOOST_CREATE, Map.of(
            "sessionToken", token,
            "postId", "11",
            "tier", "FIVE",
            "operationId", "550e8400-e29b-41d4-a716-446655440000",
            "amount", "0.01",
            "heat", "999999")));
    assertTrue(response.success());
    assertEquals(2L, store.actorUserId);
    assertEquals(ForumBoostTier.FIVE, store.tier);
    assertEquals(Instant.parse("2026-09-14T16:00:00Z"), store.dayStart);
    assertEquals(Instant.parse("2026-09-15T16:00:00Z"), store.dayEnd);
}
```

- [x] Run the service test and confirm it fails because `ForumBoostService` does not exist.

```powershell
mvn "-Dmaven.repo.local=.m2-codex" -pl vcampus-server -am -Dtest=ForumBoostServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] Implement session/role/request parsing and stable user-facing errors. Calculate boundaries with `LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")))` and convert start-of-day/end-of-day to `Instant`.
- [x] Compose the new service inside `ForumService` while preserving existing convenience constructors used by tests. Route both new action constants through `RequestRouter` to the forum service.
- [x] In `VCampusServer`, instantiate exactly one `BankRepository`, pass it as `BankPaymentPort` to `ForumBoostRepository`, reuse the existing `NotificationRepository`, and inject that store into `ForumService`.

```java
ForumBoostStore boostStore = new ForumBoostRepository(
        connections, bankRepository, notificationRepository);
ForumService forumService = new ForumService(
        forumRepository, sessionManager, forumCommunityRepository,
        forumImageService, boostStore, auditRepository);
```

- [x] Add router assertions for both action names and verify unknown/non-forum actions still follow their current paths.
- [x] Rerun focused service/router tests and the full server service package.

```powershell
mvn "-Dmaven.repo.local=.m2-codex" -pl vcampus-server -am -Dtest=ForumBoostServiceTest,RequestRouterForumTest,ForumServiceTest,ForumCommunityServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] Commit the green task.

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/service vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java vcampus-server/src/test/java/com/vcampus/server/service
git commit -m "feat: expose forum boost server actions"
```

---

### Task 6: Calculate decaying heat and apply it to hot ordering only

**Files:**

- Create: `vcampus-server/src/main/java/com/vcampus/server/service/ForumHeatPolicy.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/ForumCommunityStore.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/ForumCommunityRepository.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/ForumCommunityService.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/service/ForumHeatPolicyTest.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/ForumCommunityRepositoryTest.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/ForumCommunityServiceTest.java`

**Interfaces:**

- Adds `BoostSummary(int activeCount, int activeHeat)` to `ForumCommunityStore.FeedRow`.
- Serializes optional `boost.<rowIndex>` as `activeCount|activeHeat` without changing `row.<index>`, `engagement.<index>`, or `forumVersion=2`.
- Uses the approved score: `likes*4 + comments*8 + min(views,1000)*0.2 + boostHeat - min(ageHours,168)*2`.

- [x] Add pure policy tests for a single boost at 0, 12, 24, just before 48, and 48 hours; assert 100%, 50%, 25%, positive-before-expiry, and exactly zero at expiry. Add score tests for view cap and 168-hour age cap.

```java
@ParameterizedTest
@CsvSource({"0,100", "12,50", "24,25", "48,0"})
void boostHeatHalvesEveryTwelveHoursAndExpiresAtFortyEight(int hours, int expected) {
    Instant created = Instant.parse("2026-09-15T00:00:00Z");
    assertEquals(expected, ForumHeatPolicy.currentBoostHeat(
            100, created, created.plus(48, ChronoUnit.HOURS),
            created.plus(hours, ChronoUnit.HOURS)));
}
```

- [x] Run the policy test and confirm the missing-class failure, then implement with `Math.pow(0.5d, elapsedHours / 12d)` and the project-approved rounding rule. Clamp negative elapsed time to zero and return zero at `now >= expiresAt`.
- [x] Add repository fixtures with two normal posts, one admin-pinned post, likes/comments/views, and boosts at controlled timestamps. Add failing assertions that:
  - `LATEST` remains ordered by post creation;
  - `HOT` uses the complete score and stable `created_at DESC, id DESC` tiebreaks;
  - pinned home rows precede all paid rows;
  - expired boosts do not count;
  - today hot limits likes/comments to Shanghai day but accepts active boosts created before that day;
  - boost aggregate count/heat matches the row payload.
- [x] Modify repository queries with parameterized `now`, active condition `created_at <= now AND expires_at > now`, and server-owned score SQL. Use an injected `Clock` constructor overload so tests never depend on wall time. Do not alter `BOOKMARKED`, `MINE`, announcement, or `LATEST` semantics.
- [x] Extend `FeedRow` with a compatibility constructor that defaults `BoostSummary(0, 0)` so unrelated test fixtures remain readable.
- [x] Add service serialization assertions for `boost.0=2|75` and verify a row with no boost still emits `0|0`.
- [x] Run all heat/community tests and the current forum repository/service suite.

```powershell
mvn "-Dmaven.repo.local=.m2-codex" -pl vcampus-server -am -Dtest=ForumHeatPolicyTest,ForumCommunityRepositoryTest,ForumCommunityServiceTest,ForumRepositoryTest,ForumServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] Commit the green task.

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/service/ForumHeatPolicy.java vcampus-server/src/main/java/com/vcampus/server/database/ForumCommunityStore.java vcampus-server/src/main/java/com/vcampus/server/database/ForumCommunityRepository.java vcampus-server/src/main/java/com/vcampus/server/service/ForumCommunityService.java vcampus-server/src/test/java/com/vcampus/server
git commit -m "feat: rank forum posts with decaying boost heat"
```

---

### Task 7: Add client protocol models and socket gateway calls

**Files:**

- Modify: `vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumData.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumGateway.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/SocketForumGateway.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumGatewayTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumFixtures.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumDataTest.java`

**Interfaces:**

- Produces client records matching all `BoostState`, `BoostReceipt`, and `BoostSummary` response fields.
- `ForumGateway.boostState(long postId)` reads eligibility and remaining limits.
- `ForumGateway.createBoost(long postId, ForumBoostTier tier, UUID operationId)` sends no money/heat fields.
- Feed parsing tolerates missing `boost.<index>` and defaults to zero.

- [x] Extend the loopback gateway test with a fake server response and capture the request. Assert the request action, `postId`, `tier=FIVE`, and exact UUID; assert that `amount`, `heat`, `expiresAt`, and `userId` keys are absent.

```java
assertEquals(Actions.FORUM_BOOST_CREATE, request.action());
assertEquals("11", request.data().get("postId"));
assertEquals("FIVE", request.data().get("tier"));
assertEquals(operationId.toString(), request.data().get("operationId"));
assertFalse(request.data().containsKey("amount"));
assertFalse(request.data().containsKey("heat"));
```

- [x] Run `ForumGatewayTest` and confirm the missing API/model failure.

```powershell
mvn "-Dmaven.repo.local=.m2-codex" -pl vcampus-client -am -Dtest=ForumGatewayTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] Add authorized low-level methods to `VCampusClient`, synchronous typed adapters to `SocketForumGateway`, and interface methods to `ForumGateway`. Keep socket exceptions translated using the gateway’s existing error convention.
- [x] Extend `ForumData.parseFeed` to parse `boost.<index>` independently of the four-field engagement payload. Test both a response with `boost.0=2|75` and a legacy response without any boost key.
- [x] Update `ForumFixtures` constructors with explicit zero/default boost summaries only where the compatibility constructor cannot preserve current call sites.
- [x] Rerun the focused test and all client forum data/gateway tests.

```powershell
mvn "-Dmaven.repo.local=.m2-codex" -pl vcampus-client -am -Dtest=ForumGatewayTest,ForumDataTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] Commit the green task.

```powershell
git add vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java vcampus-client/src/main/java/com/vcampus/client/fx/forum vcampus-client/src/test/java/com/vcampus/client/fx/forum
git commit -m "feat: add forum boost client protocol"
```

---

### Task 8: Build the one-click payment dialog and controller workflow

**Files:**

- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumBoostDialog.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumDetailView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumController.java`
- Modify: `vcampus-client/src/main/resources/com/vcampus/client/fx/forum/forum.css`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumUiTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumControllerTest.java`

**Interfaces:**

- Dialog renders 5/10/15-yuan actions from server-returned available tiers and explains 50/100/150 initial heat, 12-hour half-life, 48-hour expiry, and no refunds.
- A tier click is the final payment confirmation; no second confirmation dialog is allowed.
- Controller creates one UUID per attempt, disables all tier buttons while pending, and keeps the same UUID only when the outcome is uncertain.
- Successful response shows charge, heat, new balance, bank reference, and refreshed active aggregate.

- [x] Add JavaFX UI tests that open `ForumBoostDialog` with a sample state and locate all three amount buttons, remaining-operation/amount/post limits, half-life/expiry text, and the exact no-refund/auto-debit warning.
- [x] Add UI tests for ineligible states: admin has no payment entry, author sees a disabled reason, pinned/announcement/limit states show the server reason, and unavailable tiers cannot be clicked.
- [x] Run `ForumUiTest` and confirm the missing dialog/control failures.

```powershell
mvn "-Dmaven.repo.local=.m2-codex" -pl vcampus-client -am -Dtest=ForumUiTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] Implement the dialog with stable CSS IDs for tests: `forum-boost-dialog`, `forum-boost-tier-five`, `forum-boost-tier-ten`, `forum-boost-tier-fifteen`, `forum-boost-limits`, `forum-boost-warning`, `forum-boost-status`. Populate amounts/heat from `ForumBoostTier`, but populate eligibility and remaining limits only from server state.
- [x] Add a controller test using the existing recording/proxy `ForumGateway`. Click one tier twice before the delayed future returns; assert only one gateway call, a non-null UUID, and disabled buttons. Complete success and assert the status includes `10.00`, `100`, the balance, and `FB-...`.
- [x] Add a controller test for definite business failure: buttons re-enable and a later click gets a fresh UUID. Add a transport-uncertain test: retrying the same visible attempt uses the original UUID until a definite receipt is received or the detail is reloaded.
- [x] Implement detail loading so post/comments/engagement/boost state are fetched off the JavaFX thread and rendered on it. Compute client-side button visibility with `ForumAccessPolicy.canBoost(roles)` only as presentation logic; rely on the server for authorization.
- [x] Wire the dialog’s tier callback directly to the async payment call. Do not add `AlertType.CONFIRMATION` or any second-click confirmation. Refresh boost state and the visible detail/card aggregate after success.
- [x] Add card/detail labels such as `助力 2 次 · 当前热度 75`; do not label the post as officially pinned or promoted indefinitely.
- [x] Add CSS matching existing forum design tokens, including disabled/busy states and a visible warning style. Verify no hard-coded local fonts, dimensions that clip at normal DPI, or color-only status indication.
- [x] Run the UI/controller tests, then the entire client test suite.

```powershell
mvn "-Dmaven.repo.local=.m2-codex" -pl vcampus-client -am -Dtest=ForumUiTest,ForumControllerTest,ForumGatewayTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn "-Dmaven.repo.local=.m2-codex" -pl vcampus-client -am test
```

- [x] Commit the green task.

```powershell
git add vcampus-client
git commit -m "feat: add forum boost payment experience"
```

---

### Task 9: Verify bank-area message behavior and ledger navigation

**Files:**

- Modify: `vcampus-client/src/test/java/com/vcampus/client/ui/NotificationBankUiTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/notification/NotificationViewTest.java`
- Modify if required by the failing tests: `vcampus-client/src/main/java/com/vcampus/client/fx/notification/NotificationUi.java`
- Modify if required by the failing tests: `vcampus-client/src/main/java/com/vcampus/client/fx/CampusApplication.java`

**Interfaces:**

- Consumes `NotificationType.BANK_FORUM_BOOST_PAID`, `NotificationSource.BANK`, `NotificationTarget.BANK_LEDGER`.
- Produces existing message-center bank filtering and existing bank-ledger navigation without a feature-specific alternate route.

- [x] Extend the Swing `NotificationBankUiTest` and JavaFX `NotificationViewTest` fixtures with a boost-payment notification. Assert it appears under the bank source filter, displays its amount/heat/reference content, does not appear under the forum-only filter, and clicking it invokes the existing bank-ledger target route with the payment account ID.
- [x] Run the test. It passed immediately: the generic source/target handling already satisfies the feature, so no production notification UI code was changed.

```powershell
mvn "-Dmaven.repo.local=.m2-codex" -pl vcampus-client -am -Dtest=NotificationBankUiTest,NotificationViewTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] Implement only the missing generic enum handling revealed by the test; do not introduce a separate boost inbox or duplicate bank page. No implementation was required here; the earlier client compilation check already added the new bank-ledger display label.
- [x] Rerun notification, bank UI, and forum UI tests.

```powershell
mvn "-Dmaven.repo.local=.m2-codex" -pl vcampus-client -am -Dtest=*Notification*,*Bank*UiTest,ForumUiTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] Commit the green test and any required generic UI change.

```powershell
git add vcampus-client/src/test/java/com/vcampus/client/ui/NotificationBankUiTest.java vcampus-client/src/test/java/com/vcampus/client/fx/notification/NotificationViewTest.java vcampus-client/src/main/java/com/vcampus/client/fx/notification/NotificationUi.java vcampus-client/src/main/java/com/vcampus/client/fx/CampusApplication.java
git commit -m "test: verify forum boost bank notifications"
```

---

### Task 10: Update user-facing documentation and perform full verification

**Files:**

- Modify: `docs/requirements.md`
- Modify: `docs/forum.md`
- Modify if present: the bank module document located by `rg --files docs | rg "bank|银行"`

**Interfaces:**

- Documents the delivered tiers, eligibility, Shanghai-day limits, decay, expiry, score, no-refund policy, ledger type, and message-center bank notification.
- Captures the verification evidence and any manual MySQL/UI steps that remain environment-dependent.

- [x] Update `docs/requirements.md` only after the feature tests are green. Mark the forum-bank integration implemented and include its automated acceptance scope.
- [x] Replace the old forum hot-order description in `docs/forum.md` with the exact approved formula and state explicitly that `LATEST` stays chronological and admin pins stay above paid heat.
- [x] Add `FORUM_BOOST` to the bank documentation’s ledger-type table and state that payment notices are filed under `BANK` and navigate to `BANK_LEDGER`.
- [x] Run a repository scan for unfinished plan markers and for prohibited client-supplied money/heat fields. No prohibited client fields were found; all marker hits were existing placeholder terminology or the scan command itself.

```powershell
rg -n "TBD|TODO|FIXME|placeholder" vcampus-common vcampus-server vcampus-client database docs
rg -n 'put\("(amount|heat|expiresAt|createdAt)"' vcampus-client/src/main/java/com/vcampus/client/fx/forum vcampus-client/src/main/java/com/vcampus/client/VCampusClient.java
```

- [x] Run focused end-to-end regression tests for protocol, routing, transaction, sorting, gateway, UI, and notification behavior.

```powershell
mvn "-Dmaven.repo.local=.m2-codex" -Dtest=ForumBoostTierTest,ForumPolicyTest,ForumActionsTest,BankVocabularyTest,NotificationVocabularyTest,ForumBoostMigrationTest,BankRepositoryTest,ForumBoostRepositoryTest,ForumBoostServiceTest,RequestRouterForumTest,ForumHeatPolicyTest,ForumCommunityRepositoryTest,ForumCommunityServiceTest,ForumDataTest,ForumGatewayTest,ForumControllerTest,ForumUiTest,NotificationBankUiTest,NotificationViewTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] Run the required clean full build and retain the final Maven summary as completion evidence: 1,065 tests, 0 failures/errors, 10 skipped, `BUILD SUCCESS`.

```powershell
mvn "-Dmaven.repo.local=.m2-codex" clean verify
```

- [x] Attempt the MySQL environment check. The configured application account can reach port 3306 but cannot connect to the randomized isolated test catalog created by `DatabaseBootstrapperMysqlTest`; all seven opt-in cases stop with access denied. Migration 023 and the two-client smoke test therefore remain environment-dependent and are not claimed as passed.
- [x] Review `git diff --check`, confirm no credentials or machine-specific paths were added to tracked files, and inspect the complete branch diff against its base.

```powershell
git diff --check
git status --short
git diff --stat HEAD~9..HEAD
```

- [x] Commit documentation after all automated evidence is green.

```powershell
git add docs
git commit -m "docs: document forum bank boost workflow"
```

- [ ] Before declaring completion, invoke `superpowers:verification-before-completion`, rerun any command it requires, then invoke `superpowers:requesting-code-review`. Address review findings with `superpowers:receiving-code-review` and fresh red-green tests.
