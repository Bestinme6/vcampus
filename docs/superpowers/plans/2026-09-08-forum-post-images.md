# Forum Post Images Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users upload, order, publish, and view up to three validated images on a forum post.

**Architecture:** A forum-specific file store accepts authenticated chunked uploads into staged storage, validates and normalizes images, and exposes thumbnails/full bytes only after a database post binding. The forum client uploads on its request executor, creates the post with staged keys, and lazily caches gallery images.

**Tech Stack:** Java 21, JavaFX 21, Socket `MessageCodec`, JDBC, MySQL/H2, ImageIO with a pure-Java WebP reader, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-08-forum-images-reports-design.md`

## Global Constraints

- Zero to three JPG, PNG, or WebP inputs per post; each input is at most 5 MiB.
- Validate magic bytes, full decode, dimensions, pixel count, ownership, byte total, and chunk order on the server.
- Normalize stored/output images to JavaFX-readable JPG or PNG and generate thumbnails.
- Keep paths server-generated under `data/forum-images`; never expose absolute paths.
- Preserve editor state and selected local files after upload or publish failure.

---

### Task 1: Forum image configuration and file store

**Files:**
- Modify: `pom.xml`
- Modify: `vcampus-server/pom.xml`
- Create: `vcampus-server/src/main/java/com/vcampus/server/config/ForumImageConfig.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/image/ForumImageException.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/image/ForumImageStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/image/FileForumImageStore.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/config/ForumImageConfigTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/image/FileForumImageStoreTest.java`

**Interfaces:**
- Produces: start/append/complete operations keyed by owner user ID and upload ID.
- Produces: `StagedImage(key, mimeType, byteSize, width, height)` plus thumbnail/full ranged reads.
- Produces: bind/release and expiry cleanup methods.

- [ ] **Step 1: Verify and pin the WebP reader dependency**

Check the official artifact documentation for a Java 21-compatible, pure-Java ImageIO WebP reader. Add its version to parent dependency management and its runtime dependency to `vcampus-server`; verify with a real WebP fixture before keeping it.

- [ ] **Step 2: Write failing config and store tests**

Use temporary directories and literal fixtures to assert environment defaults, 5 MiB limit, valid JPG/PNG/WebP normalization, alpha-to-PNG behavior, JPEG output otherwise, MIME mismatch, corrupt input, excessive dimensions/pixels, chunk order, owner mismatch, size mismatch, ranged reads, path containment, expiry, and quota release.

- [ ] **Step 3: Run tests and confirm the forum store is absent**

Run `ForumImageConfigTest,FileForumImageStoreTest`; expected test compilation failure names the missing types.

- [ ] **Step 4: Implement minimal config and store**

Use opaque UUID upload IDs and SHA-256-derived storage keys. Write chunks with `CREATE_NEW`, track expected offset and total, decode once at completion, normalize through ImageIO, and atomically move completed files inside the root. Reject images whose decoded width/height or `width * height` exceeds configured limits. Synchronize quota/session accounting and make cleanup idempotent.

- [ ] **Step 5: Run store tests and commit**

```powershell
git add pom.xml vcampus-server/pom.xml vcampus-server/src/main/java/com/vcampus/server/config/ForumImageConfig.java vcampus-server/src/main/java/com/vcampus/server/image/ForumImageException.java vcampus-server/src/main/java/com/vcampus/server/image/ForumImageStore.java vcampus-server/src/main/java/com/vcampus/server/image/FileForumImageStore.java vcampus-server/src/test/java/com/vcampus/server/config/ForumImageConfigTest.java vcampus-server/src/test/java/com/vcampus/server/image/FileForumImageStoreTest.java
git commit -m "feat(forum): add validated forum image storage"
```

### Task 2: Image schema, post binding, and Socket service

**Files:**
- Modify: `database/schema.sql`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/ForumPostImageStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/ForumPostImageRepository.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/service/ForumImageService.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/ForumStore.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/ForumRepository.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/ForumService.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/ForumMigrationTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/ForumPostImageRepositoryTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/service/ForumImageServiceTest.java`

**Interfaces:**
- Produces actions `forum.image.upload.start`, `.chunk`, `.complete`, `.thumbnail`, and `.read`.
- Changes post creation to accept `List<String> stagedImageKeys`.
- Post detail returns ordered public image IDs and metadata.

- [ ] **Step 1: Write failing schema, binding, permission, and rollback tests**

Assert `forum_post_images` constraints and ordered reads. Assert zero/one/three bindings succeed, four fail, another user's key fails, reuse fails, and a failed post transaction leaves no image rows. Assert ordinary reads require visible content while managers may review hidden content.

- [ ] **Step 2: Run focused server tests and confirm missing schema/actions**

Expected: migration and compilation failures identify the missing image contract.

- [ ] **Step 3: Add schema and transactional post binding**

Create image rows with `(post_id, position)` uniqueness and store metadata copied from validated staged records. Lock and claim each key for the creating user before commit. On rollback, release claims; after commit, mark claims bound. Post detail queries metadata ordered by `position`.

- [ ] **Step 4: Implement authenticated image service and routing**

Map store exceptions to stable error messages. Decode Base64 chunks, enforce frame-safe chunk length, authenticate every operation, and authorize reads through repository visibility checks. Wire one `FileForumImageStore` and repositories in `VCampusServer`.

- [ ] **Step 5: Run focused server tests and commit**

```powershell
git add database/schema.sql vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java vcampus-server/src/main/java/com/vcampus/server/database/ForumPostImageStore.java vcampus-server/src/main/java/com/vcampus/server/database/ForumPostImageRepository.java vcampus-server/src/main/java/com/vcampus/server/service/ForumImageService.java vcampus-server/src/main/java/com/vcampus/server/database/ForumStore.java vcampus-server/src/main/java/com/vcampus/server/database/ForumRepository.java vcampus-server/src/main/java/com/vcampus/server/service/ForumService.java vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java vcampus-server/src/main/java/com/vcampus/server/network/VCampusServer.java vcampus-server/src/test/java/com/vcampus/server/database/ForumMigrationTest.java vcampus-server/src/test/java/com/vcampus/server/database/ForumPostImageRepositoryTest.java vcampus-server/src/test/java/com/vcampus/server/service/ForumImageServiceTest.java
git commit -m "feat(forum): bind uploaded images to posts"
```

### Task 3: Client uploader, cache, and gateway contract

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/ForumViewData.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumGateway.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/SocketForumGateway.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumImageDraft.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumImageUploader.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumImageCache.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumGatewayTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumImageUploaderTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumImageCacheTest.java`

**Interfaces:**
- Produces: `createPost(section, title, content, List<String> imageKeys)`.
- Produces: uploader `List<String> upload(List<ForumImageDraft>, DoubleConsumer progress)`.
- Produces: asynchronous cache APIs for thumbnail and full image bytes keyed by public image ID.

- [ ] **Step 1: Write failing gateway/uploader/cache tests**

Assert exact action names and parameters, fixed chunk offsets, progress from 0 to 1, stop-on-failure with retained drafts, response metadata parsing, request coalescing, cache bounds, and retry after failed image fetch.

- [ ] **Step 2: Run focused client tests and confirm APIs are absent**

Expected: compilation failures name uploader, cache, and image-aware post creation.

- [ ] **Step 3: Implement request methods and client image helpers**

Read local files only on the request executor. Preserve immutable `ForumImageDraft` values containing `Path`, display name, byte size, and preview image. Base64-encode one configured chunk at a time. Cache successful decoded JavaFX `Image` instances with a bounded access-order map; do not cache failures.

- [ ] **Step 4: Run client helper tests and commit**

```powershell
git add vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java vcampus-client/src/main/java/com/vcampus/client/ui/ForumViewData.java vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumGateway.java vcampus-client/src/main/java/com/vcampus/client/fx/forum/SocketForumGateway.java vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumImageDraft.java vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumImageUploader.java vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumImageCache.java vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumGatewayTest.java vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumImageUploaderTest.java vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumImageCacheTest.java
git commit -m "feat(forum): add forum image client pipeline"
```

### Task 4: Editor and post gallery

**Files:**
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumEditorView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumDetailView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumController.java`
- Modify: `vcampus-client/src/main/resources/com/vcampus/client/fx/forum/forum.css`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumUiTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumControllerTest.java`
- Test: `vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumVisualTest.java`

**Interfaces:**
- Consumes uploader/cache and ordered post image metadata from Task 3.
- Produces three-image editor, upload progress, ordered thumbnail gallery, modal full view, placeholders, and retry.

- [ ] **Step 1: Write failing JavaFX behavior tests**

Assert add enforces three files and 5 MiB, unsupported input is rejected, remove/reorder changes submission order, dirty state includes images, publish retains fields/drafts on error, success submits ordered keys, gallery preserves aspect ratio, and failed loads expose retry without removing text/comments.

- [ ] **Step 2: Run focused UI tests and confirm image controls are absent**

Expected: editor selectors and gallery nodes are missing.

- [ ] **Step 3: Implement editor and gallery**

Use `FileChooser` filters for `*.jpg`, `*.jpeg`, `*.png`, and `*.webp`, while enforcing actual server validation. Add stable IDs `forum-image-add`, `forum-image-remove-<index>`, `forum-image-left-<index>`, `forum-image-right-<index>`, `forum-upload-progress`, `forum-post-image-<id>`, and `forum-image-retry-<id>`. Upload through `ForumAsync`; all file and network work runs outside the JavaFX thread.

- [ ] **Step 4: Update documentation and visual captures**

Document `VCAMPUS_FORUM_IMAGE_ROOT`, maximum bytes, pixel limit, chunk size, staging expiry, and quota variables in `README.md`. Update `docs/requirements.md` to implemented status. Capture editor and detail at 780, 1060, and 1220 pixel widths.

- [ ] **Step 5: Run focused tests and commit**

```powershell
git add README.md docs/requirements.md vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumEditorView.java vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumDetailView.java vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumController.java vcampus-client/src/main/resources/com/vcampus/client/fx/forum/forum.css vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumUiTest.java vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumControllerTest.java vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumVisualTest.java
git commit -m "feat(forum): add post image editor and gallery"
```

### Task 5: Integrated verification

**Files:**
- Verify: all files changed by the three forum plans.

**Interfaces:**
- Consumes: administrator comment deletion, reports, and post images.
- Produces: a packaged client/server build with all forum acceptance criteria checked.

- [ ] **Step 1: Run protocol, server, and JavaFX focused suites**

Run all tests whose class name contains `Forum` plus the image store/service/cache/uploader tests. Expected: zero failures and only documented environment skips.

- [ ] **Step 2: Run the root build**

```powershell
& './.tools/apache-maven-3.9.11/bin/mvn.cmd' -s './.tools/maven-settings.xml' clean verify
```

Expected: `BUILD SUCCESS` for parent, common, server, and client.

- [ ] **Step 3: Inspect the final diff and generated artifacts**

Run `git diff --check`, verify `database/schema.sql` matches repository behavior, confirm no absolute path or fixture personal data appears, and restore visual snapshots that changed only because of rendering nondeterminism.

- [ ] **Step 4: Commit final integration corrections**

```powershell
git add README.md docs/requirements.md database/schema.sql vcampus-common vcampus-server vcampus-client
git commit -m "test(forum): verify images reports and moderation"
```
