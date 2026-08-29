# VCampus Shop Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a persistent virtual shop with product and inventory administration, server-side cart, atomic bank checkout, cancellable paid orders, fulfillment, notifications, and an embedded Swing client.

**Architecture:** `ShopRepository` owns product, cart, order, and inventory SQL and controls checkout/cancellation transactions. It calls the already implemented `BankPaymentWriter` with the same JDBC `Connection`, so bank balance, ledger, inventory, order, and cart changes commit or roll back together; `ShopService` and the existing Socket router expose the feature to Swing.

**Tech Stack:** Java 21, Swing, Java Socket, JDBC, MySQL 8, H2 MySQL mode, JUnit 5, Maven.

**Spec:** `docs/superpowers/specs/2026-08-29-bank-shop-design.md`

## Global Constraints

- Complete `docs/superpowers/plans/2026-08-29-bank-module-implementation.md` first; this plan consumes its exact `BankPaymentWriter` interface.
- Preserve MySQL database -> application server -> Swing client; the client must never import JDBC or connect to MySQL.
- Use the length-prefixed `MessageCodec` protocol and `shop.` action prefix.
- Recalculate prices and validate enabled state and inventory on the server while rows are locked.
- Checkout success creates a `PAID` order; failed checkout creates no order and changes no balance, ledger, inventory, or cart rows.
- Only `PAID` orders can be cancelled; cancellation refunds and restocks atomically. `SHIPPED` orders can only be confirmed to `COMPLETED`.
- Historical order-item snapshots and inventory movements are immutable.
- Keep Swing changes on the Event Dispatch Thread and network work off it; use dark text on light buttons.
- Update `database/schema.sql`, add `database/migrations/008_shop.sql`, update `database/seed.sql`, and update requirements/docs.

Before Maven commands in this Windows workspace, define:

```powershell
$mvn = 'C:\Users\Bestinme\Documents\Codex\2026-08-25\wo\work\tools\apache-maven-3.9.16\bin\mvn.cmd'
$mavenRepoArg = '-Dmaven.repo.local=C:\Users\Bestinme\Documents\Codex\2026-08-25\wo\work\tools\m2-repository'
```

---

## File Structure

### New files

- `vcampus-common/src/main/java/com/vcampus/common/model/ShopOrderStatus.java` — `PAID`, `SHIPPED`, `COMPLETED`, `CANCELLED`.
- `vcampus-common/src/main/java/com/vcampus/common/model/ShopInventoryMovementType.java` — inventory audit vocabulary.
- `vcampus-common/src/main/java/com/vcampus/common/model/ShopAccessPolicy.java` — shop administrator role decision.
- `database/migrations/008_shop.sql` — shop tables and notification constraint upgrade.
- Server model records: `ShopProductRecord`, `ShopCartItemRecord`, `ShopOrderRecord`, `ShopOrderItemRecord`, `ShopInventoryMovementRecord`.
- `vcampus-server/src/main/java/com/vcampus/server/database/ShopStore.java` — service-facing shop contract.
- `vcampus-server/src/main/java/com/vcampus/server/database/ShopRepository.java` — all shop JDBC queries and transactions.
- `vcampus-server/src/main/java/com/vcampus/server/database/ShopRuleException.java` — safe business-rule failures.
- `vcampus-server/src/main/java/com/vcampus/server/service/ShopService.java` — sessions, permissions, validation, and encoding.
- `vcampus-client/src/main/java/com/vcampus/client/ui/ShopAsync.java` — background request/EDT completion helper.
- `vcampus-client/src/main/java/com/vcampus/client/ui/ShopViewData.java` — typed response parsing and UI policy.
- `vcampus-client/src/main/java/com/vcampus/client/ui/ShopModulePanel.java` — embedded shop UI.
- Tests named `ShopVocabularyTest`, `ShopMigrationTest`, `ShopRepositoryTest`, `ShopCheckoutTransactionTest`, `ShopOrderLifecycleTest`, `ShopServiceTest`, `RequestRouterShopTest`, `ShopViewDataTest`, and `ShopModuleNavigationTest`.

### Modified files

- `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Notification enums and vocabulary test
- `database/schema.sql`, `database/seed.sql`
- `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`
- `vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java`
- `vcampus-client/src/main/java/com/vcampus/client/ui/MainModuleRoute.java`
- `vcampus-client/src/main/java/com/vcampus/client/ui/MainFrame.java`
- Notification UI/parser files
- `docs/requirements.md`, `docs/message-center.md`

---

### Task 1: Shared shop vocabulary and database migration

**Files:**
- Create the three common model files listed above.
- Modify: `Actions.java`, notification enums, `NotificationVocabularyTest.java`.
- Create: `ShopVocabularyTest.java`, `008_shop.sql`, `ShopMigrationTest.java`.
- Modify: `database/schema.sql`.

**Interfaces:**
- Produces: the fourteen `Actions.SHOP_*` constants from spec section 6.
- Produces: source `SHOP`, target `SHOP_ORDERS`, type `SHOP_ORDER_SHIPPED`.
- Produces: five shop tables and their unique/check/foreign-key constraints.

- [ ] **Step 1: Write failing vocabulary tests**

```java
@Test void shopWireVocabularyIsStable() {
    assertEquals("CANCELLED", ShopOrderStatus.CANCELLED.name());
    assertEquals("ORDER_CANCEL", ShopInventoryMovementType.ORDER_CANCEL.name());
    assertEquals("shop.checkout", Actions.SHOP_CHECKOUT);
    assertEquals("shop.admin.order.ship", Actions.SHOP_ADMIN_ORDER_SHIP);
    assertEquals("SHOP", NotificationSource.SHOP.name());
    assertEquals("SHOP_ORDERS", NotificationTarget.SHOP_ORDERS.name());
    assertEquals("SHOP_ORDER_SHIPPED", NotificationType.SHOP_ORDER_SHIPPED.name());
}
```

- [ ] **Step 2: Run the common tests and verify RED**

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-common' '-Dtest=ShopVocabularyTest,NotificationVocabularyTest' test
```

- [ ] **Step 3: Add shared enums, policy, and actions**

Define `ShopOrderStatus { PAID, SHIPPED, COMPLETED, CANCELLED }`, `ShopInventoryMovementType { INITIAL, ADMIN_ADJUST, SALE, ORDER_CANCEL }`, and `ShopAccessPolicy.canManage(Set<UserRole>)` as `SHOP_ADMIN || SUPER_ADMIN`. Add every action exactly as named in spec section 6 without changing existing constants.

- [ ] **Step 4: Run common tests and verify GREEN**

Run Step 2. Expected: all specified tests pass.

- [ ] **Step 5: Write failing schema assertions**

```java
@Test void freshAndUpgradeScriptsContainShopContract() throws Exception {
    String schema = Files.readString(Path.of("..", "database", "schema.sql"));
    String migration = Files.readString(Path.of("..", "database", "migrations", "008_shop.sql"));
    for (String table : List.of("shop_products", "shop_cart_items", "shop_orders", "shop_order_items", "shop_inventory_movements")) {
        assertTrue(schema.contains(table));
        assertTrue(migration.contains(table));
    }
    assertTrue(schema.contains("UNIQUE (checkout_operation_id)"));
    assertTrue(schema.contains("SHOP_ORDER_SHIPPED"));
}
```

- [ ] **Step 6: Add schema/migration and verify**

Implement section 3.2 using `DECIMAL(15,2)`, non-negative stock checks, positive cart/order quantities, unique SKU, `(user_id, product_id)` cart primary key, unique `order_no`, unique `checkout_operation_id`, immutable order snapshots, signed inventory `quantity_delta`, and indexes for product enabled/name, user orders, order status, and inventory history. Migration 008 uses `CREATE TABLE IF NOT EXISTS` and safely recreates notification checks with every old plus SHOP value.

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-server' '-am' '-Dtest=ShopMigrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
git add vcampus-common database vcampus-server/src/test/java/com/vcampus/server/database/ShopMigrationTest.java
git commit -m "feat(shop): add shop vocabulary and schema"
```

---

### Task 2: Product, inventory, and persistent cart repository

**Files:**
- Create: server shop model records, `ShopStore.java`, `ShopRepository.java`, `ShopRuleException.java`, `ShopRepositoryTest.java`.

**Interfaces:**
- Produces: `searchProducts(ProductQuery)`, `saveProduct(long operatorId, ProductInput)`, `setProductEnabled(...)`, `adjustInventory(...)`.
- Produces: `cart(long userId)`, `setCartQuantity(long userId,long productId,int quantity)`, and `removeCartItem(long userId,long productId)`.
- Produces: `ShopRepository(ConnectionFactory, BankPaymentWriter, NotificationWriter)`.
- Produces: `ProductInput(Long productId,String sku,String name,String description,BigDecimal price,boolean enabled)`, `ProductQuery(String keyword,Boolean enabled,int page,int pageSize)`, `ProductPage(List<ShopProductRecord> rows,int page,int pageSize,int total)`, `ProductSaveResult(long productId)`, `InventoryResult(long productId,int stockAfter)`, and `CartResult(List<ShopCartItemRecord> rows,BigDecimal estimatedTotal)`.

- [ ] **Step 1: Write failing H2 repository tests**

```java
@Test void cartPersistsOnlyProductAndQuantityAndUsesCurrentPrice() throws Exception {
    long productId = repository.saveProduct(9L, new ProductInput(null, "SKU-1", "教材", "说明", new BigDecimal("20.00"), true)).productId();
    repository.adjustInventory(9L, productId, 5, "首次入库");
    repository.setCartQuantity(1L, productId, 2);
    repository.saveProduct(9L, new ProductInput(productId, "SKU-1", "教材", "说明", new BigDecimal("25.00"), true));
    assertEquals(new BigDecimal("50.00"), repository.cart(1L).estimatedTotal());
}

@Test void inventoryAdjustmentCannotMakeStockNegative() throws Exception {
    assertThrows(ShopRuleException.class, () -> repository.adjustInventory(9L, productId, -6, "盘点"));
}
```

Also assert disabled products are hidden from ordinary searches but visible to admin searches, SKU is unique, zero cart quantity removes the row, and cart survives a new repository instance.

- [ ] **Step 2: Run repository tests and verify RED**

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-server' '-am' '-Dtest=ShopRepositoryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- [ ] **Step 3: Implement focused store records and JDBC methods**

```java
public interface ShopStore {
    ProductPage searchProducts(ProductQuery query) throws SQLException;
    ProductSaveResult saveProduct(long operatorId, ProductInput input) throws SQLException;
    boolean setProductEnabled(long operatorId, long productId, boolean enabled) throws SQLException;
    InventoryResult adjustInventory(long operatorId, long productId, int delta, String reason) throws SQLException;
    CartResult cart(long userId) throws SQLException;
    CartResult setCartQuantity(long userId, long productId, int quantity) throws SQLException;
    CartResult removeCartItem(long userId, long productId) throws SQLException;
}
```

Product save edits descriptive fields and price but never stock. Inventory adjustment locks the product, validates `stock + delta >= 0`, updates stock, and inserts `INITIAL` for first positive stocking or `ADMIN_ADJUST` otherwise in one transaction. Cart queries join current product data; disabled items remain visible with `enabled=false`.

- [ ] **Step 4: Run tests and commit**

Run Step 2, then:

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/database/ShopStore.java vcampus-server/src/main/java/com/vcampus/server/database/ShopRepository.java vcampus-server/src/main/java/com/vcampus/server/database/ShopRuleException.java vcampus-server/src/main/java/com/vcampus/server/model/Shop* vcampus-server/src/test/java/com/vcampus/server/database/ShopRepositoryTest.java
git commit -m "feat(shop): add products inventory and cart"
```

---

### Task 3: Atomic bank checkout and idempotent paid orders

**Files:**
- Modify: `ShopStore.java`, `ShopRepository.java`
- Create: `ShopCheckoutTransactionTest.java`

**Interfaces:**
- Produces: `CheckoutResult checkout(long buyerUserId, String operationId)`.
- Consumes: `BankPaymentWriter.debitForShop(Connection,long,BigDecimal,String,String)` from the bank plan.
- Produces: unique `orderNo` format `SO` + UTC `yyyyMMddHHmmss` + 12 uppercase hex characters.
- Produces: `CheckoutResult(long orderId,String orderNo,BigDecimal totalAmount,ShopOrderStatus status,boolean duplicate)`.

- [ ] **Step 1: Write failing successful-checkout and repricing tests**

```java
@Test void checkoutUsesCurrentPricesAndCommitsEveryResource() throws Exception {
    String operationId = UUID.randomUUID().toString();
    CheckoutResult result = repository.checkout(BUYER_ID, operationId);
    assertEquals(ShopOrderStatus.PAID, result.status());
    assertEquals(new BigDecimal("50.00"), result.totalAmount());
    assertEquals(1, scalarInt("SELECT COUNT(*) FROM shop_orders"));
    assertEquals(0, scalarInt("SELECT COUNT(*) FROM shop_cart_items WHERE user_id=" + BUYER_ID));
    assertEquals(1, scalarInt("SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='SHOP_PAYMENT'"));
}

@Test void repeatedOperationReturnsOriginalOrderWithoutSecondDebit() throws Exception {
    CheckoutResult first = repository.checkout(BUYER_ID, OPERATION_ID);
    CheckoutResult second = repository.checkout(BUYER_ID, OPERATION_ID);
    assertEquals(first.orderNo(), second.orderNo());
    assertEquals(1, scalarInt("SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='SHOP_PAYMENT'"));
}
```

- [ ] **Step 2: Add failing rollback and concurrency tests**

Cover empty cart, disabled product, insufficient stock, insufficient funds, frozen bank account, an injected payment-writer failure, and an injected order-item failure. After every failure assert unchanged balance/ledger/stock/cart and zero orders. Race two buyers for the final unit with a barrier; assert one `PAID` order, one failure, stock zero, and one payment.

- [ ] **Step 3: Run checkout tests and verify RED**

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-server' '-am' '-Dtest=ShopCheckoutTransactionTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- [ ] **Step 4: Implement the exact transaction order**

Open one connection, disable auto-commit, query an existing order by `(buyer_user_id, checkout_operation_id)` and return it when found; otherwise generate `orderNo`, lock cart rows, lock product rows by ascending ID, reject disabled/insufficient items, and recompute totals with `MoneyPolicy`. Call `debitForShop` on the same connection with `referenceNo=orderNo`, decrement inventory, insert one `SALE` movement per item, insert order and snapshots, delete only the locked cart rows, then commit. Roll back every participating table on any exception.

- [ ] **Step 5: Run checkout tests repeatedly and commit**

Run Step 3 three times, then:

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/database/ShopStore.java vcampus-server/src/main/java/com/vcampus/server/database/ShopRepository.java vcampus-server/src/test/java/com/vcampus/server/database/ShopCheckoutTransactionTest.java
git commit -m "feat(shop): add atomic bank checkout"
```

---

### Task 4: Order lifecycle, refund, fulfillment, and notifications

**Files:**
- Modify: `ShopStore.java`, `ShopRepository.java`
- Create: `ShopOrderLifecycleTest.java`

**Interfaces:**
- Produces: `searchOrders(OrderQuery)`, `order(long requesterId,long orderId,boolean admin)`.
- Produces: `cancelOrder(long buyerId,long orderId)`, `shipOrder(long operatorId,long orderId)`, `confirmOrder(long buyerId,long orderId)`.
- Consumes: `BankPaymentWriter.refundForShop(...)` and `NotificationWriter.insert(...)`.
- Produces: `OrderQuery(Long buyerUserId,ShopOrderStatus status,int page,int pageSize)`, `OrderPage(List<ShopOrderRecord> rows,int page,int pageSize,int total)`, and `OrderResult(long orderId,String orderNo,BigDecimal totalAmount,ShopOrderStatus status)`.

- [ ] **Step 1: Write failing lifecycle tests**

```java
@Test void cancellingPaidOrderRefundsRestocksAndChangesStateAtomically() throws Exception {
    CheckoutResult paid = repository.checkout(BUYER_ID, UUID.randomUUID().toString());
    OrderResult cancelled = repository.cancelOrder(BUYER_ID, paid.orderId());
    assertEquals(ShopOrderStatus.CANCELLED, cancelled.status());
    assertEquals(1, scalarInt("SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='SHOP_REFUND'"));
    assertEquals(1, scalarInt("SELECT COUNT(*) FROM shop_inventory_movements WHERE movement_type='ORDER_CANCEL'"));
}

@Test void shippingNotifiesBuyerAndConfirmationCompletesOrder() throws Exception {
    OrderResult shipped = repository.shipOrder(ADMIN_ID, paidOrderId);
    assertEquals(ShopOrderStatus.SHIPPED, shipped.status());
    assertEquals(1, scalarInt("SELECT COUNT(*) FROM notifications WHERE notification_type='SHOP_ORDER_SHIPPED'"));
    assertEquals(ShopOrderStatus.COMPLETED, repository.confirmOrder(BUYER_ID, paidOrderId).status());
}
```

Assert another user cannot read/cancel the order; cancellation after shipping fails; repeated cancellation does not double refund; notification failure rolls shipping back to `PAID`; and frozen buyer accounts accept cancellation refunds.

- [ ] **Step 2: Run lifecycle tests and verify RED**

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-server' '-am' '-Dtest=ShopOrderLifecycleTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- [ ] **Step 3: Implement state transitions with row locks**

Cancellation locks the owned order, returns the existing `CANCELLED` result on retry, rejects other states, locks products ascending, locks/refunds the bank account with deterministic reference `REFUND-<orderNo>`, restores stock, inserts `ORDER_CANCEL` movements, and updates status/time before commit. Shipping changes only `PAID -> SHIPPED`, writes `SHOP_ORDER_SHIPPED` targeting `SHOP_ORDERS` in the same transaction, and treats already shipped as an idempotent success. Confirmation changes only the buyer's `SHIPPED -> COMPLETED`.

- [ ] **Step 4: Run tests and commit**

Run Step 2, then:

```powershell
git add vcampus-server/src/main/java/com/vcampus/server/database/ShopStore.java vcampus-server/src/main/java/com/vcampus/server/database/ShopRepository.java vcampus-server/src/test/java/com/vcampus/server/database/ShopOrderLifecycleTest.java
git commit -m "feat(shop): add order fulfillment and refunds"
```

---

### Task 5: Shop service, Socket routing, and production composition

**Files:**
- Create: `ShopService.java`, `ShopServiceTest.java`, `RequestRouterShopTest.java`
- Modify: `RequestRouter.java`, `VCampusServer.java`, `VCampusClient.java`

**Interfaces:**
- Produces one `ShopService` method and one client method per `Actions.SHOP_*` action.
- Production composition passes the same `BankRepository` instance as `BankPaymentWriter` to `ShopRepository`.

- [ ] **Step 1: Write failing permission and identity tests**

```java
@Test void checkoutUsesSessionBuyerAndRequiresUuid() {
    ResponseMessage response = service.checkout(request(STUDENT, Map.of("operationId", UUID.randomUUID().toString())));
    assertTrue(response.success());
    verify(store).checkout(eq(STUDENT_ID), anyString());
}

@Test void ordinaryUserCannotSaveProductOrShipOrder() {
    assertEquals("无权执行商店管理操作", service.saveProduct(request(STUDENT, Map.of())).message());
    assertEquals("无权执行商店管理操作", service.shipOrder(request(STUDENT, Map.of())).message());
}
```

Cover expired sessions, quantity bounds, page bounds, invalid price/UUID, ownership, each safe `ShopRuleException` message, and generic database failure.

- [ ] **Step 2: Run service/router tests and verify RED**

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-server' '-am' '-Dtest=ShopServiceTest,RequestRouterShopTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- [ ] **Step 3: Implement response encoding and routing**

Use `RowCodec` for lists and stable fields: product rows contain ID/SKU/name/description/price/stock/enabled; cart rows add quantity/subtotal; order rows contain ID/orderNo/total/status/timestamps; order detail adds snapshot rows. Add all switch cases in `RequestRouter` and inject `ShopService` after `BankService`.

In `VCampusServer` construct once:

```java
BankRepository bankRepository = new BankRepository(connections, notificationRepository);
ShopRepository shopRepository = new ShopRepository(connections, bankRepository, notificationRepository);
```

Then inject those repositories into their services.

- [ ] **Step 4: Add explicit client methods**

```java
public ResponseMessage checkoutShop(String token, String operationId) throws IOException {
    return sendAuthorized(Actions.SHOP_CHECKOUT, token, Map.of("operationId", operationId));
}
```

Add concrete methods for product search, cart get/set/remove, order search/get/cancel/confirm, product save/enable, inventory adjustment, admin order search, and shipping. Each sends only documented parameters and the session token.

- [ ] **Step 5: Run tests and commit**

Run Step 2, then:

```powershell
git add vcampus-server vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java
git commit -m "feat(shop): expose shop socket services"
```

---

### Task 6: Embedded Swing shop module and notification deep link

**Files:**
- Create: `ShopAsync.java`, `ShopViewData.java`, `ShopModulePanel.java`, client tests.
- Modify: `MainModuleRoute.java`, `MainFrame.java`, notification UI/parser files.

**Interfaces:**
- Produces: `ShopModulePanel.activate()` and `openOrders()`.
- Produces: route `ModuleCode.SHOP -> "shop"` and notification target `SHOP_ORDERS -> ShopModulePanel.openOrders()`.

- [ ] **Step 1: Write failing UI parsing/policy tests**

```java
@Test void shopIsEmbeddedAndAdminViewsFollowRoles() {
    assertEquals(Optional.of("shop"), MainModuleRoute.route(ModuleCode.SHOP));
    assertFalse(ShopViewData.showAdminTabs(Set.of(UserRole.STUDENT)));
    assertTrue(ShopViewData.showAdminTabs(Set.of(UserRole.SHOP_ADMIN)));
}

@Test void orderActionsFollowState() {
    assertTrue(ShopViewData.canCancel(ShopOrderStatus.PAID));
    assertTrue(ShopViewData.canConfirm(ShopOrderStatus.SHIPPED));
    assertFalse(ShopViewData.canCancel(ShopOrderStatus.COMPLETED));
}
```

Parse representative product/cart/order rows and assert SHOP source labels plus `SHOP_ORDERS` navigation.

- [ ] **Step 2: Run client tests and verify RED**

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-client' '-am' '-Dtest=ShopViewDataTest,ShopModuleNavigationTest,MainModuleRouteTest,NotificationViewDataTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- [ ] **Step 3: Build ordinary and administrator tabs**

`ShopModulePanel` uses “商品列表”, “购物车”, “我的订单” and, for shop administrators, “商品管理”, “订单管理”. Product list supports keyword/page/add; cart supports quantity/delete/checkout; order detail renders immutable snapshots and state-appropriate buttons. Admin views support create/edit/enable, signed inventory adjustment with reason, order search, and ship. Use one generated checkout UUID per click, disable request buttons during `ShopAsync.run`, refresh dependent tabs after mutations, and use black foreground on every light button.

- [ ] **Step 4: Wire workspace and notification navigation**

Add `showShop()` in `MainFrame`, lazy-register the panel, handle route `"shop"`, and map `SHOP_ORDERS` to `openOrders()`. Extend notification filters/display labels for source `SHOP`.

- [ ] **Step 5: Run client tests and commit**

Run Step 2, then:

```powershell
git add vcampus-client
git commit -m "feat(shop): add embedded Swing shop module"
```

---

### Task 7: Seed data, documentation, and end-to-end verification

**Files:**
- Modify: `database/seed.sql`, `docs/requirements.md`, `docs/message-center.md`
- Create: `docs/shop.md`

**Interfaces:**
- Produces idempotent demo products/inventory and complete operator documentation.
- Produces fully verified bank/shop integration.

- [ ] **Step 1: Add idempotent product and inventory seeds**

Upsert fictional SKUs and descriptive fields without overwriting administrator-adjusted stock. Insert each initial inventory movement with a deterministic seed reference/reason only when absent, and change stock only in the same guarded statement/transaction so running `seed.sql` twice is neutral.

- [ ] **Step 2: Update documentation**

Mark shop requirements implemented; document product/cart/order states, role matrix, checkout/refund atomicity, actions, migration order `007` then `008`, and manual startup in `docs/shop.md`. Add `SHOP_ORDER_SHIPPED` and `SHOP_ORDERS` to message-center docs.

- [ ] **Step 3: Run focused and full verification**

```powershell
& $mvn $mavenRepoArg '-pl=vcampus-common,vcampus-server,vcampus-client' test
& $mvn $mavenRepoArg clean verify
rg -n "java\.sql|javax\.sql|jdbc:" vcampus-client/src
```

Expected: Maven reports `BUILD SUCCESS`; the `rg` command returns no client JDBC matches.

- [ ] **Step 4: Perform MySQL/manual acceptance**

Apply migration 008 and run seed twice. With two ordinary clients, one shop administrator, and one bank administrator verify: persistent carts, server repricing, disabled product rejection, insufficient funds, frozen payer, concurrent last-stock checkout, one paid order per UUID, cancellation refund/restock, shipping notification deep link, confirmation, immutable snapshots/ledgers/movements, and permission isolation.

- [ ] **Step 5: Commit**

```powershell
git add database/seed.sql docs
git commit -m "docs(shop): finish shop module delivery"
```
