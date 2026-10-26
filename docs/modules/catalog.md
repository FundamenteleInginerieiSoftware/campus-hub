# Catalog Module (`com.campus.catalog`)

**Owner:** Liviu Nedelcu (`@NliviuN`)

## Purpose
Manages the university's laboratory equipment inventory:
- **Categories (`categories`):** equipment groupings (e.g. Oscilloscopes, 3D Printers).
- **Lab rooms (`lab_rooms`):** physical rooms where equipment is located (e.g. `B204`).
- **Assets (`assets`):** individual bookable items with operational status (`AVAILABLE`, `UNDER_MAINTENANCE`, `RETIRED`), required `model` (for same-model substitution), unique `serial_number`, and `@Version` optimistic locking.

Operational status tracks only physical condition (`AVAILABLE | UNDER_MAINTENANCE | RETIRED`); time-slot availability is computed by the `reservation` module.

## Public API (`com.campus.catalog.api`)

Other modules (`reservation`, `incident`, `reassignment`) interact with the catalog exclusively through `CatalogApi`:

| Method | Used by | Description |
|---|---|---|
| `getAsset(Long assetId)` | `reservation`, `incident`, `reassignment` | Returns `AssetSummary` or throws `NotFoundException` (`ASSET_NOT_FOUND`) |
| `isBookable(Long assetId)` | `reservation` | Returns `true` if the asset exists and its status is `AVAILABLE` |
| `findAvailableByModel(String model, Long excludeAssetId)` | `reassignment` (`SameModelStrategy`) | Returns all `AVAILABLE` assets with the same model except `excludeAssetId` |
| `findAvailableByCategory(Long categoryId, Long excludeAssetId)` | `reassignment` (`SameCategoryStrategy`) | Returns all `AVAILABLE` assets in the same category except `excludeAssetId` |
| `markUnderMaintenance(Long assetId)` | `incident`, `reassignment` | Moves asset to `UNDER_MAINTENANCE` (rejects `RETIRED` assets with `409 ASSET_RETIRED`) |
| `markAvailable(Long assetId)` | `incident` (when resolved) | Moves asset back to `AVAILABLE` (rejects `RETIRED` assets with `409 ASSET_RETIRED`) |
| `lockForBooking(Long assetId)` | `reservation` | Acquires a `PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`) lock inside the caller's `@Transactional` booking method |

## How to test

```bash
cd backend
./gradlew test --tests "com.campus.catalog.*"
```

- `CatalogServiceTest`: unit tests with Mockito and a fixed `Clock`.
- `AssetRepositoryTest`: `@DataJpaTest` against the local PostgreSQL `campus_test` database verifying `findByIdForUpdate` and case-insensitive uniqueness constraints.
