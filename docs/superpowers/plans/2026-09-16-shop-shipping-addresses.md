# Shared Shop Shipping Addresses Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every user up to three shared shipping addresses and require an immutable address snapshot for every new ordinary-shop or secondhand order.

**Architecture:** A shared address repository owns the three-slot book and resolves a buyer's saved or one-time address inside the caller's checkout transaction. Ordinary-shop checkout, both JavaFX and Swing clients, and later the secondhand order repository consume the same value and protocol contract. Order snapshots are independent of mutable address-book rows.

**Tech Stack:** Java 21, Maven, Swing, JavaFX, Socket/`MessageCodec`, MySQL JDBC, H2/JUnit 5 tests.

**Spec:** `docs/superpowers/specs/2026-09-16-shop-shipping-addresses-design.md` (prerequisite for `docs/superpowers/specs/2026-09-16-secondhand-market-design.md`).

## Global Constraints

- Preserve MySQL → application server → client; clients never connect to MySQL.
- Keep shared protocol/models in `vcampus-common`, JDBC and Socket handling in `vcampus-server`, UI/networking in `vcampus-client`.
- Use `MessageCodec`, not Java native serialization; keep network/database work off the UI threads.
- A user has at most three slots, numbered 1–3; no automatic overwrite; checkout requires a saved slot plus version or an inline address.
- Server derives address owner and order buyer from the session. Never put full addresses/phone numbers into notification text or logs.
- New orders store recipient, phone, address snapshots; legacy shop orders may have null snapshots and must display that fact.
- Do not stage or overwrite the pre-existing unrelated library-module worktree changes. Use `git add -- <listed paths>` for each commit.
- Use migration `025_shop_shipping_addresses.sql` and keep `database/schema.sql` in sync; verify numbering before execution because later work may add migrations.
- Root verification: `mvn clean verify` with Java release 21.

---

### Task 1: Address value, three-slot repository, and migration

**Files:**
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/ShippingAddressValue.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/ShippingAddressChoice.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/ShopAddress.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/ShopAddressStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/ShopAddressRepository.java`
- Create: `database/migrations/025_shop_shipping_addresses.sql`
- Modify: `database/schema.sql`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/ShopAddressRepositoryTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/ShopAddressMigrationTest.java`

**Interfaces:**
- `ShippingAddressValue(String recipient, String phone, String address)` validates trimmed lengths 1–40, 5–32, and 5–255 and rejects control characters.
- `ShippingAddressChoice(Integer slot, String version, ShippingAddressValue inline)` requires exactly one of saved `(slot, version)` or `inline`.
- `ShopAddress(int slot, String version, ShippingAddressValue value)` is the address-book response.
- `ShopAddressStore.list(long userId)`, `save(long userId, Integer replaceSlot, ShippingAddressValue value)`, `delete(long userId, int slot)`; `ShopAddressRepository.resolve(Connection c, long userId, ShippingAddressChoice choice)` returns the checked snapshot value.

- [ ] **Step 1: Write failing repository and migration tests.** Use H2 MySQL mode and a `users(id)` fixture. Assert three saves occupy slots 1–3, the fourth without `replaceSlot` fails, overwriting slot 2 generates a new version, and an old version cannot resolve.

```java
repo.save(1L, null, new ShippingAddressValue("张三", "13800000000", "校内一号楼 101"));
var second = repo.save(1L, null, new ShippingAddressValue("张三", "13800000000", "校内二号楼 102"));
repo.save(1L, null, new ShippingAddressValue("张三", "13800000000", "校内三号楼 103"));
assertThrows(IllegalArgumentException.class, () -> repo.save(1L, null, second.value()));
var replaced = repo.save(1L, 2, new ShippingAddressValue("李四", "13900000000", "校内四号楼 104"));
assertNotEquals(second.version(), replaced.version());
assertEquals(3, repo.list(1L).size());
```

- [ ] **Step 2: Run the tests to confirm RED.**

```powershell
mvn -pl vcampus-server -am '-Dtest=ShopAddressRepositoryTest,ShopAddressMigrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: compilation fails because address types/repository do not exist.

- [ ] **Step 3: Add schema and repository.** The table uses `(user_id, slot)` as primary key, `CHECK(slot BETWEEN 1 AND 3)`, a per-save UUID `version_token`, and a foreign key to `users(id)`. Lock the `users` row before selecting a free slot or replacing/deleting. `resolve` locks the selected row, checks owner via `user_id` and compares the token; inline values never touch the address table.

```java
public ShippingAddressValue resolve(Connection c, long userId, ShippingAddressChoice choice)
        throws SQLException {
    if (choice.inline() != null) return choice.inline();
    try (var s = c.prepareStatement("SELECT recipient,phone,address_text,version_token "
            + "FROM shop_shipping_addresses WHERE user_id=? AND slot=? FOR UPDATE")) {
        s.setLong(1, userId); s.setInt(2, choice.slot());
        try (var r = s.executeQuery()) {
            if (!r.next() || !r.getString(4).equals(choice.version()))
                throw new IllegalArgumentException("收货地址已变化，请重新确认");
            return new ShippingAddressValue(r.getString(1), r.getString(2), r.getString(3));
        }
    }
}
```

- [ ] **Step 4: Run targeted tests GREEN, including a concurrent two-client fourth-save attempt.**

```powershell
mvn -pl vcampus-server -am '-Dtest=ShopAddressRepositoryTest,ShopAddressMigrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: all pass; no user has more than three rows.

- [ ] **Step 5: Commit only Task 1 paths.**

```powershell
git add -- database/schema.sql database/migrations/025_shop_shipping_addresses.sql vcampus-common/src/main/java/com/vcampus/common/model/ShippingAddressValue.java vcampus-common/src/main/java/com/vcampus/common/model/ShippingAddressChoice.java vcampus-common/src/main/java/com/vcampus/common/model/ShopAddress.java vcampus-server/src/main/java/com/vcampus/server/database/ShopAddressStore.java vcampus-server/src/main/java/com/vcampus/server/database/ShopAddressRepository.java vcampus-server/src/test/java/com/vcampus/server/database/ShopAddressRepositoryTest.java vcampus-server/src/test/java/com/vcampus/server/database/ShopAddressMigrationTest.java
git commit -m "feat(shop): add shared shipping address book"
```

### Task 2: Authenticated address protocol

**Files:**
- Modify: `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/service/ShopAddressService.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/service/ShopAddressServiceTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterShopTest.java`

**Interfaces:** `shop.address.list`, `shop.address.save`, `shop.address.delete`; save accepts `recipient`, `phone`, `address`, and optional `replaceSlot`. Response rows encode `slot`, `version`, `recipient`, `phone`, `address` with `RowCodec`.

- [ ] **Step 1: Write failing service/router tests.** A session for user 1 lists only user 1's addresses and cannot replace/delete user 2's slot. Test missing/extra parameters and full-address responses only through authorized calls.

```java
var response = service.save(RequestMessage.create("shop.address.save",
        Map.of("sessionToken", user1Token, "recipient", "张三",
                "phone", "13800000000", "address", "校内一号楼 101")));
assertTrue(response.success());
assertEquals(1, repo.list(1L).size());
assertEquals(0, repo.list(2L).size());
```

- [ ] **Step 2: Run RED.**

```powershell
mvn -pl vcampus-server -am '-Dtest=ShopAddressServiceTest,RequestRouterShopTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: new action/service symbols are absent.

- [ ] **Step 3: Implement strict service parameters and routing.** Keep the existing `RequestRouter` constructors callable by tests; add a final constructor overload accepting `ShopAddressService`, and have prior overloads delegate with `null`. Production wiring passes the real service.

```java
public static final String SHOP_ADDRESS_LIST = "shop.address.list";
public static final String SHOP_ADDRESS_SAVE = "shop.address.save";
public static final String SHOP_ADDRESS_DELETE = "shop.address.delete";
case Actions.SHOP_ADDRESS_LIST -> addressService.list(request);
case Actions.SHOP_ADDRESS_SAVE -> addressService.save(request);
case Actions.SHOP_ADDRESS_DELETE -> addressService.delete(request);
```

- [ ] **Step 4: Run targeted tests GREEN and verify no address/phone enters error logs.**

```powershell
mvn -pl vcampus-server -am '-Dtest=ShopAddressServiceTest,RequestRouterShopTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- [ ] **Step 5: Commit only Task 2 paths.**

```powershell
git add -- vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java vcampus-server/src/main/java/com/vcampus/server/service/ShopAddressService.java vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java vcampus-server/src/test/java/com/vcampus/server/service/ShopAddressServiceTest.java vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterShopTest.java
git commit -m "feat(shop): expose authenticated address actions"
```

### Task 3: Require snapshots in ordinary-shop checkout

**Files:**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/ShopStore.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/ShopRepository.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/model/ShopOrderRecord.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/ShopService.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`
- Modify: `database/schema.sql`
- Modify: `database/migrations/025_shop_shipping_addresses.sql`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/ShopCheckoutTransactionTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/ShopOrderLifecycleTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/service/ShopServiceTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/ShopRepositoryTest.java`

**Interfaces:** `ShopStore.checkout(long, String, ShippingAddressChoice)`, `checkoutCart(long, String, Set<Long>, ShippingAddressChoice)`, and `buyNow(long, String, long, int, ShippingAddressChoice)`. Inject Task 1's `ShopAddressRepository` into `ShopRepository` and wire it in `VCampusServer`; update its H2 test constructors. `ShopOrderRecord` exposes nullable `ShippingAddressValue shippingAddress()` for legacy rows. Protocol accepts saved `addressMode=saved,addressSlot,addressVersion` or inline `addressMode=inline,recipient,phone,address`.

- [ ] **Step 1: Write failing checkout tests.** Extend existing H2 fixtures with `shop_shipping_addresses`, supply a choice to every successful checkout, and assert that changing the address book later does not alter the order snapshot. Assert inline checkout leaves the book empty, missing address fails before debit, stale saved version fails before debit, and a duplicate operation returns the original order after the book changes.

```java
var choice = new ShippingAddressChoice(null, null,
        new ShippingAddressValue("张三", "13800000000", "校内一号楼 101"));
var paid = repository.checkout(1L, operationId, choice);
assertEquals("校内一号楼 101", repository.order(1L, paid.orderId(), false)
        .order().shippingAddress().address());
assertEquals(0, addressRepo.list(1L).size());
```

- [ ] **Step 2: Run RED.**

```powershell
mvn -pl vcampus-server -am '-Dtest=ShopCheckoutTransactionTest,ShopOrderLifecycleTest,ShopServiceTest,ShopRepositoryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: checkout signatures and order snapshot accessors are missing.

- [ ] **Step 3: Migrate nullable legacy columns and change checkout.** Preserve the existing duplicate-operation lookup as the first transactional step, then resolve saved/inline address in that same transaction before `payments.debitForShop`, insert the three snapshot fields with the order, and return null snapshot for old rows. Change `ShopService` strict parameter validation for both checkout actions; add focused order-detail fields without exposing the address in list or notification responses.

```java
CheckoutResult existing = existingCheckout(connection, buyerUserId, operation);
if (existing != null) { connection.commit(); return existing; }
ShippingAddressValue shipping = addresses.resolve(connection, buyerUserId, choice);
payments.debitForShop(connection, buyerUserId, total, orderNo, "校园商店订单 " + orderNo);
long orderId = insertOrder(connection, orderNo, buyerUserId, operation, total, shipping);
```

- [ ] **Step 4: Run GREEN, including migration test and all existing ordinary-shop server tests.**

```powershell
mvn -pl vcampus-server -am '-Dtest=ShopCheckoutTransactionTest,ShopOrderLifecycleTest,ShopServiceTest,ShopRepositoryTest,ShopMigrationTest,ShopAddressMigrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- [ ] **Step 5: Commit only Task 3 paths.**

```powershell
git add -- database/schema.sql database/migrations/025_shop_shipping_addresses.sql vcampus-server/src/main/java/com/vcampus/server/database/ShopStore.java vcampus-server/src/main/java/com/vcampus/server/database/ShopRepository.java vcampus-server/src/main/java/com/vcampus/server/model/ShopOrderRecord.java vcampus-server/src/main/java/com/vcampus/server/service/ShopService.java vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java vcampus-server/src/test/java/com/vcampus/server/database/ShopCheckoutTransactionTest.java vcampus-server/src/test/java/com/vcampus/server/database/ShopOrderLifecycleTest.java vcampus-server/src/test/java/com/vcampus/server/service/ShopServiceTest.java vcampus-server/src/test/java/com/vcampus/server/database/ShopRepositoryTest.java
git commit -m "feat(shop): snapshot shipping addresses at checkout"
```

### Task 4: JavaFX address book and checkout selection

**Files:**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopAddressView.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopAddressSelectionView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopGateway.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/SocketShopGateway.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopData.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopCheckoutView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopOrderDetailView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopController.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopAddressViewTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopCheckoutViewTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopControllerTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopGatewayTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopReviewImagePipelineTest.java`

**Interfaces:** `ShopGateway.addresses()`, `saveAddress(Integer replaceSlot, ShippingAddressValue value)`, `deleteAddress(int slot)` and checkout/buy-now methods with `ShippingAddressChoice`; `ShopAddressSelectionView.choice()` returns `Optional<ShippingAddressChoice>`. Update both existing `ShopGateway` fake classes in `ShopControllerTest` and `ShopReviewImagePipelineTest` to implement the new checkout signatures.

- [ ] **Step 1: Write failing JavaFX/controller and gateway tests.** Assert checkout is disabled without a choice; three saved entries trigger explicit replacement when saving a fourth; inline one-time address is sent as `addressMode=inline`; old order details display “历史订单无收货地址”.

```java
assertTrue(((Button) checkout.lookup("#shop-checkout-confirm")).isDisabled());
((TextField) selection.lookup("#shop-address-recipient")).setText("张三");
((TextField) selection.lookup("#shop-address-phone")).setText("13800000000");
((TextField) selection.lookup("#shop-address-text")).setText("校内一号楼 101");
assertEquals("校内一号楼 101", selection.choice().orElseThrow().inline().address());
assertFalse(((Button) checkout.lookup("#shop-checkout-confirm")).isDisabled());
```

- [ ] **Step 2: Run RED.**

```powershell
mvn -pl vcampus-client -am '-Dtest=ShopAddressViewTest,ShopCheckoutViewTest,ShopControllerTest,ShopGatewayTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: new selection/view and gateway methods do not exist.

- [ ] **Step 3: Add background-backed address views and protocol serialization.** The chooser presents saved slots and an inline form; “保存” calls the independent address action before payment. On three saved rows, require a specific slot and confirmation. Keep the checkout operation ID stable across network retry, send the chosen slot/version or inline fields, and refresh the order page with snapshot display.

```java
Map<String, String> address = choice.inline() == null
        ? Map.of("addressMode", "saved", "addressSlot", Integer.toString(choice.slot()),
                 "addressVersion", choice.version())
        : Map.of("addressMode", "inline", "recipient", choice.inline().recipient(),
                 "phone", choice.inline().phone(), "address", choice.inline().address());
values.putAll(address);
return sendAuthorized(Actions.SHOP_CHECKOUT, token, values);
```

- [ ] **Step 4: Run GREEN, including integration UI tests; verify JavaFX changes occur on its application thread.**

```powershell
mvn -pl vcampus-client -am '-Dtest=ShopAddressViewTest,ShopCheckoutViewTest,ShopControllerTest,ShopGatewayTest,ShopIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- [ ] **Step 5: Commit only Task 4 paths.**

```powershell
git add -- vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopAddressView.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopAddressSelectionView.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopGateway.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/SocketShopGateway.java vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopData.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopView.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopCheckoutView.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopOrderDetailView.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopController.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopAddressViewTest.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopCheckoutViewTest.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopControllerTest.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopGatewayTest.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopReviewImagePipelineTest.java
git commit -m "feat(shop): choose shared shipping addresses in JavaFX"
```

### Task 5: Swing compatibility and release verification

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/ShopModulePanel.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java`
- Modify: `docs/shop.md`
- Modify: `docs/requirements.md`
- Test: `vcampus-client/src/test/java/com/vcampus/client/ui/ShopModuleNavigationTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/ui/ShopViewDataTest.java`

**Interfaces:** The old Swing checkout calls `VCampusClient.checkoutShop(String token,String operationId,ShippingAddressChoice choice)`; no no-address checkout remains exposed. Add package-private `ShopModulePanel.AddressPrompt` with `ShippingAddressChoice choose(Component parent,List<ShopAddress> saved)` and a constructor overload for test injection; the existing constructor delegates to the real dialog. The cart button invokes package-private `startCheckout()`, which the test calls on the Event Dispatch Thread.

- [ ] **Step 1: Add a failing Swing checkout test.** For an empty address book, clicking checkout opens an address form and sends an inline choice; closing that form sends no checkout request. Verify address-book actions run through `ShopAsync`, not on the Event Dispatch Thread.

```java
CountDownLatch prompted = new CountDownLatch(1);
ShopModulePanel.AddressPrompt cancel = (parent, saved) -> { prompted.countDown(); return null; };
ShopModulePanel panel = new ShopModulePanel(fakeClient, "token", roles, null, cancel);
SwingUtilities.invokeAndWait(panel::startCheckout);
assertTrue(prompted.await(2, TimeUnit.SECONDS));
assertEquals(0, fakeClient.checkoutCalls());
```

- [ ] **Step 2: Run RED.**

```powershell
mvn -pl vcampus-client -am '-Dtest=ShopModuleNavigationTest,ShopViewDataTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: address UI hooks and checkout choice are absent.

- [ ] **Step 3: Add minimal Swing select/new-address dialog and update docs.** Fetch saved addresses asynchronously, collect either saved slot/version or validated inline value, and call the address-required checkout API. Do not add a secondhand Swing UI.

```java
ShippingAddressChoice choice = selected == null
        ? new ShippingAddressChoice(null, null,
                new ShippingAddressValue(recipientField.getText(), phoneField.getText(), addressField.getText()))
        : new ShippingAddressChoice(selected.slot(), selected.version(), null);
ShopAsync.run(this, () -> ShopViewData.requireSuccess(
        client.checkoutShop(token, operationId, choice)), this::showReceipt, this::showError);
```

- [ ] **Step 4: Run targeted tests, then full root verification and `git diff --check`.**

```powershell
mvn -pl vcampus-client -am '-Dtest=ShopModuleNavigationTest,ShopViewDataTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn clean verify
git diff --check
```

Expected: targeted tests and all root modules pass; no whitespace errors. Also manually inspect legacy order rendering and a current order with an address.

- [ ] **Step 5: Commit only Task 5 paths.**

```powershell
git add -- vcampus-client/src/main/java/com/vcampus/client/ui/ShopModulePanel.java vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java vcampus-client/src/test/java/com/vcampus/client/ui/ShopModuleNavigationTest.java vcampus-client/src/test/java/com/vcampus/client/ui/ShopViewDataTest.java docs/shop.md docs/requirements.md
git commit -m "feat(shop): retain Swing checkout with required addresses"
```
