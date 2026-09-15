# Shop Product Reviews Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add verified-purchase 1–5 star product reviews with images, helpful likes, score summaries, repeat-purchase labels, moderation, and stable helpfulness ordering to the campus shop.

**Architecture:** Keep reviews as an independent `shop.review.*` subsystem. MySQL owns review truth; server repositories enforce order ownership, lifecycle, idempotency, aggregates, likes, and moderation; the Socket protocol carries typed rows to a JavaFX client that performs all I/O off the application thread. Reuse the existing safe image-store primitive through a review-specific adapter and storage root without coupling reviews to forum tables.

**Tech Stack:** Java 21, Maven multi-module build, MySQL 8 / H2 MySQL-mode tests, JDBC transactions, length-prefixed `MessageCodec`, `RowCodec`, Java Socket client, JavaFX + CSS, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-15-shop-product-reviews-design.md`

## Global Constraints

- Preserve MySQL database → application server → JavaFX client; the client never opens JDBC connections.
- Use Java release 21 and the existing length-prefixed `MessageCodec`; do not use Java native serialization.
- Put shared actions and enums in `vcampus-common`, JDBC and files in `vcampus-server`, and JavaFX/network adapters in `vcampus-client`.
- Use `shop.review.*` for ordinary actions and `shop.admin.review.*` for administrative actions.
- Keep Socket, image, and database work off the JavaFX application thread.
- Rating is an integer from 1 through 5; trimmed review text is 5–1000 characters.
- Each completed order item has at most one review; later completed purchases of the same product may create a repeat-purchase review.
- Each review has at most three JPG, PNG, or WebP images, each at most 5 MiB, 20,000,000 decoded pixels, and 192 KiB per transport chunk.
- Public aggregates and lists include only `NORMAL` reviews.
- Public order is helpful-like count descending, creation time descending, review ID descending.
- Do not add review notifications, replies, reports, recommendation ranking, or a new legacy Swing review UI.
- Update `database/schema.sql`, `database/migrations/024_shop_reviews.sql`, `docs/requirements.md`, and `docs/shop.md` with the code.
- Root verification command is `mvn clean verify`.

## File Structure Map

### Shared contract

- Create `vcampus-common/src/main/java/com/vcampus/common/model/ShopReviewStatus.java`: shared review state vocabulary.
- Modify `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`: ordinary and administrative review actions.
- Create `vcampus-common/src/test/java/com/vcampus/common/model/ShopReviewVocabularyTest.java`: enum contract.
- Create `vcampus-common/src/test/java/com/vcampus/common/protocol/ShopReviewActionsTest.java`: exact action strings and uniqueness.

### Database and server domain

- Create `database/migrations/024_shop_reviews.sql`: upgrade DDL.
- Modify `database/schema.sql`: fresh-install DDL.
- Create `vcampus-server/src/test/java/com/vcampus/server/database/ShopReviewMigrationTest.java`: schema and constraint contract.
- Create `vcampus-server/src/main/java/com/vcampus/server/model/ShopReviewRecord.java`: immutable public/admin review row.
- Create `vcampus-server/src/main/java/com/vcampus/server/model/ShopReviewImageRecord.java`: persisted image metadata.
- Create `vcampus-server/src/main/java/com/vcampus/server/database/ShopReviewStore.java`: typed review repository boundary.
- Create `vcampus-server/src/main/java/com/vcampus/server/database/ShopReviewRepository.java`: JDBC transactions, list, aggregate, likes, moderation, and image metadata.
- Create `vcampus-server/src/test/java/com/vcampus/server/database/ShopReviewRepositoryTest.java`: H2 behavior, concurrency, ordering, and rollback tests.

### Server image and protocol services

- Create `vcampus-server/src/main/java/com/vcampus/server/config/ShopReviewImageConfig.java`: review image limits and `VCAMPUS_SHOP_REVIEW_IMAGE_DIR`.
- Create `vcampus-server/src/main/java/com/vcampus/server/image/ShopReviewImageStore.java`: review-specific safe image boundary.
- Create `vcampus-server/src/main/java/com/vcampus/server/image/FileShopReviewImageStore.java`: adapter over `FileShopImageStore`.
- Create `vcampus-server/src/main/java/com/vcampus/server/database/ShopReviewImageRepository.java`: visibility-checked image lookup and referenced keys.
- Create `vcampus-server/src/main/java/com/vcampus/server/service/ShopReviewImageService.java`: upload/read/claim/consume/cleanup protocol.
- Create `vcampus-server/src/main/java/com/vcampus/server/service/ShopReviewService.java`: review/list/like/moderation protocol.
- Create image/config/service/repository tests beside existing shop image tests.
- Modify `vcampus-server/src/main/java/com/vcampus/server/service/ShopService.java`: product summaries and order-item eligibility.
- Modify `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`: review routes with unavailable-service guards.
- Modify `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`: construct services and start/stop image cleanup.

### Client contract and views

- Modify `vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java`: raw request builders.
- Modify `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopData.java`: strict review/summary/order-item decoders.
- Modify `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopGateway.java`: typed review boundary.
- Modify `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SocketShopGateway.java`: Socket adapter.
- Create `ShopReviewImageDraft.java`, `ShopReviewImageUploader.java`, and `ShopReviewImageCache.java` in the shop JavaFX package.
- Create `ShopReviewEditorView.java`: create/update form.
- Create `ShopReviewListView.java`: summary, distribution, cards, pagination, likes, thumbnails.
- Create `ShopReviewAdminView.java`: review search and moderation controls.
- Modify `ShopProductCard.java`, `ShopDetailView.java`, `ShopOrderDetailView.java`, `ShopAdminView.java`, `ShopView.java`, `ShopController.java`, and `shop.css`: complete UI and navigation.
- Add focused data, gateway, controller, view, image pipeline, admin, integration, and visual tests in `vcampus-client/src/test/java/com/vcampus/client/fx/shop/`.

---

### Task 1: Shared review vocabulary and action contract

**Files:**
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/ShopReviewStatus.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Create: `vcampus-common/src/test/java/com/vcampus/common/model/ShopReviewVocabularyTest.java`
- Create: `vcampus-common/src/test/java/com/vcampus/common/protocol/ShopReviewActionsTest.java`

**Interfaces:**
- Consumes: existing `Actions` constant naming and Java enum patterns.
- Produces: `ShopReviewStatus { NORMAL, HIDDEN, DELETED }` and the exact action constants used by every later task.

- [ ] **Step 1: Write failing enum and action tests**

```java
@Test
void reviewStatusesMatchDatabaseVocabulary() {
    assertEquals(List.of("NORMAL", "HIDDEN", "DELETED"),
            Arrays.stream(ShopReviewStatus.values()).map(Enum::name).toList());
}

@Test
void reviewActionsUseDedicatedShopPrefixes() {
    assertEquals("shop.review.list", Actions.SHOP_REVIEW_LIST);
    assertEquals("shop.review.create", Actions.SHOP_REVIEW_CREATE);
    assertEquals("shop.review.update", Actions.SHOP_REVIEW_UPDATE);
    assertEquals("shop.review.delete", Actions.SHOP_REVIEW_DELETE);
    assertEquals("shop.review.like.set", Actions.SHOP_REVIEW_LIKE_SET);
    assertEquals("shop.review.image.upload.start", Actions.SHOP_REVIEW_IMAGE_UPLOAD_START);
    assertEquals("shop.review.image.upload.chunk", Actions.SHOP_REVIEW_IMAGE_UPLOAD_CHUNK);
    assertEquals("shop.review.image.upload.complete", Actions.SHOP_REVIEW_IMAGE_UPLOAD_COMPLETE);
    assertEquals("shop.review.image.getChunk", Actions.SHOP_REVIEW_IMAGE_GET_CHUNK);
    assertEquals("shop.admin.review.search", Actions.SHOP_ADMIN_REVIEW_SEARCH);
    assertEquals("shop.admin.review.hide", Actions.SHOP_ADMIN_REVIEW_HIDE);
    assertEquals("shop.admin.review.restore", Actions.SHOP_ADMIN_REVIEW_RESTORE);
}
```

- [ ] **Step 2: Run the tests and confirm the contract is absent**

Run: `mvn -pl vcampus-common -Dtest=ShopReviewVocabularyTest,ShopReviewActionsTest test`

Expected: compilation fails because `ShopReviewStatus` and the action constants do not exist.

- [ ] **Step 3: Add the enum and constants**

```java
public enum ShopReviewStatus {
    NORMAL,
    HIDDEN,
    DELETED
}
```

Add all constants asserted above to the shop block in `Actions`. Do not alias them to forum actions.

- [ ] **Step 4: Run the focused common-module tests**

Run: `mvn -pl vcampus-common -Dtest=ShopReviewVocabularyTest,ShopReviewActionsTest test`

Expected: PASS.

- [ ] **Step 5: Commit the contract**

```bash
git add vcampus-common/src/main/java/com/vcampus/common/model/ShopReviewStatus.java vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java vcampus-common/src/test/java/com/vcampus/common/model/ShopReviewVocabularyTest.java vcampus-common/src/test/java/com/vcampus/common/protocol/ShopReviewActionsTest.java
git commit -m "feat(shop): define review protocol vocabulary"
```

### Task 2: Review schema and migration contract

**Files:**
- Create: `database/migrations/024_shop_reviews.sql`
- Modify: `database/schema.sql`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/ShopReviewMigrationTest.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/database/DatabaseScriptsTest.java`

**Interfaces:**
- Consumes: `users`, `shop_products`, `shop_orders`, and `shop_order_items` from the current schema.
- Produces: four review tables with exact names and constraints for repository tasks.

- [ ] **Step 1: Add a failing migration test**

Create the prerequisite tables in H2 MySQL mode, execute the four `CREATE TABLE` statements extracted from migration 024, then assert the contract:

```java
statement.execute("INSERT INTO shop_product_reviews"
        + "(operation_id,order_item_id,product_id,author_user_id,rating,content,repeat_purchase,status)"
        + " VALUES('00000000-0000-0000-0000-000000000001',31,11,7,5,'纸张厚实，书写顺滑',FALSE,'NORMAL')");
assertThrows(SQLException.class, () -> statement.execute(
        "INSERT INTO shop_product_reviews(operation_id,order_item_id,product_id,author_user_id,rating,content,repeat_purchase,status)"
                + " VALUES('00000000-0000-0000-0000-000000000002',31,11,7,4,'重复订单商品评价',FALSE,'NORMAL')"));
assertThrows(SQLException.class, () -> statement.execute(
        "UPDATE shop_product_reviews SET rating=6 WHERE id=1"));
assertThrows(SQLException.class, () -> statement.execute(
        "INSERT INTO shop_review_likes(review_id,user_id) VALUES(1,7),(1,7)"));
```

Also assert that both `database/schema.sql` and the migration contain all four table names and the three review status literals.

- [ ] **Step 2: Run the migration tests and observe the missing file failure**

Run: `mvn -pl vcampus-server -am -Dtest=ShopReviewMigrationTest,DatabaseScriptsTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because migration 024 and its schema tables are absent.

- [ ] **Step 3: Add exact DDL to migration 024 and the fresh schema**

Use these table shapes in dependency order:

```sql
CREATE TABLE IF NOT EXISTS shop_product_reviews (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operation_id VARCHAR(64) NOT NULL UNIQUE,
    order_item_id BIGINT NOT NULL UNIQUE,
    product_id BIGINT NOT NULL,
    author_user_id BIGINT NOT NULL,
    rating TINYINT NOT NULL,
    content VARCHAR(1000) NOT NULL,
    repeat_purchase BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    INDEX idx_shop_review_product_status_time (product_id,status,created_at,id),
    INDEX idx_shop_review_author_time (author_user_id,created_at,id),
    CONSTRAINT fk_shop_review_order_item FOREIGN KEY (order_item_id) REFERENCES shop_order_items(id),
    CONSTRAINT fk_shop_review_product FOREIGN KEY (product_id) REFERENCES shop_products(id),
    CONSTRAINT fk_shop_review_author FOREIGN KEY (author_user_id) REFERENCES users(id),
    CONSTRAINT chk_shop_review_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT chk_shop_review_content CHECK (CHAR_LENGTH(TRIM(content)) BETWEEN 5 AND 1000),
    CONSTRAINT chk_shop_review_status CHECK (status IN ('NORMAL','HIDDEN','DELETED'))
);
```

Add `shop_review_images` with unique `(review_id, position)`, positions 0–2, size/dimension checks, and original/thumbnail storage keys; add `shop_review_likes` with primary key `(review_id,user_id)`; add `shop_review_moderation_logs` with `HIDE`/`RESTORE` check and indexes on review/operator/time. Image and like foreign keys may cascade only on physical review deletion; normal application flows remain soft-delete.

- [ ] **Step 4: Make bootstrap table inventory expectations include the new tables**

Extend `DatabaseScriptsTest` expected table names with:

```java
Set.of("shop_product_reviews", "shop_review_images",
       "shop_review_likes", "shop_review_moderation_logs")
```

- [ ] **Step 5: Run migration and bootstrap script tests**

Run: `mvn -pl vcampus-server -am -Dtest=ShopReviewMigrationTest,DatabaseScriptsTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 6: Commit the database contract**

```bash
git add database/migrations/024_shop_reviews.sql database/schema.sql vcampus-server/src/test/java/com/vcampus/server/database/ShopReviewMigrationTest.java vcampus-server/src/test/java/com/vcampus/server/database/DatabaseScriptsTest.java
git commit -m "feat(shop): add product review schema"
```

### Task 3: Core review repository lifecycle

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/model/ShopReviewRecord.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/model/ShopReviewImageRecord.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/ShopReviewStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/ShopReviewRepository.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/ShopReviewRepositoryTest.java`

**Interfaces:**
- Consumes: Task 2 tables and existing `ConnectionFactory`, `ShopRuleException`, `ShopOrderStatus`.
- Produces:

```java
ReviewMutationResult create(long authorUserId, CreateReview command) throws SQLException;
ReviewMutationResult update(long authorUserId, UpdateReview command) throws SQLException;
boolean delete(long authorUserId, long reviewId) throws SQLException;
Map<Long, Long> reviewIdsByOrderItems(Set<Long> orderItemIds) throws SQLException;

record CreateReview(String operationId, long orderItemId, int rating, String content,
                    List<PendingImage> images) {}
record UpdateReview(long reviewId, int rating, String content, ImagePlan imagePlan) {}
record PendingImage(String storageKey, String thumbnailStorageKey, String mimeType,
                    long byteSize, String sha256, int width, int height) {}
record ImagePlan(List<ImagePlanItem> items) {}
record ImagePlanItem(Long existingImageId, PendingImage upload) {}
record ReviewMutationResult(ShopReviewRecord review, boolean duplicate,
                            Set<String> keptKeys, Set<String> deletedKeys) {}
```

- [ ] **Step 1: Write H2 tests for verified purchase, repeat purchase, idempotency, update, and delete**

Seed two completed orders for the same buyer/product plus one shipped order and another buyer. Assert:

```java
var first = repository.create(7L, new CreateReview(OP1, firstItemId, 5,
        "第一次购买，纸张质量很好", List.of()));
var duplicate = repository.create(7L, new CreateReview(OP1, firstItemId, 5,
        "第一次购买，纸张质量很好", List.of()));
var repeat = repository.create(7L, new CreateReview(OP2, secondItemId, 4,
        "第二次购买，装订依然牢固", List.of()));

assertFalse(first.review().repeatPurchase());
assertTrue(duplicate.duplicate());
assertEquals(first.review().id(), duplicate.review().id());
assertTrue(repeat.review().repeatPurchase());
assertThrows(ShopRuleException.class, () -> repository.create(8L,
        new CreateReview(OP3, firstItemId, 5, "不能评价他人订单商品", List.of())));
assertThrows(ShopRuleException.class, () -> repository.create(7L,
        new CreateReview(OP4, shippedItemId, 5, "订单尚未完成不能评价", List.of())));
```

Then verify only the author can update/delete, updates preserve the ID, and delete changes status to `DELETED` without removing the row.

- [ ] **Step 2: Run the repository test and confirm missing types**

Run: `mvn -pl vcampus-server -am -Dtest=ShopReviewRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because the review models and store do not exist.

- [ ] **Step 3: Define immutable model records and constructor validation**

`ShopReviewRecord` must expose:

```java
public record ShopReviewRecord(long id, long orderItemId, long productId,
        long authorUserId, String authorDisplayName, int rating, String content,
        boolean repeatPurchase, ShopReviewStatus status, int likeCount,
        boolean likedByViewer, Instant createdAt, Instant updatedAt) { }
```

`ShopReviewImageRecord` must expose ID, review ID, position, storage keys, MIME type, size, SHA-256, width, height, and creation time. Defensive-copy list/set members in all `ShopReviewStore` results.

- [ ] **Step 4: Implement create as a transaction with row locking**

Use this sequence:

```sql
SELECT id,order_item_id,author_user_id FROM shop_product_reviews
WHERE operation_id=? FOR UPDATE;

SELECT oi.id,oi.product_id,o.id AS order_id,o.buyer_user_id,o.status,o.completed_at
FROM shop_order_items oi JOIN shop_orders o ON o.id=oi.order_id
WHERE oi.id=? FOR UPDATE;
```

Reject the wrong buyer or any status other than `COMPLETED`. Compute repeat purchase using another completed order for the same buyer/product where `(completed_at < current.completed_at) OR (completed_at = current.completed_at AND order_id < current.order_id)`. Insert the review and ordered images in one transaction. On a unique operation race, roll back, re-read by operation ID, and return it only when author and order item match.

- [ ] **Step 5: Implement update, soft delete, and order-item references**

Lock the review. Only its author may mutate a `NORMAL` row. For update, validate the retained image IDs belong to that review, shift positions before applying the new plan, insert uploads, delete omitted metadata, and return removed storage keys for post-commit file deletion. For delete:

```sql
UPDATE shop_product_reviews
SET status='DELETED',deleted_at=CURRENT_TIMESTAMP
WHERE id=? AND author_user_id=? AND status='NORMAL';
```

`reviewIdsByOrderItems` must return all review statuses so a deleted review still prevents a second review for the same order item.

- [ ] **Step 6: Run the lifecycle repository tests**

Run: `mvn -pl vcampus-server -am -Dtest=ShopReviewRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS for lifecycle tests.

- [ ] **Step 7: Commit the core repository**

```bash
git add vcampus-server/src/main/java/com/vcampus/server/model/ShopReviewRecord.java vcampus-server/src/main/java/com/vcampus/server/model/ShopReviewImageRecord.java vcampus-server/src/main/java/com/vcampus/server/database/ShopReviewStore.java vcampus-server/src/main/java/com/vcampus/server/database/ShopReviewRepository.java vcampus-server/src/test/java/com/vcampus/server/database/ShopReviewRepositoryTest.java
git commit -m "feat(shop): implement verified review lifecycle"
```

### Task 4: Review aggregates, helpful likes, stable ordering, and moderation

**Files:**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/ShopReviewStore.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/ShopReviewRepository.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/database/ShopReviewRepositoryTest.java`

**Interfaces:**
- Consumes: Task 3 repository lifecycle and records.
- Produces:

```java
ReviewSummary summary(long productId) throws SQLException;
Map<Long, ReviewSummary> summaries(Set<Long> productIds) throws SQLException;
ReviewPage list(ReviewQuery query) throws SQLException;
LikeResult setLike(long actorUserId, long reviewId, boolean enabled) throws SQLException;
AdminReviewPage searchAdmin(AdminReviewQuery query) throws SQLException;
boolean hide(long operatorUserId, long reviewId, String reason) throws SQLException;
boolean restore(long operatorUserId, long reviewId, String reason) throws SQLException;

record ReviewSummary(BigDecimal average, int total, List<Integer> starCounts) {}
record ReviewQuery(long productId, long viewerUserId, int page, int pageSize) {}
record ReviewPage(List<ShopReviewRecord> rows,
                  Map<Long,List<ShopReviewImageRecord>> images,
                  int page, int pageSize, int total) {}
record LikeResult(long reviewId, boolean enabled, boolean changed, int likeCount) {}
record AdminReviewQuery(String keyword, ShopReviewStatus status, int page, int pageSize) {}
```

`ReviewSummary.average()` is `null` when `total == 0`; otherwise it has scale 1. `starCounts` is an immutable five-element list indexed by `rating - 1`.

- [ ] **Step 1: Add failing aggregate and visibility tests**

Create 5-star and 3-star normal reviews plus hidden and deleted reviews, then assert:

```java
ReviewSummary summary = repository.summary(productId);
assertEquals(new BigDecimal("4.0"), summary.average());
assertEquals(2, summary.total());
assertEquals(List.of(0, 0, 1, 0, 1), summary.starCounts());
```

Verify `summaries(Set.of(productId, unratedProductId))` returns an explicit empty summary for both requested IDs.

- [ ] **Step 2: Add failing like, stable-order, and moderation tests**

Assert self-like rejection, idempotent enable/disable, two users liking concurrently without duplicate rows, and order by likes/time/ID. Assert hidden reviews leave public list and aggregate, restoration re-enters both, and repeated hide/restore does not add another moderation log.

- [ ] **Step 3: Run the focused test and observe missing methods**

Run: `mvn -pl vcampus-server -am -Dtest=ShopReviewRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because aggregate, like, list, and moderation methods are absent.

- [ ] **Step 4: Implement aggregate and list SQL**

Aggregate only normal reviews. Public list must use:

```sql
LEFT JOIN (
  SELECT review_id,COUNT(*) AS like_count
  FROM shop_review_likes GROUP BY review_id
) likes ON likes.review_id=r.id
WHERE r.product_id=? AND r.status='NORMAL'
ORDER BY COALESCE(likes.like_count,0) DESC,r.created_at DESC,r.id DESC
LIMIT ? OFFSET ?
```

Add `EXISTS(SELECT 1 FROM shop_review_likes WHERE review_id=r.id AND user_id=?)` for viewer state and batch-load images for returned review IDs.

- [ ] **Step 5: Implement like and moderation transactions**

Lock the target review and reject non-normal or self-owned rows. Use insert-on-enable and delete-on-disable, derive `changed` from affected rows, then count likes before committing. Hide/restore must update only the expected source state and insert exactly one log when the state changes.

- [ ] **Step 6: Run repository tests**

Run: `mvn -pl vcampus-server -am -Dtest=ShopReviewRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS, including concurrent like and deterministic sort assertions.

- [ ] **Step 7: Commit interactions and moderation**

```bash
git add vcampus-server/src/main/java/com/vcampus/server/database/ShopReviewStore.java vcampus-server/src/main/java/com/vcampus/server/database/ShopReviewRepository.java vcampus-server/src/test/java/com/vcampus/server/database/ShopReviewRepositoryTest.java
git commit -m "feat(shop): add review likes and moderation"
```

### Task 5: Review image storage, uploads, reads, and cleanup

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/config/ShopReviewImageConfig.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/image/ShopReviewImageStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/image/FileShopReviewImageStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/ShopReviewImageRepository.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/service/ShopReviewImageService.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/config/ShopReviewImageConfigTest.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/image/FileShopReviewImageStoreTest.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/database/ShopReviewImageRepositoryTest.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/service/ShopReviewImageServiceTest.java`

**Interfaces:**
- Consumes: `FileShopImageStore`, Task 3 `PendingImage`, review/image tables, `SessionManager`, and `ShopAccessPolicy`.
- Produces:

```java
ShopImageStore.UploadTicket start(long ownerId, String mimeType, long bytes);
void append(long ownerId, String uploadId, int index, byte[] bytes);
Completed complete(long ownerId, String uploadId);
ShopImageStore.ImageDescriptor describe(String storageKey);
ShopImageStore.ImageRange readRange(String storageKey, long offset, int maxBytes);
void cleanup(Set<String> referencedKeys, Instant now);

List<ShopReviewStore.PendingImage> claim(long ownerId, List<String> uploadIds);
void consume(long ownerId, List<String> uploadIds);
ScheduledExecutorService startCleanupScheduler(Duration interval);
```

- [ ] **Step 1: Write failing configuration and file-store tests**

```java
var config = ShopReviewImageConfig.fromEnvironment(Map.of());
assertTrue(config.root().endsWith(Path.of("data", "shop-review-images")));
assertEquals(5L * 1024 * 1024, config.maxImageBytes());
assertEquals(192 * 1024, config.chunkBytes());
assertEquals(20_000_000L, config.maxPixels());
assertEquals(Duration.ofMinutes(30), config.uploadTtl());

var custom = ShopReviewImageConfig.fromEnvironment(
        Map.of("VCAMPUS_SHOP_REVIEW_IMAGE_DIR", temp.resolve("reviews").toString()));
assertEquals(temp.resolve("reviews").toAbsolutePath().normalize(), custom.root());
```

Test JPG, PNG, and WebP normalization; forged MIME, oversized bytes, over-pixel images, wrong chunk index, duplicate completion, and cleanup that preserves referenced original/thumbnail keys.

- [ ] **Step 2: Run image configuration/store tests**

Run: `mvn -pl vcampus-server -am -Dtest=ShopReviewImageConfigTest,FileShopReviewImageStoreTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because the review image classes are absent.

- [ ] **Step 3: Implement the configuration and adapter**

`ShopReviewImageConfig.asStoreConfig()` must return:

```java
new ShopImageConfig(root, 5L * 1024 * 1024, 192 * 1024,
        20_000_000L, Duration.ofMinutes(30));
```

`FileShopReviewImageStore` delegates to `FileShopImageStore` using a fixed positive marker instead of a product ID, just as `FileForumImageStore` does. It must expose both full and thumbnail descriptors/ranges.

- [ ] **Step 4: Write failing repository visibility and service ownership tests**

Test that ordinary users can read image metadata only for `NORMAL` reviews, shop/super administrators can inspect hidden/deleted review images, upload IDs cannot be claimed by another user, duplicate IDs and more than three IDs are rejected, consume removes completed tickets, and completed tickets expire after 30 minutes.

- [ ] **Step 5: Implement `ShopReviewImageRepository` and `ShopReviewImageService`**

Mirror the proven `ForumImageService` flow, but authorize ordinary requests with `AccessPolicy.canAccess(ModuleCode.SHOP, roles)` and administrative visibility with `ShopAccessPolicy.canManage(roles)`. `getChunk` accepts only `THUMBNAIL` or `DETAIL`, returns canonical Base64 and exact chunk geometry, and uses 192 KiB reads. `cleanup(now)` passes all committed original and thumbnail keys from the repository to the store.

- [ ] **Step 6: Run all review image tests**

Run: `mvn -pl vcampus-server -am -Dtest=ShopReviewImageConfigTest,FileShopReviewImageStoreTest,ShopReviewImageRepositoryTest,ShopReviewImageServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 7: Commit the image backend**

```bash
git add vcampus-server/src/main/java/com/vcampus/server/config/ShopReviewImageConfig.java vcampus-server/src/main/java/com/vcampus/server/image/ShopReviewImageStore.java vcampus-server/src/main/java/com/vcampus/server/image/FileShopReviewImageStore.java vcampus-server/src/main/java/com/vcampus/server/database/ShopReviewImageRepository.java vcampus-server/src/main/java/com/vcampus/server/service/ShopReviewImageService.java vcampus-server/src/test/java/com/vcampus/server/config/ShopReviewImageConfigTest.java vcampus-server/src/test/java/com/vcampus/server/image/FileShopReviewImageStoreTest.java vcampus-server/src/test/java/com/vcampus/server/database/ShopReviewImageRepositoryTest.java vcampus-server/src/test/java/com/vcampus/server/service/ShopReviewImageServiceTest.java
git commit -m "feat(shop): add review image pipeline"
```

### Task 6: Review service protocol, routing, audit, and server wiring

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/service/ShopReviewService.java`
- Create: `vcampus-server/src/test/java/com/vcampus/server/service/ShopReviewServiceTest.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterShopTest.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`

**Interfaces:**
- Consumes: Tasks 1, 3, 4, and 5 store/image interfaces plus `SessionManager` and `AuditStore`.
- Produces these endpoint methods for `RequestRouter`:

```java
ResponseMessage list(RequestMessage request);
ResponseMessage create(RequestMessage request);
ResponseMessage update(RequestMessage request);
ResponseMessage delete(RequestMessage request);
ResponseMessage setLike(RequestMessage request);
ResponseMessage searchAdmin(RequestMessage request);
ResponseMessage hide(RequestMessage request);
ResponseMessage restore(RequestMessage request);
```

- [ ] **Step 1: Write failing service tests for exact accepted fields and server-owned facts**

Use a proxy `ShopReviewStore` and real sessions. Verify create accepts only:

```java
Set.of("sessionToken", "operationId", "orderItemId", "rating", "content",
       "imageCount", "image.0", "image.1", "image.2")
```

where keys beyond `imageCount - 1` are absent. Explicitly test rejection of `authorUserId`, `productId`, `repeatPurchase`, `status`, `likeCount`, and `ratingAverage`. Verify rating 0/6, text length 4/1001, duplicate upload IDs, and malformed UUID fail before calling the store.

- [ ] **Step 2: Add failing response and permission tests**

Assert public rows encode author display name but not username/user ID, product list uses page size 10, self-like failures remain business conflicts, ordinary users cannot call admin actions, and hide/restore require trimmed 2–255 character reasons. Assert create/update/delete/like/hide/restore produce stable audit action/result records.

- [ ] **Step 3: Run service tests**

Run: `mvn -pl vcampus-server -am -Dtest=ShopReviewServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because `ShopReviewService` does not exist.

- [ ] **Step 4: Implement strict request parsing and response encoding**

Create must call `imageService.claim(session.userId(), uploadIds)`, then repository `create`, and call `consume` only after repository success. Encode each review base row with `RowCodec`:

```text
id | productId | orderItemId | authorDisplayName | rating | content |
repeatPurchase | likeCount | likedByViewer | createdAt | updatedAt
```

Add `row.N.imageCount` and `row.N.image.M` metadata rows. Admin search adds status as a keyed field rather than exposing author IDs.

- [ ] **Step 5: Route all actions and preserve old constructor compatibility**

Add nullable `ShopReviewService` and `ShopReviewImageService` only to the widest `RequestRouter` constructor; existing overloads delegate `null`. Each new route returns `校园商店评价暂不可用` when its service is absent instead of throwing. Route image upload/read actions to `ShopReviewImageService`.

- [ ] **Step 6: Wire production services and cleanup lifecycle**

In `VCampusServer`, instantiate one `ShopReviewRepository`, one `FileShopReviewImageStore`, one `ShopReviewImageService`, and one `ShopReviewService`. Start a 30-minute cleanup scheduler after binding the server; shut it down in `close()` alongside the existing shop/forum image executors.

- [ ] **Step 7: Run service and router tests**

Run: `mvn -pl vcampus-server -am -Dtest=ShopReviewServiceTest,RequestRouterShopTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 8: Commit server protocol delivery**

```bash
git add vcampus-server/src/main/java/com/vcampus/server/service/ShopReviewService.java vcampus-server/src/test/java/com/vcampus/server/service/ShopReviewServiceTest.java vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterShopTest.java vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java
git commit -m "feat(shop): expose review socket services"
```

### Task 7: Product summaries and order-item review eligibility

**Files:**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/ShopService.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/ShopServiceTest.java`
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterShopTest.java`

**Interfaces:**
- Consumes: `ShopReviewStore.summaries`, `summary`, and `reviewIdsByOrderItems`; existing `ShopStore.ProductPage`, `ProductDetail`, and `OrderDetail`.
- Produces additive response keys decoded in Task 8:

```text
row.N.ratingAverage, row.N.ratingCount
ratingAverage, ratingCount, ratingStars.1 ... ratingStars.5
row.N.reviewable, row.N.reviewId
```

- [ ] **Step 1: Add failing product summary response tests**

Configure a product page with one rated and one unrated item. Assert the rated row contains `4.8` and `36`, the unrated row contains an empty average and `0`, and detail returns five exact star counts. Assert no average is fabricated as `0.0`.

- [ ] **Step 2: Add failing order eligibility tests**

For the current buyer, assert a completed order item without any review gets `reviewable=true` and empty `reviewId`; a reviewed item gets `reviewable=false` and its ID. Shipped/paid/cancelled orders and administrator views get `reviewable=false` regardless of review absence.

- [ ] **Step 3: Run the focused service tests**

Run: `mvn -pl vcampus-server -am -Dtest=ShopServiceTest,RequestRouterShopTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `ShopService` does not query or encode review metadata.

- [ ] **Step 4: Inject the review read boundary into `ShopService`**

Add a constructor accepting `ShopReviewStore`; update production and test construction. Keep existing constructors by delegating to an empty read implementation that returns empty summaries/maps, so unrelated router tests remain focused.

In product search, collect page product IDs once and call `summaries(ids)` once. In order detail, collect order item IDs once and call `reviewIdsByOrderItems(ids)` once. Do not issue one query per product or order item.

- [ ] **Step 5: Encode additive summary and eligibility keys**

Format non-null averages with one decimal using `setScale(1, RoundingMode.HALF_UP).toPlainString()`. `reviewable` is true only when the current session is the order buyer, order status is `COMPLETED`, and no review ID exists.

- [ ] **Step 6: Run shop service/router tests**

Run: `mvn -pl vcampus-server -am -Dtest=ShopServiceTest,RequestRouterShopTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 7: Commit product/order integration**

```bash
git add vcampus-server/src/main/java/com/vcampus/server/service/ShopService.java vcampus-server/src/test/java/com/vcampus/server/service/ShopServiceTest.java vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterShopTest.java vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java
git commit -m "feat(shop): expose ratings and review eligibility"
```

### Task 8: Client review models, decoders, raw requests, and gateway

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopData.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopGateway.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SocketShopGateway.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopDataTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopGatewayTest.java`

**Interfaces:**
- Consumes: Tasks 6–7 response fields and action constants.
- Produces:

```java
record RatingSummary(BigDecimal average, int total, List<Integer> starCounts) {}
record ReviewImageRef(long id, String mimeType, long byteSize, String sha256,
                      int position, int width, int height) {}
record Review(long id, long productId, long orderItemId, String authorDisplayName,
              int rating, String content, boolean repeatPurchase, int likeCount,
              boolean likedByViewer, Instant createdAt, Instant updatedAt,
              List<ReviewImageRef> images) {}
record ReviewPage(List<Review> rows, int page, int pageSize, int total) {}
record ReviewDraft(String operationId, long orderItemId, int rating, String content,
                   List<String> uploadIds) {}
record ReviewUpdate(long reviewId, int rating, String content,
                    List<ReviewImagePlanItem> images) {}
record LikeResult(long reviewId, boolean enabled, boolean changed, int likeCount) {}
```

`Product` gains `RatingSummary ratingSummary`; `OrderItem` gains `boolean reviewable` and nullable `Long reviewId`. Keep convenience constructors that supply empty values for existing fixtures.

- [ ] **Step 1: Write failing strict decoder tests**

Test `4.8/36/[0,0,2,4,30]`, empty average with zero count, rating outside 1–5, negative likes, mismatched `likedByViewer`, duplicate image positions, more than three images, malformed hashes, invalid pagination, and order items whose `reviewable=true` also carry a review ID.

- [ ] **Step 2: Write failing gateway request tests**

Use the existing recording Socket test server to assert create sends only operation/order-item/rating/content/image IDs; like sends only `reviewId` and explicit `enabled`; admin hide/restore sends review ID and reason; image chunks are canonical Base64. Assert transport failures become `ShopTransportException`, while server rejections remain `ShopData.Rejected`.

- [ ] **Step 3: Run client data/gateway tests**

Run: `mvn -pl vcampus-client -am -Dtest=ShopDataTest,ShopGatewayTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation or assertion failure because review models and calls are absent.

- [ ] **Step 4: Implement additive product/order decoding and review decoding**

Use a one-decimal rating parser:

```java
private static BigDecimal ratingAverage(String value, int total) {
    if (total == 0 && (value == null || value.isEmpty())) return null;
    BigDecimal parsed = new BigDecimal(value);
    if (total < 1 || parsed.scale() != 1
            || parsed.compareTo(new BigDecimal("1.0")) < 0
            || parsed.compareTo(new BigDecimal("5.0")) > 0) {
        throw new IllegalArgumentException("评分数据无效");
    }
    return parsed;
}
```

Treat absent additive product summary keys as empty only for legacy test fixtures; reject partial key sets. Apply the same all-or-none rule to order item review metadata.

- [ ] **Step 5: Add exact raw request builders and typed gateway methods**

Add methods for review list/create/update/delete/like, admin search/hide/restore, upload start/chunk/complete, and chunk reads. Build indexed image parameters deterministically using `LinkedHashMap`; never send author, product, repeat, status, counts, or aggregates.

- [ ] **Step 6: Run data and gateway tests**

Run: `mvn -pl vcampus-client -am -Dtest=ShopDataTest,ShopGatewayTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 7: Commit the client transport contract**

```bash
git add vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopData.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopGateway.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/SocketShopGateway.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopDataTest.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopGatewayTest.java
git commit -m "feat(shop): add client review protocol"
```

### Task 9: Client review image draft, uploader, and cache

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopReviewImageDraft.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopReviewImageUploader.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopReviewImageCache.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopReviewImagePipelineTest.java`

**Interfaces:**
- Consumes: Task 8 gateway upload/read methods and exact 5 MiB/192 KiB limits.
- Produces:

```java
record DraftImage(String key, Path file, String displayName, long byteSize,
                  String mimeType, Image preview, String uploadId) {}
CompletableFuture<String> upload(Path file, Consumer<Double> progress);
CompletableFuture<byte[]> load(ReviewImageRef ref, ImageVariant variant);
void clearMemory();
```

- [ ] **Step 1: Write failing pipeline tests**

Assert supported extensions and decoded preview, 3-image maximum, stable left/right reordering, removal, 5 MiB rejection, chunk indexes and sizes, progress reaching `1.0`, retry retaining the same completed upload ID, cache SHA-256 validation, corrupt-disk-cache replacement, and close/cancellation behavior.

- [ ] **Step 2: Run the pipeline test**

Run: `mvn -pl vcampus-client -am -Dtest=ShopReviewImagePipelineTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation fails because review image client classes are absent.

- [ ] **Step 3: Implement the draft and uploader**

Validate file size before reading, decode with `ImageIO`, accept `.jpg/.jpeg/.png/.webp`, and reject undecodable files. Upload sequential 192 KiB chunks through `ShopGateway`, call complete once, and keep the returned upload ID in the draft so a form retry does not upload again.

- [ ] **Step 4: Implement a separate review image cache**

Use `user.home/.vcampus/cache/shop-reviews` unless an optional review cache environment override is already introduced consistently in tests. Download all chunks, verify invariant metadata and final SHA-256, then atomically replace the cache file. No cache work may execute on the JavaFX thread.

- [ ] **Step 5: Run the pipeline test**

Run: `mvn -pl vcampus-client -am -Dtest=ShopReviewImagePipelineTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 6: Commit client image support**

```bash
git add vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopReviewImageDraft.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopReviewImageUploader.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopReviewImageCache.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopReviewImagePipelineTest.java
git commit -m "feat(shop): add review image client pipeline"
```

### Task 10: Product cards, detail summary, review cards, and helpful likes

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopReviewListView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopProductCard.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopDetailView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopController.java`
- Modify: `vcampus-client/src/main/resources/com/vcampus/client/fx/shop/shop.css`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopCatalogViewTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopDetailViewTest.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopReviewListViewTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopControllerTest.java`

**Interfaces:**
- Consumes: Task 8 models/gateway and Task 9 cache.
- Produces callbacks:

```java
interface ShopReviewListView.Listener {
    void page(long productId, int page);
    void like(long reviewId, boolean enabled);
    void openImage(ShopData.ReviewImageRef image);
}
```

- [ ] **Step 1: Write failing product card tests**

Assert a rated product renders `4.8` and `36 条评价`, an unrated product renders `暂无评分`, and accessible text includes the rating only when present. Keep price, stock, keyboard activation, and cover behavior unchanged.

- [ ] **Step 2: Write failing detail/review list tests**

Assert average, five distribution counts, verified-purchase label, optional repeat label, edited marker only when `updatedAt` is after `createdAt`, 0–3 image frames, selected helpful state, disabled self-like only when the server model marks it unavailable, 10-row pagination, empty/loading/error states, and the exact server order without client re-sorting.

- [ ] **Step 3: Run view tests**

Run: `mvn -pl vcampus-client -am -Dtest=ShopCatalogViewTest,ShopDetailViewTest,ShopReviewListViewTest,ShopControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because score and review nodes are absent.

- [ ] **Step 4: Render compact card rating and responsive detail summary**

Add stable node IDs:

```text
shop-product-rating-<productId>
shop-review-summary
shop-review-distribution-1 ... shop-review-distribution-5
shop-review-card-<reviewId>
shop-review-like-<reviewId>
shop-review-image-<imageId>
```

At wide widths, summary and list share a row; at narrow widths, `ShopDetailView` switches them to a single vertical flow. Use existing blue/white card styling and amber stars.

- [ ] **Step 5: Add controller loading and optimistic-safe like flow**

After product detail succeeds, display product content immediately and separately request review page 1. A review failure calls `showFailure` only inside the review region. On like click, disable that button, send the explicit target state, then replace its count/state from the server result; on failure restore the old model and show the server message.

- [ ] **Step 6: Load review images in the background**

Use `ShopReviewImageCache.load` on the controller executor and `Platform.runLater` for node updates. Guard callbacks with the same product/review generation ticket so late images cannot populate another product.

- [ ] **Step 7: Run view/controller tests**

Run: `mvn -pl vcampus-client -am -Dtest=ShopCatalogViewTest,ShopDetailViewTest,ShopReviewListViewTest,ShopControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 8: Commit public review UI**

```bash
git add vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopReviewListView.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopProductCard.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopDetailView.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopController.java vcampus-client/src/main/resources/com/vcampus/client/fx/shop/shop.css vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopCatalogViewTest.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopDetailViewTest.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopReviewListViewTest.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopControllerTest.java
git commit -m "feat(shop): show product ratings and reviews"
```

### Task 11: Review editor, completed-order entry points, and moderation UI

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopReviewEditorView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopReviewAdminView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopOrderDetailView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopAdminView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopController.java`
- Modify: `vcampus-client/src/main/resources/com/vcampus/client/fx/shop/shop.css`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopReviewEditorViewTest.java`
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopReviewAdminViewTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopOrdersViewTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopAdminViewTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopControllerTest.java`

**Interfaces:**
- Consumes: Tasks 8–10 client gateway, models, image draft/uploader, and controller request executor.
- Produces listener operations for create/update/delete, order-item entry, and admin search/hide/restore.

- [ ] **Step 1: Write failing editor tests**

Assert no submit without a selected 1–5 star, trimmed content at 4/5/1000/1001 characters, live character count, at most three files, image reorder/removal, create/update prefill, busy state, draft retention after server failure, and navigation confirmation when the form is dirty.

- [ ] **Step 2: Write failing order entry tests**

Assert completed eligible items render `去评价`, reviewed items render `查看评价`, other states show neither, and a successful confirm-receipt mutation presents exactly two choices: `立即评价` and `稍后评价`. Choosing later reopens the refreshed order; choosing now opens the first eligible order item.

- [ ] **Step 3: Write failing moderation tests**

Assert the admin navigation has `评价管理`, search filters keyword/status/page, public IDs and display names are shown without user IDs, hide/restore dialogs reject reasons shorter than 2 or longer than 255, normal rows offer hide, hidden rows offer restore, and deleted rows are read-only.

- [ ] **Step 4: Run editor/order/admin/controller tests**

Run: `mvn -pl vcampus-client -am -Dtest=ShopReviewEditorViewTest,ShopReviewAdminViewTest,ShopOrdersViewTest,ShopAdminViewTest,ShopControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because entry points and views are absent.

- [ ] **Step 5: Implement the editor and upload orchestration**

Create mode owns one UUID for the form lifetime. Upload only draft images lacking an upload ID, in display order; then call create. Update mode sends retained image IDs and new upload IDs as one ordered plan. A transport-uncertain create retry reuses both UUID and completed upload IDs. On success, clear the draft/cache and open the new review's product detail.

- [ ] **Step 6: Implement order actions and confirm-receipt choice**

Extend `ShopOrderDetailView.Listener` with:

```java
void review(long orderId, long orderItemId, Long reviewId);
```

After `confirmOrder` succeeds, reload the order before choosing the first eligible item. Do not submit a review inside the confirmation transaction or infer eligibility from the old screen.

- [ ] **Step 7: Implement admin review navigation and reason-gated actions**

Add `admin/reviews` routing to `ShopController`, a third navigation button in `ShopAdminView`, and background gateway calls for search/hide/restore. Refresh the current admin page after successful state mutation; show failures without discarding filter values.

- [ ] **Step 8: Run focused UI tests**

Run: `mvn -pl vcampus-client -am -Dtest=ShopReviewEditorViewTest,ShopReviewAdminViewTest,ShopOrdersViewTest,ShopAdminViewTest,ShopControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 9: Commit authoring and moderation UI**

```bash
git add vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopReviewEditorView.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopReviewAdminView.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopOrderDetailView.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopAdminView.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopView.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopController.java vcampus-client/src/main/resources/com/vcampus/client/fx/shop/shop.css vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopReviewEditorViewTest.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopReviewAdminViewTest.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopOrdersViewTest.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopAdminViewTest.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopControllerTest.java
git commit -m "feat(shop): add review authoring and moderation"
```

### Task 12: Integration, visual QA, documentation, and full verification

**Files:**
- Modify: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterShopTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopIntegrationTest.java`
- Modify: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopVisualTest.java`
- Modify: `docs/design/javafx-shop/design-qa.md`
- Modify: `docs/requirements.md`
- Modify: `docs/shop.md`
- Modify: `docs/testing.md`

**Interfaces:**
- Consumes: all prior tasks.
- Produces: end-to-end protocol evidence, deterministic screenshots, updated operator documentation, and a clean root verification result.

- [ ] **Step 1: Add a real `MessageCodec` Socket integration test**

Start the existing temporary Socket server fixture with real `RequestRouter`, sessions, H2 review repository, and temporary image directory. Exercise:

```text
login buyer A → list product → completed order item → upload image → create 5-star review
login buyer B → like review → list review → count is 1 and likedByViewer is true
login shop admin → hide with reason → product summary becomes empty
login shop admin → restore with reason → product summary returns 5.0 / 1
```

Assert client-sent author/product/count/status fields are rejected and no client source contains `java.sql` or `jdbc:`.

- [ ] **Step 2: Run the integration test and fix only cross-layer contract mismatches**

Run: `mvn -pl vcampus-client -am -Dtest=ShopIntegrationTest,RequestRouterShopTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected before reconciliation: failures identify any mismatched field names, action routes, or constructor wiring. Align producer and decoder names exactly; do not weaken validation to make malformed data pass.

- [ ] **Step 3: Add deterministic visual scenes**

Extend `ShopVisualTest` to generate at 1440, 1280, and 1000 pixel widths:

```text
catalog-rated.png
catalog-unrated.png
detail-reviews.png
detail-reviews-narrow.png
detail-review-images.png
detail-reviews-empty.png
detail-reviews-loading.png
detail-reviews-error.png
review-editor.png
review-editor-three-images.png
review-confirm-choice.png
review-admin-hidden.png
```

Use fixture names/content from the approved mockup; do not seed production data.

- [ ] **Step 4: Run visual tests and inspect every generated PNG**

Run: `mvn -pl vcampus-client -am -Dtest=ShopVisualTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS and all listed screenshots exist under `vcampus-client/target/shop-screenshots/`. Inspect images for clipped controls, overlapping text, inaccessible pagination, distorted thumbnails, and narrow-width horizontal scrolling; correct CSS/view constraints and rerun until clear.

- [ ] **Step 5: Update requirements, shop guide, testing record, and visual QA**

Record exact action names, permission rules, `VCAMPUS_SHOP_REVIEW_IMAGE_DIR`, 5 MiB/3 image/20,000,000 pixel/192 KiB limits, rating aggregation, helpful ordering, idempotency, moderation, and manual scenarios. Mark the feature complete in `docs/requirements.md` only after the automated suite passes.

- [ ] **Step 6: Run focused module verification**

Run: `mvn -pl vcampus-common test`

Expected: PASS.

Run: `mvn -pl vcampus-server -am test`

Expected: PASS.

Run: `mvn -pl vcampus-client -am test`

Expected: PASS.

- [ ] **Step 7: Run the root build and structural checks**

Run: `mvn clean verify`

Expected: `BUILD SUCCESS` with all modules and tests passing.

Run: `rg -n "java\.sql|jdbc:" vcampus-client/src/main`

Expected: no matches.

Run: `git diff --check`

Expected: no whitespace errors.

- [ ] **Step 8: Perform MySQL multi-client manual acceptance**

With two ordinary users and one shop administrator:

1. Complete two orders for the same product under buyer A and verify first/repeat labels.
2. Confirm a shipped order and choose both immediate and later review paths on separate items.
3. Create, reconnect, edit, and soft-delete reviews with 0 and 3 images.
4. Like concurrently from buyer B and another client; verify idempotent totals and public order.
5. Take the product offline after purchase and verify order-based authoring still works.
6. Hide/restore as administrator and verify public list, average, count, distribution, and audit log change together.
7. Interrupt create after the request is sent, retry the same UUID, and verify one review and one image set.

- [ ] **Step 9: Commit integration evidence and documentation**

```bash
git add vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterShopTest.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopIntegrationTest.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopVisualTest.java docs/design/javafx-shop/design-qa.md docs/requirements.md docs/shop.md docs/testing.md
git commit -m "docs(shop): verify product review delivery"
```

## Completion Checklist

- [ ] All 12 task commits exist and each task's focused tests passed before advancing.
- [ ] Product list and detail summaries match only `NORMAL` reviews.
- [ ] Order ownership, completion, one-review-per-item, and repeat-purchase rules are enforced by the server and database.
- [ ] Review create is UUID-idempotent; like set is target-state idempotent.
- [ ] Public ordering is likes descending, creation time descending, ID descending.
- [ ] Images meet count, type, size, pixel, chunk, hash, and cleanup constraints.
- [ ] Author edit/delete and administrator hide/restore preserve the approved visibility rules and audit history.
- [ ] JavaFX uses background I/O and remains usable in every approved viewport/state.
- [ ] `database/schema.sql`, migration 024, requirements, shop guide, testing record, and visual QA agree.
- [ ] `mvn clean verify`, client JDBC scan, and `git diff --check` all pass.
