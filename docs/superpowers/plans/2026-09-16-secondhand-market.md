# Secondhand Marketplace Implementation Plan

> **Execution constraint:** Implement inline in the current conversation without subagents. Follow the tasks in order and keep the TDD checkpoints. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a secondhand market inside the existing shop where users sell single items, handle their own deliveries, receive virtual-bank proceeds after buyer confirmation, and build order-backed seller ratings.

**Architecture:** Keep the user-facing entry inside the JavaFX shop, but use separate secondhand tables, services, repositories, actions, image namespaces, and owner checks. Reuse the shared shipping-address contract from the prerequisite plan, bank transaction primitives, notification infrastructure, and established image/review UI patterns. Each money/status transition uses one JDBC connection and a unique operation reference.

**Tech Stack:** Java 21, Maven, JavaFX + CSS, Socket/`MessageCodec`, MySQL JDBC, H2/JUnit 5 tests, virtual-bank `BigDecimal` accounting.

**Spec:** `docs/superpowers/specs/2026-09-16-secondhand-market-design.md` and `docs/superpowers/specs/2026-09-16-shop-shipping-addresses-design.md`.

## Global Constraints

- Complete `docs/superpowers/plans/2026-09-16-shop-shipping-addresses.md` first; secondhand checkout consumes its `ShippingAddressChoice`, `ShippingAddressValue`, and `ShopAddressRepository.resolve(Connection,long,ShippingAddressChoice)`.
- Preserve MySQL → server → client, `MessageCodec` length-prefix protocol, and JavaFX-thread confinement; never let a client connect to MySQL or send authoritative buyer/seller IDs.
- Build all new client UI with JavaFX and external CSS resources; do not add or extend Swing shop UI.
- All secondhand actions use `shop.used.*`; the existing ordinary-shop actions and its administrator semantics remain unchanged.
- One listing represents one physical item. Row/state locks prevent concurrent double sales; canceled orders remain as history, and the item may be relisted only when it was not moderated/downlisted.
- Buyer debit/held liability, refund, and seller payout use dedicated bank ledger types, exact `DECIMAL(15,2)` / `BigDecimal`, operation UUIDs, and one JDBC transaction per transition.
- An order address is an immutable snapshot. Only buyer, that order's seller, and authorized shop/super administrators see its full detail; public pages/notifications/logs do not.
- The shipped order does not auto-confirm or auto-pay. Admin resolution is audited and cannot both refund and settle one escrow.
- Leave the existing unrelated library changes untouched and stage only paths for each task. Use migration `026_secondhand_market.sql` after address migration `025`; verify numbering before execution.
- After each task run its targeted tests; final root command is `mvn clean verify`.

---

### Task 1: Independent secondhand schema and listing ownership

**Files:**
- Create: `database/migrations/026_secondhand_market.sql`
- Modify: `database/schema.sql`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/SecondhandItemStatus.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/SecondhandModerationStatus.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/SecondhandListingStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/SecondhandListingRepository.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/SecondhandRuleException.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/SecondhandMigrationTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/SecondhandListingRepositoryTest.java`

**Interfaces:** `SecondhandListingStore.create(long sellerId, ListingInput input)`, `update(long actorId,long itemId,ListingInput input)`, `withdraw(long actorId,long itemId)`, `moderate(long adminId,long itemId,boolean visible,String reason)`, `search(String keyword,int page,int size)` returning nested `ListingPage`, and `get(long itemId,long viewerId,boolean admin)` returning nested `Listing`. `ListingInput` is nested in the store interface and contains title, description, category, condition, and `BigDecimal price`. `SecondhandRuleException` is a business rejection. Item lifecycle is `AVAILABLE`, `RESERVED`, `SOLD`, `WITHDRAWN`; moderation is `VISIBLE` or `HIDDEN` and is separate from lifecycle.

- [ ] **Step 1: Write failing schema and ownership tests.** Create item as user 1; user 2 cannot edit/withdraw; admin can hide; seller cannot re-publish a hidden item; a withdrawn item is not public. Assert `price > 0` and seller foreign key constraints.

```java
long id = listings.create(1L, new ListingInput("旧教材", "九成新，含笔记", "BOOK", "九成新", new BigDecimal("12.00")));
assertThrows(SecondhandRuleException.class, () -> listings.update(2L, id,
        new ListingInput("改名", "九成新，含笔记", "BOOK", "九成新", new BigDecimal("12.00"))));
listings.moderate(9L, id, false, "违规商品信息");
assertTrue(listings.search("旧教材", 1, 10).rows().isEmpty());
```

- [ ] **Step 2: Run RED.**

```powershell
mvn -pl vcampus-server -am '-Dtest=SecondhandMigrationTest,SecondhandListingRepositoryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: secondhand schema and repository symbols are absent.

- [ ] **Step 3: Add all secondhand tables and listing repository.** Migration and fresh schema define `secondhand_items`, item images, orders, escrow, listing moderation logs, order-resolution logs, reviews, review images, likes, and review-moderation logs. Order rows include buyer/seller/item IDs, operation ID, amount, status, item and address snapshots; escrow has unique `order_id`, amount, and `HELD/REFUNDED/SETTLED`. Listing write methods lock the item row and compare `seller_id` with the session-derived actor. Administrator moderation uses `ShopAccessPolicy.canManage` in the service added later; repository records a reason/audit row transactionally.

```java
if (item.sellerId() != actorId || item.status() != SecondhandItemStatus.AVAILABLE
        || item.moderation() != SecondhandModerationStatus.VISIBLE)
    throw new SecondhandRuleException("无权修改该二手物品");
```

- [ ] **Step 4: Run GREEN and verify schema/migration agree on table names and constraints.**

```powershell
mvn -pl vcampus-server -am '-Dtest=SecondhandMigrationTest,SecondhandListingRepositoryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- [ ] **Step 5: Commit only Task 1 paths.**

```powershell
git add -- database/schema.sql database/migrations/026_secondhand_market.sql vcampus-common/src/main/java/com/vcampus/common/model/SecondhandItemStatus.java vcampus-common/src/main/java/com/vcampus/common/model/SecondhandModerationStatus.java vcampus-server/src/main/java/com/vcampus/server/database/SecondhandListingStore.java vcampus-server/src/main/java/com/vcampus/server/database/SecondhandListingRepository.java vcampus-server/src/main/java/com/vcampus/server/database/SecondhandRuleException.java vcampus-server/src/test/java/com/vcampus/server/database/SecondhandMigrationTest.java vcampus-server/src/test/java/com/vcampus/server/database/SecondhandListingRepositoryTest.java
git commit -m "feat(shop): add owned secondhand listings"
```

### Task 2: Listing actions, images, and server-side moderation

**Files:**
- Modify: `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/service/SecondhandListingService.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/service/SecondhandImageService.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/config/SecondhandImageConfig.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/SecondhandImageRepository.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/service/SecondhandListingServiceTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/service/SecondhandImageServiceTest.java`

**Interfaces:** `shop.used.item.search/get/create/update/withdraw`, `shop.used.admin.item.hide/restore`, and `shop.used.image.upload.start/chunk/complete/commit/getChunk`. Images are max five per item and use a dedicated server directory. `SecondhandImageConfig.fromEnvironment()` returns a `ShopImageConfig` rooted at `VCAMPUS_SECONDHAND_IMAGE_DIR` (default `data/secondhand-images`), so `FileShopImageStore` can provide the existing validation/storage primitives. Image write and item update require the seller/administrator rule from Task 1; image read uses public visibility or authorized owner/admin visibility.

- [ ] **Step 1: Write failing permission and image tests.** Student 1 can create and upload to own item; student 2 cannot upload/commit; admin can hide with a reason; a hidden item/image is absent from public reads; content validates format, size and decoded pixel count.

```java
assertTrue(call("shop.used.item.create", sellerToken,
        Map.of("title", "旧教材", "description", "九成新，含笔记", "category", "BOOK",
                "condition", "九成新", "price", "12.00")).success());
assertFalse(call("shop.used.image.upload.start", otherToken,
        Map.of("itemId", Long.toString(itemId), "expectedBytes", "120", "mimeType", "image/png")).success());
```

- [ ] **Step 2: Run RED.**

```powershell
mvn -pl vcampus-server -am '-Dtest=SecondhandListingServiceTest,SecondhandImageServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: action/service classes do not exist.

- [ ] **Step 3: Implement session-bound actions and isolated image storage.** Wire the actions into `RequestRouter`/`VCampusServer` without breaking existing test constructors. Reuse `ShopImageStore`/`FileShopImageStore` validation and thumbnail primitives with `VCAMPUS_SECONDHAND_IMAGE_DIR`, while storing secondhand metadata in `secondhand_item_images`. Keep Socket/file work off UI threads. If SQL commit outcome is unknown, retain a finalized file for reconciliation rather than delete a possibly referenced image.

```java
long actor = sessions.find(token).orElseThrow(
        () -> new SecondhandRuleException("登录已过期")).userId();
var item = listings.get(itemId, actor, false);
if (item.sellerId() != actor || item.status() != SecondhandItemStatus.AVAILABLE
        || item.moderation() != SecondhandModerationStatus.VISIBLE)
    throw new SecondhandRuleException("无权上传该物品图片");
```

- [ ] **Step 4: Run GREEN plus existing ordinary-shop image tests.**

```powershell
mvn -pl vcampus-server -am '-Dtest=SecondhandListingServiceTest,SecondhandImageServiceTest,ShopImageServiceTest,FileShopImageStoreTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- [ ] **Step 5: Commit only Task 2 paths.**

```powershell
git add -- vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java vcampus-server/src/main/java/com/vcampus/server/service/SecondhandListingService.java vcampus-server/src/main/java/com/vcampus/server/service/SecondhandImageService.java vcampus-server/src/main/java/com/vcampus/server/config/SecondhandImageConfig.java vcampus-server/src/main/java/com/vcampus/server/database/SecondhandImageRepository.java vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java vcampus-server/src/test/java/com/vcampus/server/service/SecondhandListingServiceTest.java vcampus-server/src/test/java/com/vcampus/server/service/SecondhandImageServiceTest.java
git commit -m "feat(shop): expose secondhand listings and images"
```

### Task 3: Bank hold, refund, and seller payout primitives

**Files:**
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/BankLedgerType.java`
- Modify: `database/schema.sql`
- Modify: `database/migrations/026_secondhand_market.sql`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/SecondhandPaymentWriter.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/BankRepository.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/SecondhandPaymentWriterTest.java`

**Interfaces:** `SecondhandPaymentWriter.hold(Connection,long buyer,BigDecimal amount,String reference)`, `refund(Connection,long buyer,BigDecimal amount,String reference)`, `payout(Connection,long seller,BigDecimal amount,String reference)`; all return `BankPaymentWriter.PaymentResult`. Ledger types: `SECONDHAND_HOLD`, `SECONDHAND_REFUND`, `SECONDHAND_PAYOUT`.

- [ ] **Step 1: Write failing H2 bank tests.** Holding debits the buyer once, refund credits the buyer once, payout credits a frozen seller account, and mismatched duplicate amounts fail. A frozen buyer cannot start a hold.

```java
payments.hold(c, 1L, new BigDecimal("12.00"), "USED-1");
payments.payout(c, 2L, new BigDecimal("12.00"), "USED-1");
assertEquals(new BigDecimal("12.00"), bank.account(2L).balance());
```

- [ ] **Step 2: Run RED.**

```powershell
mvn -pl vcampus-server -am '-Dtest=SecondhandPaymentWriterTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: new writer/ledger types are absent.

- [ ] **Step 3: Extend the existing bank movement path.** Preserve its unique `(account_id,entry_type,reference_no)` idempotency and frozen-account rule: debit rejects frozen, credit accepts frozen. Update both fresh-schema and migration `chk_bank_ledger_type` constraints with the three exact literals.

```java
public PaymentResult payout(Connection c, long seller, BigDecimal amount, String ref)
        throws SQLException {
    var movement = writeMovement(c, seller, amount, ref,
            "二手交易结算 " + ref, BankLedgerType.SECONDHAND_PAYOUT, true);
    return new PaymentResult(movement.accountId(), movement.balanceAfter(), movement.duplicate());
}
```

- [ ] **Step 4: Run GREEN and existing bank/shop payment tests.**

```powershell
mvn -pl vcampus-server -am '-Dtest=SecondhandPaymentWriterTest,BankRepositoryTest,ShopCheckoutTransactionTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- [ ] **Step 5: Commit only Task 3 paths.**

```powershell
git add -- vcampus-common/src/main/java/com/vcampus/common/model/BankLedgerType.java database/schema.sql database/migrations/026_secondhand_market.sql vcampus-server/src/main/java/com/vcampus/server/database/SecondhandPaymentWriter.java vcampus-server/src/main/java/com/vcampus/server/database/BankRepository.java vcampus-server/src/test/java/com/vcampus/server/database/SecondhandPaymentWriterTest.java
git commit -m "feat(bank): record secondhand holds refunds and payouts"
```

### Task 4: Atomic purchase, cancellation, delivery, settlement, and admin resolution

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/SecondhandOrderStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/SecondhandOrderRepository.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/service/SecondhandOrderService.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/NotificationType.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/NotificationTarget.java`
- Modify: `database/schema.sql`
- Modify: `database/migrations/026_secondhand_market.sql`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/SecondhandOrderTransactionTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/service/SecondhandOrderServiceTest.java`

**Interfaces:** `SecondhandOrderStore.buy(long buyer,long item,String operationId,ShippingAddressChoice choice)` returns nested `BuyResult(orderId,orderNo,status)`; `order(long requester,long orderId,boolean admin)` returns nested `OrderDetail` with status, seller, buyer, escrow status and immutable `ShippingAddressValue`; `search(long requester,boolean selling,int page)` lists only that actor's purchase or sale orders; `searchAdmin(String keyword,ShopOrderStatus status,int page)` lists all for authorized admins; `cancel(long buyer,long order)`, `ship(long seller,long order)`, `confirm(long buyer,long order)`, `resolve(long admin,long order,Resolution resolution,String operationId,String reason)`. `Resolution` and `EscrowStatus` are nested enums with `REFUND/SETTLE` and `HELD/REFUNDED/SETTLED` respectively. Order status reuses `ShopOrderStatus` values but not ordinary-shop tables. Public actions are `shop.used.order.buy/search/get/cancel/ship/confirm`; admin actions are `shop.used.admin.order.search/get/resolve`.

- [ ] **Step 1: Write failing transaction and service tests.** Two concurrent buyers cannot buy one item; own-item purchase fails; insufficient/frozen balance and stale address leave no order or hold; retry returns the original order/address without second debit; reuse of another buyer's UUID cannot reveal their order; only seller ships; only buyer confirms; cancellation refunds and relists a visible item; hidden item remains hidden; admin can search/detail all orders and settle or refund a shipped order exactly once.

```java
var first = orders.buy(2L, itemId, operationId, addressChoice);
var repeat = orders.buy(2L, itemId, operationId, addressChoice);
assertEquals(first.orderId(), repeat.orderId());
assertEquals(1, scalar("SELECT COUNT(*) FROM secondhand_escrows WHERE order_id=" + first.orderId()));
assertEquals(1, scalar("SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='SECONDHAND_HOLD'"));
```

- [ ] **Step 2: Run RED.**

```powershell
mvn -pl vcampus-server -am '-Dtest=SecondhandOrderTransactionTest,SecondhandOrderServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: order repository/service are absent.

- [ ] **Step 3: Implement each transition in one JDBC transaction.** For buy: lookup operation ID first, lock item row, reject self/hidden/unavailable, resolve address, create order snapshot, call `hold`, create `HELD` escrow, mark item `RESERVED`, insert buyer/seller notifications, commit. For cancel: lock order and escrow, require buyer and `PAID`, refund and mark `REFUNDED/CANCELLED`; relist only when not withdrawn/hidden. For ship: require order seller and `PAID`, mark `SHIPPED` with one notification. For confirm: require buyer and `SHIPPED`, pay seller and mark `SETTLED/COMPLETED`. For admin resolve: require `ShopAccessPolicy.canManage`, a 2–255 character reason, and unique operation ID; only `HELD` may become refunded/settled; log reason and actor in the same transaction. Admin may refund `PAID` or refund/settle `SHIPPED`, never settle `PAID`.

```java
if (escrow.status() != EscrowStatus.HELD)
    throw new SecondhandRuleException("该交易已经完成资金处理");
if (resolution == Resolution.SETTLE && order.status() != ShopOrderStatus.SHIPPED)
    throw new SecondhandRuleException("交付前不能结算卖家款项");
if (resolution == Resolution.SETTLE) {
    payments.payout(c, order.sellerId(), escrow.amount(), order.orderNo());
    markEscrow(c, order.id(), EscrowStatus.SETTLED);
    markOrder(c, order.id(), ShopOrderStatus.COMPLETED);
} else {
    payments.refund(c, order.buyerId(), escrow.amount(), order.orderNo());
    markEscrow(c, order.id(), EscrowStatus.REFUNDED);
    markOrder(c, order.id(), ShopOrderStatus.CANCELLED);
}
```

- [ ] **Step 4: Run GREEN, including injected failure rollback and notification idempotency.**

```powershell
mvn -pl vcampus-server -am '-Dtest=SecondhandOrderTransactionTest,SecondhandOrderServiceTest,ShopCheckoutTransactionTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- [ ] **Step 5: Commit only Task 4 paths.**

```powershell
git add -- vcampus-server/src/main/java/com/vcampus/server/database/SecondhandOrderStore.java vcampus-server/src/main/java/com/vcampus/server/database/SecondhandOrderRepository.java vcampus-server/src/main/java/com/vcampus/server/service/SecondhandOrderService.java vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java vcampus-common/src/main/java/com/vcampus/common/model/NotificationType.java vcampus-common/src/main/java/com/vcampus/common/model/NotificationTarget.java database/schema.sql database/migrations/026_secondhand_market.sql vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java vcampus-server/src/test/java/com/vcampus/server/database/SecondhandOrderTransactionTest.java vcampus-server/src/test/java/com/vcampus/server/service/SecondhandOrderServiceTest.java
git commit -m "feat(shop): settle secondhand orders through held funds"
```

### Task 5: Order-backed reviews and seller reputation

**Files:**
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/SecondhandReviewStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/SecondhandReviewRepository.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/service/SecondhandReviewService.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/service/SecondhandReviewImageService.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/SecondhandReviewImageRepository.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/config/SecondhandReviewImageConfig.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/SecondhandReviewRepositoryTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/service/SecondhandReviewServiceTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/service/SecondhandReviewImageServiceTest.java`

**Interfaces:** `SecondhandReviewStore.create(long buyer,String operationId,long orderId,int rating,String content,List<PendingImage> images)`, `update/delete/like`, `listSeller(long seller,int page)`, `summary(long seller)`, `moderate(long admin,long review,boolean hidden,String reason)`. `PendingImage` is a nested review-store record of claimed upload ID, storage keys, MIME, byte size and hash. `SecondhandReviewImageConfig.fromEnvironment()` returns a `ShopReviewImageConfig` rooted at `VCAMPUS_SECONDHAND_REVIEW_IMAGE_DIR` (default `data/secondhand-review-images`). Actions use `shop.used.review.list/create/update/delete/like.set`, `shop.used.review.image.upload.start/chunk/complete/getChunk`, and `shop.used.admin.review.search/hide/restore`. One `secondhand_reviews.order_id` is unique even after soft deletion.

- [ ] **Step 1: Write failing eligibility/reputation tests.** Only a `COMPLETED` order buyer can review; rating 1–5 and text 5–1000; same order cannot review twice even after deletion; another completed order for the same seller may add a review; hidden/deleted reviews do not affect summary. Test max three images, owner edit/delete, no self-like, and administrator hide/restore reason audit.

```java
var review = reviews.create(buyerId, operationId, completedOrderId, 5,
        "物品与描述一致", List.of());
assertEquals(1, reviews.summary(sellerId).count());
reviews.moderate(adminId, review.id(), true, "评价包含违规内容");
assertEquals(0, reviews.summary(sellerId).count());
```

- [ ] **Step 2: Run RED.**

```powershell
mvn -pl vcampus-server -am '-Dtest=SecondhandReviewRepositoryTest,SecondhandReviewServiceTest,SecondhandReviewImageServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: secondhand review service/repository are absent.

- [ ] **Step 3: Add review transactions and isolated images.** Reuse the existing shop review rules and image validation helpers but not its tables or permission assumptions. Lock the reviewed order and enforce `buyer_user_id` plus `COMPLETED`; insert `order_id UNIQUE` and `operation_id UNIQUE`; summary queries include only `NORMAL`. Image metadata and files use secondhand-review namespace. For any ambiguous SQL commit outcome keep finalized files until reconciliation.

```java
if (order.buyerId() != buyerId || order.status() != ShopOrderStatus.COMPLETED)
    throw new SecondhandRuleException("仅已完成订单的买家可以评价");
if (rating < 1 || rating > 5 || content.trim().length() < 5 || content.trim().length() > 1000)
    throw new IllegalArgumentException("评价内容无效");
```

- [ ] **Step 4: Run GREEN and ordinary-shop review regression.**

```powershell
mvn -pl vcampus-server -am '-Dtest=SecondhandReviewRepositoryTest,SecondhandReviewServiceTest,SecondhandReviewImageServiceTest,ShopReviewRepositoryTest,ShopReviewServiceTest,ShopReviewImageServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- [ ] **Step 5: Commit only Task 5 paths.**

```powershell
git add -- vcampus-server/src/main/java/com/vcampus/server/database/SecondhandReviewStore.java vcampus-server/src/main/java/com/vcampus/server/database/SecondhandReviewRepository.java vcampus-server/src/main/java/com/vcampus/server/service/SecondhandReviewService.java vcampus-server/src/main/java/com/vcampus/server/service/SecondhandReviewImageService.java vcampus-server/src/main/java/com/vcampus/server/database/SecondhandReviewImageRepository.java vcampus-server/src/main/java/com/vcampus/server/config/SecondhandReviewImageConfig.java vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java vcampus-server/src/test/java/com/vcampus/server/database/SecondhandReviewRepositoryTest.java vcampus-server/src/test/java/com/vcampus/server/service/SecondhandReviewServiceTest.java vcampus-server/src/test/java/com/vcampus/server/service/SecondhandReviewImageServiceTest.java
git commit -m "feat(shop): rate completed secondhand sales"
```

### Task 6: Buyer-side JavaFX market and checkout

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandGateway.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SocketSecondhandGateway.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandData.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandController.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandCatalogView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandDetailView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandCheckoutView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandImageCache.java`
- Create: `vcampus-client/src/main/resources/com/vcampus/client/fx/shop/secondhand.css`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopRoute.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/CampusApplication.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/SecondhandBuyerViewTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/SocketSecondhandGatewayTest.java`

**Interfaces:** `SecondhandGateway.search/get/imageChunk/buy/orders/order/cancel/confirm`, plus `ShopGateway.addresses()` from the address plan; checkout uses `ShopAddressSelectionView.choice().orElseThrow()` and a stable UUID. `SecondhandController.open(long itemId)` and `view()` expose its detail and root to `ShopView`. The new shop route is `used` and `used-order/<id>`.

- [ ] **Step 1: Write failing buyer/navigation tests.** Shop header exposes “二手市场”; public catalog hides hidden/reserved items; own item has no buy button; checkout requires a shared address; a one-time address is not saved; network retry keeps the same buy UUID; order detail displays immutable address and buyer confirm only in `SHIPPED`.

```java
assertNotNull(shopView.lookup("#shop-tab-used"));
secondhand.open(itemId);
assertNull(secondhand.view().lookup("#used-buy"));
secondhand.open(otherSellerItemId);
assertNotNull(secondhand.view().lookup("#used-buy"));
```

- [ ] **Step 2: Run RED.**

```powershell
mvn -pl vcampus-client -am '-Dtest=SecondhandBuyerViewTest,SocketSecondhandGatewayTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: secondhand client classes and route are absent.

- [ ] **Step 3: Implement market routing, typed gateway, catalog/detail/checkout/order views.** Preserve existing shop controller instance and route state; add a secondhand controller for its own pages. Reuse shared address selection and send `shop.used.order.buy` with item ID, operation UUID, and either saved slot/version or inline address; parse order and seller-rating responses strictly. Run Socket/image requests on the existing shop worker executor and return results on the JavaFX application thread.

```java
ShippingAddressChoice choice = addressSelection.choice().orElseThrow();
String operationId = checkoutOperationId;
request(ticket, () -> usedGateway.buy(itemId, operationId, choice),
        receipt -> openUsedOrder(receipt.orderId()), error -> checkout.showError(message(error)));
```

- [ ] **Step 4: Run GREEN and existing shop navigation/controller tests.**

```powershell
mvn -pl vcampus-client -am '-Dtest=SecondhandBuyerViewTest,SocketSecondhandGatewayTest,ShopControllerTest,ShopIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- [ ] **Step 5: Commit only Task 6 paths.**

```powershell
git add -- vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandGateway.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/SocketSecondhandGateway.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandData.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandController.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandCatalogView.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandDetailView.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandCheckoutView.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandImageCache.java vcampus-client/src/main/resources/com/vcampus/client/fx/shop/secondhand.css vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopView.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopRoute.java vcampus-client/src/main/java/com/vcampus/client/fx/CampusApplication.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/SecondhandBuyerViewTest.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/SocketSecondhandGatewayTest.java
git commit -m "feat(shop): browse and buy secondhand items"
```

### Task 7: Seller, review, and administrator JavaFX flows

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandSellerView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandItemEditorView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandReviewView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandAdminView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandImageUploader.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandReviewImageUploader.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandGateway.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SocketSecondhandGateway.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandController.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandData.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandDetailView.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/SecondhandSellerViewTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/SecondhandReviewViewTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/SecondhandAdminViewTest.java`

**Interfaces:** `SecondhandGateway.create/update/withdraw/uploadImages/ship/reviews/createReview/likeReview/adminHideItem/adminRestoreItem/adminResolve/adminHideReview/adminRestoreReview`; all take item/order/review IDs, never seller or buyer IDs from the UI.

- [ ] **Step 1: Write failing seller/review/admin UI tests.** A seller can edit and upload images only for own `AVAILABLE/VISIBLE` item, mark own `PAID` order delivered, and view only that order's address. A completed-order buyer can review once; seller rating is displayed on item cards. Admin screens require shop/super admin roles, show reason forms, and offer mutually exclusive refund/settle choices for shipped orders.

```java
seller.showOrder(ownPaidOrder);
assertNotNull(seller.lookup("#used-ship"));
seller.showOrder(otherSellerOrder);
assertNull(seller.lookup("#used-ship"));
assertNull(seller.lookup("#used-address"));
```

- [ ] **Step 2: Run RED.**

```powershell
mvn -pl vcampus-client -am '-Dtest=SecondhandSellerViewTest,SecondhandReviewViewTest,SecondhandAdminViewTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: seller/review/admin view classes do not exist.

- [ ] **Step 3: Implement each role-specific view with strict controls.** “发布物品 / 我的出售 / 我的购买” live inside the secondhand page. Use existing image chooser/upload and review controls through secondhand-specific gateway methods; owner/admin checks remain authoritative on server. The admin reason must be 2–255 characters; hide/restore and refund/settle buttons require explicit confirmation. Never put full address in listing rows, notifications or review views.

```java
boolean canShip = currentUserId == order.sellerId()
        && order.status() == ShopOrderStatus.PAID;
shipButton.setVisible(canShip);
shipButton.setManaged(canShip);
```

- [ ] **Step 4: Run GREEN, including ordinary-shop review/editor regression.**

```powershell
mvn -pl vcampus-client -am '-Dtest=SecondhandSellerViewTest,SecondhandReviewViewTest,SecondhandAdminViewTest,ShopReviewEditorViewTest,ShopReviewAdminViewTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- [ ] **Step 5: Commit only Task 7 paths.**

```powershell
git add -- vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandSellerView.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandItemEditorView.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandReviewView.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandAdminView.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandImageUploader.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandReviewImageUploader.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandGateway.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/SocketSecondhandGateway.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandController.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandData.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/SecondhandDetailView.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/SecondhandSellerViewTest.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/SecondhandReviewViewTest.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/SecondhandAdminViewTest.java
git commit -m "feat(shop): manage secondhand sales and reviews"
```

### Task 8: Cross-module acceptance, documentation, and release gate

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/NotificationDestination.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/NotificationDetailDialog.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/CampusApplication.java`
- Modify: `docs/shop.md`
- Modify: `docs/requirements.md`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/SecondhandOrderTransactionTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/SecondhandReviewRepositoryTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/SecondhandBuyerViewTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/ui/NotificationShopUiTest.java`

**Interfaces:** `NotificationTarget.SECONDHAND_ORDERS` from Task 4 navigates to `used-order/<id>`; destination requires a positive order ID, and the detail button says “查看二手订单”. This task verifies Tasks 1–7 and the shared-address prerequisite as one user journey.

- [ ] **Step 1: Add a failing notification destination test and cross-module contract test.** `SECONDHAND_ORDERS` with no ID must not navigate; a positive ID must lead to the secondhand order route. Seed buyer, seller, admin and one visible item. Assert buyer selects saved address, buys, seller sees that order's address and ships, buyer confirms, seller balance increases once, buyer creates review, seller summary becomes 5.0; another user cannot read the order address. Add injected rollback, concurrent purchase, canceled order relist, and admin shipped-order refund cases to the named server tests.

```java
assertFalse(new NotificationDestination(NotificationTarget.SECONDHAND_ORDERS, null).navigable());
var paid = orders.buy(buyerId, itemId, operationId, savedChoice);
orders.ship(sellerId, paid.orderId());
orders.confirm(buyerId, paid.orderId());
assertEquals(ShopOrderStatus.COMPLETED, orders.order(buyerId, paid.orderId(), false).status());
assertEquals(new BigDecimal("12.00"), bank.account(sellerId).balance());
```

- [ ] **Step 2: Run RED for notification routing.**

```powershell
mvn -pl vcampus-client -am '-Dtest=NotificationShopUiTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: the null-ID case fails because `NotificationDestination` currently accepts the new target by default. Run the server contract cases as well; if any fail, return to the owning task rather than weakening the assertion.

- [ ] **Step 3: Route secondhand notifications and update user-facing docs.** Require a positive related order ID in `NotificationDestination`; map `SECONDHAND_ORDERS` in `CampusApplication.notificationRoute` to `used-order/<id>`; set the detail-dialog button label. Document shared address selection, seller permissions, order escrow, exception resolution, review rules, image directory configuration, and test acceptance steps in `docs/shop.md`; change feature status in `docs/requirements.md`.

```java
case FORUM_POST, SHOP_ORDERS, SECONDHAND_ORDERS, LIBRARY_CATALOG ->
        relatedEntityId != null && relatedEntityId > 0;
case SECONDHAND_ORDERS -> "used-order/" + id;
```

- [ ] **Step 4: Run targeted acceptance, full root build and diff check.**

```powershell
mvn -pl vcampus-server -am '-Dtest=SecondhandOrderTransactionTest,SecondhandReviewRepositoryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -pl vcampus-client -am '-Dtest=NotificationShopUiTest,SecondhandBuyerViewTest,SecondhandSellerViewTest,SecondhandReviewViewTest,SecondhandAdminViewTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn clean verify
git diff --check
```

Expected: all pass; manual two-client smoke test confirms seller/buyer pages and address privacy. Review changed paths before staging so unrelated library work remains untouched.

- [ ] **Step 5: Commit only documentation and acceptance-test paths.**

```powershell
git add -- docs/shop.md docs/requirements.md vcampus-client/src/main/java/com/vcampus/client/ui/NotificationDestination.java vcampus-client/src/main/java/com/vcampus/client/ui/NotificationDetailDialog.java vcampus-client/src/main/java/com/vcampus/client/fx/CampusApplication.java vcampus-server/src/test/java/com/vcampus/server/database/SecondhandOrderTransactionTest.java vcampus-server/src/test/java/com/vcampus/server/database/SecondhandReviewRepositoryTest.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/SecondhandBuyerViewTest.java vcampus-client/src/test/java/com/vcampus/client/ui/NotificationShopUiTest.java
git commit -m "test(shop): verify secondhand address payment and reviews"
```
