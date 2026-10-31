# Catalog Domain, Flyway Schema & CatalogApi Facade (Nedelcu Liviu)

**Role:** Member 2 — Catalog, Inventory & Shared Services (`@NliviuN`)
**Date:** 2026-10-26
**Branch / PR:** `feat/catalog-domain` (#24)
**See also:** `docs/modules/catalog.md`

---

## Part 1: In simple terms

This milestone sets up the university's equipment inventory in PostgreSQL and opens the "service counter" (`CatalogApi`) that the other modules use:

1. **Three core tables:** `categories` (e.g. Oscilloscopes), `lab_rooms` (e.g. `EC105`), and `assets` (each physical device with its model, unique serial number, and operational status `AVAILABLE`, `UNDER_MAINTENANCE`, or `RETIRED`).
2. **Physical condition vs. booking schedule:** An asset's status only reflects whether it works physically — never whether someone booked it from 14:00 to 16:00 (time-slot availability belongs to Calin's `reservation` module).
3. **Preventing double-bookings at the database level:** When two students click "Book" on the same oscilloscope at the exact same moment, `lockForBooking` makes the second transaction wait until the first finishes, preventing overlapping reservations.

---

## Part 2: Technical details

### Files

| File | Purpose |
|---|---|
| `db/migration/V2026_10_24_1345__catalog_create_tables.sql` | Creates `categories`, `lab_rooms`, and `assets` with case-insensitive `UNIQUE` indexes (`LOWER(...)`) and FK constraints |
| `catalog/domain/Category.java`, `LabRoom.java`, `Asset.java` | JPA entities with `FetchType.LAZY` relations, `@Version` optimistic locking on `Asset`, and domain transition methods (`markUnderMaintenance`, `markAvailable`, `retire`, `updateDetails`) |
| `catalog/api/AssetStatus.java`, `AssetSummary.java`, `CatalogApi.java` | Public cross-module facade (`catalog.api` is the only package imported by `reservation`, `incident`, and `reassignment`) |
| `catalog/repository/*Repository.java` | Spring Data JPA repositories, including `@Lock(LockModeType.PESSIMISTIC_WRITE)` on `AssetRepository.findByIdForUpdate` |
| `catalog/service/CatalogService.java` | `@Transactional` implementation of `CatalogApi` using the shared campus `Clock` |

### Key design choices

| Choice | Why |
|---|---|
| `PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`) on `Asset` row | Overlapping reservations cannot be prevented by checking `reservations` alone when two `INSERT`s run concurrently; locking the parent `assets` row serializes booking checks per asset (`Propagation.MANDATORY` ensures the caller already opened a transaction) |
| Required `model` column on `assets` | Enables Calin's `SameModelStrategy` (`findAvailableByModel`) to swap a damaged Rigol DS1054Z with an identical model before falling back to `SameCategoryStrategy` |
| Case-insensitive uniqueness + normalization in entity constructors | Trimming and uppercasing `serialNumber` and `labRoom.code` in Java matches the `LOWER(...)` unique indexes in PostgreSQL |
| Reject transitions out of `RETIRED` (`409 ASSET_RETIRED`) | Once hardware is decommissioned, incidents or repairs cannot accidentally put it back into circulation |
