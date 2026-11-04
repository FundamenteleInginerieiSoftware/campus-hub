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

## REST Endpoints (`com.campus.catalog.web`)

- `GET /api/v1/categories`, `POST|PUT|DELETE /api/v1/admin/categories/**`: category list and admin CRUD (`409 CATEGORY_NAME_TAKEN`, `409 CATEGORY_IN_USE`).
- `GET /api/v1/lab-rooms`, `POST|PUT|DELETE /api/v1/admin/lab-rooms/**`: lab room list and admin CRUD (`409 LAB_ROOM_CODE_TAKEN`, `409 LAB_ROOM_IN_USE`).
- `GET /api/v1/assets`: paginated search and filtering (`AssetSpecifications.withFilters` + `@EntityGraph(attributePaths = {"category", "labRoom"})` + sort whitelist + `PageResponse<AssetResponse>`). Hides `RETIRED` equipment from `STUDENT` queries while keeping all statuses visible to `ADMIN` inventory views.
- `GET /api/v1/assets/{id}`, `POST /api/v1/admin/assets`, `PUT /api/v1/admin/assets/{id}`, `POST /api/v1/admin/assets/{id}/retire`: equipment details, inventory registration/update, and soft retirement (`409 SERIAL_NUMBER_TAKEN`, `409 ASSET_RETIRED`).

## How to test

```bash
cd backend
./gradlew test --tests "com.campus.catalog.*"
```

- `CatalogServiceTest`, `AssetServiceTest`, `CategoryServiceTest`, `LabRoomServiceTest`: unit tests with Mockito and a fixed `Clock`.
- `AssetRepositoryTest`: `@DataJpaTest` against the local PostgreSQL `campus_test` database verifying `findByIdForUpdate`, case-insensitive uniqueness constraints, `AssetSpecifications` filter combinations, and `@EntityGraph` eager loading.
- `AssetControllerTest`, `CategoryControllerTest`, `LabRoomControllerTest`: `@WebMvcTest` with `SecurityConfig` verifying status codes, role checks, validation, and `ProblemDetail` error codes.
