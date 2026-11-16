# 20 — Shared Paging Helper and Catalog Service Cleanup

**Issue:** Refs [#268](https://github.com/FundamenteleInginerieiSoftware/campus-hub/issues/268)  
**Date:** 16 November 2026  
**Author:** Liviu Nedelcu (`@NliviuN`)

---

## Part 1: In Simple Terms

During the code read-through in Issue #268, several small pieces of duplicated boilerplate were flagged across the `catalog` and `common` modules:

1. **Repeated pagination & sort validation:** Both `AssetService` and `DatabaseAuditService` had identical checks for negative page numbers, page sizes out of bounds, huge offsets, and `field,direction` sort parsing.
2. **Repeated conflict-handling boilerplate:** Creating and updating categories, lab rooms, and assets all wrapped `saveAndFlush` in identical `try/catch (DataIntegrityViolationException)` blocks.
3. **Repeated lookup in `CatalogService`:** Four methods repeated the same `orElseThrow(() -> new NotFoundException("ASSET_NOT_FOUND", ...))` lambda.
4. **Outdated comments:** `StorageValidation` still referred to `S3StorageService` as a future class even though Matei implemented it earlier, and the audit sort parameter lacked a note about `createdAt` defaulting to descending order.

Consolidating these into small, single-purpose helpers eliminates duplication so any future rule change happens in one place and is easier to explain.

---

## Part 2: Technical Details

### 1. Shared `PageRequests` Helper (`com.campus.common.paging`)
- Added [`PageRequests.java`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/main/java/com/campus/common/paging/PageRequests.java):
  - `validatePageBounds(page, size, maxPageSize)` enforces `page >= 0`, `1 <= size <= maxPageSize`, and `(long) page * size <= Integer.MAX_VALUE`.
  - `of(page, size, sort)` builds a `Pageable` after validating bounds.
  - `parseSort(sort, defaultSort, allowedFields, defaultDirections, tieBreakSameDirection)` enforces the field whitelist, parses `asc`/`desc`, and appends deterministic `id` tie-breaking.
- Added unit tests in [`PageRequestsTest.java`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/test/java/com/campus/common/paging/PageRequestsTest.java).
- Updated [`AssetService.java`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/main/java/com/campus/catalog/service/AssetService.java) and [`DatabaseAuditService.java`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/main/java/com/campus/common/audit/DatabaseAuditService.java) to use `PageRequests`.

### 2. Catalog Persistence & Lookup Helpers (`com.campus.catalog.service`)
- Added package-private [`CatalogPersistence.saveOrThrowConflict(...)`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/main/java/com/campus/catalog/service/CatalogPersistence.java) and replaced all 5 `saveAndFlush` + `DataIntegrityViolationException` blocks across `AssetService`, `CategoryService`, and `LabRoomService`.
- Extracted `findOrThrow(assetId)` and `findForUpdateOrThrow(assetId)` in [`CatalogService.java`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/main/java/com/campus/catalog/service/CatalogService.java).

### 3. Documentation & Swagger Details
- Updated [`StorageValidation.java`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/main/java/com/campus/storage/service/StorageValidation.java) Javadoc to reference `{@link S3StorageService}` directly.
- Clarified sort default direction behavior (`createdAt` defaults to `desc`, other fields default to `asc`) in [`AdminAuditLogController.java`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/main/java/com/campus/common/audit/web/AdminAuditLogController.java).
