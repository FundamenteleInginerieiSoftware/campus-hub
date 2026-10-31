# Catalog Admin CRUD & Asset Retirement (Nedelcu Liviu)

**Role:** Member 2 — Catalog, Inventory & Shared Services (`@NliviuN`)
**Date:** 2026-10-29
**Branches / PRs:** `feat/catalog-categories-rooms` (#66), `feat/catalog-admin-assets`
**See also:** `02-catalog-domain-and-api.md` (catalog entities), `03-database-audit-service.md` (`AuditService`)

---

## Part 1: In simple terms

These two PRs expose the REST endpoints for managing laboratory inventory and reading equipment details:

1. **Categories & lab rooms (#66):** Students and admins can list all categories and lab rooms; admins can add, edit, and delete unused ones (`409 CATEGORY_IN_USE` / `LAB_ROOM_IN_USE` stops deleting a category or room that still holds equipment).
2. **Equipment registration, updates & details:** Admins can register new equipment (`409 SERIAL_NUMBER_TAKEN` on duplicate serial numbers) and update details (`name`, `model`, `description`, `category`, `labRoom`), while students and admins can view full item details (`GET /api/v1/assets/{id}`).
3. **Retire instead of delete:** Equipment items are never deleted (`POST /api/v1/admin/assets/{id}/retire` moves them to `RETIRED`), keeping past reservations and incident reports valid. Every admin action is recorded in `audit_logs`.

---

## Part 2: Technical details

### Files

| Area | Files | Purpose |
|---|---|---|
| `catalog/web/` | `CategoryRequest`, `CategoryResponse`, `LabRoomRequest`, `LabRoomResponse`, `CreateAssetRequest`, `UpdateAssetRequest`, `AssetResponse`, `AdminActor`, `CategoryController`, `LabRoomController`, `AssetController` | Validation (`@Valid`), flat DTO `record`s (never exposing `@Entity`), Swagger annotations, and JWT `sub` extraction until `CurrentUser` (#40 / #69) lands |
| `catalog/service/` | `CategoryService`, `LabRoomService`, `AssetService` | `@Transactional` business logic, race-safe uniqueness handling (`DataIntegrityViolationException` -> `409`), and `AuditService.record(...)` calls |
| `catalog/repository/` | `CategoryRepository`, `LabRoomRepository`, `AssetRepository` | `existsBy...IgnoreCaseAndIdNot` for rename checks and `@EntityGraph(attributePaths = {"category", "labRoom"})` on `findWithDetailsById` |

### Key design choices

| Choice | Why |
|---|---|
| `@EntityGraph(attributePaths = {"category", "labRoom"})` on `findWithDetailsById` | Fetches `Asset` + `Category` + `LabRoom` in one SQL `JOIN` so `AssetResponse.from(asset)` never triggers N+1 queries with `open-in-view=false` |
| `findByIdForUpdate` (`PESSIMISTIC_WRITE`) in `AssetService.retire` | Serializes admin retirement with concurrent student bookings (`CatalogApi.lockForBooking`) without locking the joined `categories` or `lab_rooms` rows |
| `POST /api/v1/admin/assets/{id}/retire` instead of `DELETE` | Preserves foreign-key history in `reservations` and `incident_reports`; double-retire or updating a retired item returns `409 ASSET_RETIRED` |
| Pre-check + `catch (DataIntegrityViolationException)` on `saveAndFlush` | Ensures two simultaneous requests with the same category name, room code, or serial number return `409` instead of `500` |
