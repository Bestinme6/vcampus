# Shop JavaFX and Product Images Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Replace the primary Swing shop experience with the approved JavaFX card storefront, add server-owned product images, product detail, buy-now confirmation, and partial-cart checkout without regressing bank, inventory, order, refund, or notification guarantees.

**Architecture:** Preserve the MySQL → application server → JavaFX client boundary. Store normalized JPG/PNG files under a server-configured root and keep only image metadata in MySQL; move image data through bounded Base64 Socket chunks. Add a typed JavaFX shop gateway/controller/view stack while retaining the legacy Swing shop as a compatibility fallback.

**Tech Stack:** Java 21, JavaFX 21.0.11, CSS, MySQL 8, JDBC, Socket, MessageCodec, Maven, JUnit 5, H2 test fixtures, Java ImageIO.

**Spec:** docs/superpowers/specs/2026-09-01-shop-javafx-images-design.md

## Global Constraints

- Keep the three-tier flow MySQL → application server → JavaFX client; no client JDBC.
- Continue using MessageCodec protocol version 1; no Java native object serialization.
- MessageCodec strings remain below 1 MB; image chunks represent at most 192 KiB of decoded bytes.
- Accept JPG and PNG only, at most 2 MiB per image, five images per product, and 20,000,000 decoded pixels per image.
- Keep all Socket, file, hash, and image work off the JavaFX Application Thread; update JavaFX nodes only on that thread.
- Keep prices as BigDecimal and MySQL DECIMAL(15,2); never trust client prices, buyer IDs, stock, categories, paths, or MIME declarations.
- Preserve the existing checkout transaction: bank debit, ledger, order, snapshots, inventory, inventory ledger, cart cleanup, and notification commit or roll back together.
- Preserve all unrelated dirty-worktree changes. Stage and commit only the files named by each task.
- Do not apply migrations to the user's MySQL instance or start the production server as part of automated work.

---

## File Structure Map

### Common

- vcampus-common/.../model/ShopCategory.java: fixed wire-safe category vocabulary and Chinese labels.
- vcampus-common/.../model/ShopProductSort.java: server-whitelisted product ordering.
- vcampus-common/.../protocol/Actions.java: additive shop detail, image, and buy-now actions.

### Server

- vcampus-server/.../config/ShopImageConfig.java: environment-backed image limits and storage root.
- vcampus-server/.../image/ShopImageStore.java: storage interface for upload sessions, finalized variants, reads, and cleanup.
- vcampus-server/.../image/FileShopImageStore.java: secure filesystem/ImageIO implementation.
- vcampus-server/.../image/ShopImageException.java: stable validation failures safe to expose through ShopImageService.
- vcampus-server/.../service/ShopImageService.java: session, permission, chunk, download, and commit orchestration.
- vcampus-server/.../model/ShopProductImageRecord.java: immutable JDBC image metadata.
- Existing ShopStore, ShopRepository, ShopService, RequestRouter, and VCampusServer: product/category/detail/checkout wiring.

### Client

- vcampus-client/.../fx/shop/ShopGateway.java and SocketShopGateway.java: typed synchronous Socket boundary.
- vcampus-client/.../fx/shop/ShopData.java: immutable page/detail/cart/order/image records and strict decoders.
- vcampus-client/.../fx/shop/ShopImageCache.java: chunk reassembly, SHA-256 validation, atomic disk cache.
- vcampus-client/.../fx/shop/ShopController.java: FX-thread-confined navigation and async generation guards.
- Focused catalog, detail, cart, checkout, order, and admin view classes under fx/shop.
- vcampus-client/.../resources/com/vcampus/client/fx/shop/shop.css: shop-scoped styles.

---

### Task 1: Add the Common Shop Vocabulary and Protocol Contract

**Files:**
- Create: vcampus-common/src/main/java/com/vcampus/common/model/ShopCategory.java
- Create: vcampus-common/src/main/java/com/vcampus/common/model/ShopProductSort.java
- Modify: vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java
- Create: vcampus-common/src/test/java/com/vcampus/common/model/ShopCategoryTest.java
- Create: vcampus-common/src/test/java/com/vcampus/common/protocol/ShopImageActionsTest.java
- Modify: vcampus-common/src/test/java/com/vcampus/common/protocol/MessageCodecTest.java

**Interfaces:**
- Produces: ShopCategory values LEARNING_STATIONERY, DAILY_SUPPLIES, DIGITAL_ACCESSORIES, CAMPUS_MERCH, OTHER; displayName(); parse(String).
- Produces: ShopProductSort values NEWEST, PRICE_ASC, PRICE_DESC, NAME_ASC.
- Produces action constants SHOP_PRODUCT_GET, SHOP_IMAGE_GET_CHUNK, SHOP_BUY_NOW, SHOP_ADMIN_IMAGE_UPLOAD_START, SHOP_ADMIN_IMAGE_UPLOAD_CHUNK, SHOP_ADMIN_IMAGE_UPLOAD_COMPLETE, SHOP_ADMIN_IMAGE_COMMIT.

- [ ] **Step 1: Write vocabulary and action tests**

    @Test
    void categoryParsesOnlyStableWireNames() {
        assertEquals(ShopCategory.CAMPUS_MERCH, ShopCategory.parse("CAMPUS_MERCH"));
        assertEquals("校园周边", ShopCategory.CAMPUS_MERCH.displayName());
        assertThrows(IllegalArgumentException.class, () -> ShopCategory.parse("校内周边"));
    }

    @Test
    void shopImageActionsKeepShopPrefixes() {
        assertEquals("shop.product.get", Actions.SHOP_PRODUCT_GET);
        assertEquals("shop.admin.image.commit", Actions.SHOP_ADMIN_IMAGE_COMMIT);
    }

- [ ] **Step 2: Add a protocol-size regression test**

    @Test
    void encodedImageChunkFitsOneMessageString() throws Exception {
        byte[] bytes = new byte[192 * 1024];
        String base64 = Base64.getEncoder().encodeToString(bytes);
        assertTrue(base64.getBytes(StandardCharsets.UTF_8).length < 1024 * 1024);
        roundTrip(RequestMessage.create(Actions.SHOP_ADMIN_IMAGE_UPLOAD_CHUNK,
                Map.of("contentBase64", base64)));
    }

- [ ] **Step 3: Run the focused tests and confirm failure**

    mvn -pl vcampus-common -Dtest=ShopCategoryTest,ShopImageActionsTest,MessageCodecTest test

Expected: compilation fails because the enums and action constants do not exist.

- [ ] **Step 4: Implement the minimal stable contract**

    public enum ShopCategory {
        LEARNING_STATIONERY("学习文具"),
        DAILY_SUPPLIES("生活用品"),
        DIGITAL_ACCESSORIES("数码配件"),
        CAMPUS_MERCH("校园周边"),
        OTHER("其他");

        private final String displayName;
        ShopCategory(String displayName) { this.displayName = displayName; }
        public String displayName() { return displayName; }
        public static ShopCategory parse(String value) {
            return ShopCategory.valueOf(Objects.requireNonNull(value, "value").trim());
        }
    }

Add the exact action values listed in Interfaces and a simple ShopProductSort enum.

- [ ] **Step 5: Run the common module tests**

    mvn -pl vcampus-common test

Expected: all common tests pass, including protocol compatibility.

- [ ] **Step 6: Commit**

    git add vcampus-common/src/main/java/com/vcampus/common/model/ShopCategory.java vcampus-common/src/main/java/com/vcampus/common/model/ShopProductSort.java vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java vcampus-common/src/test
    git commit -m "feat(shop): define image and catalog protocol"

---

### Task 2: Add Category and Product Image Metadata to the Database Contract

**Files:**
- Modify: database/schema.sql
- Create: database/migrations/013_shop_images_javafx.sql
- Modify: vcampus-server/src/main/java/com/vcampus/server/model/ShopProductRecord.java
- Create: vcampus-server/src/main/java/com/vcampus/server/model/ShopProductImageRecord.java
- Modify: vcampus-server/src/main/java/com/vcampus/server/database/ShopStore.java
- Modify: vcampus-server/src/main/java/com/vcampus/server/database/ShopRepository.java
- Create: vcampus-server/src/test/java/com/vcampus/server/database/ShopImageMigrationTest.java
- Modify: vcampus-server/src/test/java/com/vcampus/server/database/ShopRepositoryTest.java

**Interfaces:**
- ShopProductRecord gains ShopCategory category before price.
- ProductInput becomes ProductInput(Long productId, String sku, String name, String description, ShopCategory category, BigDecimal price, boolean enabled).
- ProductQuery becomes ProductQuery(String keyword, ShopCategory category, Boolean enabled, ShopProductSort sort, int page, int pageSize).
- Produces ProductDetail product(long productId, boolean includeDisabled).
- Produces List<ShopProductImageRecord> productImages(long productId).
- ShopProductImageRecord fields: id, productId, storageKey, thumbnailStorageKey, mimeType, byteSize, sha256, sortOrder, cover, createdAt, updatedAt.

- [ ] **Step 1: Write migration contract tests**

    @Test
    void migrationAddsCategoryAndImageMetadataWithoutDeletingProducts() throws Exception {
        executeShopMigration008();
        insertProduct("SKU-OLD", "旧商品");
        executeMigration("013_shop_images_javafx.sql");
        assertEquals("OTHER", scalarString(
                "SELECT category FROM shop_products WHERE sku='SKU-OLD'"));
        assertTrue(tableExists("SHOP_PRODUCT_IMAGES"));
    }

    @Test
    void imageOrderIsUniquePerProduct() {
        assertThrows(SQLException.class, () -> insertTwoImagesAtOrder(1L, 0));
    }

- [ ] **Step 2: Run migration tests and verify failure**

    mvn -pl vcampus-server -am -Dtest=ShopImageMigrationTest test

Expected: fail because migration 013 and the image table are absent.

- [ ] **Step 3: Add the additive schema**

    ALTER TABLE shop_products
        ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT 'OTHER';

    CREATE TABLE IF NOT EXISTS shop_product_images (
        id BIGINT PRIMARY KEY AUTO_INCREMENT,
        product_id BIGINT NOT NULL,
        storage_key VARCHAR(160) NOT NULL UNIQUE,
        thumbnail_storage_key VARCHAR(160) NOT NULL UNIQUE,
        mime_type VARCHAR(32) NOT NULL,
        byte_size BIGINT NOT NULL,
        sha256 CHAR(64) NOT NULL,
        sort_order INT NOT NULL,
        is_cover BOOLEAN NOT NULL DEFAULT FALSE,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        UNIQUE KEY uk_shop_image_product_order (product_id, sort_order),
        INDEX idx_shop_image_product_cover (product_id, is_cover),
        CONSTRAINT fk_shop_image_product FOREIGN KEY (product_id)
            REFERENCES shop_products(id) ON DELETE CASCADE,
        CONSTRAINT chk_shop_image_size CHECK (byte_size > 0),
        CONSTRAINT chk_shop_image_order CHECK (sort_order >= 0)
    );

Mirror the same definitions in schema.sql. Make migration 013 idempotent using the repository's existing information_schema procedure pattern; do not reset a non-null category.

- [ ] **Step 4: Write repository tests for category filters and stable sorts**

Test NEWEST, PRICE_ASC, PRICE_DESC, NAME_ASC with equal-value ID tie breakers. Test category filtering before pagination and includeDisabled behavior.

    @Test
    void categoryFilterRunsBeforeStablePricePagination() throws Exception {
        long first = product("SKU-A", ShopCategory.CAMPUS_MERCH, "10.00");
        long second = product("SKU-B", ShopCategory.CAMPUS_MERCH, "10.00");
        product("SKU-C", ShopCategory.DAILY_SUPPLIES, "1.00");
        ProductPage page = repository.searchProducts(new ProductQuery("", ShopCategory.CAMPUS_MERCH,
                true, ShopProductSort.PRICE_ASC, 1, 20));
        assertEquals(List.of(first, second),
                page.rows().stream().map(ShopProductRecord::id).toList());
    }

- [ ] **Step 5: Implement the records and parameter-bound repository queries**

Whitelist SQL order fragments:

    NEWEST -> "p.created_at DESC,p.id DESC"
    PRICE_ASC -> "p.price ASC,p.id ASC"
    PRICE_DESC -> "p.price DESC,p.id DESC"
    NAME_ASC -> "p.name ASC,p.id ASC"

Never concatenate a client-provided sort string. Read category with ShopCategory.valueOf and map legacy nulls to OTHER only while migration compatibility is required.

- [ ] **Step 6: Run focused server tests**

    mvn -pl vcampus-server -am -Dtest=ShopImageMigrationTest,ShopRepositoryTest test

Expected: all focused tests pass.

- [ ] **Step 7: Commit**

    git add database/schema.sql database/migrations/013_shop_images_javafx.sql vcampus-server/src/main/java/com/vcampus/server/model vcampus-server/src/main/java/com/vcampus/server/database/ShopStore.java vcampus-server/src/main/java/com/vcampus/server/database/ShopRepository.java vcampus-server/src/test/java/com/vcampus/server/database
    git commit -m "feat(shop): add categories and image metadata"

---

### Task 3: Build the Secure Filesystem Image Store

**Files:**
- Create: vcampus-server/src/main/java/com/vcampus/server/config/ShopImageConfig.java
- Create: vcampus-server/src/main/java/com/vcampus/server/image/ShopImageStore.java
- Create: vcampus-server/src/main/java/com/vcampus/server/image/FileShopImageStore.java
- Create: vcampus-server/src/main/java/com/vcampus/server/image/ShopImageException.java
- Create: vcampus-server/src/test/java/com/vcampus/server/config/ShopImageConfigTest.java
- Create: vcampus-server/src/test/java/com/vcampus/server/image/FileShopImageStoreTest.java

**Interfaces:**
- ShopImageConfig(Path root, long maxImageBytes, int chunkBytes, long maxPixels, Duration uploadTtl).
- Defaults: root data/shop-images, 2 MiB, 192 KiB, 20,000,000 pixels, 30 minutes.
- ShopImageStore methods: startUpload(ownerId, productId, mimeType, expectedBytes), appendChunk(ownerId, uploadId, index, bytes), completeUpload(ownerId, uploadId), finalizeUpload(ownerId, uploadId), open(storageKey), deleteIfExists(storageKey), cleanup(referencedKeys, now).
- UploadedImage returns uploadId, productId, mimeType, normalizedBytes, sha256, width, height.
- FinalizedImage returns storageKey and thumbnailStorageKey.

- [ ] **Step 1: Write configuration and path-safety tests**

Cover environment/default parsing, rejecting roots whose temp/final paths escape root, rejecting storage keys containing slash, backslash, colon, dot segments, or NUL.

    @Test
    void storageKeyCannotEscapeConfiguredRoot() {
        assertThrows(ShopImageException.class, () -> store.open("../secret"));
        assertThrows(ShopImageException.class, () -> store.open("folder/image.png"));
        assertFalse(Files.exists(tempDir.resolve("secret")));
    }

- [ ] **Step 2: Write upload lifecycle tests with JUnit TempDir**

Cover exact chunk order, duplicate chunk rejection, wrong owner, expected-size mismatch, more than 2 MiB, expired upload, and finalize/delete behavior.

    @Test
    void chunksAreOrderedAndBoundToOwner() {
        UploadTicket ticket = store.startUpload(9L, 4L, "image/png", png.length);
        store.appendChunk(9L, ticket.uploadId(), 0, firstChunk);
        assertThrows(ShopImageException.class,
                () -> store.appendChunk(8L, ticket.uploadId(), 1, secondChunk));
        assertThrows(ShopImageException.class,
                () -> store.appendChunk(9L, ticket.uploadId(), 0, firstChunk));
    }

- [ ] **Step 3: Write image validation tests**

Generate a small PNG with BufferedImage and ImageIO. Assert accepted PNG produces a SHA-256 and thumbnail. Assert text renamed .png, truncated JPEG, 20,000,001-pixel declared image, and unsupported GIF fail with stable Chinese messages.

    @Test
    void completedPngIsDecodedHashedAndThumbnailed() {
        String uploadId = upload(validPng(1200, 800));
        UploadedImage image = store.completeUpload(9L, uploadId);
        assertEquals(64, image.sha256().length());
        FinalizedImage files = store.finalizeUpload(9L, uploadId);
        assertTrue(store.open(files.thumbnailStorageKey()).readAllBytes().length > 0);
    }

- [ ] **Step 4: Run focused tests and verify failure**

    mvn -pl vcampus-server -am -Dtest=ShopImageConfigTest,FileShopImageStoreTest test

Expected: compilation fails because image-store classes do not exist.

- [ ] **Step 5: Implement FileShopImageStore**

Use root/temp and root/files. Generate UUID upload IDs and random immutable storage keys. Resolve every path with root.resolve(key).normalize and verify startsWith(root). Accumulate chunks in a temp file while tracking next index and decoded byte count. On completion, inspect magic bytes, decode once with ImageIO, reject null images and pixel overflow, then re-encode PNG with alpha or JPEG without alpha and generate a 480×480 aspect-preserving thumbnail.

- [ ] **Step 6: Implement atomic writes and cleanup**

Write normalized and thumbnail bytes to sibling temporary files, then Files.move with ATOMIC_MOVE and REPLACE_EXISTING where supported. cleanup deletes expired temp sessions and only finalized files older than 24 hours that are absent from referencedKeys.

- [ ] **Step 7: Run tests and commit**

    mvn -pl vcampus-server -am -Dtest=ShopImageConfigTest,FileShopImageStoreTest test
    git add vcampus-server/src/main/java/com/vcampus/server/config/ShopImageConfig.java vcampus-server/src/main/java/com/vcampus/server/image vcampus-server/src/test/java/com/vcampus/server/config/ShopImageConfigTest.java vcampus-server/src/test/java/com/vcampus/server/image
    git commit -m "feat(shop): add secure image file storage"

---

### Task 4: Commit Product Image Plans Atomically

**Files:**
- Modify: vcampus-server/src/main/java/com/vcampus/server/database/ShopStore.java
- Modify: vcampus-server/src/main/java/com/vcampus/server/database/ShopRepository.java
- Create: vcampus-server/src/main/java/com/vcampus/server/service/ShopImageService.java
- Modify: vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java
- Modify: vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java
- Create: vcampus-server/src/test/java/com/vcampus/server/database/ShopImageCommitTest.java
- Create: vcampus-server/src/test/java/com/vcampus/server/service/ShopImageServiceTest.java
- Modify: vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterShopTest.java

**Interfaces:**
- ImagePlan(List<ImagePlanItem> items) with at most five ordered items.
- ImagePlanItem(Long existingImageId, String uploadId, boolean cover); exactly one source is non-null.
- replaceProductImages(operatorId, productId, finalizedUploads, plan) returns ImageCommitResult(keptKeys, deletedKeys, images).
- ShopImageService methods uploadStart, uploadChunk, uploadComplete, commit.

- [ ] **Step 1: Write repository transaction tests**

Test reordered existing images, replacing one image, one-cover enforcement, duplicate existing IDs, image from another product, six images, ordinary-user operator ID, and SQL failure rollback. Verify original rows remain after every rejected plan.

    @Test
    void invalidReplacementLeavesOriginalRows() throws Exception {
        List<ShopProductImageRecord> before = repository.productImages(productId);
        ImagePlan invalid = plan(existing(before.get(0).id(), true),
                existing(imageOwnedByOtherProduct, false));
        assertThrows(ShopRuleException.class,
                () -> repository.replaceProductImages(adminId, productId, Map.of(), invalid));
        assertEquals(before, repository.productImages(productId));
    }

- [ ] **Step 2: Write service permission and upload-binding tests**

Use SessionManager sessions for STUDENT, SHOP_ADMIN, and SUPER_ADMIN. Verify only managers start/continue/complete/commit, upload IDs stay bound to owner and product, and contentBase64 rejects malformed input before touching storage.

    @Test
    void studentCannotStartImageUpload() {
        ResponseMessage response = service.uploadStart(request(studentToken,
                Map.of("productId", "4", "mimeType", "image/png", "expectedBytes", "120")));
        assertFalse(response.success());
        assertEquals(0, imageStore.startCalls());
    }

- [ ] **Step 3: Run tests and confirm failure**

    mvn -pl vcampus-server -am -Dtest=ShopImageCommitTest,ShopImageServiceTest,RequestRouterShopTest test

- [ ] **Step 4: Implement transaction-safe metadata replacement**

Lock the product and current image rows FOR UPDATE. Validate the complete plan before deleting rows. Finalized files exist before SQL commit; on SQL rollback delete only newly finalized keys. After commit, delete removed old keys. If process death leaves a finalized orphan, the Task 3 retention cleanup removes it later.

- [ ] **Step 5: Implement protocol encoding**

uploadStart parameters: token, productId, mimeType, expectedBytes.
uploadChunk parameters: token, uploadId, chunkIndex, contentBase64.
uploadComplete parameters: token, uploadId.
commit parameters: token, productId, itemCount, and item.N encoded as RowCodec(existingImageId, uploadId, cover).

Response errors must distinguish permission, expired upload, invalid image, product missing, and invalid image plan without exposing paths.

- [ ] **Step 6: Wire RequestRouter and VCampusServer**

Construct one ShopImageConfig and FileShopImageStore, inject the same store into ShopImageService, and route only the four admin image actions to it. Keep existing ShopService routes unchanged.

- [ ] **Step 7: Run tests and commit**

    mvn -pl vcampus-server -am -Dtest=ShopImageCommitTest,ShopImageServiceTest,RequestRouterShopTest test
    git add vcampus-server/src/main/java/com/vcampus/server/database vcampus-server/src/main/java/com/vcampus/server/service/ShopImageService.java vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java vcampus-server/src/test/java/com/vcampus/server
    git commit -m "feat(shop): commit product image plans"

---

### Task 5: Expose Product Detail and Chunked Image Reads

**Files:**
- Modify: vcampus-server/src/main/java/com/vcampus/server/database/ShopStore.java
- Modify: vcampus-server/src/main/java/com/vcampus/server/database/ShopRepository.java
- Modify: vcampus-server/src/main/java/com/vcampus/server/service/ShopService.java
- Modify: vcampus-server/src/main/java/com/vcampus/server/service/ShopImageService.java
- Modify: vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java
- Modify: vcampus-server/src/test/java/com/vcampus/server/service/ShopServiceTest.java
- Modify: vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterShopTest.java
- Create: vcampus-server/src/test/java/com/vcampus/server/service/ShopImageReadTest.java

**Interfaces:**
- product.get returns product row plus imageCount and image.N metadata rows.
- product.search preserves the legacy nine-field row and adds row.N.category, row.N.coverImageId, row.N.coverHash keys.
- image.getChunk parameters: token, imageId, variant THUMBNAIL or DETAIL, chunkIndex.
- image chunk response: imageId, mimeType, sha256, totalBytes, totalChunks, chunkIndex, contentBase64.

- [ ] **Step 1: Add compatibility-focused response tests**

Verify old nine-field product row bytes remain unchanged. Verify additive category and cover keys, enabled-product detail for buyers, disabled-product detail for managers, and disabled-product rejection for ordinary users.

    @Test
    void searchPreservesLegacyRowAndAddsImageMetadata() {
        ResponseMessage response = service.searchProducts(searchRequest(studentToken));
        assertEquals(9, RowCodec.decode(response.data().get("row.0")).size());
        assertEquals("CAMPUS_MERCH", response.data().get("row.0.category"));
        assertEquals("41", response.data().get("row.0.coverImageId"));
    }

- [ ] **Step 2: Add image-read tests**

Verify thumbnail and detail variants, first/middle/last chunks, out-of-range indices, missing files, disabled-product authorization, and a reconstructed byte stream whose SHA-256 matches response metadata.

    @Test
    void chunksReassembleToAdvertisedHash() {
        byte[] bytes = IntStream.range(0, totalChunks)
                .mapToObj(this::readChunk)
                .map(Base64.getDecoder()::decode)
                .reduce(new byte[0], TestBytes::concat);
        assertEquals(expectedSha256, HexFormat.of().formatHex(sha256.digest(bytes)));
    }

- [ ] **Step 3: Run focused tests and verify failure**

    mvn -pl vcampus-server -am -Dtest=ShopServiceTest,RequestRouterShopTest,ShopImageReadTest test

- [ ] **Step 4: Implement product response encoding**

Keep RowCodec product rows at nine fields. Add category and cover metadata as separate keys. Encode detail images in sort_order order with id, mimeType, byteSize, sha256, sortOrder, cover.

- [ ] **Step 5: Implement bounded image reads**

Resolve image metadata through ShopRepository, enforce product visibility, read only the requested variant, and return a Base64 slice whose decoded size is at most 192 KiB. Never include storageKey or thumbnailStorageKey in a response.

- [ ] **Step 6: Run tests and commit**

    mvn -pl vcampus-server -am -Dtest=ShopServiceTest,RequestRouterShopTest,ShopImageReadTest test
    git add vcampus-server/src/main/java/com/vcampus/server vcampus-server/src/test/java/com/vcampus/server
    git commit -m "feat(shop): serve product detail and image chunks"

---

### Task 6: Refactor Checkout for Buy-Now and Selected Cart Items

**Files:**
- Modify: vcampus-server/src/main/java/com/vcampus/server/database/ShopStore.java
- Modify: vcampus-server/src/main/java/com/vcampus/server/database/ShopRepository.java
- Modify: vcampus-server/src/main/java/com/vcampus/server/service/ShopService.java
- Modify: vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java
- Modify: vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java
- Modify: vcampus-server/src/test/java/com/vcampus/server/database/ShopCheckoutTransactionTest.java
- Modify: vcampus-server/src/test/java/com/vcampus/server/service/ShopServiceTest.java
- Modify: vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterShopTest.java

**Interfaces:**
- Existing checkout(buyerUserId, operationId) remains and delegates to checkoutCart with all cart IDs.
- Produces checkoutCart(long buyerUserId, String operationId, Set<Long> selectedProductIds).
- Produces buyNow(long buyerUserId, String operationId, long productId, int quantity).
- VCampusClient.checkoutShop(token, operationId, selectedProductIds) sends selectedCount and selected.N.
- VCampusClient.buyNowShop(token, operationId, productId, quantity).

- [ ] **Step 1: Write buy-now transaction tests**

Verify success does not add, change, or remove any cart row; retry returns duplicate original order; insufficient balance, frozen account, disabled product, and insufficient stock leave bank, inventory, order, notification, and cart unchanged.

    @Test
    void buyNowPaysWithoutChangingExistingCart() throws Exception {
        CartResult before = repository.cart(buyerId);
        CheckoutResult paid = repository.buyNow(buyerId, operationId, directProductId, 2);
        assertEquals(new BigDecimal("40.00"), paid.totalAmount());
        assertEquals(before, repository.cart(buyerId));
        assertTrue(repository.buyNow(buyerId, operationId, directProductId, 2).duplicate());
    }

- [ ] **Step 2: Write selected-cart tests**

Put three products in a cart, select two, and verify only two order items and only two cart deletions. Reject an empty selection, duplicate IDs, IDs not in the buyer's cart, and more than 100 selected IDs.

    @Test
    void selectedCheckoutDeletesOnlyPurchasedRows() throws Exception {
        repository.checkoutCart(buyerId, operationId, Set.of(firstId, thirdId));
        assertEquals(List.of(secondId),
                repository.cart(buyerId).rows().stream()
                        .map(ShopCartItemRecord::productId).toList());
        assertEquals(Set.of(firstId, thirdId),
                repository.order(buyerId, orderId, false).items().stream()
                        .map(ShopOrderItemRecord::productId).collect(Collectors.toSet()));
    }

- [ ] **Step 3: Run focused tests and verify failures**

    mvn -pl vcampus-server -am -Dtest=ShopCheckoutTransactionTest,ShopServiceTest,RequestRouterShopTest test

- [ ] **Step 4: Extract one private transactional checkout engine**

Create a private CheckoutMode with DIRECT and CART. Both modes build locked server-side item snapshots, sort by product ID before locking, calculate totals from database prices, and call the existing bank/ledger/order/inventory/notification writers. Only CART deletes selected cart rows.

- [ ] **Step 5: Add strict service parsing**

checkout accepts no selectedCount for legacy all-cart behavior. When present, selectedCount must be 1..100 and selected.N must be unique positive longs. buyNow requires productId and quantity 1..999. Neither action accepts buyer ID, price, total, or account ID.

- [ ] **Step 6: Run the full existing shop transaction suite**

    mvn -pl vcampus-server -am -Dtest=ShopCheckoutTransactionTest,ShopOrderLifecycleTest,ShopServiceTest,RequestRouterShopTest test

Expected: legacy checkout, cancellation refund, shipment, and confirmation still pass.

- [ ] **Step 7: Commit**

    git add vcampus-server/src/main/java/com/vcampus/server/database vcampus-server/src/main/java/com/vcampus/server/service/ShopService.java vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java vcampus-server/src/test
    git commit -m "feat(shop): add buy now and partial checkout"

---

### Task 7: Build the Typed JavaFX Shop Gateway and Data Decoders

**Files:**
- Create: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopGateway.java
- Create: vcampus-client/src/main/java/com/vcampus/client/fx/shop/SocketShopGateway.java
- Create: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopData.java
- Modify: vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java
- Create: vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopDataTest.java
- Create: vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopGatewayTest.java

**Interfaces:**
- Typed records: ImageRef, Product, ProductPage, ProductDetail, CartItem, Cart, CheckoutReceipt, Order, OrderPage, OrderDetail, ImageChunk, UploadTicket.
- ShopGateway synchronous methods: search, product, imageChunk, cart, setCartQuantity, removeCartItem, checkout, buyNow, orders, order, cancelOrder, confirmOrder, adminSearchProducts, saveProduct, setProductEnabled, adjustInventory, adminOrders, shipOrder, bankBalance, uploadStart, uploadChunk, uploadComplete, commitImages.
- Every method obtains buyer/operator identity only from the constructor token.

- [ ] **Step 1: Write strict decoder tests**

Cover additive category/cover keys, old-server rows without them mapping to OTHER and no image, malformed money, malformed image hashes, duplicate image orders, more than five detail images, cart/order snapshots, and failure ResponseMessage propagation.

    @Test
    void legacyProductRowUsesOtherAndNoCover() {
        ProductPage page = ShopData.productPage(legacyNineFieldResponse());
        assertEquals(ShopCategory.OTHER, page.rows().get(0).category());
        assertNull(page.rows().get(0).cover());
    }

- [ ] **Step 2: Write gateway parameter tests with a recording VCampusClient**

Assert search sends category and sort enum names; buyNow sends productId/quantity/operationId; checkout sends selected IDs; upload sends exact chunkIndex/Base64; commit encodes the ordered image plan.

    @Test
    void buyNowUsesServerOwnedIdentityAndPrice() {
        gateway.buyNow(productId, 2, operationId);
        RequestMessage sent = client.lastRequest();
        assertEquals(Actions.SHOP_BUY_NOW, sent.action());
        assertEquals(Map.of("token", token, "productId", Long.toString(productId),
                "quantity", "2", "operationId", operationId), sent.parameters());
    }

- [ ] **Step 3: Run tests and verify failure**

    mvn -pl vcampus-client -am -Dtest=ShopDataTest,ShopGatewayTest test

- [ ] **Step 4: Implement immutable typed data**

Use List.copyOf and Set.copyOf in compact constructors. Product price and totals must be non-negative scale-two BigDecimal. Image SHA-256 must match [0-9a-f]{64}; image count must be 0..5.

- [ ] **Step 5: Implement SocketShopGateway**

Call VCampusClient synchronously and immediately decode ResponseMessage. Do not create threads here; ShopController owns async execution. bankBalance uses the existing BANK_ACCOUNT_GET response balance key.

- [ ] **Step 6: Run client tests and commit**

    mvn -pl vcampus-client -am -Dtest=ShopDataTest,ShopGatewayTest test
    git add vcampus-client/src/main/java/com/vcampus/client/fx/shop vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java vcampus-client/src/test/java/com/vcampus/client/fx/shop
    git commit -m "feat(shop): add typed JavaFX gateway"

---

### Task 8: Implement Chunk Reassembly and the Client Image Cache

**Files:**
- Create: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopImageCache.java
- Create: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopImageCacheConfig.java
- Create: vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopImageCacheTest.java

**Interfaces:**
- ShopImageCache(ShopGateway gateway, Executor executor, ShopImageCacheConfig config).
- CompletableFuture<byte[]> load(ImageRef ref, ImageVariant variant).
- clearMemory(), pruneDisk(), close().
- Cache key: imageId-sha256-THUMBNAIL or imageId-sha256-DETAIL.
- Default disk root: system property user.home/.vcampus/cache/shop; override with VCAMPUS_SHOP_CACHE_DIR.

- [ ] **Step 1: Write cache-hit and reassembly tests**

Use a fake gateway returning three chunks. Verify ordered reassembly, one network fetch for concurrent identical loads, memory hit, disk hit after a new cache instance, and separate thumbnail/detail keys.

    @Test
    void concurrentLoadsShareOneChunkSequence() {
        CompletableFuture<byte[]> first = cache.load(ref, ImageVariant.THUMBNAIL);
        CompletableFuture<byte[]> second = cache.load(ref, ImageVariant.THUMBNAIL);
        assertArrayEquals(expected, first.join());
        assertArrayEquals(expected, second.join());
        assertEquals(totalChunks, gateway.imageChunkCalls());
    }

- [ ] **Step 2: Write corruption and lifecycle tests**

Cover wrong chunk index, changed totalChunks, invalid Base64 surfaced by gateway, SHA mismatch, truncated disk cache, close during load, atomic temporary-file cleanup, and pruning files older than 30 days.

    @Test
    void badHashDeletesPartAndAllowsRetry() {
        gateway.setAdvertisedHash("0".repeat(64));
        assertThrows(CompletionException.class,
                () -> cache.load(ref, ImageVariant.DETAIL).join());
        assertFalse(Files.exists(cachePartPath(ref, ImageVariant.DETAIL)));
        gateway.setAdvertisedHash(realHash);
        assertArrayEquals(expected, cache.load(ref, ImageVariant.DETAIL).join());
    }

- [ ] **Step 3: Run tests and verify failure**

    mvn -pl vcampus-client -am -Dtest=ShopImageCacheTest test

- [ ] **Step 4: Implement cache loading**

Use ConcurrentHashMap<CacheKey, CompletableFuture<byte[]>> for in-flight deduplication. Read disk on executor, otherwise request chunks sequentially, verify metadata consistency and SHA-256, then write sibling .part and atomically move to .img.

- [ ] **Step 5: Enforce lifecycle rules**

close prevents new loads, cancels owned futures where possible, clears memory, and leaves completed valid disk entries. Failed loads remove their in-flight map entry so retry performs a fresh request.

- [ ] **Step 6: Run tests and commit**

    mvn -pl vcampus-client -am -Dtest=ShopImageCacheTest test
    git add vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopImageCache.java vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopImageCacheConfig.java vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopImageCacheTest.java
    git commit -m "feat(shop): cache chunked product images"

---

### Task 9: Build the JavaFX Catalog and Product Detail

**Files:**
- Create: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopUi.java
- Create: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopView.java
- Create: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopController.java
- Create: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopCatalogView.java
- Create: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopProductCard.java
- Create: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopDetailView.java
- Create: vcampus-client/src/main/resources/com/vcampus/client/fx/shop/shop.css
- Create: vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopControllerTest.java
- Create: vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopCatalogViewTest.java
- Create: vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopDetailViewTest.java

**Interfaces:**
- ShopController(ShopGateway gateway, Set<UserRole> roles, Executor executor, ShopImageCache cache, Runnable back, Runnable unreadRefresh).
- Public methods view(), open(String route), openProduct(long productId), deactivate(), close().
- Initial routes: catalog and product/{id}.
- ShopView.Listener owns search, product, addToCart, buyNow, and back callbacks.

- [ ] **Step 1: Write controller generation-guard tests**

Use a controllable executor. Start search A, then search B; complete A last and verify the view still shows B. Verify deactivate and close suppress callbacks and image completions.

    @Test
    void staleSearchCannotReplaceNewerResults() {
        controller.search("A", null, ShopProductSort.NEWEST, 1);
        controller.search("B", null, ShopProductSort.NEWEST, 1);
        gateway.completeSearch("B");
        gateway.completeSearch("A");
        assertEquals("B", view.lastPage().rows().get(0).name());
    }

- [ ] **Step 2: Write catalog interaction tests**

Cover category and sort changes resetting page to one, 4/3/2 responsive column calculation at representative widths, whole-card detail action, pagination, empty/loading/error/retry states, and missing/broken thumbnail placeholders.

    @Test
    void catalogColumnsRespondWithoutHorizontalScroll() {
        assertEquals(4, ShopCatalogView.columnsFor(1320));
        assertEquals(3, ShopCatalogView.columnsFor(1040));
        assertEquals(2, ShopCatalogView.columnsFor(760));
    }

- [ ] **Step 3: Write detail interaction tests**

Cover 0..5 images, thumbnail selection, long text wrapping, quantity bounds 1..min(stock,999), disabled/out-of-stock buttons, add-to-cart staying on detail, and buy-now route carrying product ID and quantity without touching cart.

    @Test
    void buyNowUsesCurrentQuantityAndDoesNotMutateCart() {
        detail.show(productWithStock(4));
        detail.setQuantity(3);
        clickOn("#shop-buy-now");
        assertEquals(new BuyNowSelection(productId, 3), listener.buyNowSelection());
        assertEquals(0, listener.cartMutationCalls());
    }

- [ ] **Step 4: Run focused tests and verify failure**

    mvn -pl vcampus-client -am -Dtest=ShopControllerTest,ShopCatalogViewTest,ShopDetailViewTest test

- [ ] **Step 5: Implement the approved B layout**

Use one ScrollPane per page, TilePane cards with computed pref tile width, blue/white scoped style classes, image ratio-preserving ImageView, and accessible text containing product name, price, and stock state. Do not use TableView on buyer pages.

- [ ] **Step 6: Implement async controller paths**

All public controller methods assert Platform.isFxApplicationThread in tests. Submit gateway/cache work to executor and return via Platform.runLater only when session and request generations still match.

- [ ] **Step 7: Run tests and commit**

    mvn -pl vcampus-client -am -Dtest=ShopControllerTest,ShopCatalogViewTest,ShopDetailViewTest test
    git add vcampus-client/src/main/java/com/vcampus/client/fx/shop vcampus-client/src/main/resources/com/vcampus/client/fx/shop vcampus-client/src/test/java/com/vcampus/client/fx/shop
    git commit -m "feat(shop): build JavaFX catalog and detail"

---

### Task 10: Build Cart, Checkout Confirmation, and Buyer Orders

**Files:**
- Create: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopCartView.java
- Create: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopCheckoutView.java
- Create: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopOrdersView.java
- Create: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopOrderDetailView.java
- Modify: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopController.java
- Modify: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopView.java
- Create: vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopCartViewTest.java
- Create: vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopCheckoutViewTest.java
- Create: vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopOrdersViewTest.java

**Interfaces:**
- New routes: cart, checkout/direct/{productId}/{quantity}, checkout/cart, orders, order/{id}.
- CheckoutDraft contains source DIRECT or CART, immutable selected items, estimatedTotal, balance, and operationId.
- Controller creates the UUID operationId once per confirmation view and reuses it for retry until success or draft changes.

- [ ] **Step 1: Write cart selection tests**

Verify enabled/in-stock rows begin selectable, invalid rows cannot be selected, select-all includes only eligible rows, quantity updates refresh server cart, removal updates count, total uses selected rows, and selected IDs reach checkout draft.

    @Test
    void selectAllSkipsIneligibleCartItems() {
        cartView.show(cart(enabledItem, disabledItem, outOfStockItem));
        cartView.selectAll();
        assertEquals(Set.of(enabledItem.productId()), cartView.selectedProductIds());
        assertEquals(enabledItem.subtotal(), cartView.selectedTotal());
    }

- [ ] **Step 2: Write confirmation and idempotency UI tests**

Verify no debit request before confirm click, direct draft does not call cart mutation, double click disables confirm, failure re-enables it and retains the same operationId, price/stock-change error reloads product/cart and requires a new click, and success opens order detail.

    @Test
    void retryReusesOperationIdButChangedDraftGetsNewId() {
        controller.openCheckout(draft);
        String first = view.operationId();
        gateway.failCheckout("网络连接中断");
        controller.confirmCheckout();
        assertEquals(first, gateway.lastOperationId());
        controller.changeCheckoutQuantity(2);
        assertNotEquals(first, view.operationId());
    }

- [ ] **Step 3: Write order lifecycle UI tests**

Cover cancel only for PAID, confirm only for SHIPPED, snapshot item text, current-cover fallback, paging, message errors, and action refresh.

    @Test
    void orderActionsFollowServerStatusVocabulary() {
        orderView.show(order(ShopOrderStatus.PAID));
        assertFalse(lookup("#shop-order-cancel").isDisabled());
        assertTrue(lookup("#shop-order-confirm").isDisabled());
    }

- [ ] **Step 4: Run focused tests and verify failure**

    mvn -pl vcampus-client -am -Dtest=ShopCartViewTest,ShopCheckoutViewTest,ShopOrdersViewTest test

- [ ] **Step 5: Implement cart and checkout views**

Use card rows with checkbox, 64px thumbnail, quantity control, status, price, and remove. Use a sticky-like right summary within a two-column BorderPane at wide widths and stack it below at narrow widths. Confirmation displays balance from bankBalance but labels it as pre-payment information.

- [ ] **Step 6: Implement order views and controller routes**

Reuse existing ShopOrderStatus vocabulary. Do not infer success from a timeout; retry with the same operation ID. On success call unreadRefresh and reload cart badge.

- [ ] **Step 7: Run tests and commit**

    mvn -pl vcampus-client -am -Dtest=ShopCartViewTest,ShopCheckoutViewTest,ShopOrdersViewTest test
    git add vcampus-client/src/main/java/com/vcampus/client/fx/shop vcampus-client/src/test/java/com/vcampus/client/fx/shop
    git commit -m "feat(shop): add cart checkout and orders"

---

### Task 11: Build JavaFX Product and Order Administration

**Files:**
- Create: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopAdminView.java
- Create: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopProductEditorView.java
- Create: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopImageDraft.java
- Create: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopImageUploader.java
- Modify: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopController.java
- Modify: vcampus-client/src/main/java/com/vcampus/client/fx/shop/ShopView.java
- Create: vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopAdminViewTest.java
- Create: vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopProductEditorViewTest.java
- Create: vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopImageUploaderTest.java

**Interfaces:**
- Admin routes: admin/products, admin/product/new, admin/product/{id}, admin/orders.
- ShopImageDraft maintains ordered DraftImage entries EXISTING or UPLOAD, exactly one cover when non-empty, and at most five entries.
- ShopImageUploader.upload(Path file, productId, ProgressListener) returns CompletableFuture<String> uploadId.

- [ ] **Step 1: Write role and admin-table tests**

Verify STUDENT and TEACHER cannot see or open admin routes; SHOP_ADMIN and SUPER_ADMIN can. Test product query, enabled state, edit navigation, inventory adjustment reason, admin order query, and shipment status rules.

    @Test
    void onlyShopManagersCanOpenAdministration() {
        assertFalse(ShopAccessPolicy.canManage(Set.of(UserRole.STUDENT)));
        assertTrue(ShopAccessPolicy.canManage(Set.of(UserRole.SHOP_ADMIN)));
        assertThrows(IllegalStateException.class, () -> studentController.open("admin/products"));
    }

- [ ] **Step 2: Write editor draft tests**

Cover fixed categories, server-owned SKU, valid price, 1000-character description, five-image cap, reorder, set cover, delete current cover selecting the first remaining image, cancel discarding the draft, and save encoding one ordered plan.

    @Test
    void deletingCoverPromotesFirstRemainingImage() {
        ShopImageDraft draft = draft(existing(11, true), existing(12, false));
        draft.remove(11);
        assertEquals(12, draft.images().get(0).existingImageId());
        assertTrue(draft.images().get(0).cover());
    }

- [ ] **Step 3: Write uploader tests**

Use temporary JPG/PNG files. Verify client preflight extension, magic, 2 MiB size, 192 KiB chunk count, progress, cancellation, one failed image not deleting other draft entries, and FileChooser callbacks staying on the FX thread while file reads run on the executor.

    @Test
    void uploaderUsesBoundedSequentialChunks() {
        String uploadId = uploader.upload(pngPath, productId, progress).join();
        assertNotNull(uploadId);
        assertTrue(gateway.uploadedChunks().stream()
                .allMatch(chunk -> chunk.decodedBytes() <= 192 * 1024));
        assertEquals(IntStream.range(0, gateway.uploadedChunks().size()).boxed().toList(),
                gateway.uploadedChunks().stream().map(UploadedChunk::index).toList());
    }

- [ ] **Step 4: Run focused tests and verify failure**

    mvn -pl vcampus-client -am -Dtest=ShopAdminViewTest,ShopProductEditorViewTest,ShopImageUploaderTest test

- [ ] **Step 5: Implement admin views**

Use TableView only for product/order management. Use the approved editor form and visual five-slot gallery. Save a new product first to obtain productId, upload selected files, then commit the image plan. For existing products, do not mutate server metadata until image.commit.

- [ ] **Step 6: Implement failure-safe async behavior**

Disable only the active save/upload controls. Keep successful draft uploads after one sibling failure. On cancel, forget upload IDs and let server TTL cleanup handle temporary files. On successful save, refresh catalog and admin table.

- [ ] **Step 7: Run tests and commit**

    mvn -pl vcampus-client -am -Dtest=ShopAdminViewTest,ShopProductEditorViewTest,ShopImageUploaderTest test
    git add vcampus-client/src/main/java/com/vcampus/client/fx/shop vcampus-client/src/test/java/com/vcampus/client/fx/shop
    git commit -m "feat(shop): add JavaFX shop administration"

---

### Task 12: Integrate the Native Shop, Deep Links, Seed Data, and Visual QA

**Files:**
- Modify: vcampus-client/src/main/java/com/vcampus/client/fx/CampusApplication.java
- Modify: vcampus-client/src/main/java/com/vcampus/client/fx/FxCampusView.java
- Modify: vcampus-client/src/main/java/com/vcampus/client/ui/NotificationDestination.java
- Modify: database/seed.sql
- Modify: docs/requirements.md
- Modify: docs/shop.md
- Create: docs/design/javafx-shop/design-qa.md
- Create screenshots under docs/design/javafx-shop/screenshots
- Create: vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopIntegrationTest.java
- Create: vcampus-client/src/test/java/com/vcampus/client/fx/shop/ShopVisualTest.java
- Modify: vcampus-client/src/test/java/com/vcampus/client/fx/CampusFlowTest.java

**Interfaces:**
- CampusApplication owns one session-scoped ShopController and dedicated executor; close it on logout/application close.
- Routes shop, shop-cart, shop-orders, and shop-order/{id} open the native JavaFX shop.
- Legacy --swing continues opening ShopModulePanel.

- [ ] **Step 1: Write integration and lifecycle tests**

Verify workspace shop opens native JavaFX, dashboard order task opens orders, SHOP_ORDERS notification with entity ID opens order detail, back returns to workspace, logout closes controller/cache/executor, old-session callbacks cannot alter a new session, and --swing still reaches the legacy panel.

    @Test
    void notificationDeepLinkOpensNativeOrderAndLogoutClosesIt() {
        application.openNotification(NotificationTarget.SHOP_ORDERS, orderId);
        assertEquals("order/" + orderId, shopController.currentRoute());
        application.logout();
        assertTrue(shopController.isClosed());
    }

- [ ] **Step 2: Run integration tests and verify failure**

    mvn -pl vcampus-client -am -Dtest=ShopIntegrationTest,CampusFlowTest test

- [ ] **Step 3: Wire CampusApplication without growing business logic**

Follow the LibraryController integration pattern. Add showShop(), deactivate it before changing modules, and close all shop resources in resetSessionState. Route notification entities through openOrder rather than reconstructing a Swing dialog.

- [ ] **Step 4: Update seed and documentation**

Assign existing fictional products fixed categories using idempotent updates that do not overwrite non-OTHER administrator values. Document VCAMPUS_SHOP_IMAGE_DIR, JPG/PNG limits, migration 013, client cache override, and manual acceptance. Mark requirements complete only after verification.

- [ ] **Step 5: Render actual JavaFX screenshots**

Render catalog, detail, cart, checkout, and editor at 1440×900, 1280×800, and approximately 1000px width using deterministic fixtures. Also render empty, long-name, no-image, broken-image, disabled, and insufficient-stock states. Record every inspected filename and any fixed issue in design-qa.md.

- [ ] **Step 6: Run focused client and complete module tests**

    mvn -pl vcampus-client -am test

Expected: all client/common dependencies pass; generated screenshots contain no clipping, horizontal scrolling, double vertical scrolling, or unreadable controls.

- [ ] **Step 7: Run root verification**

    mvn clean verify

Expected: reactor SUCCESS for vcampus-common, vcampus-server, and vcampus-client with zero test failures.

- [ ] **Step 8: Perform static architecture checks**

    rg -n "java\.sql|jdbc:mysql|DriverManager" vcampus-client/src/main/java
    rg -n "ObjectInputStream|ObjectOutputStream|Serializable" vcampus-common/src/main/java vcampus-client/src/main/java vcampus-server/src/main/java
    rg -n "VCAMPUS_SHOP_IMAGE_DIR|shop_product_images|shop\.buyNow" database docs vcampus-*

Expected: no client JDBC or native serialization; image config/schema/action references appear only in intended modules.

- [ ] **Step 9: Commit delivery artifacts**

    git add database/seed.sql docs/requirements.md docs/shop.md docs/design/javafx-shop vcampus-client/src/main/java/com/vcampus/client/fx/CampusApplication.java vcampus-client/src/main/java/com/vcampus/client/fx/FxCampusView.java vcampus-client/src/main/java/com/vcampus/client/ui/NotificationDestination.java vcampus-client/src/test
    git commit -m "feat(shop): integrate JavaFX storefront"

## Manual Acceptance After Automated Verification

1. Start MySQL and the application server with a writable VCAMPUS_SHOP_IMAGE_DIR.
2. Log in as a shop administrator, create one product, upload five JPG/PNG images, reorder them, change the cover, save, reconnect, and verify persistence.
3. Attempt a renamed text file, a file larger than 2 MiB, and an interrupted upload; verify old product images remain and temporary data expires.
4. Log in from two buyer clients. Verify thumbnails load and cache, detail images lazy-load, and one broken/missing server file shows a placeholder without breaking product text.
5. Buy one product with Buy Now while another product is in the cart; verify the cart is unchanged.
6. Select only two of three cart items and pay; verify only selected items leave the cart.
7. Retry the same operation ID after simulating a lost response; verify one order, one debit, one inventory deduction, and one payment notification.
8. Re-run cancellation/refund, shipment, notification deep link, and receipt confirmation.
9. Restart the server and verify referenced images remain while expired temporary uploads are cleaned.
10. Record any test requiring the user's live database or filesystem as not run until the user performs it; do not infer success from H2 or Maven.
