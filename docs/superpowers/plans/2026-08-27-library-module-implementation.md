# VCampus Library Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the complete VCampus library MVP: catalog and copy management, self-service and administrator circulation, renewal and overdue rules, notifications, and role-aware Swing pages.

**Architecture:** Add shared `library.*` vocabulary and policies in `vcampus-common`, JDBC stores and transactional services in `vcampus-server`, and a role-aware `LibraryFrame` in `vcampus-client`. Keep book metadata, physical copies, and circulation records separate; every mutation is authorized and committed on the server, and every Swing network call runs off the Event Dispatch Thread.

**Tech Stack:** Java 21, Maven multi-module build, Swing, Java Socket, `MessageCodec`/`RowCodec`, JDBC, MySQL 8.0.44, JUnit 5, H2 in MySQL compatibility mode.

**Spec:** `docs/superpowers/specs/2026-08-27-library-module-design.md`

## Global Constraints

- Preserve the three-tier flow: MySQL database -> application server -> Swing client; the client never connects to MySQL.
- Put protocol actions, enums, validation, and access policies in `vcampus-common`; put Socket routing, transactions, scheduling, and JDBC in `vcampus-server`; put Swing and client networking in `vcampus-client`.
- Use the length-prefixed binary `MessageCodec` protocol and `RowCodec`; do not use Java native object serialization.
- Use the `library.` action prefix for every new request.
- Java release is 21; the final verification command is `mvn clean verify`.
- Keep Swing mutations on the Event Dispatch Thread and network/database work off it.
- Never commit database passwords, tokens, generated visual-companion files, or real personal data.
- First release excludes reservations, fines, e-books, procurement, inventory counting, and complex reports.
- Students: 5 active loans, 30-day loan, one 15-day renewal. Teachers: 10 active loans, 60-day loan, one 30-day renewal.
- Any overdue loan blocks new borrowing and renewal but never blocks return or viewing.
- Current exported workspace has no `.git`; execute the listed commit steps only after running the plan in a real Git checkout. Until then, each passing task test suite is the checkpoint.

---

## Planned File Structure

### Shared module

- Create `vcampus-common/src/main/java/com/vcampus/common/model/LibraryCopyStatus.java`: physical-copy state.
- Create `vcampus-common/src/main/java/com/vcampus/common/model/LibraryLoanChannel.java`: self-service versus administrator desk.
- Create `vcampus-common/src/main/java/com/vcampus/common/model/LibraryReturnCondition.java`: normal return versus lost closure.
- Create `vcampus-common/src/main/java/com/vcampus/common/model/LibraryCirculationOperation.java`: administrator preview operation.
- Create `vcampus-common/src/main/java/com/vcampus/common/model/LibraryAccessPolicy.java`: borrowing and management capability.
- Create `vcampus-common/src/main/java/com/vcampus/common/model/LibraryCodePolicy.java`: barcode and ISBN normalization/validation.
- Modify `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`: all sixteen `library.*` constants.
- Modify notification enums for library due/overdue vocabulary and deep-link target.

### Server module

- Create `LibraryCatalogStore.java` and `LibraryLoanStore.java`: focused service-facing persistence contracts and immutable records.
- Create `LibraryCatalogRepository.java`: catalog and copy JDBC queries/mutations.
- Create `LibraryLoanRepository.java`: borrower lookup and circulation transactions.
- Create `LibraryRuleException.java`: readable circulation-rule failures that trigger transaction rollback.
- Create `LibraryLoanPolicy.java`: role-based limits and durations.
- Create `LibraryService.java`: request validation, authorization, response encoding, and store orchestration.
- Create `LibraryNoticeStore.java`, `LibraryNoticeRepository.java`, and `LibraryOverdueNotifier.java`: idempotent reminder scanning and lifecycle.
- Modify `RequestRouter.java` and `VCampusServer.java`: route and wire the subsystem.

### Client module

- Modify `VCampusClient.java`: typed request methods for every library action.
- Create `LibraryViewData.java`: strict parsing of catalog, copy, loan, borrower, and receipt responses.
- Create `LibraryPanel.java`: shared asynchronous request and styling base.
- Create `LibraryFrame.java`: role-aware tab container and notification target selection.
- Create `LibraryCatalogPanel.java`, `MyLibraryLoansPanel.java`, `LibraryInventoryPanel.java`, `LibraryCirculationPanel.java`, and `LibraryLoanManagementPanel.java`: focused UI surfaces.
- Create `LibraryTabPolicy.java`: deterministic role-to-tab mapping for tests.
- Modify `MainFrame.java`: open the library and handle `LIBRARY_LOANS` notifications.

### Database and documentation

- Create `database/migrations/003_library.sql`.
- Modify `database/schema.sql`, `database/seed.sql`, and `docs/requirements.md`.
- Create `docs/library.md`.

---

### Task 1: Shared Library Vocabulary and Policies

**Files:**
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/LibraryCopyStatus.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/LibraryLoanChannel.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/LibraryReturnCondition.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/LibraryCirculationOperation.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/LibraryAccessPolicy.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/LibraryCodePolicy.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/NotificationType.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/NotificationSource.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/NotificationTarget.java`
- Test: `vcampus-common/src/test/java/com/vcampus/common/model/LibraryPolicyTest.java`
- Test: `vcampus-common/src/test/java/com/vcampus/common/model/NotificationVocabularyTest.java`

**Interfaces:**
- Produces: `LibraryAccessPolicy.canBorrow(Set<UserRole>)`, `canManage(Set<UserRole>)`.
- Produces: `LibraryCodePolicy.normalizeIsbn(String)`, `requireValidBarcode(String)`.
- Produces: stable enum values and sixteen action strings consumed by every later task.

- [x] **Step 1: Write failing access and code-policy tests**

```java
@Test void studentAndTeacherBorrowWhileOnlyLibraryAdminsManage() {
    assertTrue(LibraryAccessPolicy.canBorrow(Set.of(UserRole.STUDENT)));
    assertTrue(LibraryAccessPolicy.canBorrow(Set.of(UserRole.TEACHER, UserRole.LIBRARY_ADMIN)));
    assertFalse(LibraryAccessPolicy.canBorrow(Set.of(UserRole.SUPER_ADMIN)));
    assertTrue(LibraryAccessPolicy.canManage(Set.of(UserRole.LIBRARY_ADMIN)));
    assertTrue(LibraryAccessPolicy.canManage(Set.of(UserRole.SUPER_ADMIN)));
}

@Test void normalizesIsbnAndValidatesCampusBarcode() {
    assertEquals("9787111565271", LibraryCodePolicy.normalizeIsbn("978-7-111-56527-1"));
    assertEquals("B000000128", LibraryCodePolicy.requireValidBarcode(" B000000128 "));
    assertThrows(IllegalArgumentException.class,
            () -> LibraryCodePolicy.requireValidBarcode("B128"));
}
```

- [x] **Step 2: Run the tests and verify they fail because the types do not exist**

Run: `mvn -pl vcampus-common -Dtest=LibraryPolicyTest test`

Expected: compilation failure naming `LibraryAccessPolicy` or `LibraryCodePolicy`.

- [x] **Step 3: Add the enums and policy implementations**

```java
public enum LibraryCopyStatus { AVAILABLE, ON_LOAN, LOST, DAMAGED, WITHDRAWN }
public enum LibraryLoanChannel { SELF_SERVICE, ADMIN_DESK }
public enum LibraryReturnCondition { NORMAL, LOST }
public enum LibraryCirculationOperation { BORROW, RETURN }

public final class LibraryAccessPolicy {
    public static boolean canBorrow(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        return roles.contains(UserRole.STUDENT) || roles.contains(UserRole.TEACHER);
    }
    public static boolean canManage(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        return roles.contains(UserRole.LIBRARY_ADMIN) || roles.contains(UserRole.SUPER_ADMIN);
    }
}

```

`normalizeIsbn` must remove spaces and hyphens, uppercase the final ISBN-10 character, and reject values that are not a checksum-valid ISBN-10 or ISBN-13. `requireValidBarcode` must trim and require `^B[0-9]{9}$`.

- [x] **Step 4: Add exact action and notification vocabulary**

```java
public static final String LIBRARY_CATALOG_SEARCH = "library.catalog.search";
public static final String LIBRARY_CATALOG_GET = "library.catalog.get";
public static final String LIBRARY_LOAN_MY = "library.loan.my";
public static final String LIBRARY_LOAN_BORROW = "library.loan.borrow";
public static final String LIBRARY_LOAN_RETURN = "library.loan.return";
public static final String LIBRARY_LOAN_RENEW = "library.loan.renew";
public static final String LIBRARY_ADMIN_BOOK_CREATE = "library.admin.book.create";
public static final String LIBRARY_ADMIN_BOOK_UPDATE = "library.admin.book.update";
public static final String LIBRARY_ADMIN_BOOK_SET_ENABLED = "library.admin.book.set-enabled";
public static final String LIBRARY_ADMIN_COPY_SEARCH = "library.admin.copy.search";
public static final String LIBRARY_ADMIN_COPY_CREATE = "library.admin.copy.create";
public static final String LIBRARY_ADMIN_COPY_SET_STATUS = "library.admin.copy.set-status";
public static final String LIBRARY_ADMIN_LOAN_SEARCH = "library.admin.loan.search";
public static final String LIBRARY_ADMIN_CIRCULATION_PREVIEW = "library.admin.circulation.preview";
public static final String LIBRARY_ADMIN_LOAN_BORROW = "library.admin.loan.borrow";
public static final String LIBRARY_ADMIN_LOAN_RETURN = "library.admin.loan.return";
```

Append `LIBRARY_DUE_SOON`, `LIBRARY_OVERDUE`, `LIBRARY`, and `LIBRARY_LOANS` to the matching notification enums. Extend `NotificationVocabularyTest` to encode and decode those exact names.

- [x] **Step 5: Run shared-module tests**

Run: `mvn -pl vcampus-common test`

Expected: all common tests pass.

- [x] **Step 6: Record the shared contract checkpoint (tests; exported workspace has no Git)**

```bash
git add vcampus-common/src/main vcampus-common/src/test
git commit -m "feat(library): add shared protocol and policies"
```

---

### Task 2: Database Schema and Persistence Contracts

**Files:**
- Create: `database/migrations/003_library.sql`
- Modify: `database/schema.sql`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/LibraryCatalogStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/LibraryLoanStore.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/LibraryMigrationTest.java`

**Interfaces:**
- Consumes: shared library enums from Task 1.
- Produces: three tables, notification constraints, and immutable store records used by Tasks 3–6.

- [x] **Step 1: Write a failing migration contract test**

```java
@Test void migrationDefinesLibraryTablesAndNotificationVocabulary() throws Exception {
    String sql = Files.readString(Path.of("..", "database", "migrations", "003_library.sql"));
    assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS books"));
    assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS book_copies"));
    assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS library_loans"));
    assertTrue(sql.contains("uk_library_active_copy"));
    assertTrue(sql.contains("LIBRARY_DUE_SOON"));
    assertTrue(sql.contains("LIBRARY_LOANS"));
}
```

- [x] **Step 2: Run the migration test and verify the missing-file failure**

Run: `mvn -pl vcampus-server -am -Dtest=LibraryMigrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: failure because `003_library.sql` does not exist.

- [x] **Step 3: Add the migration and mirror it into the canonical schema**

The migration must create the columns and constraints from the approved spec. The active-loan guard must be literal MySQL 8 syntax:

```sql
active_copy_id BIGINT GENERATED ALWAYS AS
    (CASE WHEN returned_at IS NULL THEN copy_id ELSE NULL END) STORED,
UNIQUE KEY uk_library_active_copy (active_copy_id)
```

Drop and recreate the three named notification `CHECK` constraints so their allowed values include `LIBRARY_DUE_SOON`, `LIBRARY_OVERDUE`, `LIBRARY`, and `LIBRARY_LOANS`. Copy the final definitions into `database/schema.sql`; do not make migration and fresh-install schemas diverge.

- [x] **Step 4: Define focused catalog and loan store contracts**

`LibraryCatalogStore` must expose:

```java
CatalogPage search(CatalogQuery query) throws SQLException;
Optional<CatalogItem> findBook(long bookId) throws SQLException;
long createBook(BookCommand command) throws SQLException;
MutationResult updateBook(long bookId, BookCommand command) throws SQLException;
MutationResult setBookEnabled(long bookId, boolean enabled) throws SQLException;
CopyPage searchCopies(CopyQuery query) throws SQLException;
long createCopy(CreateCopy command) throws SQLException;
MutationResult setCopyStatus(long copyId, LibraryCopyStatus status, String reason)
        throws SQLException;
```

Define immutable nested records `CatalogQuery`, `CatalogItem`, `CatalogPage`, `BookCommand`, `CopyQuery`, `CopyRecord`, `CopyPage`, and `CreateCopy`, plus `MutationResult { NOT_FOUND, UNCHANGED, CHANGED, CONFLICT }`. Page records must defensively copy row lists.

Use these exact record fields:

```java
record CatalogQuery(String keyword, String category, int page, int pageSize) { }
record CatalogItem(long bookId, String isbn, String title, String authors,
        String publisher, Integer publishYear, String category, String description,
        boolean enabled, int totalCopies, int availableCopies) { }
record CatalogPage(List<CatalogItem> rows, int page, int pageSize, int total) {
    public CatalogPage { rows = List.copyOf(rows); }
}
record BookCommand(String isbn, String title, String authors, String publisher,
        Integer publishYear, String category, String description) { }
record CopyQuery(Long bookId, String keyword, LibraryCopyStatus status,
        int page, int pageSize) { }
record CopyRecord(long copyId, long bookId, String barcode, String title,
        String shelfLocation, LibraryCopyStatus status, String statusReason,
        Instant updatedAt) { }
record CopyPage(List<CopyRecord> rows, int page, int pageSize, int total) {
    public CopyPage { rows = List.copyOf(rows); }
}
record CreateCopy(long bookId, String barcode, String shelfLocation) { }
```

`LibraryLoanStore` must expose:

```java
Optional<Borrower> findBorrower(String username) throws SQLException;
CirculationPreview previewCirculation(long borrowerUserId, String barcode,
        LibraryCirculationOperation operation, Instant now, int maxActiveLoans)
        throws SQLException;
LoanPage searchBorrowerLoans(long borrowerUserId, LoanQuery query) throws SQLException;
LoanPage searchAllLoans(LoanQuery query) throws SQLException;
BorrowReceipt borrow(BorrowCommand command) throws SQLException;
ReturnReceipt returnLoan(ReturnCommand command) throws SQLException;
RenewReceipt renew(RenewCommand command) throws SQLException;
```

Define these exact records; list-valued page constructors defensively copy rows:

```java
record Borrower(long userId, String username, String displayName,
        UserRole baseIdentity, boolean enabled) { }
record CirculationPreview(long copyId, long bookId, String title, String barcode,
        LibraryCopyStatus copyStatus, Long activeLoanId, int activeLoans, int maxLoans,
        boolean overdue, boolean allowed, String message) { }
record LoanQuery(String keyword, Boolean active, Boolean overdue, int page, int pageSize) { }
record LoanRecord(long loanId, long bookId, String isbn, String title, long copyId,
        String barcode, String borrowerUsername, String borrowerDisplayName,
        Instant borrowedAt, Instant dueAt, int renewalCount, Instant returnedAt,
        LibraryReturnCondition returnCondition, LibraryLoanChannel channel,
        boolean overdue, boolean renewable) { }
record LoanPage(List<LoanRecord> rows, int page, int pageSize, int total) {
    public LoanPage { rows = List.copyOf(rows); }
}
record BorrowCommand(long borrowerUserId, Long bookId, String barcode,
        long operatorUserId, LibraryLoanChannel channel, Instant borrowedAt,
        Instant dueAt, int maxActiveLoans) { }
record ReturnCommand(long borrowerUserId, Long loanId, String barcode,
        long operatorUserId, LibraryReturnCondition condition, String reason,
        Instant returnedAt, boolean administrator) { }
record RenewCommand(long borrowerUserId, long loanId, Instant now, Duration extension) { }
record BorrowReceipt(long loanId, long copyId, String barcode, String title, Instant dueAt) { }
record ReturnReceipt(long loanId, long copyId, String barcode,
        LibraryReturnCondition condition, Instant returnedAt) { }
record RenewReceipt(long loanId, Instant dueAt, int renewalCount) { }
```

- [x] **Step 5: Run migration and compilation checks**

Run: `mvn -pl vcampus-server -am -Dtest=LibraryMigrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: migration test passes and both store contracts compile.

- [x] **Step 6: Record the schema checkpoint (tests; exported workspace has no Git)**

```bash
git add database vcampus-server/src/main/java/com/vcampus/server/database/LibraryCatalogStore.java vcampus-server/src/main/java/com/vcampus/server/database/LibraryLoanStore.java vcampus-server/src/test/java/com/vcampus/server/database/LibraryMigrationTest.java
git commit -m "feat(library): add schema and store contracts"
```

---

### Task 3: Catalog and Copy Repository

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/LibraryCatalogRepository.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/LibraryCatalogRepositoryTest.java`

**Interfaces:**
- Consumes: `LibraryCatalogStore` from Task 2 and `LibraryCodePolicy` from Task 1.
- Produces: complete JDBC catalog/copy behavior for `LibraryService`.

- [x] **Step 1: Write failing H2 repository tests**

Create an isolated `jdbc:h2:mem:<uuid>;MODE=MySQL;DB_CLOSE_DELAY=-1` database per test. Cover these exact cases:

```java
@Test void searchReturnsAggregatedCopyCountsAndEscapesWildcards() throws Exception {
    CatalogPage page = repository.search(new CatalogQuery("100%", "", 1, 10));
    assertEquals(1, page.total());
    assertEquals(4, page.rows().getFirst().totalCopies());
    assertEquals(2, page.rows().getFirst().availableCopies());
}
```

In the same test class, assert that duplicate normalized ISBN and duplicate barcode inserts throw `SQLException`, searching `B000000128` returns exactly one copy, `setCopyStatus` returns `CONFLICT` for an active `ON_LOAN` copy, and `setBookEnabled(id, false)` returns `CHANGED` without deleting its row.

The test schema must include `books`, `book_copies`, and the minimum `library_loans` columns needed to detect active loans.

- [x] **Step 2: Run tests and verify repository absence**

Run: `mvn -pl vcampus-server -am -Dtest=LibraryCatalogRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation failure for `LibraryCatalogRepository`.

- [x] **Step 3: Implement catalog search and details**

Use one count query and one paginated row query. Aggregate copy counts without multiplying rows:

```sql
SELECT b.id, b.isbn, b.title, b.authors, b.publisher, b.publish_year,
       b.category, b.description, b.enabled,
       COUNT(c.id) AS total_copies,
       SUM(CASE WHEN c.status = 'AVAILABLE' THEN 1 ELSE 0 END) AS available_copies
FROM books b
LEFT JOIN book_copies c ON c.book_id = b.id
WHERE (? = '' OR b.title LIKE ? ESCAPE '!' OR b.authors LIKE ? ESCAPE '!'
       OR b.isbn = ?)
  AND (? = '' OR b.category = ?)
GROUP BY b.id
ORDER BY b.title, b.id
LIMIT ? OFFSET ?
```

Reuse the repository's existing project convention for escaping `!`, `%`, and `_` in `LIKE` input.

- [x] **Step 4: Implement book and copy mutations**

Normalize ISBN and barcode before binding. `setCopyStatus` must lock the copy, reject every manual change while its current state is `ON_LOAN`, reject manually setting an idle copy to `ON_LOAN`, require a nonblank reason for `LOST`, `DAMAGED`, or `WITHDRAWN`, and clear `status_reason` when returning an idle copy to `AVAILABLE`.

- [x] **Step 5: Run catalog repository tests**

Run: `mvn -pl vcampus-server -am -Dtest=LibraryCatalogRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: all catalog repository tests pass.

- [x] **Step 6: Record the catalog checkpoint (tests; exported workspace has no Git)**

```bash
git add vcampus-server/src/main/java/com/vcampus/server/database/LibraryCatalogRepository.java vcampus-server/src/test/java/com/vcampus/server/database/LibraryCatalogRepositoryTest.java
git commit -m "feat(library): implement catalog persistence"
```

---

### Task 4: Circulation Policy and Transactional Loan Repository

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/service/LibraryLoanPolicy.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/LibraryLoanRepository.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/LibraryRuleException.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/service/LibraryLoanPolicyTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/LibraryLoanRepositoryTest.java`

**Interfaces:**
- Consumes: `LibraryLoanStore`, shared enums, and `ConnectionFactory`.
- Produces: `LibraryLoanPolicy.ruleFor(Set<UserRole>)` and all circulation transactions.

- [x] **Step 1: Write failing role-rule tests**

```java
@Test void returnsApprovedStudentAndTeacherRules() {
    assertEquals(new LoanRule(5, Duration.ofDays(30), Duration.ofDays(15)),
            LibraryLoanPolicy.ruleFor(Set.of(UserRole.STUDENT)));
    assertEquals(new LoanRule(10, Duration.ofDays(60), Duration.ofDays(30)),
            LibraryLoanPolicy.ruleFor(Set.of(UserRole.TEACHER, UserRole.LIBRARY_ADMIN)));
    assertThrows(IllegalArgumentException.class,
            () -> LibraryLoanPolicy.ruleFor(Set.of(UserRole.SUPER_ADMIN)));
}
```

- [x] **Step 2: Implement `LoanRule` and `ruleFor` minimally, then run its test**

Create the rule exception alongside the policy:

```java
public final class LibraryRuleException extends RuntimeException {
    public LibraryRuleException(String message) { super(message); }
}
```

Run: `mvn -pl vcampus-server -am -Dtest=LibraryLoanPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: pass.

- [x] **Step 3: Write failing transaction tests**

Cover the approved behavior with real H2 transactions:

```java
@Test void oneRenewalExtendsFromExistingDueAtAndClearsDueNoticeMarker() throws Exception {
    RenewReceipt receipt = repository.renew(new RenewCommand(
            201L, 501L, Instant.parse("2026-08-27T00:00:00Z"), Duration.ofDays(15)));
    assertEquals(Instant.parse("2026-09-26T00:00:00Z"), receipt.dueAt());
    assertEquals(1, receipt.renewalCount());
    assertNull(timestamp("SELECT due_notice_sent_at FROM library_loans WHERE id = 501"));
}
```

Add equally concrete tests that assert: circulation preview returns borrower/copy state without mutating rows; borrow creates one active row and sets the copy to `ON_LOAN`; the sixth student loan throws `LibraryRuleException("借阅数量已达上限")`; overdue blocks borrow and renew; self-return of another user's loan fails; a second renewal fails; lost administrator return sets `return_condition=LOST`, copy status `LOST`, and reason; and the two-thread race yields exactly one success.

For the concurrency test, use two executor tasks, a `CountDownLatch` start gate, separate JDBC connections, and assert one `BorrowReceipt`, one `LibraryRuleException`, one active loan, and `ON_LOAN` copy status.

- [x] **Step 4: Implement borrower lookup and paginated loan queries**

Borrower lookup must return only enabled student or teacher accounts and their base identity. Implement `previewCirculation` as a read-only query that joins borrower, copy, book, and active loan state and returns the exact `CirculationPreview` record from Task 2. Loan rows must include loan ID, book ID, ISBN, title, copy ID, barcode, borrower username/display name, times, renewal count, return condition, channel, and a derived overdue boolean.

- [x] **Step 5: Implement borrow, return, and renew transactions**

Borrow must lock in this order: borrower `users` row, active borrower loans, then available copy using `FOR UPDATE SKIP LOCKED`. Insert the loan and set the copy to `ON_LOAN` before commit.

Renew must lock in this order: borrower `users` row, then target loan. Compute `Instant newDueAt = locked.dueAt().plus(command.extension())`, then bind it to `UPDATE library_loans SET due_at=?, renewal_count=renewal_count+1, due_notice_sent_at=NULL WHERE id=? AND returned_at IS NULL`. Return and lost closure must update loan and copy in one transaction. Roll back on every `SQLException` or `LibraryRuleException`.

- [x] **Step 6: Run policy and repository tests**

Run: `mvn -pl vcampus-server -am -Dtest=LibraryLoanPolicyTest,LibraryLoanRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: all circulation tests pass, including the two-client race.

- [x] **Step 7: Commit the circulation checkpoint**

```bash
git add vcampus-server/src/main/java/com/vcampus/server/service/LibraryLoanPolicy.java vcampus-server/src/main/java/com/vcampus/server/database/LibraryLoanRepository.java vcampus-server/src/main/java/com/vcampus/server/database/LibraryRuleException.java vcampus-server/src/test/java/com/vcampus/server/service/LibraryLoanPolicyTest.java vcampus-server/src/test/java/com/vcampus/server/database/LibraryLoanRepositoryTest.java
git commit -m "feat(library): add transactional circulation"
```

---

### Task 5: Library Service, Router, and Client Transport

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/service/LibraryService.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/service/LibraryServiceTest.java`
- Modify test: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterTest.java`

**Interfaces:**
- Consumes: both stores, `LibraryLoanPolicy`, `SessionManager`, `AuditStore`, `Clock`, and Task 1 actions.
- Produces: sixteen service methods and typed client methods consumed by Swing panels.

- [x] **Step 1: Write failing service authorization and encoding tests**

Use in-memory fake stores and real `SessionManager` sessions. Cover:

```java
@Test void superAdministratorCannotSelfBorrow() {
    ResponseMessage response = service.borrow(authorized(
            Actions.LIBRARY_LOAN_BORROW, superAdmin.token(), Map.of("bookId", "10")));
    assertFalse(response.success());
    assertEquals("没有执行该操作的权限", response.message());
    assertEquals(0, loans.borrowCalls());
}
```

Use the same fake stores to assert stable catalog row order, student denial for book creation and global-loan search, administrator circulation preview and borrowing for a student, owned-loan enforcement on self-return, and readable failures for invalid ISBN, barcode, page, and enum input.

Catalog row order must be: book ID, ISBN, title, authors, publisher, publish year, category, description, enabled, total copies, available copies. Loan row order must match the fields defined in Task 4.

- [x] **Step 2: Run tests and verify `LibraryService` is absent**

Run: `mvn -pl vcampus-server -am -Dtest=LibraryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation failure.

- [x] **Step 3: Implement the service API**

Create these public methods with the existing project response pattern:

```java
ResponseMessage searchCatalog(RequestMessage request)
ResponseMessage getCatalogItem(RequestMessage request)
ResponseMessage myLoans(RequestMessage request)
ResponseMessage borrow(RequestMessage request)
ResponseMessage returnLoan(RequestMessage request)
ResponseMessage renew(RequestMessage request)
ResponseMessage createBook(RequestMessage request)
ResponseMessage updateBook(RequestMessage request)
ResponseMessage setBookEnabled(RequestMessage request)
ResponseMessage searchCopies(RequestMessage request)
ResponseMessage createCopy(RequestMessage request)
ResponseMessage setCopyStatus(RequestMessage request)
ResponseMessage searchLoans(RequestMessage request)
ResponseMessage previewCirculation(RequestMessage request)
ResponseMessage adminBorrow(RequestMessage request)
ResponseMessage adminReturn(RequestMessage request)
```

Use `PAGE_SIZE = 10`. Catch `IllegalArgumentException` and `LibraryRuleException` as readable business failures; log `SQLException` and return “图书馆数据暂时不可用，请稍后重试”. Include `maxLoans`, `initialLoanDays`, and `renewalDays` in `myLoans` success data. After each successful book/copy mutation, borrow, return, renew, or administrator borrow/return, call `AuditStore.record(operatorUserId, request.action(), "SUCCESS", null)`; service tests must assert the exact action code is recorded once.

- [x] **Step 4: Route every action and update router tests**

Add `LibraryService libraryService` to the `RequestRouter` constructor and switch. Update all existing test constructor calls with `null` in the new position, then add a route test proving `LIBRARY_CATALOG_SEARCH` reaches a fake catalog store once.

- [x] **Step 5: Add typed `VCampusClient` methods**

Expose one method per action. Representative signatures:

```java
ResponseMessage searchLibraryCatalog(String token, String keyword, String category, int page)
ResponseMessage myLibraryLoans(String token, String scope, int page)
ResponseMessage borrowLibraryBook(String token, long bookId)
ResponseMessage returnLibraryLoan(String token, long loanId)
ResponseMessage renewLibraryLoan(String token, long loanId)
ResponseMessage createLibraryBook(String token, Map<String,String> values)
ResponseMessage searchLibraryCopies(String token, Map<String,String> filters)
ResponseMessage previewLibraryCirculation(String token, String username, String barcode,
        LibraryCirculationOperation operation)
ResponseMessage adminBorrowLibraryCopy(String token, String username, String barcode)
ResponseMessage adminReturnLibraryCopy(String token, String barcode,
        LibraryReturnCondition condition, String reason)
```

All methods must delegate to existing `sendAuthorized`; do not duplicate Socket code.

- [x] **Step 6: Run service and router tests**

Run: `mvn -pl vcampus-server -am -Dtest=LibraryServiceTest,RequestRouterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: both suites pass and existing forced-password behavior remains intact.

- [x] **Step 7: Commit the service checkpoint**

```bash
git add vcampus-server/src/main/java/com/vcampus/server/service/LibraryService.java vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java vcampus-server/src/test/java/com/vcampus/server/service/LibraryServiceTest.java vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterTest.java vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java
git commit -m "feat(library): expose library service protocol"
```

---

### Task 6: Idempotent Due and Overdue Notifications

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/LibraryNoticeStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/LibraryNoticeRepository.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/service/LibraryOverdueNotifier.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/LibraryNoticeRepositoryTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/service/LibraryOverdueNotifierTest.java`

**Interfaces:**
- Consumes: `NotificationWriter`, `ConnectionFactory`, library loan markers, and notification enums.
- Produces: `LibraryNoticeStore.sendDueSoon(Instant, Instant, int)` and `sendOverdue(Instant, int)`, plus notifier lifecycle.

- [x] **Step 1: Write failing transaction and idempotency tests**

```java
@Test void dueSoonLoanGetsExactlyOneNotificationAcrossTwoRuns() throws Exception {
    assertEquals(1, repository.sendDueSoon(NOW, NOW.plus(Duration.ofDays(3)), 100));
    assertEquals(0, repository.sendDueSoon(NOW, NOW.plus(Duration.ofDays(3)), 100));
    assertEquals(1, count("notifications", "notification_type='LIBRARY_DUE_SOON'"));
    assertNotNull(timestamp("SELECT due_notice_sent_at FROM library_loans WHERE id=501"));
}
```

Add corresponding assertions for one overdue notification across two runs, zero notices for returned loans, and full rollback when the injected `NotificationWriter` throws.

The failure test must inject a `NotificationWriter` that throws `SQLException` and assert both the notification row and marker update roll back.

- [x] **Step 2: Implement notice scanning transactions**

Select at most `batchSize` eligible rows with `FOR UPDATE SKIP LOCKED`. For each row, call `NotificationWriter.insert(connection, draft)` and update the matching marker on the same connection before commit. Due-soon criteria are `now < due_at <= now + 3 days`; overdue criteria are `due_at < now`; both require `returned_at IS NULL` and a null marker.

- [x] **Step 3: Write and implement notifier scheduling tests**

Inject `Clock`, `ScheduledExecutorService`, and `LibraryNoticeStore`. `runOnce()` must call both scans exactly once with batch size 100 regardless of either return count. `start()` runs once immediately and then every hour. `close()` calls `shutdownNow()`.

- [x] **Step 4: Wire repository, service, and lifecycle into `VCampusServer`**

Construct one `LibraryCatalogRepository`, one `LibraryLoanRepository`, one `LibraryService`, and one `LibraryOverdueNotifier`. Pass the service to `RequestRouter`, call `notifier.start()` before accepting clients, and close it before shutting down the client executor.

- [x] **Step 5: Run notification and server tests**

Run: `mvn -pl vcampus-server -am -Dtest=LibraryNoticeRepositoryTest,LibraryOverdueNotifierTest,RequestRouterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: all tests pass with no duplicate messages.

- [x] **Step 6: Commit the notification checkpoint**

```bash
git add vcampus-server/src/main/java/com/vcampus/server/database/LibraryNoticeStore.java vcampus-server/src/main/java/com/vcampus/server/database/LibraryNoticeRepository.java vcampus-server/src/main/java/com/vcampus/server/service/LibraryOverdueNotifier.java vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java vcampus-server/src/test
git commit -m "feat(library): add due and overdue notifications"
```

---

### Task 7: Client Response Parsing and Reader Experience

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/LibraryViewData.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/LibraryPanel.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/LibraryTabPolicy.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/LibraryFrame.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/LibraryCatalogPanel.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/MyLibraryLoansPanel.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/ui/LibraryViewDataTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/ui/LibraryTabPolicyTest.java`

**Interfaces:**
- Consumes: Task 5 client methods and Task 1 policies/enums.
- Produces: `new LibraryFrame(client, token, roles)`, `openMyLoans()`, and strict view records.

- [x] **Step 1: Write failing parser tests with exact row shapes**

```java
@Test void parsesCatalogPageAndLoanRules() {
    Map<String,String> data = new LinkedHashMap<>();
    data.put("page", "1"); data.put("pageSize", "10"); data.put("total", "1"); data.put("count", "1");
    data.put("row.0", RowCodec.encode("10", "9787111565271", "Java 并发编程实战",
            "Brian Goetz", "机械工业出版社", "2020", "计算机", "并发编程", "true", "4", "2"));
    CatalogPage page = LibraryViewData.catalogPage(success(data));
    assertEquals(2, page.rows().getFirst().availableCopies());
    assertEquals("Java 并发编程实战", page.rows().getFirst().title());
}

@Test void parsesLoanPageWithNullableReturnFieldsAndDerivedOverdueFlag() {
    Map<String,String> data = new LinkedHashMap<>();
    data.put("page", "1"); data.put("pageSize", "10"); data.put("total", "1"); data.put("count", "1");
    data.put("maxLoans", "5"); data.put("initialLoanDays", "30"); data.put("renewalDays", "15");
    data.put("row.0", RowCodec.encode("501", "10", "9787111565271", "Java 并发编程实战",
            "101", "B000000128", "2026000001", "张同学", "2026-08-01T00:00:00Z",
            "2026-08-31T00:00:00Z", "0", "", "", "SELF_SERVICE", "true", "false"));
    LoanPage page = LibraryViewData.loanPage(success(data));
    assertTrue(page.rows().getFirst().overdue());
    assertNull(page.rows().getFirst().returnedAt());
}

@Test void rejectsMalformedRowsWithReadableMessage() {
    var response = success(Map.of("count", "1", "row.0", RowCodec.encode("1")));
    var error = assertThrows(IllegalArgumentException.class,
            () -> LibraryViewData.catalogPage(response));
    assertEquals("服务器返回的图书馆数据格式不正确", error.getMessage());
}

private ResponseMessage success(Map<String,String> data) {
    return ResponseMessage.success("request", "查询成功", data);
}
```

Define immutable client records `CatalogRow`, `CatalogPage`, `LoanRow`, `LoanPage`, `CopyRow`, `CopyPage`, `BorrowerPreview`, and `MutationReceipt`.

- [x] **Step 2: Implement strict parsers and run parser tests**

Run: `mvn -pl vcampus-client -am -Dtest=LibraryViewDataTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: pass; missing fields, invalid numbers, enums, booleans, and timestamps are rejected.

- [x] **Step 3: Write and implement role-to-tab policy tests**

```java
assertEquals(List.of("图书检索", "我的借阅"),
        LibraryTabPolicy.titles(Set.of(UserRole.STUDENT)));
assertEquals(List.of("图书检索", "我的借阅", "书目馆藏", "借还办理", "借阅查询"),
        LibraryTabPolicy.titles(Set.of(UserRole.TEACHER, UserRole.LIBRARY_ADMIN)));
assertEquals(List.of("书目馆藏", "借还办理", "借阅查询"),
        LibraryTabPolicy.titles(Set.of(UserRole.SUPER_ADMIN)));
```

- [x] **Step 4: Implement `LibraryPanel` asynchronous behavior**

Follow `AcademicPanel`: store `client` and `sessionToken`, use `CompletableFuture.supplyAsync`, marshal completion through `SwingUtilities.invokeLater`, disable all registered mutation buttons while busy, retain existing table data on failure, and show readable dialogs.

- [x] **Step 5: Implement catalog and personal-loan panels**

`LibraryCatalogPanel` must provide keyword/category filters, page controls, available/total counts, detail display, and a confirmation dialog showing title, the server-returned loan duration, and a client-estimated due date before borrowing; the receipt's server-generated `dueAt` remains authoritative. `MyLibraryLoansPanel` must support current, due-soon, overdue, and history scopes; enable renew only when `renewable=true`; confirm return and refresh after mutation.

- [x] **Step 6: Implement the initial role-aware `LibraryFrame`**

Use the approved visual hierarchy and existing `Theme`: title, subtitle, `JTabbedPane`, reader tabs first, and `openMyLoans()` selecting “我的借阅” when present. Do not create management panels yet; Task 8 adds them.

- [x] **Step 7: Run all client tests**

Run: `mvn -pl vcampus-client -am test`

Expected: all existing and new client tests pass.

- [x] **Step 8: Commit the reader UI checkpoint**

```bash
git add vcampus-client/src/main/java/com/vcampus/client/ui/Library* vcampus-client/src/test/java/com/vcampus/client/ui/Library*
git commit -m "feat(library): add reader Swing experience"
```

---

### Task 8: Administrator Swing Experience and Application Navigation

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/LibraryInventoryPanel.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/LibraryCirculationPanel.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/LibraryLoanManagementPanel.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/LibraryFrame.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/MainFrame.java`
- Modify test: `vcampus-client/src/test/java/com/vcampus/client/ui/WorkspaceCardResolverTest.java`
- Modify test: `vcampus-client/src/test/java/com/vcampus/client/ui/NotificationViewDataTest.java`

**Interfaces:**
- Consumes: Task 7 frame/parsers and Task 5 transport.
- Produces: complete library UI reachable from the workspace and notifications.

- [x] **Step 1: Add failing navigation and notification vocabulary tests**

Assert that the library workspace card remains present for students, teachers, library administrators, and super administrators, and that a notification row with target `LIBRARY_LOANS` parses successfully.

- [x] **Step 2: Implement inventory management**

`LibraryInventoryPanel` must include book search, create/edit dialogs, enable/disable action, copy search, create-copy dialog, and status-change dialog. Status reason is mandatory for `LOST`, `DAMAGED`, and `WITHDRAWN`; an `ON_LOAN` copy exposes no manual status action.

- [x] **Step 3: Implement administrator circulation**

`LibraryCirculationPanel` uses a two-stage flow: query/validate username and barcode, show borrower display name, base identity, active count, limit, overdue state, book title, and copy status; only then enable “办理借阅” or “办理归还”. Lost closure requires reason and explicit confirmation.

- [x] **Step 4: Implement cross-user loan search**

`LibraryLoanManagementPanel` filters by username/name/barcode/title, active state, overdue state, and page. The table shows borrower, book, barcode, borrowed time, due time, return time, channel, and overdue state.

- [x] **Step 5: Add management tabs and application entry points**

Add management panels to `LibraryFrame` only when `LibraryAccessPolicy.canManage(roles)`. In `MainFrame.ModuleCard.openModule()`, open `LibraryFrame` for `ModuleCode.LIBRARY`. Add `NotificationTarget.LIBRARY_LOANS` handling that checks `LibraryAccessPolicy.canBorrow(roles)`, creates the frame, calls `openMyLoans()`, and shows it.

- [x] **Step 6: Run client regression tests**

Run: `mvn -pl vcampus-client -am test`

Expected: all client tests pass, including notification parsing and workspace card tests.

- [x] **Step 7: Commit the administrator UI checkpoint**

```bash
git add vcampus-client/src/main vcampus-client/src/test
git commit -m "feat(library): add administrator Swing workflows"
```

---

### Task 9: Demo Data, Documentation, and Full Verification

**Files:**
- Modify: `database/seed.sql`
- Create: `docs/library.md`
- Modify: `docs/requirements.md`
- Test: all Maven modules and local MySQL manual acceptance.

**Interfaces:**
- Consumes: all prior tasks.
- Produces: repeatable course-demo data, operator documentation, and verified delivery state.

- [x] **Step 1: Add deterministic, anonymized demo data**

Insert at least six books across three categories, ten copies with valid `B#########` barcodes, one active student loan, one due-soon teacher loan, one overdue student loan, and one returned history row. Resolve borrower IDs with `SELECT id FROM users WHERE username = ...`; do not assume auto-increment values and do not add real personal data.

- [x] **Step 2: Document operation and failure recovery**

`docs/library.md` must contain: roles, loan rules, reader workflow, administrator workflow, copy states, notification timing, environment prerequisites, migration command, demo sequence, concurrency behavior, and recovery messages for disconnected client or unavailable database.

- [x] **Step 3: Update requirements status accurately**

After automated verification passes, change the library row in `docs/requirements.md` from `待开发` to `开发完成，待本机 MySQL 双客户端验收`. Do not claim MySQL acceptance until Step 5 is actually performed.

- [x] **Step 4: Run the complete automated suite**

Run: `mvn clean verify`

Expected: reactor build succeeds for `vcampus-common`, `vcampus-server`, and `vcampus-client`; no test failures or compilation warnings introduced by the library module.

- [ ] **Step 5: Run local MySQL and two-client acceptance**

Apply `001_auth_columns.sql`, `002_notifications.sql`, and `003_library.sql` to MySQL 8.0.44, load `seed.sql`, start `com.vcampus.server.ServerMain`, and start two `com.vcampus.client.ClientMain` clients. Execute all ten acceptance scenarios in the design spec, including simultaneous attempts to borrow the last available copy and notification deep linking.

- [x] **Step 6: Record final status after manual acceptance**

If all MySQL scenarios pass, update the library requirement status to `开发完成，已验收`; otherwise keep `待本机 MySQL 双客户端验收` and record the exact failed scenario in `docs/library.md` under “验收记录”.

- [x] **Step 7: Commit the delivery checkpoint**

```bash
git add database/seed.sql docs/library.md docs/requirements.md
git commit -m "docs(library): add demo and acceptance guide"
```

---

## Plan Coverage Check

- Catalog, physical copies, status history preservation: Tasks 2–3.
- Student/teacher rules, renewal, overdue blocking, self-service and administrator circulation: Tasks 1, 4–5.
- Concurrency and active-copy uniqueness: Tasks 2 and 4.
- Due-soon/overdue notifications and idempotency: Tasks 1, 2, and 6.
- Role-aware Swing reader and administrator views: Tasks 7–8.
- Message-center deep link: Tasks 1 and 8.
- Schema, seed, requirements, documentation, automated and two-client verification: Tasks 2 and 9.
