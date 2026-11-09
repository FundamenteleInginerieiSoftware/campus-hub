# Document 24: ReservationApi bookBundle Facade & Project Assistant Architecture

**Author:** Calin Murariu (Member 3 - Reservations & Reassignment Engine)  
**Date:** 2026-11-09  
**Issue:** #140 (Project Assistant) & PR #232

---

## Part 1: In Simple Terms (for non-programmers)

When a student wants to build a project (like a digital thermometer or an autonomous robot), they need several pieces of lab equipment at once (an Arduino, sensors, breadboards, power supplies). 

Instead of forcing the student to find and reserve each item individually across multiple screens, Matei designed the **Project Assistant** feature: the student describes their project and clicks **Reserve all**.

To make this work seamlessly inside the server without messy HTTP calls or leaking internal database details between modules:
1. We added an in-process contract method `bookBundle` directly to `ReservationApi`.
2. It allows booking kits from **1 to 5 components** all-or-nothing: either all needed items are locked and reserved under one common bundle ID, or none are.
3. We updated our system architecture rules (ArchUnit) so that Matei's new `project` module can cleanly access `catalog.api` and `reservation.api` as an independent leaf module.

---

## Part 2: Technical Details (for developers & defense)

### 1. In-Process Public Contract (`ReservationApi`)

Added to `com.campus.reservation.api.ReservationApi`:
```java
/**
 * Atomically books a kit of 1 to 5 assets for the given time slot (all-or-nothing).
 * Used in-process by the project assistant engine (#140) and other cross-module workflows.
 *
 * @param userId the user ID on whose behalf the bundle is booked
 * @param assetIds the list of asset IDs to reserve (1 to 5 items)
 * @param start start of the reservation time slot
 * @param end end of the reservation time slot
 * @return the unique bundle identifier assigned to all reservations in the kit
 */
UUID bookBundle(Long userId, List<Long> assetIds, LocalDateTime start, LocalDateTime end);
```

### 2. Concurrency, Deadlock Prevention & Slot Rules

`ReservationService.bookBundle` enforces the exact same enterprise invariants as `createMultiAssetReservation`:
- **Slot Invariants:** `validateSlotRules(start, end, now)` enforces 08:00–20:00 operating hours, 30m slot granularity, 30m–4h duration, and max 14-day horizon.
- **Single-Item & Multi-Item Kits (1..5 assets):** Supports single-item project kits (e.g. an oscilloscope or standalone kit) through to 5-item hardware bundles with uniform bundle semantics.
- **Deterministic Pessimistic Locking:** `assetIds` are sorted in ascending order (`List<Long> sortedAssetIds = assetIds.stream().sorted().toList()`), and each asset row is locked sequentially via `catalogApi.lockForBooking(assetId)` before checking availability, eliminating circular deadlock wait-chains.
- **Atomic Availability & Hold Overlap:** Rejects the entire transaction with `409 RESERVATION_CONFLICT` or `409 SLOT_HELD` if any single item is already booked or held by another student.
- **Quota Enforcement:** Students are limited to 2 active reservations (`reservationRepository.lockUserQuota(userId)` with PostgreSQL advisory locking), while administrators bypass student quota limits.

### 3. ArchUnit Architectural Guardrails

Updated `backend/src/test/java/com/campus/architecture/ModuleBoundaryArchitectureTest.java`:
- Added `"project"` to `FEATURE_MODULES` with `.allowEmptyShould(true)`.
- Added `nothing_depends_on_project` rule enforcing that no module in the monolith depends on `com.campus.project` (leaf module pattern as specified in AGENTS.md §5).

### 4. Verification

- All unit and integration tests passing in `ReservationServiceTest` (`bookBundle_success_multipleAssets`, `bookBundle_success_singleAsset`, `bookBundle_rejectsInvalidSize`, `bookBundle_adminBypassesQuota`).
- ArchUnit rules verified green via `ModuleBoundaryArchitectureTest`.
- Spotless formatting verified (`./gradlew spotlessApply`).
