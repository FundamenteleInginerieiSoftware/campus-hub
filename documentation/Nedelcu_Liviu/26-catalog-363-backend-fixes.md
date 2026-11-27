# 26 — Catalog Concurrency Fix (`CatalogPersistence`), `null` Exclude Parity & Backend Polish

**Issue:** Refs [#363](https://github.com/FundamenteleInginerieiSoftware/campus-hub/issues/363) (PR 1 of 2: Backend)  
**Date:** 27 November 2026  
**Author:** Liviu Nedelcu (`@NliviuN`)

---

## Part 1: In Simple Terms

Matei's live audit against PostgreSQL 18 found a critical concurrency bug and a few smaller gaps in the catalog backend:

1. **Duplicate race gave 500 instead of 409 (`CatalogPersistence`):** When two admins tried to create or rename an asset/category/lab room to the same unique value at the exact same millisecond, PostgreSQL aborted the second transaction with a duplicate-key error (`23505`). Our catch block then tried to run another `existsBy...` query on that same aborted transaction, which made Hibernate/PostgreSQL crash with HTTP `500` instead of `409 Conflict`. Now we inspect the database error code (`23505`) and unique constraint name directly from the exception cause chain without touching the database.
2. **`null` exclude ID on `CatalogApi`:** Calling `findAvailableByCategory(categoryId, null)` returned 0 assets because SQL evaluates `id <> NULL` as `UNKNOWN`, whereas `findAvailableByModel(model, null)` returned all available units. Both now use a non-excluding query when `excludeAssetId` is `null`.
3. **Small backend improvements:** Made `Asset.markUnderMaintenance` / `markAvailable` true no-ops when already in the target status (so `@Version` and `updatedAt` are not bumped unnecessarily), added a bulk `CatalogApi.findAssetsByIds(...)` lookup so teammates can drop N+1 queries, and included class-level (`globalErrors`) validation errors in `GlobalExceptionHandler`.

---

## Part 2: Technical Details

### 1. Files Changed
- **`CatalogPersistence.java`:** Replaced the `BooleanSupplier isDuplicate` DB query in `catch (DataIntegrityViolationException)` with cause-chain inspection for PostgreSQL SQLState `23505` + unique constraint names (`assets_serial_number_key` / `uq_assets_serial_number_lower`, `categories_name_key` / `uq_categories_name_lower`, `lab_rooms_code_key` / `uq_lab_rooms_code_lower`). Updated `AssetService`, `CategoryService`, and `LabRoomService` callers.
- **`CatalogApi.java`, `CatalogService.java`, `AssetRepository.java`:**
  - Added `findByModelAndStatus` and `findByCategoryIdAndStatus` and branch on `excludeAssetId == null` in `CatalogService`.
  - Added `Map<Long, AssetSummary> findAssetsByIds(Collection<Long> assetIds)` (short-circuits on `null`/empty without hitting the DB).
- **`Asset.java`:** Early-return no-op in `markUnderMaintenance` and `markAvailable` when already in the target status.
- **`GlobalExceptionHandler.java`:** Concatenates `getGlobalErrors()` with `getFieldErrors()` in `handleValidation`.

### 2. Tests & Verification
- Added **`CatalogConcurrencyIT.java`** (`@SpringBootTest` + `CyclicBarrier` against real PostgreSQL `campus_test`):
  - `concurrentAssetCreateWithSameSerialReturns201And409Not500`
  - `concurrentCategoryUpdateToSameNameReturns200And409Not500`
  - `findAvailableWithNullExcludeIdAndBulkFindByIdsWorkOnRealPostgres`
- Updated `AssetServiceTest`, `CategoryServiceTest`, `LabRoomServiceTest`, `CatalogServiceTest`, and `GlobalExceptionHandlerTest`.
- Verified with `./gradlew spotlessApply test --tests "com.campus.catalog.**" --tests "com.campus.common.error.**" --tests "com.campus.architecture.**"`.
