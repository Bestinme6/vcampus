# VCampus Bank Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a working virtual-bank module with lazy accounts, immutable ledgers, administrator top-up and freeze controls, atomic user transfers, notifications, and an embedded Swing client.

**Architecture:** Add shared bank vocabulary and protocol actions, implement all JDBC mutations in `BankRepository`, and expose them through `BankService` and the existing Socket router. `BankRepository` also implements a narrow `BankPaymentWriter` that later lets the shop debit or refund inside the shop-owned JDBC transaction without duplicating bank SQL.

**Tech Stack:** Java 21, Swing, Java Socket, JDBC, MySQL 8, H2 MySQL mode, JUnit 5, Maven.

**Spec:** `docs/superpowers/specs/2026-08-29-bank-shop-design.md`

## Global Constraints

- Preserve MySQL database -> application server -> Swing client; the client must never import JDBC or connect to MySQL.
- Use the length-prefixed `MessageCodec` protocol and `bank.` action prefix.
- Store every amount as `DECIMAL(15,2)` / `BigDecimal`; reject scientific notation, non-positive values, values with more than two decimals, and values above `9999999999999.99`.
- User identity always comes from `SessionManager`; never trust a client-supplied user ID for self-service actions.
- Keep balance changes, ledger rows, and notifications in one JDBC transaction.
- Frozen accounts may receive funds and view data, but may not transfer or pay the shop.
- Never update or delete ledger rows.
- Keep Swing changes on the Event Dispatch Thread and network work off it; use dark text on light buttons.
- Update `database/schema.sql`, add `database/migrations/007_bank.sql`, update `database/seed.sql`, and update requirements/docs.

Before Maven commands in this Windows workspace, define:

```powershell
$mvn = 'C:\Users\Bestinme\Documents\Codex\2026-08-25\wo\work\tools\apache-maven-3.9.16\bin\mvn.cmd'
$mavenRepoArg = '-Dmaven.repo.local=C:\Users\Bestinme\Documents\Codex\2026-08-25\wo\work\tools\m2-repository'
```

---

## File Structure

### New files

- `vcampus-common/src/main/java/com/vcampus/common/model/BankAccountStatus.java` — `ACTIVE`/`FROZEN` wire values.
- `vcampus-common/src/main/java/com/vcampus/common/model/BankLedgerType.java` — immutable ledger event vocabulary.
- `vcampus-common/src/main/java/com/vcampus/common/model/BankLedgerDirection.java` — `DEBIT`/`CREDIT` wire values.
- `vcampus-common/src/main/java/com/vcampus/common/model/MoneyPolicy.java` — exact positive-money parsing.
- `vcampus-common/src/main/java/com/vcampus/common/model/BankAccessPolicy.java` — bank administrator role decision.
- `database/migrations/007_bank.sql` — upgrade script for bank tables and notification constraints.
- `vcampus-server/src/main/java/com/vcampus/server/model/BankAccountRecord.java` — account query result.
- `vcampus-server/src/main/java/com/vcampus/server/model/BankLedgerRecord.java` — ledger query result.
- `vcampus-server/src/main/java/com/vcampus/server/database/BankStore.java` — service-facing bank contract and request/result records.
- `vcampus-server/src/main/java/com/vcampus/server/database/BankPaymentWriter.java` — transaction-aware shop debit/refund port.
- `vcampus-server/src/main/java/com/vcampus/server/database/BankRuleException.java` — safe business-rule failures such as frozen account and insufficient balance.
- `vcampus-server/src/main/java/com/vcampus/server/database/BankRepository.java` — JDBC account, ledger, transfer, top-up, status, and payment implementation.
- `vcampus-server/src/main/java/com/vcampus/server/service/BankService.java` — session, permission, validation, and response encoding.
- `vcampus-client/src/main/java/com/vcampus/client/ui/BankAsync.java` — background request/EDT completion helper.
- `vcampus-client/src/main/java/com/vcampus/client/ui/BankViewData.java` — typed parsing of bank responses.
- `vcampus-client/src/main/java/com/vcampus/client/ui/BankModulePanel.java` — embedded bank UI.
- Tests named `BankVocabularyTest`, `MoneyPolicyTest`, `BankMigrationTest`, `BankRepositoryTest`, `BankServiceTest`, `RequestRouterBankTest`, `BankViewDataTest`, and `BankModuleNavigationTest` in their matching modules.

### Modified files

- `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Notification enums and `NotificationVocabularyTest`
- `database/schema.sql`, `database/seed.sql`
- `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`
- `vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java`
- `vcampus-client/src/main/java/com/vcampus/client/ui/MainModuleRoute.java`
- `vcampus-client/src/main/java/com/vcampus/client/ui/MainFrame.java`
- `vcampus-client/src/main/java/com/vcampus/client/ui/NotificationPanel.java`
- `vcampus-client/src/main/java/com/vcampus/client/ui/NotificationViewData.java`
- `docs/requirements.md`, `docs/message-center.md`

---

### Task 1: Shared bank vocabulary, money rules, and database migration

**Files:**
- Create the five common model files listed above.
- Modify: `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Modify: the three notification enums.
- Create: common vocabulary and money-policy tests.
- Modify: `database/schema.sql`
- Create: `database/migrations/007_bank.sql`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/BankMigrationTest.java`

**Interfaces:**
- Produces: `MoneyPolicy.parsePositive(String): BigDecimal` and `MoneyPolicy.format(BigDecimal): String`.
- Produces: notification source `BANK`, target `BANK_LEDGER`, and types `BANK_TRANSFER_RECEIVED`, `BANK_ACCOUNT_TOPPED_UP`, `BANK_ACCOUNT_STATUS_CHANGED`.
- Produces: the seven `Actions.BANK_*` constants named in the spec.

- [ ] **Step 1: Write failing common tests**

```java
@Test void parsesCanonicalMoney() {
    assertEquals(new BigDecimal("12.30"), MoneyPolicy.parsePositive("12.30"));
    assertEquals("12.30", MoneyPolicy.format(new BigDecimal("12.3")));
}

@ParameterizedTest
@ValueSource(strings = {"", "0", "-1", "1.001", "1e2", "9999999999999.991"})
void rejectsUnsafeMoney(String value) {
    assertThrows(IllegalArgumentException.class, () -> MoneyPolicy.parsePositive(value));
}

@Test void bankWireVocabularyIsStable() {
    assertEquals("FROZEN", BankAccountStatus.FROZEN.name());
    assertEquals("SHOP_REFUND", BankLedgerType.SHOP_REFUND.name());
    assertEquals("DEBIT", BankLedgerDirection.DEBIT.name());
    assertEquals("bank.transfer.create", Actions.BANK_TRANSFER_CREATE);
    assertEquals("BANK", NotificationSource.BANK.name());
    assertEquals("BANK_LEDGER", NotificationTarget.BANK_LEDGER.name());
}
```

- [ ] **Step 2: Run the common tests and verify RED**

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-common' '-Dtest=MoneyPolicyTest,BankVocabularyTest,NotificationVocabularyTest' test
```

Expected: compilation fails because the bank types and constants are absent.

- [ ] **Step 3: Add the shared types and exact money parser**

```java
public final class MoneyPolicy {
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+(?:\\.[0-9]{1,2})?");
    private static final BigDecimal MAX = new BigDecimal("9999999999999.99");
    public static BigDecimal parsePositive(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!DECIMAL.matcher(normalized).matches()) throw new IllegalArgumentException("金额格式无效");
        BigDecimal amount = new BigDecimal(normalized).setScale(2, RoundingMode.UNNECESSARY);
        if (amount.signum() <= 0 || amount.compareTo(MAX) > 0) throw new IllegalArgumentException("金额超出范围");
        return amount;
    }
    public static String format(BigDecimal value) { return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString(); }
    private MoneyPolicy() {}
}
```

Add action constants exactly as `BANK_ACCOUNT_GET`, `BANK_TRANSFER_CREATE`, `BANK_LEDGER_SEARCH`, `BANK_ADMIN_ACCOUNT_SEARCH`, `BANK_ADMIN_TOPUP`, `BANK_ADMIN_FREEZE`, and `BANK_ADMIN_UNFREEZE`. Implement `BankAccessPolicy.canManage(Set<UserRole>)` as `BANK_ADMIN || SUPER_ADMIN`.

- [ ] **Step 4: Run the common tests and verify GREEN**

Run the Step 2 command. Expected: all specified common tests pass.

- [ ] **Step 5: Add failing migration assertions**

```java
@Test void freshAndUpgradeScriptsContainBankContract() throws Exception {
    String schema = Files.readString(Path.of("..", "database", "schema.sql"));
    String migration = Files.readString(Path.of("..", "database", "migrations", "007_bank.sql"));
    for (String token : List.of("bank_accounts", "bank_ledger_entries", "BANK_TRANSFER_RECEIVED", "BANK_LEDGER")) {
        assertTrue(schema.contains(token));
        assertTrue(migration.contains(token));
    }
    assertTrue(schema.contains("UNIQUE (user_id)"));
    assertTrue(schema.contains("UNIQUE (account_id, entry_type, reference_no)"));
}
```

- [ ] **Step 6: Run the migration test and verify RED**

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-server' '-am' '-Dtest=BankMigrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: failure because migration 007 is absent.

- [ ] **Step 7: Add schema and idempotent migration**

Create `bank_accounts` and `bank_ledger_entries` with the columns and checks from section 3.1 of the spec. In migration 007 use `CREATE TABLE IF NOT EXISTS`, then drop/re-add the three named notification checks so their allowed lists include the bank values while preserving every existing academic, account, library, and forum value. Add indexes on ledger `(account_id, created_at)` and `(reference_no)`.

- [ ] **Step 8: Run Task 1 tests and commit**

Run Steps 2 and 6, then:

```powershell
git add vcampus-common database vcampus-server/src/test/java/com/vcampus/server/database/BankMigrationTest.java
git commit -m "feat(bank): add bank vocabulary and schema"
```

---

### Task 2: Lazy accounts, account search, and immutable ledger queries

**Files:**
- Create: `BankAccountRecord.java`, `BankLedgerRecord.java`, `BankStore.java`, `BankRepository.java`, `BankRuleException.java`.
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/BankRepositoryTest.java`

**Interfaces:**
- Produces: `BankStore.account(long userId)`, `searchAccounts(AccountQuery)`, and `searchLedger(LedgerQuery)`.
- Produces: records `AccountQuery(String keyword, BankAccountStatus status, int page, int pageSize)` and `LedgerQuery(long accountUserId, BankLedgerType type, int page, int pageSize)` plus paged results.
- Produces: `AccountPage(List<BankAccountRecord> rows,int page,int pageSize,int total)` and `LedgerPage(List<BankLedgerRecord> rows,int page,int pageSize,int total)`.
- Produces: `BankRepository(ConnectionFactory, NotificationWriter)`.

- [ ] **Step 1: Write failing H2 repository tests**

```java
@Test void firstAccessCreatesExactlyOneZeroBalanceAccount() throws Exception {
    BankAccountRecord first = repository.account(1L);
    BankAccountRecord second = repository.account(1L);
    assertEquals(first.id(), second.id());
    assertEquals(new BigDecimal("0.00"), first.balance());
    assertEquals(BankAccountStatus.ACTIVE, first.status());
    assertEquals(1, scalarInt("SELECT COUNT(*) FROM bank_accounts WHERE user_id=1"));
}

@Test void ledgerQueryNeverExposesAnotherUsersRows() throws Exception {
    assertEquals(0, repository.searchLedger(new LedgerQuery(2L, null, 1, 20)).total());
}
```

The fixture creates enabled users plus the two bank tables, with H2 URL `MODE=MySQL;DB_CLOSE_DELAY=-1`.

- [ ] **Step 2: Run repository tests and verify RED**

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-server' '-am' '-Dtest=BankRepositoryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: compilation fails because the repository contract is absent.

- [ ] **Step 3: Implement lazy account creation and paged reads**

Use `INSERT INTO bank_accounts(user_id) VALUES (?) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)` before selecting the row. Query account display fields by joining `users`; query ledgers only through `account_id` resolved from the requested account user. Return immutable `List.copyOf(rows)` pages and map money with `getBigDecimal(...).setScale(2)`. Define `BankRuleException extends SQLException` with a constructor accepting the safe Chinese client message; repository methods use it only for expected business rejection, never for raw SQL details.

```java
public interface BankStore {
    BankAccountRecord account(long userId) throws SQLException;
    AccountPage searchAccounts(AccountQuery query) throws SQLException;
    LedgerPage searchLedger(LedgerQuery query) throws SQLException;
}
```

- [ ] **Step 4: Run repository tests and verify GREEN**

Run the Step 2 command. Expected: both tests pass.

- [ ] **Step 5: Commit**

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/database/BankStore.java vcampus-server/src/main/java/com/vcampus/server/database/BankRepository.java vcampus-server/src/main/java/com/vcampus/server/database/BankRuleException.java vcampus-server/src/main/java/com/vcampus/server/model/BankAccountRecord.java vcampus-server/src/main/java/com/vcampus/server/model/BankLedgerRecord.java vcampus-server/src/test/java/com/vcampus/server/database/BankRepositoryTest.java
git commit -m "feat(bank): add accounts and ledger queries"
```

---

### Task 3: Administrator top-up, freeze controls, and notifications

**Files:**
- Modify: `BankStore.java`, `BankRepository.java`, `BankRepositoryTest.java`

**Interfaces:**
- Produces: `TopUpResult topUp(long operatorUserId, long targetUserId, BigDecimal amount, String operationId)`.
- Produces: `StatusResult setStatus(long operatorUserId, long targetUserId, BankAccountStatus status)`.
- Produces: `TopUpResult(long accountId,BigDecimal balanceAfter,String referenceNo,boolean duplicate)` and `StatusResult(long accountId,BankAccountStatus status,boolean changed)`.

- [ ] **Step 1: Add failing transaction and notification tests**

```java
@Test void topUpWritesBalanceLedgerAndNotificationAtomically() throws Exception {
    TopUpResult result = repository.topUp(9L, 1L, new BigDecimal("50.00"), OPERATION_ID);
    assertEquals(new BigDecimal("50.00"), result.balanceAfter());
    assertEquals(1, scalarInt("SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='ADMIN_TOPUP'"));
    assertEquals(1, scalarInt("SELECT COUNT(*) FROM notifications WHERE notification_type='BANK_ACCOUNT_TOPPED_UP'"));
}

@Test void notificationFailureRollsBackTopUp() throws Exception {
    NotificationWriter failingWriter = new NotificationWriter() {
        @Override public void insert(Connection connection, NotificationDraft draft) throws SQLException {
            throw new SQLException("boom");
        }
        @Override public void insertBatch(Connection connection, List<NotificationDraft> drafts) throws SQLException {
            throw new SQLException("boom");
        }
    };
    BankRepository failing = new BankRepository(connections, failingWriter);
    assertThrows(SQLException.class, () -> failing.topUp(9L, 1L, new BigDecimal("50.00"), OPERATION_ID));
    assertEquals(new BigDecimal("0.00"), repository.account(1L).balance());
}
```

Also assert freeze and unfreeze create `BANK_ACCOUNT_STATUS_CHANGED`, while applying the current state reports `changed=false` and creates no notification.

- [ ] **Step 2: Run the tests and verify RED**

Run the Task 2 Maven command. Expected: compilation fails on missing mutation methods.

- [ ] **Step 3: Implement both mutations in explicit transactions**

For top-up: disable auto-commit, lazy-create and `SELECT ... FOR UPDATE`, first query the unique `(account_id, ADMIN_TOPUP, operationId)` row, return its `balance_after` when present, otherwise update balance, insert the immutable credit ledger, insert a BANK notification through the supplied `NotificationWriter`, and commit. For status: lock the account, update only when different, notify the target, and commit. Roll back on every exception and restore auto-commit in `finally`.

- [ ] **Step 4: Run the tests and verify GREEN**

Run the Task 2 command. Expected: top-up/status/idempotency/rollback tests pass.

- [ ] **Step 5: Commit**

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/database/BankStore.java vcampus-server/src/main/java/com/vcampus/server/database/BankRepository.java vcampus-server/src/test/java/com/vcampus/server/database/BankRepositoryTest.java
git commit -m "feat(bank): add top-up and account status controls"
```

---

### Task 4: Atomic transfer and reusable shop payment port

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/BankPaymentWriter.java`
- Modify: `BankStore.java`, `BankRepository.java`, `BankRepositoryTest.java`

**Interfaces:**
- Produces: `TransferResult transfer(long senderUserId, String recipientUsername, BigDecimal amount, String operationId)`.
- Produces `BankPaymentWriter.debitForShop(Connection,long,BigDecimal,String,String)` and `refundForShop(Connection,long,BigDecimal,String,String)`, each returning `PaymentResult(long accountId, BigDecimal balanceAfter, boolean duplicate)`.
- Produces: `TransferResult(String referenceNo,BigDecimal senderBalanceAfter,BigDecimal recipientBalanceAfter,boolean duplicate)`.

- [ ] **Step 1: Add failing transfer, concurrency, and payment-port tests**

```java
@Test void transferCreatesTwoBalancedLedgersAndOneRecipientNotification() throws Exception {
    repository.topUp(9L, 1L, new BigDecimal("100.00"), UUID.randomUUID().toString());
    TransferResult result = repository.transfer(1L, "recipient", new BigDecimal("30.00"), OPERATION_ID);
    assertEquals(new BigDecimal("70.00"), result.senderBalanceAfter());
    assertEquals(2, scalarInt("SELECT COUNT(*) FROM bank_ledger_entries WHERE reference_no='" + OPERATION_ID + "'"));
    assertEquals(1, scalarInt("SELECT COUNT(*) FROM notifications WHERE notification_type='BANK_TRANSFER_RECEIVED'"));
}

@Test void frozenSenderCannotTransferButFrozenRecipientCanReceive() throws Exception {
    repository.setStatus(9L, 1L, BankAccountStatus.FROZEN);
    assertThrows(BankRuleException.class, () -> repository.transfer(1L, "recipient", TEN, OPERATION_ID));
    repository.setStatus(9L, 2L, BankAccountStatus.FROZEN);
    repository.setStatus(9L, 1L, BankAccountStatus.ACTIVE);
    assertDoesNotThrow(() -> repository.transfer(1L, "recipient", TEN, UUID.randomUUID().toString()));
}
```

Add an executor/barrier test where two `80.00` transfers compete against `100.00`; assert exactly one succeeds and no balance is negative. Add a connection-owned test proving `debitForShop` changes are rolled back when the caller rolls back.

- [ ] **Step 2: Run tests and verify RED**

Run the Task 2 Maven command. Expected: missing transfer and payment-port symbols.

- [ ] **Step 3: Implement fixed-order account locking and immutable ledgers**

Resolve the enabled recipient by exact username and reject self-transfer. Lazy-create both accounts, sort their account IDs, lock in that order, reject only a frozen sender, and check sender balance after locking. Insert `TRANSFER_OUT`/`DEBIT` and `TRANSFER_IN`/`CREDIT` with the same operation ID; notify only the recipient. An existing pair of reference rows returns the original balances without new writes.

Implement the payment port without commit/rollback/close: it must use the caller's `Connection`, lock the buyer account, enforce `ACTIVE` only for debit, allow refunds to frozen accounts, update balance, and insert `SHOP_PAYMENT` or `SHOP_REFUND`. It never sends a notification itself.

- [ ] **Step 4: Run tests and verify GREEN**

Run the Task 2 command three times to exercise the concurrency test. Expected: every run passes.

- [ ] **Step 5: Commit**

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/database/BankPaymentWriter.java vcampus-server/src/main/java/com/vcampus/server/database/BankStore.java vcampus-server/src/main/java/com/vcampus/server/database/BankRepository.java vcampus-server/src/test/java/com/vcampus/server/database/BankRepositoryTest.java
git commit -m "feat(bank): add atomic transfers and payment port"
```

---

### Task 5: Bank service, Socket routing, and client API

**Files:**
- Create: `BankService.java`, `BankServiceTest.java`, `RequestRouterBankTest.java`
- Modify: `RequestRouter.java`, `VCampusServer.java`, `VCampusClient.java`

**Interfaces:**
- Produces one service method per `Actions.BANK_*` constant.
- Produces matching `VCampusClient` methods using authorized Socket requests.

- [ ] **Step 1: Write failing service permission and validation tests**

```java
@Test void ordinaryUserCannotTopUpOrSearchAllAccounts() {
    assertEquals("无权执行银行管理操作", service.topUp(request(STUDENT, Map.of())).message());
    assertEquals("无权执行银行管理操作", service.searchAccounts(request(STUDENT, Map.of())).message());
}

@Test void transferUsesSessionUserAndValidatesOperationId() {
    ResponseMessage response = service.transfer(request(STUDENT, Map.of(
            "recipientUsername", "recipient", "amount", "12.30", "operationId", UUID.randomUUID().toString())));
    assertTrue(response.success());
    verify(store).transfer(eq(STUDENT_ID), eq("recipient"), eq(new BigDecimal("12.30")), anyString());
}
```

Cover expired sessions, malformed UUID/money/page, disabled recipient, frozen account, insufficient funds, duplicate operation, and database failures with stable Chinese messages.

- [ ] **Step 2: Run service tests and verify RED**

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-server' '-am' '-Dtest=BankServiceTest,RequestRouterBankTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- [ ] **Step 3: Implement service encoding and router wiring**

Encode rows with `RowCodec`; account response keys are `accountId`, `username`, `displayName`, `balance`, `status`, `updatedAt`; transfer/top-up responses include `balanceAfter`, `referenceNo`, and `duplicate`. Validate `operationId` using `UUID.fromString` and return generic database errors while logging server-side details.

Extend `RequestRouter` constructor with `BankService`, add seven switch cases, instantiate one `BankRepository(connections, notificationRepository)` in `VCampusServer`, and inject it into `BankService`. Preserve it for the shop wiring in the second plan.

- [ ] **Step 4: Add client methods and router assertions**

```java
public ResponseMessage transferBank(String token, String recipientUsername, String amount, String operationId) throws IOException {
    return sendAuthorized(Actions.BANK_TRANSFER_CREATE, token, Map.of(
            "recipientUsername", recipientUsername, "amount", amount, "operationId", operationId));
}
```

Add equivalents for account, ledger, admin account search, top-up, freeze, and unfreeze. Assert every action reaches its matching mocked service method.

- [ ] **Step 5: Run tests and commit**

Run the Step 2 command, then:

```powershell
git add vcampus-server vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java
git commit -m "feat(bank): expose bank socket services"
```

---

### Task 6: Embedded Swing bank module and bank notification navigation

**Files:**
- Create: `BankAsync.java`, `BankViewData.java`, `BankModulePanel.java`, client tests.
- Modify: `MainModuleRoute.java`, `MainFrame.java`, notification UI/parser files.

**Interfaces:**
- Produces: `BankModulePanel.activate()` and `openLedger()`.
- Produces: route `ModuleCode.BANK -> "bank"` and notification target `BANK_LEDGER -> BankModulePanel.openLedger()`.

- [ ] **Step 1: Write failing UI policy/parser tests**

```java
@Test void bankIsEmbeddedAndAdminTabsFollowRoles() {
    assertEquals(Optional.of("bank"), MainModuleRoute.route(ModuleCode.BANK));
    assertFalse(BankViewData.showAdminTabs(Set.of(UserRole.STUDENT)));
    assertTrue(BankViewData.showAdminTabs(Set.of(UserRole.BANK_ADMIN)));
}

@Test void frozenAccountDisablesTransfer() {
    assertFalse(BankViewData.canTransfer(BankAccountStatus.FROZEN));
    assertTrue(BankViewData.canTransfer(BankAccountStatus.ACTIVE));
}
```

Also parse representative `RowCodec` account/ledger rows and assert `NotificationSource.BANK` label plus `BANK_LEDGER` navigation.

- [ ] **Step 2: Run client tests and verify RED**

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-client' '-am' '-Dtest=BankViewDataTest,BankModuleNavigationTest,MainModuleRouteTest,NotificationViewDataTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- [ ] **Step 3: Build the three user tabs and three admin views**

`BankModulePanel` uses one `JTabbedPane`: “账户首页”, “转账”, “流水明细”, and, for bank administrators, “账户管理”, “充值与冻结”, “全量流水”. Transfer/top-up creates one UUID when the button is clicked and reuses it only for a network retry. Disable the button during `BankAsync.run`, show server messages via `UiDialogs`, and refresh balance/ledger after success. Use `Theme.stylePrimaryButton` then explicitly set `setForeground(Color.BLACK)` for light buttons.

- [ ] **Step 4: Wire workspace and message-center navigation**

Add `showBank()` in `MainFrame`, lazy-register `BankModulePanel`, handle route `"bank"`, and add `BANK_LEDGER` in `navigateFromNotification`. Extend notification filters and display labels for source `BANK`.

- [ ] **Step 5: Run client tests and commit**

Run the Step 2 command, then:

```powershell
git add vcampus-client
git commit -m "feat(bank): add embedded Swing bank module"
```

---

### Task 7: Seed data, documentation, and full verification

**Files:**
- Modify: `database/seed.sql`, `docs/requirements.md`, `docs/message-center.md`
- Create: `docs/bank.md`

**Interfaces:**
- Produces: idempotent demo balances and operator instructions.
- Produces: a verified bank module and stable `BankPaymentWriter` for the shop plan.

- [ ] **Step 1: Add idempotent seed rows**

Insert demo accounts by selecting existing demo users, using `ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)`. Seed opening balances through one `ADMIN_TOPUP` ledger row per account with deterministic references such as `SEED-BANK-<username>`, and update balances only when that reference is absent so rerunning `seed.sql` cannot double funds.

- [ ] **Step 2: Update documentation**

Mark bank requirements implemented, document roles/actions/freeze semantics/environment setup in `docs/bank.md`, and add the three bank notification types plus `BANK_LEDGER` navigation to `docs/message-center.md`.

- [ ] **Step 3: Run module and full verification**

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-common,vcampus-server,vcampus-client' test
& $mvn $mavenRepoArg clean verify
rg -n "java\.sql|javax\.sql|jdbc:" vcampus-client/src
```

Expected: Maven reports `BUILD SUCCESS`; the `rg` command returns no client JDBC matches.

- [ ] **Step 4: Perform MySQL/manual acceptance**

Apply `007_bank.sql`, run `seed.sql` twice, and verify balances do not double. With two ordinary clients and one bank administrator: open accounts, top up, transfer, freeze sender, receive while frozen, unfreeze, inspect both ledgers, inspect notifications, and confirm ordinary clients cannot open admin tabs or invoke admin actions.

- [ ] **Step 5: Commit**

```powershell
git add database/seed.sql docs
git commit -m "docs(bank): finish bank module delivery"
```
