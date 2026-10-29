# Database Audit Log & Catalog Admin CRUD (Nedelcu Liviu)

**Role:** Member 2 — Catalog, Inventory & Shared Services (`@NliviuN`)
**Date:** 2026-10-29
**Branches / PRs:** `feat/common-audit-service` (#64), `feat/catalog-categories-rooms` (#66), `feat/catalog-admin-assets`

---

## Part 1: In simple terms

Week 4 turns the catalog and audit log from internal building blocks into real HTTP endpoints for administrators and students:

1. **Permanent audit trail in PostgreSQL:** Every sensitive action (creating/updating/deleting a category or lab room, registering or retiring equipment, or automated reservation jobs) is now saved in the `audit_logs` table instead of only printing to the console.
2. **Managing categories, lab rooms, and equipment:** Admins can add and edit categories, rooms, and equipment items, while students can browse categories, rooms, and item details.
3. **Safe deletion & retirement rules:** Categories and lab rooms that still hold equipment cannot be deleted (`409 CATEGORY_IN_USE` / `LAB_ROOM_IN_USE`), and equipment items are **retired** (`POST /api/v1/admin/assets/{id}/retire`), never deleted, so historical bookings and incident reports stay intact.

---

## Part 2: Technical details

### Files

| Area | Files | Purpose |
|---|---|---|
| `common/audit/` | `V2026_10_29_1140__audit_create_audit_logs.sql`, `AuditLog`, `AuditLogRepository`, `DatabaseAuditService` | Replaces `LoggingAuditService`; persists audit rows in the caller's transaction (`Propagation.REQUIRED`) using `LocalDateTime.now(clock)` |
| `catalog/web/` | `CategoryRequest`, `CategoryResponse`, `LabRoomRequest`, `LabRoomResponse`, `CreateAssetRequest`, `UpdateAssetRequest`, `AssetResponse`, `AdminActor`, `CategoryController`, `LabRoomController`, `AssetController` | Request/response DTO `record`s, Swagger-documented endpoints under `/api/v1/**` and `/api/v1/admin/**`, and JWT `sub` extraction |
| `catalog/service/` | `CategoryService`, `LabRoomService`, `AssetService` | Uniqueness & in-use checks, `DataIntegrityViolationException` race-condition handling, `@EntityGraph` eager fetch on `findWithDetailsById`, and `AuditService` calls |

### Key design choices

| Choice | Why |
|---|---|
| Default `@Transactional` (`REQUIRED`) on `DatabaseAuditService.record` | Runs inside the same SQL transaction as the admin mutation: if the mutation rolls back, its audit row rolls back too — no phantom audit entries |
| Nullable `actor_id` FK (`REFERENCES users(id)`) | Supports both human admins (`actorId = 3`) and scheduled background jobs (`actorId = null`, e.g. no-show sweeps) while still enforcing referential integrity on non-null user IDs |
| Pre-check + `catch (DataIntegrityViolationException)` on `saveAndFlush` | Returns a clean `409` (`CATEGORY_NAME_TAKEN`, `LAB_ROOM_CODE_TAKEN`, `SERIAL_NUMBER_TAKEN`) even when two admins submit the same name/code/serial simultaneously |
| `@EntityGraph(attributePaths = {"category", "labRoom"})` on `findWithDetailsById` | Loads `Asset` + `Category` + `LabRoom` in one SQL `JOIN` while keeping `@ManyToOne(fetch = LAZY)` and `open-in-view=false` |
| `findByIdForUpdate` inside `AssetService.retire` | Serializes admin retirement against concurrent student bookings (`CatalogApi.lockForBooking`) without locking the joined `categories` or `lab_rooms` rows |
