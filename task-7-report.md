# Task 7 — Typed JavaFX Shop Gateway

Implemented the token-bound synchronous shop gateway and strict immutable response decoders.

- Added `ShopData`, `ShopGateway`, and `SocketShopGateway` under `fx/shop`.
- Added category/sort, product/detail/image, image-upload, and image-plan request helpers to `VCampusClient`.
- Preserved the inherited decoder and wire-shape tests; made `VCampusClient` extensible for its recording test transport.
- No UI, threading, server, database, or protocol changes were made.

Verification:

- `mvn -pl vcampus-client -am -Dtest=ShopDataTest,ShopGatewayTest -Dsurefire.failIfNoSpecifiedTests=false test` — 17 passing.
- `mvn clean verify` — passing (567 tests; 2 existing skipped image-store tests).

The initial focused-test attempt was RED because the unimplemented `fx.shop` package was absent. Maven was also not on PATH; the supplied workspace Maven runtime was used for the recorded verification.
