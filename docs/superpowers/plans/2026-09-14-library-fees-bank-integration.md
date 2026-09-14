# Library Fees and Bank Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate transparent library overdue/damage/loss bills and let borrowers explicitly pay them through the bank module without duplicate charges or inconsistent loan state.

**Architecture:** The library module owns fee policy, assessment, bills, and borrowing restrictions; a narrow bank payment port owns account inspection, debit, refund, and ledger entries. Loan closeout calls a transaction-aware fee writer so the loan, copy, bill, and notification commit together, while payment/refund lock the bill and bank account and commit their ledger, status, and notification atomically.

**Tech Stack:** Java 21, Maven, JUnit 5, H2 repository tests, MySQL 8 production schema, JavaFX, Swing-compatible socket client, existing length-prefixed `MessageCodec` protocol.

**Spec:** `docs/superpowers/specs/2026-09-14-library-fees-bank-integration-design.md`

## Global Constraints

- Before implementation, use `superpowers:using-git-worktrees` and start from a commit that includes both `344cbaf` and any account-security work currently uncommitted in the primary checkout; never overwrite those user changes.
- Preserve the three-tier path: MySQL → application server → JavaFX/Swing client. Client code never reads bank or library tables.
- Follow red-green-refactor for every task: first run the named failing test, then add the smallest production change, rerun it, and commit only that task.
- All new wire actions start with `library.` and live in `vcampus-common`; never use Java native object serialization.
- Use `BigDecimal`/SQL `DECIMAL` for money, scale 2 for amounts and scale 4 for rates. Never use `double` or `float` for fee arithmetic.
- Use server time and `Asia/Shanghai` date boundaries for overdue calculations. Client clocks and client-calculated amounts are never authoritative.
- Keep database/network work off the JavaFX Application Thread and keep all JavaFX state mutation on that thread.
- Preserve compatibility overloads for existing catalog/loan records and constructors while migrating current tests.
- Fee payment is whole-bill only. There is no partial payment, installment, external price lookup, cash payment, or appeal workflow in this plan.
- A pending fee blocks borrow and renew, but never blocks return, viewing, or payment.
- Run Maven commands from the repository root. Reuse `"-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository"` so dependency resolution does not create new workspace artifacts.

---

## File Map

### Shared protocol and domain

- Create `vcampus-common/src/main/java/com/vcampus/common/model/LibraryDamageLevel.java`: minor/major damage vocabulary.
- Create `vcampus-common/src/main/java/com/vcampus/common/model/LibraryFeeStatus.java`: bill lifecycle vocabulary.
- Create `vcampus-common/src/main/java/com/vcampus/common/model/LibraryFeeAssessment.java`: immutable itemized result.
- Create `vcampus-common/src/main/java/com/vcampus/common/model/LibraryFeePolicy.java`: validated policy and pure fee calculation.
- Modify `Actions.java`, `BankLedgerType.java`, `NotificationType.java`, and `NotificationTarget.java`: stable cross-module vocabulary.

### Server persistence and services

- Create `LibraryFeeStore.java`: read/payment/admin bill contract.
- Create `LibraryFeeWriter.java`: transaction-aware closeout and pending-fee checks used by loan persistence.
- Create `BankPaymentPort.java`: library-safe account/debit/refund boundary implemented by the bank repository.
- Create `LibraryFeeRepository.java`: fee policy, bill, payment, waiver, refund, and notifications.
- Modify `LibraryCatalogStore.java`/`LibraryCatalogRepository.java`: replacement-price persistence.
- Modify `LibraryLoanStore.java`/`LibraryLoanRepository.java`: damage level, atomic bill creation, and pending-fee restrictions.
- Modify `BankRepository.java`: generic internal movement writer plus library ledger types.
- Modify `LibraryService.java`, `RequestRouter.java`, and `VCampusServer.java`: validation, authorization, routing, audit, and production dependency wiring.

### Client

- Modify `VCampusClient.java`: typed request methods for the ten approved actions.
- Modify `LibraryData.java`: strict response decoders for price, assessments, bills, policy, and payment previews.
- Create `LibraryFeeView.java`: borrower bill list/detail/payment confirmation content.
- Create `LibraryFeeAdminView.java`: staff bill search, policy editing, waiver, and refund content.
- Modify `LibraryView.java`, `LibraryController.java`, and `LibraryAdminView.java`: navigation, price detail, return assessment, and child-view hosting.
- Modify `library.css` and notification routing in `CampusApplication.java`.

### Schema, tests, and docs

- Create `database/migrations/022_library_fees.sql`.
- Modify `database/schema.sql`, `database/seed.sql`, `docs/requirements.md`, `docs/library.md`, `docs/bank.md`, and `docs/message-center.md`.
- Add focused common, server, and client tests named in the tasks below.

---

### Task 1: Define fee vocabulary and the pure calculator

**Files:**

- Create: `vcampus-common/src/main/java/com/vcampus/common/model/LibraryDamageLevel.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/LibraryFeeStatus.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/LibraryFeeAssessment.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/LibraryFeePolicy.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/BankLedgerType.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/NotificationType.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/NotificationTarget.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Test: `vcampus-common/src/test/java/com/vcampus/common/model/LibraryFeePolicyTest.java`
- Test: `vcampus-common/src/test/java/com/vcampus/common/model/LibraryFeeVocabularyTest.java`

**Interfaces:**

- Produces: `LibraryFeePolicy.assess(LocalDate dueDate, LocalDate closedDate, LibraryReturnCondition condition, LibraryDamageLevel damageLevel, BigDecimal replacementPrice)` returning `LibraryFeeAssessment`.
- Produces: the ten action constants and enum values used by every later task.

- [x] **Step 1: Write failing calculator tests**

Create a policy using exact defaults and assert the boundary and composition rules:

```java
private static final LibraryFeePolicy POLICY = new LibraryFeePolicy(
        1, new BigDecimal("0.50"), new BigDecimal("20.00"),
        new BigDecimal("0.2000"), new BigDecimal("0.5000"), new BigDecimal("1.0000"));

@Test void keepsFirstOverdueDayFreeAndChargesFromSecondDay() {
    assertEquals(new BigDecimal("0.00"), POLICY.assess(
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2),
            LibraryReturnCondition.NORMAL, null, null).overdueAmount());
    assertEquals(new BigDecimal("0.50"), POLICY.assess(
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3),
            LibraryReturnCondition.NORMAL, null, null).overdueAmount());
}

@Test void capsOverdueAndCombinesMajorDamage() {
    LibraryFeeAssessment value = POLICY.assess(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 1),
            LibraryReturnCondition.DAMAGED, LibraryDamageLevel.MAJOR,
            new BigDecimal("89.00"));
    assertEquals(new BigDecimal("20.00"), value.overdueAmount());
    assertEquals(new BigDecimal("44.50"), value.damageAmount());
    assertEquals(new BigDecimal("64.50"), value.totalAmount());
}
```

Also assert minor damage is 20%, loss is 100%, normal return accepts a null price, damage/loss reject a null price, damage requires a level, normal/lost reject a damage level, and all results use scale 2.

- [x] **Step 2: Write failing vocabulary tests**

```java
assertEquals("library.fee.my", Actions.LIBRARY_FEE_MY);
assertEquals("library.fee.get", Actions.LIBRARY_FEE_GET);
assertEquals("library.fee.payment.preview", Actions.LIBRARY_FEE_PAYMENT_PREVIEW);
assertEquals("library.fee.pay", Actions.LIBRARY_FEE_PAY);
assertEquals("library.admin.fee.search", Actions.LIBRARY_ADMIN_FEE_SEARCH);
assertEquals("library.admin.fee.preview", Actions.LIBRARY_ADMIN_FEE_PREVIEW);
assertEquals("library.admin.fee.waive", Actions.LIBRARY_ADMIN_FEE_WAIVE);
assertEquals("library.admin.fee.refund", Actions.LIBRARY_ADMIN_FEE_REFUND);
assertEquals("library.admin.fee-policy.get", Actions.LIBRARY_ADMIN_FEE_POLICY_GET);
assertEquals("library.admin.fee-policy.update", Actions.LIBRARY_ADMIN_FEE_POLICY_UPDATE);
assertTrue(EnumSet.allOf(BankLedgerType.class).containsAll(
        Set.of(BankLedgerType.LIBRARY_FEE, BankLedgerType.LIBRARY_REFUND)));
assertTrue(EnumSet.allOf(NotificationType.class).containsAll(Set.of(
        NotificationType.LIBRARY_FEE_CREATED, NotificationType.LIBRARY_FEE_PAID,
        NotificationType.LIBRARY_FEE_WAIVED, NotificationType.LIBRARY_FEE_REFUNDED)));
assertEquals(NotificationTarget.LIBRARY_FEES,
        NotificationTarget.valueOf("LIBRARY_FEES"));
```

- [x] **Step 3: Run the common tests and confirm the red state**

Run:

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-common -Dtest=LibraryFeePolicyTest,LibraryFeeVocabularyTest test
```

Expected: compilation fails because the fee classes, actions, and enum values do not exist.

- [x] **Step 4: Implement the immutable model and exact calculation**

Use these exact public shapes:

```java
public enum LibraryDamageLevel { MINOR, MAJOR }
public enum LibraryFeeStatus { PENDING, PAID, WAIVED, REFUNDED }

public record LibraryFeeAssessment(
        int overdueDays, int billableDays, BigDecimal replacementPrice,
        BigDecimal overdueAmount, BigDecimal damageAmount,
        BigDecimal lostAmount, BigDecimal totalAmount) { }

public record LibraryFeePolicy(
        int graceDays, BigDecimal dailyOverdueFee, BigDecimal overdueCap,
        BigDecimal minorDamageRate, BigDecimal majorDamageRate,
        BigDecimal lostRate) {
    public LibraryFeeAssessment assess(LocalDate dueDate, LocalDate closedDate,
            LibraryReturnCondition condition, LibraryDamageLevel damageLevel,
            BigDecimal replacementPrice) {
        int overdueDays = Math.max(0, Math.toIntExact(ChronoUnit.DAYS.between(dueDate, closedDate)));
        int billableDays = Math.max(0, overdueDays - graceDays);
        BigDecimal overdue = dailyOverdueFee.multiply(BigDecimal.valueOf(billableDays))
                .min(overdueCap).setScale(2, RoundingMode.HALF_UP);
        BigDecimal damage = new BigDecimal("0.00");
        BigDecimal lost = new BigDecimal("0.00");
        BigDecimal price = replacementPrice == null ? null
                : replacementPrice.setScale(2, RoundingMode.UNNECESSARY);
        switch (condition) {
            case NORMAL -> {
                if (damageLevel != null) throw new IllegalArgumentException("正常归还不能设置破损程度");
            }
            case DAMAGED -> {
                if (damageLevel == null) throw new IllegalArgumentException("请选择破损程度");
                if (price == null) throw new IllegalArgumentException("该书目尚未设置赔偿参考价");
                BigDecimal rate = damageLevel == LibraryDamageLevel.MINOR
                        ? minorDamageRate : majorDamageRate;
                damage = price.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            }
            case LOST -> {
                if (damageLevel != null) throw new IllegalArgumentException("遗失不能设置破损程度");
                if (price == null) throw new IllegalArgumentException("该书目尚未设置赔偿参考价");
                lost = price.multiply(lostRate).setScale(2, RoundingMode.HALF_UP);
            }
        }
        return new LibraryFeeAssessment(overdueDays, billableDays, price, overdue,
                damage, lost, overdue.add(damage).add(lost).setScale(2));
    }
}
```

Constructor validation rejects negative days/amounts, rates outside `0.0000..1.0000`, and a major rate below the minor rate. Add the exact actions and enum members asserted in Step 2.

- [x] **Step 5: Rerun tests and commit**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-common -Dtest=LibraryFeePolicyTest,LibraryFeeVocabularyTest test
git add vcampus-common/src/main/java/com/vcampus/common/model vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java vcampus-common/src/test/java/com/vcampus/common/model/LibraryFeePolicyTest.java vcampus-common/src/test/java/com/vcampus/common/model/LibraryFeeVocabularyTest.java
git commit -m "feat(library): define fee policy and protocol"
```

Expected: both tests pass.

---

### Task 2: Add the fee schema and migration

**Files:**

- Create: `database/migrations/022_library_fees.sql`
- Modify: `database/schema.sql`
- Modify: `database/seed.sql`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/LibraryFeeMigrationTest.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/database/DatabaseScriptsTest.java`

**Interfaces:**

- Consumes: enum string values from Task 1.
- Produces: `books.replacement_price`, singleton `library_fee_policy`, and `library_fee_bills` for repository tasks.

- [x] **Step 1: Write the migration contract test**

Read both SQL files and assert they define the same columns, checks, and seed policy:

```java
String migration = Files.readString(Path.of("..", "database", "migrations", "022_library_fees.sql"));
String schema = Files.readString(Path.of("..", "database", "schema.sql"));
for (String sql : List.of(migration, schema)) {
    assertTrue(sql.contains("replacement_price DECIMAL(10,2)"));
    assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS library_fee_policy"));
    assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS library_fee_bills"));
    assertTrue(sql.contains("'PENDING', 'PAID', 'WAIVED', 'REFUNDED'"));
    assertTrue(sql.contains("'LIBRARY_FEE', 'LIBRARY_REFUND'"));
    assertTrue(sql.contains("'LIBRARY_FEE_CREATED'"));
    assertTrue(sql.contains("'LIBRARY_FEES'"));
}
assertTrue(migration.contains("VALUES (1, 1, 0.50, 20.00, 0.2000, 0.5000, 1.0000)"));
```

Update `DatabaseScriptsTest` to expect migration `022_library_fees.sql` after `021_forum_report_resolution_notifications.sql`.

- [x] **Step 2: Run the migration tests and confirm the red state**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -am -Dtest=LibraryFeeMigrationTest,DatabaseScriptsTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: the new migration is missing and the schema assertions fail.

- [x] **Step 3: Implement the additive schema**

Use migration `022_library_fees.sql` and mirror it in `schema.sql`. The core definitions are:

```sql
ALTER TABLE books ADD COLUMN replacement_price DECIMAL(10,2) NULL AFTER description;
ALTER TABLE books ADD CONSTRAINT chk_book_replacement_price
    CHECK (replacement_price IS NULL OR replacement_price >= 0.01);

CREATE TABLE IF NOT EXISTS library_fee_policy (
    id TINYINT PRIMARY KEY,
    grace_days INT NOT NULL,
    daily_overdue_fee DECIMAL(10,2) NOT NULL,
    overdue_cap DECIMAL(10,2) NOT NULL,
    minor_damage_rate DECIMAL(5,4) NOT NULL,
    major_damage_rate DECIMAL(5,4) NOT NULL,
    lost_rate DECIMAL(5,4) NOT NULL,
    changed_by_user_id BIGINT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_library_fee_policy_singleton CHECK (id = 1),
    CONSTRAINT fk_library_fee_policy_user FOREIGN KEY (changed_by_user_id) REFERENCES users(id)
);

INSERT INTO library_fee_policy
    (id, grace_days, daily_overdue_fee, overdue_cap,
     minor_damage_rate, major_damage_rate, lost_rate)
VALUES (1, 1, 0.50, 20.00, 0.2000, 0.5000, 1.0000)
ON DUPLICATE KEY UPDATE id = VALUES(id);
```

Define the bill table with this exact column, constraint, foreign-key, and index set:

```sql
CREATE TABLE IF NOT EXISTS library_fee_bills (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    bill_no CHAR(12) NOT NULL UNIQUE,
    loan_id BIGINT NOT NULL UNIQUE,
    borrower_user_id BIGINT NOT NULL,
    book_id BIGINT NOT NULL,
    copy_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    return_condition VARCHAR(16) NOT NULL,
    damage_level VARCHAR(16) NULL,
    due_at TIMESTAMP NOT NULL,
    closed_at TIMESTAMP NOT NULL,
    overdue_days INT NOT NULL,
    billable_days INT NOT NULL,
    replacement_price_snapshot DECIMAL(10,2) NULL,
    grace_days_snapshot INT NOT NULL,
    daily_overdue_fee_snapshot DECIMAL(10,2) NOT NULL,
    overdue_cap_snapshot DECIMAL(10,2) NOT NULL,
    condition_rate_snapshot DECIMAL(5,4) NULL,
    overdue_amount DECIMAL(10,2) NOT NULL,
    damage_amount DECIMAL(10,2) NOT NULL,
    lost_amount DECIMAL(10,2) NOT NULL,
    total_amount DECIMAL(10,2) NOT NULL,
    reason VARCHAR(255) NULL,
    created_by_user_id BIGINT NOT NULL,
    payment_operation_id CHAR(36) NULL UNIQUE,
    payment_reference_no VARCHAR(64) NULL UNIQUE,
    paid_at TIMESTAMP NULL,
    waived_by_user_id BIGINT NULL,
    waived_reason VARCHAR(255) NULL,
    waived_at TIMESTAMP NULL,
    refund_operation_id CHAR(36) NULL UNIQUE,
    refund_reference_no VARCHAR(64) NULL UNIQUE,
    refunded_by_user_id BIGINT NULL,
    refunded_reason VARCHAR(255) NULL,
    refunded_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_library_fee_status CHECK (status IN ('PENDING', 'PAID', 'WAIVED', 'REFUNDED')),
    CONSTRAINT chk_library_fee_condition CHECK (return_condition IN ('NORMAL', 'DAMAGED', 'LOST')),
    CONSTRAINT chk_library_fee_damage CHECK (
        (return_condition = 'DAMAGED' AND damage_level IN ('MINOR', 'MAJOR')) OR
        (return_condition <> 'DAMAGED' AND damage_level IS NULL)),
    CONSTRAINT chk_library_fee_amounts CHECK (
        overdue_amount >= 0.00 AND damage_amount >= 0.00 AND lost_amount >= 0.00 AND
        total_amount > 0.00 AND total_amount = overdue_amount + damage_amount + lost_amount),
    CONSTRAINT fk_library_fee_loan FOREIGN KEY (loan_id) REFERENCES library_loans(id),
    CONSTRAINT fk_library_fee_borrower FOREIGN KEY (borrower_user_id) REFERENCES users(id),
    CONSTRAINT fk_library_fee_book FOREIGN KEY (book_id) REFERENCES books(id),
    CONSTRAINT fk_library_fee_copy FOREIGN KEY (copy_id) REFERENCES book_copies(id),
    CONSTRAINT fk_library_fee_creator FOREIGN KEY (created_by_user_id) REFERENCES users(id),
    CONSTRAINT fk_library_fee_waiver FOREIGN KEY (waived_by_user_id) REFERENCES users(id),
    CONSTRAINT fk_library_fee_refunder FOREIGN KEY (refunded_by_user_id) REFERENCES users(id),
    INDEX idx_library_fee_owner_status (borrower_user_id, status, created_at),
    INDEX idx_library_fee_status_time (status, created_at)
);
```

Extend the existing notification type/target checks and bank ledger type check without dropping any current values. Add `('LIBRARY_FEE_BILL', 1)` to `library_code_sequences`; format bill numbers as `LFB` plus nine digits.

- [x] **Step 4: Seed realistic reference prices**

Modify every demo `books` insert/update in `database/seed.sql` to set an explicit two-decimal `replacement_price`, using varied values such as `49.00`, `68.00`, and `89.00`. Keep the seed idempotent and do not backfill arbitrary prices in the migration for real existing rows.

- [x] **Step 5: Rerun tests and commit**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -am -Dtest=LibraryFeeMigrationTest,DatabaseScriptsTest -Dsurefire.failIfNoSpecifiedTests=false test
git add database/schema.sql database/seed.sql database/migrations/022_library_fees.sql vcampus-server/src/test/java/com/vcampus/server/database/LibraryFeeMigrationTest.java vcampus-server/src/test/java/com/vcampus/server/database/DatabaseScriptsTest.java
git commit -m "feat(library): add fee bill schema"
```

Expected: both migration tests pass.

---

### Task 3: Persist and expose the replacement price

**Files:**

- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/LibraryCatalogStore.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/LibraryCatalogRepository.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/LibraryService.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/LibraryCatalogRepositoryTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/service/LibraryServiceTest.java`

**Interfaces:**

- Produces: `CatalogItem.replacementPrice()` and `BookCommand.replacementPrice()` as nullable `BigDecimal`; create/update service requests require a positive value.
- Produces: response metadata `replacementPrice` and `row.N.replacementPrice` consumed by Task 8.

- [x] **Step 1: Write failing repository tests**

Add `replacement_price` to the H2 fixture and assert create, update, search, and find preserve exact scale-2 values:

```java
BookCommand command = new BookCommand(null, "Java 核心技术", "Cay Horstmann",
        "机械工业出版社", 2026, "计算机", "说明", new BigDecimal("89.00"));
CreatedBook created = repository.createBook(command);
assertEquals(new BigDecimal("89.00"), repository.findBook(created.bookId()).orElseThrow().replacementPrice());
```

Also assert `new BigDecimal("0.00")` is rejected and that reading a migrated historical null remains supported.

- [x] **Step 2: Write failing service payload tests**

Assert create/update reject a blank, zero, negative, or more-than-two-decimal `replacementPrice`; assert detail returns `replacementPrice=89.00` while catalog rows carry `row.0.replacementPrice=89.00` as metadata.

- [x] **Step 3: Run the catalog/service tests and confirm the red state**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -am -Dtest=LibraryCatalogRepositoryTest,LibraryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: record constructors and payload keys do not exist.

- [x] **Step 4: Add price to catalog SQL and service encoding**

Extend the records with compatibility constructors that pass `null` for the new final field:

```java
record CatalogItem(long bookId, String catalogCode, String isbn, String title,
        String authors, String publisher, Integer publishYear, String category,
        String description, boolean enabled, int totalCopies, int availableCopies,
        int onLoanCopies, long borrowCount, BigDecimal replacementPrice) { }

record BookCommand(String isbn, String title, String authors, String publisher,
        Integer publishYear, String category, String description,
        BigDecimal replacementPrice) { }
```

Select `b.replacement_price`, bind it in `INSERT`/`UPDATE`, parse request text with `MoneyPolicy.parsePositive`, and reject stored command values that cannot be represented at exact scale 2. Add metadata instead of changing the legacy `RowCodec` column count:

```java
data.put(prefix + "replacementPrice",
        row.replacementPrice() == null ? "" : row.replacementPrice().toPlainString());
```

- [x] **Step 5: Rerun tests and commit**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -am -Dtest=LibraryCatalogRepositoryTest,LibraryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
git add vcampus-server/src/main/java/com/vcampus/server/database/LibraryCatalogStore.java vcampus-server/src/main/java/com/vcampus/server/database/LibraryCatalogRepository.java vcampus-server/src/main/java/com/vcampus/server/service/LibraryService.java vcampus-server/src/test/java/com/vcampus/server/database/LibraryCatalogRepositoryTest.java vcampus-server/src/test/java/com/vcampus/server/service/LibraryServiceTest.java
git commit -m "feat(library): maintain replacement prices"
```

Expected: focused tests pass without changing legacy row widths.

---

### Task 4: Define fee persistence boundaries and bill creation

**Files:**

- Create: `vcampus-server/src/main/java/com/vcampus/server/database/LibraryFeeWriter.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/LibraryFeeStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/LibraryFeeRepository.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/LibraryFeeRepositoryTest.java`

**Interfaces:**

- Consumes: `LibraryFeePolicy`, `LibraryFeeAssessment`, `LibraryDamageLevel`, `LibraryFeeStatus`.
- Produces: transaction-aware `hasPending`/`createForReturn`, fee preview, owner/admin search, detail, and policy operations. Payment state transitions are added in Task 6 after the bank port exists.

- [x] **Step 1: Write failing fee repository creation tests**

Use H2 fixtures for users, books, copies, loans, policy, bills, notifications, and code sequence. Assert:

```java
LibraryFeeAssessment preview = repository.previewReturn(new ReturnFeePreview(
        "B000000001", Instant.parse("2026-09-03T08:00:00Z"),
        LibraryReturnCondition.DAMAGED, LibraryDamageLevel.MAJOR));
assertEquals(new BigDecimal("44.50"), preview.damageAmount());

Optional<CreatedBill> bill = LibraryTransactions.run(connections, connection ->
        repository.createForReturn(connection, new ClosedLoan(
                7L, 11L, 13L, 17L, "Java 核心技术", "B000000001",
                dueAt, returnedAt, LibraryReturnCondition.DAMAGED,
                LibraryDamageLevel.MAJOR, "封面及书脊严重破损", 3L)));
assertEquals(new BigDecimal("45.00"), bill.orElseThrow().totalAmount());
assertEquals(1, notificationCount(NotificationType.LIBRARY_FEE_CREATED));
```

Cover normal zero-fee return (empty result), unique loan idempotency, missing price for damage/loss, exact policy/price snapshots, and pending detection.

- [x] **Step 2: Run the repository test and confirm the red state**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -am -Dtest=LibraryFeeRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: fee store classes do not exist.

- [x] **Step 3: Define exact store records and operations**

`LibraryFeeWriter`:

```java
public interface LibraryFeeWriter {
    LibraryFeeWriter NONE = new LibraryFeeWriter() {
        public boolean hasPending(Connection connection, long borrowerUserId) { return false; }
        public Optional<CreatedBill> createForReturn(Connection connection, ClosedLoan loan) {
            return Optional.empty();
        }
    };

    boolean hasPending(Connection connection, long borrowerUserId) throws SQLException;
    Optional<CreatedBill> createForReturn(Connection connection, ClosedLoan loan) throws SQLException;

    record ClosedLoan(long loanId, long borrowerUserId, long bookId, long copyId,
            String title, String barcode, Instant dueAt, Instant closedAt,
            LibraryReturnCondition condition, LibraryDamageLevel damageLevel,
            String reason, long operatorUserId) { }
    record CreatedBill(long billId, String billNo, BigDecimal totalAmount) { }
}
```

`LibraryFeeStore`:

```java
public interface LibraryFeeStore extends LibraryFeeWriter {
    LibraryFeePolicy currentPolicy() throws SQLException;
    LibraryFeePolicy updatePolicy(long operatorUserId, LibraryFeePolicy policy) throws SQLException;
    LibraryFeeAssessment previewReturn(ReturnFeePreview command) throws SQLException;
    FeeBillPage searchOwner(long userId, FeeBillQuery query) throws SQLException;
    FeeBillPage searchAll(FeeBillQuery query) throws SQLException;
    Optional<FeeBill> findForOwner(long userId, long billId) throws SQLException;
    Optional<FeeBill> find(long billId) throws SQLException;
}
```

Define the records in the same interface with these exact shapes; page constructors use `List.copyOf`. Task 6 adds `PaymentPreview`, `PaymentReceipt`, and the four state-changing methods after defining the bank port.

```java
record ReturnFeePreview(String barcode, Instant closedAt,
        LibraryReturnCondition condition, LibraryDamageLevel damageLevel) { }

record FeeBillQuery(String keyword, LibraryFeeStatus status, int page, int pageSize) { }

record FeeBill(long billId, String billNo, long loanId, long borrowerUserId,
        String borrowerUsername, String borrowerDisplayName, long bookId, long copyId,
        String title, String barcode, LibraryFeeStatus status,
        LibraryReturnCondition returnCondition, LibraryDamageLevel damageLevel,
        Instant dueAt, Instant closedAt, int overdueDays, int billableDays,
        BigDecimal replacementPriceSnapshot, int graceDaysSnapshot,
        BigDecimal dailyOverdueFeeSnapshot, BigDecimal overdueCapSnapshot,
        BigDecimal conditionRateSnapshot, BigDecimal overdueAmount,
        BigDecimal damageAmount, BigDecimal lostAmount, BigDecimal totalAmount,
        String reason, String paymentReferenceNo, Instant paidAt,
        String waivedReason, Instant waivedAt, String refundReferenceNo,
        String refundedReason, Instant refundedAt, Instant createdAt) { }

record FeeBillPage(List<FeeBill> rows, int page, int pageSize, int total) {
    public FeeBillPage { rows = List.copyOf(rows); }
}
```

- [x] **Step 4: Implement preview and atomic bill creation**

`LibraryFeeRepository` initially takes `ConnectionFactory`, `NotificationWriter`, and `ZoneId`; its production-zone constructor uses `Asia/Shanghai`. `previewReturn` joins the active loan, copy, and book and delegates to `LibraryFeePolicy.assess`. Task 6 extends the constructor with `BankPaymentPort` when payment behavior is added.

`createForReturn` must use the caller's connection, fetch policy and price on that connection, generate `LFB%09d`, insert one bill, and add `LIBRARY_FEE_CREATED` targeting `LIBRARY_FEES`. Catch only the unique-loan collision needed for idempotent readback; let other SQL failures roll back the caller's transaction.

Implement `searchOwner` with an enforced `borrower_user_id=?` predicate, `searchAll` with optional bill/user/title/barcode keyword and status predicates, and both detail methods with the same ownership boundary. `currentPolicy` reads singleton row `id=1`; `updatePolicy` updates every policy field plus `changed_by_user_id` in one statement and returns the reread row. Reject pages outside `1..100000` and page sizes outside `1..100` before executing SQL.

- [x] **Step 5: Rerun tests and commit**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -am -Dtest=LibraryFeeRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test
git add vcampus-server/src/main/java/com/vcampus/server/database/LibraryFeeWriter.java vcampus-server/src/main/java/com/vcampus/server/database/LibraryFeeStore.java vcampus-server/src/main/java/com/vcampus/server/database/LibraryFeeRepository.java vcampus-server/src/test/java/com/vcampus/server/database/LibraryFeeRepositoryTest.java
git commit -m "feat(library): create itemized fee bills"
```

Expected: creation, snapshots, notification, and idempotency tests pass.

---

### Task 5: Make return closeout and borrowing restrictions atomic

**Files:**

- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/LibraryLoanStore.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/LibraryLoanRepository.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/database/LibraryLoanRepositoryTest.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/LibraryFeeCloseoutTransactionTest.java`

**Interfaces:**

- Consumes: `LibraryFeeWriter.hasPending` and `createForReturn` from Task 4.
- Produces: `ReturnCommand.damageLevel()` and fee-bearing `ReturnReceipt` used by services and clients.

- [x] **Step 1: Write failing restriction and closeout tests**

Inject a recording/failing `LibraryFeeWriter` and assert:

```java
assertThrows(LibraryRuleException.class, () -> repository.borrow(commandFor(userWithPendingFee)));
assertThrows(LibraryRuleException.class, () -> repository.renew(renewFor(userWithPendingFee)));

ReturnReceipt receipt = repository.returnLoan(new ReturnCommand(
        borrowerId, loanId, null, adminId, LibraryReturnCondition.DAMAGED,
        LibraryDamageLevel.MINOR, "封面轻微破损", returnedAt, true));
assertEquals("LFB000000001", receipt.feeBillNo());
assertEquals(new BigDecimal("17.80"), receipt.feeTotal());
```

In `LibraryFeeCloseoutTransactionTest`, make notification insertion fail during bill creation and assert `returned_at` remains null, copy remains `ON_LOAN`, and no bill remains.

- [x] **Step 2: Run tests and confirm the red state**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -am -Dtest=LibraryLoanRepositoryTest,LibraryFeeCloseoutTransactionTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: constructors and fee-writer integration do not exist.

- [x] **Step 3: Extend loan commands/receipts compatibly**

Use these final shapes and keep overloads matching old call sites:

```java
record ReturnCommand(long borrowerUserId, Long loanId, String barcode,
        long operatorUserId, LibraryReturnCondition condition,
        LibraryDamageLevel damageLevel, String reason,
        Instant returnedAt, boolean administrator) { }

record ReturnReceipt(long loanId, long copyId, String barcode,
        LibraryReturnCondition condition, Instant returnedAt,
        Long feeBillId, String feeBillNo, BigDecimal feeTotal) { }
```

Validate that `DAMAGED` requires `MINOR` or `MAJOR`; `NORMAL` and `LOST` require a null damage level.

- [x] **Step 4: Integrate the writer inside existing transactions**

Add a `LibraryFeeWriter` constructor dependency with a no-op compatibility implementation for legacy tests. Production wiring must never use the no-op.

Before borrow capacity checks and before renewal mutation, call:

```java
if (fees.hasPending(connection, borrowerUserId)) {
    throw new LibraryRuleException("存在待缴图书费用，暂时不能借书或续借");
}
```

Extend `lockReturnLoan` to read book id and due time. After updating the loan/copy and before commit, call `createForReturn` with the locked values, then include its identifiers in the receipt. Keep the existing return receipt notification and reservation notification in the same transaction.

- [x] **Step 5: Rerun tests and commit**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -am -Dtest=LibraryLoanRepositoryTest,LibraryFeeCloseoutTransactionTest -Dsurefire.failIfNoSpecifiedTests=false test
git add vcampus-server/src/main/java/com/vcampus/server/database/LibraryLoanStore.java vcampus-server/src/main/java/com/vcampus/server/database/LibraryLoanRepository.java vcampus-server/src/test/java/com/vcampus/server/database/LibraryLoanRepositoryTest.java vcampus-server/src/test/java/com/vcampus/server/database/LibraryFeeCloseoutTransactionTest.java
git commit -m "feat(library): create fees during loan closeout"
```

Expected: both focused suites pass and the failure test proves complete rollback.

---

### Task 6: Add atomic bank payment, waiver, and refund

**Files:**

- Create: `vcampus-server/src/main/java/com/vcampus/server/database/BankPaymentPort.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/BankRepository.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/LibraryFeeStore.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/LibraryFeeRepository.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/database/BankRepositoryTest.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/LibraryFeePaymentTransactionTest.java`

**Interfaces:**

- Produces: bank account snapshot, library debit, and library refund operations.
- Completes: `LibraryFeeStore.paymentPreview`, `pay`, `waive`, and `refund` from Task 4.

- [x] **Step 1: Write failing bank-port tests**

Use the real H2-backed bank repository:

```java
BankPaymentPort.AccountSnapshot account = repository.paymentAccount(userId);
assertEquals(new BigDecimal("50.00"), account.balance());

LibraryPaymentResult debit = repository.debitForLibrary(
        connection, userId, new BigDecimal("12.50"),
        "LIBRARY-FEE-LFB000000001", "图书费用 LFB000000001");
assertEquals(new BigDecimal("37.50"), debit.balanceAfter());
assertEquals("LIBRARY_FEE", ledgerType(debit.ledgerId()));
```

Assert frozen accounts cannot debit, insufficient balance returns `BankRuleException("余额不足")`, frozen accounts can receive a refund, and the same type/reference/amount returns the existing ledger as a duplicate.

- [x] **Step 2: Write failing fee payment transaction tests**

Cover all of these with real H2 tables and injected failing notifications:

- payment changes `PENDING → PAID`, debits once, records payment operation/reference, and sends `LIBRARY_FEE_PAID`;
- same operation ID returns the original receipt as duplicate;
- a different operation against a paid bill returns “账单已缴清”;
- concurrent callers create exactly one debit ledger;
- insufficient/frozen payment leaves bill pending and balance unchanged;
- waiver changes only a pending bill and sends `LIBRARY_FEE_WAIVED`;
- refund changes `PAID → REFUNDED`, credits the full paid amount, and sends `LIBRARY_FEE_REFUNDED`;
- failed notification rolls payment/refund and ledger back completely.

- [x] **Step 3: Run tests and confirm the red state**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -am -Dtest=BankRepositoryTest,LibraryFeePaymentTransactionTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `BankPaymentPort` and fee state transitions are missing.

- [x] **Step 4: Implement a narrow bank port and state transitions**

Use this port:

```java
public interface BankPaymentPort {
    AccountSnapshot paymentAccount(long userId) throws SQLException;
    LibraryPaymentResult debitForLibrary(Connection connection, long userId,
            BigDecimal amount, String referenceNo, String description) throws SQLException;
    LibraryPaymentResult refundForLibrary(Connection connection, long userId,
            BigDecimal amount, String referenceNo, String description) throws SQLException;

    record AccountSnapshot(long accountId, BigDecimal balance, BankAccountStatus status) { }
    record LibraryPaymentResult(long accountId, long ledgerId,
            BigDecimal balanceAfter, boolean duplicate) { }
}
```

Make `BankRepository` implement it. Refactor its private shop movement helper into a type-parameterized internal helper without changing `BankPaymentWriter` behavior. Library payments use stable references `LIBRARY-FEE-<billNo>` and `LIBRARY-REFUND-<billNo>`.

Extend `LibraryFeeStore` with:

```java
PaymentPreview paymentPreview(long userId, long billId) throws SQLException;
PaymentReceipt pay(long userId, long billId, String operationId, Instant now) throws SQLException;
FeeBill waive(long operatorUserId, long billId, String reason, Instant now) throws SQLException;
PaymentReceipt refund(long operatorUserId, long billId, String reason,
                      String operationId, Instant now) throws SQLException;
```

Define `PaymentPreview(FeeBill bill, BigDecimal balance, BankAccountStatus accountStatus)` and `PaymentReceipt(FeeBill bill, long ledgerId, BigDecimal balanceAfter, boolean duplicate)` in the store. Extend `LibraryFeeRepository` with the required `BankPaymentPort` constructor dependency, then lock bills with `FOR UPDATE`. Check stored operation IDs before state rejection so a lost-response retry returns the first receipt. Payment, waiver, and refund each use `LibraryTransactions.run`; write status, timestamps, bank references, and notification before commit.

- [x] **Step 5: Rerun tests and commit**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -am -Dtest=BankRepositoryTest,LibraryFeePaymentTransactionTest -Dsurefire.failIfNoSpecifiedTests=false test
git add vcampus-server/src/main/java/com/vcampus/server/database/BankPaymentPort.java vcampus-server/src/main/java/com/vcampus/server/database/BankRepository.java vcampus-server/src/main/java/com/vcampus/server/database/LibraryFeeStore.java vcampus-server/src/main/java/com/vcampus/server/database/LibraryFeeRepository.java vcampus-server/src/test/java/com/vcampus/server/database/BankRepositoryTest.java vcampus-server/src/test/java/com/vcampus/server/database/LibraryFeePaymentTransactionTest.java
git commit -m "feat(library): pay and refund fees through bank"
```

Expected: all bank and fee transaction tests pass.

---

### Task 7: Expose authorized service actions and production wiring

**Files:**

- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/LibraryService.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/LibraryServiceTest.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterLibraryFeeTest.java`

**Interfaces:**

- Consumes: `LibraryFeeStore`, extended loan commands, and bank-backed repository behavior.
- Produces: exact response payloads consumed by Task 8.

- [x] **Step 1: Write failing service authorization and payload tests**

Inject a fake `LibraryFeeStore` and assert:

- a borrower can list/get/pay only their own bill;
- another user's bill produces “账单不存在或无权查看”;
- staff-only search/preview/waive/refund/policy methods reject ordinary users;
- bank admins without library roles are rejected;
- invalid UUID, amount fields, damage-level combinations, and empty reasons return exact business messages;
- success paths record audit `SUCCESS`, permission failures `DENIED`, validation `INVALID`, state/balance conflicts `CONFLICT`, and SQL failures `DATABASE_ERROR` using the existing service audit convention.

Assert exact list row order:

```text
billId, billNo, loanId, title, barcode, status,
overdueAmount, damageAmount, lostAmount, totalAmount,
createdAt, paidAt, borrowerUsername, borrowerDisplayName
```

Owner rows leave the last two fields empty. Detail responses add snapshot fields as named map entries. Payment preview adds `balance` and `accountStatus`.

- [x] **Step 2: Write failing router tests**

Build the router with a recording `LibraryService` and assert all ten constants dispatch to their matching method. Also assert forced-password routing recognizes them as authenticated actions.

- [x] **Step 3: Run service/router tests and confirm the red state**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -am -Dtest=LibraryServiceTest,RequestRouterLibraryFeeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: fee service methods/routes are absent.

- [x] **Step 4: Implement validation, encoding, routing, and wiring**

Add a fee-aware `LibraryService` constructor while preserving existing overloads. Implement methods named:

```java
myFees, feeDetail, paymentPreview, payFee,
searchFees, previewFee, waiveFee, refundFee,
getFeePolicy, updateFeePolicy
```

Parse money with exact two/four-decimal validation, parse `LibraryDamageLevel` only for damaged returns, and parse operation IDs as UUID strings. Extend `returnData` with optional `feeBillId`, `feeBillNo`, and `feeTotal`.

In production wiring, construct one `BankRepository`, one `LibraryFeeRepository`, inject that fee repository into `LibraryLoanRepository` and `LibraryService`, and inject the real audit/notification dependencies. Route all ten actions through `RequestRouter`.

- [x] **Step 5: Rerun tests and commit**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -am -Dtest=LibraryServiceTest,RequestRouterLibraryFeeTest -Dsurefire.failIfNoSpecifiedTests=false test
git add vcampus-server/src/main/java/com/vcampus/server/service/LibraryService.java vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java vcampus-server/src/test/java/com/vcampus/server/service/LibraryServiceTest.java vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterLibraryFeeTest.java
git commit -m "feat(library): expose fee service actions"
```

Expected: service permissions, payloads, audit, and routes pass.

---

### Task 8: Add client requests and strict response decoding

**Files:**

- Modify: `vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/library/LibraryData.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/network/VCampusClientTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/library/LibraryDataTest.java`

**Interfaces:**

- Consumes: Task 7 action payloads.
- Produces: typed `LibraryData` values used by both new views.

- [x] **Step 1: Write failing socket request tests**

Capture outgoing requests and assert action plus exact parameters for each method. The public client signatures are:

```java
ResponseMessage myLibraryFees(String token, String status, int page);
ResponseMessage getLibraryFee(String token, long billId);
ResponseMessage previewLibraryFeePayment(String token, long billId);
ResponseMessage payLibraryFee(String token, long billId, String operationId);
ResponseMessage searchLibraryFees(String token, Map<String,String> filters);
ResponseMessage previewLibraryReturnFee(String token, String barcode,
        LibraryReturnCondition condition, LibraryDamageLevel damageLevel);
ResponseMessage waiveLibraryFee(String token, long billId, String reason);
ResponseMessage refundLibraryFee(String token, long billId, String reason, String operationId);
ResponseMessage getLibraryFeePolicy(String token);
ResponseMessage updateLibraryFeePolicy(String token, Map<String,String> values);
```

Extend `adminReturnLibraryCopy` with a damage-level parameter and keep an overload for existing callers.

- [x] **Step 2: Write failing decoder tests**

Add records:

```java
record FeeBill(long billId, String billNo, long loanId, String title, String barcode,
        LibraryFeeStatus status, BigDecimal overdueAmount, BigDecimal damageAmount,
        BigDecimal lostAmount, BigDecimal totalAmount, Instant createdAt, Instant paidAt,
        String borrowerUsername, String borrowerDisplayName) { }
record FeeDetail(FeeBill bill, int overdueDays, int billableDays,
        LibraryReturnCondition condition, LibraryDamageLevel damageLevel,
        BigDecimal replacementPrice, String reason, String paymentReference,
        String refundReference) { }
record FeePreview(LibraryFeeAssessment assessment, String title, String barcode) { }
record PaymentPreview(FeeDetail detail, BigDecimal balance, BankAccountStatus accountStatus) { }
```

Test valid owner/admin pages, nullable fields, exact two-decimal scale, row-width rejection, negative IDs/amounts, item sum mismatch, and unknown enum rejection.

- [x] **Step 3: Run client data/request tests and confirm the red state**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-client -am -Dtest=VCampusClientTest,LibraryDataTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: methods and records do not exist.

- [x] **Step 4: Implement exact request maps and defensive parsers**

Use `sendAuthorized` for every request, `UUID.randomUUID().toString()` only in controllers at the start of a logical mutation, and `RowCodec.decode` for list rows. `LibraryData.require` must preserve server business messages; do not convert a failed response into an `IOException`.

Parse `replacementPrice` metadata into the existing `LibraryData.Book` record as a nullable final field, preserving a compatibility constructor for current tests.

- [x] **Step 5: Rerun tests and commit**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-client -am -Dtest=VCampusClientTest,LibraryDataTest -Dsurefire.failIfNoSpecifiedTests=false test
git add vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java vcampus-client/src/main/java/com/vcampus/client/fx/library/LibraryData.java vcampus-client/src/test/java/com/vcampus/client/network/VCampusClientTest.java vcampus-client/src/test/java/com/vcampus/client/fx/library/LibraryDataTest.java
git commit -m "feat(library): add fee client protocol"
```

Expected: request and decoder tests pass.

---

### Task 9: Build the borrower fee experience

**Files:**

- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/library/LibraryFeeView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/library/LibraryView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/library/LibraryController.java`
- Modify: `vcampus-client/src/main/resources/com/vcampus/client/fx/library/library.css`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/library/LibraryFeeViewTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/library/LibraryViewTest.java`

**Interfaces:**

- Consumes: `LibraryData.FeeBill`, `FeeDetail`, and `PaymentPreview`.
- Produces: route `fees`, bill detail, explicit confirmation, and idempotent payment request.

- [ ] **Step 1: Write failing JavaFX view tests**

On the FX thread, assert:

- borrowers have a `#library-tab-fees` labeled “待缴费用”;
- catalog cards contain no replacement-price label;
- `showDetail` contains `#library-detail-replacement-price` only on detail;
- fee rows show itemized amounts and status;
- payment confirmation shows total, current balance, and frozen/insufficient warning;
- busy state disables duplicate payment;
- server messages “余额不足，账单仍待缴” and “银行账户已冻结，暂时无法缴费” remain visible.

- [ ] **Step 2: Run the view tests and confirm the red state**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-client -am -Dtest=LibraryFeeViewTest,LibraryViewTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: fee tab/view and price-detail node are absent.

- [ ] **Step 3: Implement the focused fee view**

Use a listener that exposes only navigation/payment intent:

```java
interface Listener {
    void list(String status, int page);
    void detail(long billId);
    void requestPayment(long billId);
    void confirmPayment(long billId, String operationId);
}
```

Render `PENDING/PAID/WAIVED/REFUNDED` as Chinese labels. Detail shows every nonzero fee line, calculation inputs, reason, price/policy snapshot, and bank references. The “立即缴费” button exists only for `PENDING`.

- [ ] **Step 4: Integrate controller flow and price detail**

Add route `fees` in `LibraryController.open`. Load list/detail off-thread. Payment is a two-stage flow: first fetch `payment.preview`, then show a confirmation dialog; create one UUID only after user confirms and retain it until that request completes so retry uses the same ID.

In `LibraryView.showDetail`, display:

```text
遗失赔偿参考价  ¥89.00
该价格仅用于遗失或严重破损赔偿，不是图书售价。
```

For a migrated null price show “赔偿参考价暂未设置”; do not render price anywhere in catalog cards or loan rows.

- [ ] **Step 5: Rerun tests and commit**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-client -am -Dtest=LibraryFeeViewTest,LibraryViewTest -Dsurefire.failIfNoSpecifiedTests=false test
git add vcampus-client/src/main/java/com/vcampus/client/fx/library/LibraryFeeView.java vcampus-client/src/main/java/com/vcampus/client/fx/library/LibraryView.java vcampus-client/src/main/java/com/vcampus/client/fx/library/LibraryController.java vcampus-client/src/main/resources/com/vcampus/client/fx/library/library.css vcampus-client/src/test/java/com/vcampus/client/fx/library/LibraryFeeViewTest.java vcampus-client/src/test/java/com/vcampus/client/fx/library/LibraryViewTest.java
git commit -m "feat(library): add borrower fee payment UI"
```

Expected: the borrower flow tests pass with no network work on the FX thread.

---

### Task 10: Build staff price, assessment, bill, and policy controls

**Files:**

- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/library/LibraryFeeAdminView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/library/LibraryAdminView.java`
- Modify: `vcampus-client/src/main/resources/com/vcampus/client/fx/library/library.css`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/library/LibraryFeeAdminViewTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/library/LibraryAdminViewTest.java`

**Interfaces:**

- Consumes: admin fee actions and catalog replacement-price metadata.
- Produces: mandatory price edit, fee preview before abnormal return, bill management, and policy configuration.

- [ ] **Step 1: Write failing admin JavaFX tests**

Assert on the FX thread:

- the book form contains `#library-admin-form-replacement-price`, accepts `89` as `89.00`, and rejects blank/zero/negative/three-decimal values;
- `DAMAGED` return requires `#library-admin-damage-level` and reason;
- return confirmation cannot proceed until an async server fee preview returns;
- preview displays overdue, damage/loss, and total lines;
- a new “费用管理” tab hosts search/status filter/detail;
- waive/refund require a nonblank reason and show a second confirmation;
- policy fields validate `1`, `0.50`, `20.00`, `0.2000`, `0.5000`, `1.0000` before submission;
- closing/deactivating cancels stale callbacks and closes dialogs.

- [ ] **Step 2: Run admin view tests and confirm the red state**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-client -am -Dtest=LibraryFeeAdminViewTest,LibraryAdminViewTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: price, damage level, fee preview, and fee management controls are missing.

- [ ] **Step 3: Implement `LibraryFeeAdminView` as a focused child**

Give it its own executor-safe request generation, tables, paging, and dialogs rather than adding all fee code to `LibraryAdminView`. Its listener/request boundary calls the five staff operations: search, detail, waive, refund, and get/update policy. Use the existing `LibraryUi` styles and add only fee-specific CSS selectors.

For refund, create one UUID after confirmation and retain it for the logical request. Refresh bills and unread count after successful waiver/refund.

- [ ] **Step 4: Integrate book and circulation forms**

Add replacement price to the existing book `GridPane` and request map. For returns:

```java
if (condition == LibraryReturnCondition.DAMAGED && damageLevel == null) {
    setStatus("请选择破损程度。", true);
    return;
}
```

After circulation identity/copy validation, call `previewLibraryReturnFee` with the selected condition/level. Show the itemized server result in the final confirmation, then call the extended admin return method. If the server recomputes a different amount, show the amount from the final return receipt and refresh the generated bill.

- [ ] **Step 5: Rerun tests and commit**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-client -am -Dtest=LibraryFeeAdminViewTest,LibraryAdminViewTest -Dsurefire.failIfNoSpecifiedTests=false test
git add vcampus-client/src/main/java/com/vcampus/client/fx/library/LibraryFeeAdminView.java vcampus-client/src/main/java/com/vcampus/client/fx/library/LibraryAdminView.java vcampus-client/src/main/resources/com/vcampus/client/fx/library/library.css vcampus-client/src/test/java/com/vcampus/client/fx/library/LibraryFeeAdminViewTest.java vcampus-client/src/test/java/com/vcampus/client/fx/library/LibraryAdminViewTest.java
git commit -m "feat(library): add staff fee management UI"
```

Expected: admin view tests pass and existing inventory/circulation tests remain green.

---

### Task 11: Connect notification navigation and finish acceptance coverage

**Files:**

- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/CampusApplication.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/CampusFlowTest.java`
- Modify: `vcampus-common/src/test/java/com/vcampus/common/model/NotificationVocabularyTest.java`
- Modify: `docs/requirements.md`
- Modify: `docs/library.md`
- Modify: `docs/bank.md`
- Modify: `docs/message-center.md`

**Interfaces:**

- Consumes: `NotificationTarget.LIBRARY_FEES` and completed client/server flows.
- Produces: message-center deep links and complete user/admin documentation.

- [ ] **Step 1: Write failing notification navigation tests**

Assert that opening a notification with target `LIBRARY_FEES` activates the library module and calls `LibraryController.openFee(referenceId)`. Cover created/paid/waived/refunded notification types and a stale/deleted bill that falls back to the fee list with a visible message.

- [ ] **Step 2: Run flow and vocabulary tests and confirm the red state**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-client -am -Dtest=CampusFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-common -Dtest=NotificationVocabularyTest test
```

Expected: `LIBRARY_FEES` has no client route.

- [ ] **Step 3: Implement the deep link**

Add `LibraryController.openFee(long billId)` and route `NotificationTarget.LIBRARY_FEES` to it. Do not mark the notification unread/read implicitly beyond existing message-center behavior. A missing bill opens `fees` and displays “账单不存在或状态已发生变化”。

- [ ] **Step 4: Update user, operator, and requirement documentation**

Document exact defaults, price visibility, fee formulas, pending restrictions, payment confirmation, bank ledger types, refund semantics, notification types/targets, permissions, migration `022`, and the following manual acceptance sequence:

```text
1. Admin creates a ¥89.00 book; verify price appears only in detail.
2. Return within grace period; verify no bill.
3. Return on second overdue day; verify ¥0.50 pending bill and notification.
4. Return a long-overdue major-damaged copy; verify ¥20.00 + 50% snapshot.
5. Attempt borrow/renew with pending bill; verify blocked while return remains allowed.
6. Pay with insufficient balance; verify exact message and no ledger.
7. Top up, then pay; verify one debit, paid bill, and notification.
8. Retry payment and run two-client payment; verify one debit total.
9. Waive an unpaid bill and refund a paid bill with reasons.
10. Edit reference price/policy; verify old bill remains unchanged.
```

Mark the feature “implemented, pending local MySQL multi-client acceptance” until all ten scenarios pass.

- [ ] **Step 5: Run module and full verification**

```powershell
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-common test
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-server -am test
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" -pl vcampus-client -am test
mvn "-Dmaven.repo.local=C:\Users\Bestinme\.m2\repository" clean verify
git diff --check
```

Expected: all commands exit `0`; no test reports failures; `git diff --check` prints nothing.

- [ ] **Step 6: Commit documentation and navigation**

```powershell
git add vcampus-client/src/main/java/com/vcampus/client/fx/CampusApplication.java vcampus-client/src/test/java/com/vcampus/client/fx/CampusFlowTest.java vcampus-common/src/test/java/com/vcampus/common/model/NotificationVocabularyTest.java docs/requirements.md docs/library.md docs/bank.md docs/message-center.md
git commit -m "docs(library): document fee payment workflow"
```

Expected: the final task commit contains only navigation tests/code and documentation; the implementation worktree is clean afterward.

---

## Final Review Checklist

- [ ] Each of the ten approved actions is defined once, routed once, authorized server-side, and covered by a request/route test.
- [ ] Catalog lists do not render price; detail and staff edit do.
- [ ] Fee rules match: 1-day grace, ¥0.50/day, ¥20 cap, 20% minor, 50% major, 100% loss.
- [ ] Price and policy snapshots survive later edits.
- [ ] Return/bill/notification and payment/ledger/status/notification transactions roll back as units.
- [ ] Pending bills block borrow/renew only.
- [ ] Same-operation retry and concurrent payment cannot double debit.
- [ ] Frozen/insufficient business errors reach the UI without becoming connection errors.
- [ ] Waiver and refund require reasons and write audit records.
- [ ] Notifications deep-link to the bill, and paid/refunded details expose bank references.
- [ ] `database/schema.sql`, migration `022`, seed data, and all four docs agree.
- [ ] `mvn clean verify` and `git diff --check` pass before claiming completion.
