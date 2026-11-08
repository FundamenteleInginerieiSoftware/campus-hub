# Module: Reservation

**Owner:** Călin Murariu ([@Swiorx](https://github.com/Swiorx))  
**Base Package:** `com.campus.reservation`  
**API Facade:** `com.campus.reservation.api.ReservationApi`  

---

## 1. Purpose

The `reservation` module manages the scheduling, allocation, lifecycle, and access control of campus laboratory equipment bookings. It guarantees that no two students can book the same physical asset for overlapping intervals, strictly enforces lab operating hours, limits booking quotas per student, and protects privacy via IDOR mitigations.

---

## 2. Public API: `ReservationApi`

Other modules may only interact with this module through `ReservationApi` or by publishing events. Direct access to `domain`, `repository`, `service`, or `web` packages will be enforced by ArchUnit rules.

```java
public interface ReservationApi {
    List<ReservationSummary> findUpcomingActiveByAsset(Long assetId, LocalDateTime from);
    boolean isAssetFree(Long assetId, LocalDateTime start, LocalDateTime end, Long ignoreReservationId);
    boolean moveToAsset(Long reservationId, Long newAssetId, String reason);
    boolean forceCancel(Long reservationId, String reason);
    Optional<ReservationSummary> findCurrentCheckout(Long assetId);
}
```

### Public DTOs (`api` package):
- `ReservationSummary(Long id, Long userId, Long assetId, LocalDateTime start, LocalDateTime end, ReservationStatus status)`

---

## 3. Architecture & Data Flow

```
[ HTTP Request ]
       │
       ▼
ReservationController (@RestController)
       │  (DTO Request)
       ▼
ReservationService (@Service, implements ReservationApi)
  ├── 1. Temporal Validations (Clock, opening hours, duration, slot alignment)
  ├── 2. CatalogApi.lockForBooking (Pessimistic row lock on asset FIRST)
  ├── 3. CatalogApi.isBookable (Operational status check: AVAILABLE)
  ├── 4. ReservationRepository.existsOverlap (Interval collision check)
  ├── 5. ReservationRepository.lockUserQuota + User Quota Verification (pg_advisory_xact_lock(userId), count < 2; bypassed by admins)
  ├── 6. ReservationRepository.save (Persist entity)
  └── 7. AuditService.record (CREATE_RESERVATION)
       │
       ▼
PostgreSQL Table: `reservations`
```

---

## 4. Concurrency & Race-Condition Prevention

When multiple students attempt to book the same asset at the same second:
1. **Pessimistic Row Lock:** `ReservationService` calls `catalogApi.lockForBooking(assetId)` which executes `SELECT ... FOR UPDATE` on the `assets` table.
2. **Serialization:** The database serializes simultaneous requests for that asset ID. The second transaction pauses until the first transaction commits or rolls back.
3. **Overlap Re-check:** When the second transaction unblocks, it queries `existsOverlap` which now sees the newly committed reservation from the first transaction and returns `RESERVATION_CONFLICT` (HTTP 409).
4. **Optimistic Locking:** The `@Version` column on `Reservation` prevents lost updates during concurrent status transitions (e.g. check-out vs cancellation).

---

## 5. Security & Ownership Rules

- **Current User:** Resolved via `CurrentUser.id()` from the JWT bearer token.
- **IDOR Protection:** Attempting to read or cancel another student's reservation returns **`404 Not Found`** (`RESERVATION_NOT_FOUND`), not `403 Forbidden`, preventing malicious enumeration of valid reservation IDs.
- **Admin Privilege:** Users with role `ADMIN` may view and cancel any reservation regardless of ownership.

---

## 6. How to Test

```bash
# Unit & Slice tests (no local database required):
cd backend
./gradlew test --tests "com.campus.reservation.service.*"
./gradlew test --tests "com.campus.reservation.web.*"

# Integration tests on real PostgreSQL:
./gradlew test --tests "com.campus.reservation.repository.ReservationRepositoryTest"
```

---

## 7. Frontend Components & UI Integration

- **`frontend/src/pages/MyReservationsPage.tsx`**: Complete dashboard for `/reservations` providing students and admins with quota tracking cards (backed by real database counts via `GET /api/v1/reservations/me/summary`), server-side active/past tabs (`GET /api/v1/reservations/me?status=...`), quota limit warning badge (`Limit reached (2/2)`), `PagedTable` with pagination strictly matching server totals, cancellation modal, and instant confirmation alerts.
- **`frontend/src/components/CalendarBookingView.tsx`**: Interactive Google Calendar timetable with 30-minute intervals (Mon–Fri, 08:00–20:00). Features real-time availability checks via `GET /api/v1/reservations/availability`, RFC 9457 error display (`<ErrorAlert error={availability.error} />`), synchronized week navigation (`handleNextWeek`/`handlePrevWeek`), past-time auto-disabling, asset operational state verification, and strict enforcement of the 20:00 lab closing constraint (durations exceeding 20:00 are dynamically disabled and capped).
- **`frontend/src/api/reservations.ts`**: Frontend API client bindings mapping to the reservation REST endpoints (`createReservation`, `cancelReservation`, `fetchMyReservations`, `fetchMyReservationSummary`, `checkAvailability`, `fetchAssetSummary`).

---


## 8. Simultaneous Multi-Asset Bundle Reservations

The module supports simultaneous atomic reservation of multiple laboratory equipment items (bundles):
- **Endpoint:** `POST /api/v1/reservations/bundle` (`CreateMultiAssetReservationRequest(assetIds, startTime, endTime)`).
- **Bundle Constraints:** The bundle must contain between 2 and 5 assets (`@Size(min = 2, max = 5)`). If fewer than 2 or more than 5 assets are requested, rejected with `409 INVALID_BUNDLE_SIZE`.
- **Deadlock Avoidance via Sorted Pessimistic Locking:** Asset IDs are sorted in ascending order before acquiring pessimistic locks (`catalogApi.lockForBooking(id)`), strictly preventing database deadlocks between concurrent multi-asset booking requests.
- **Atomic All-or-Nothing Guarantee:**
  - Every asset in the bundle is checked for operational bookability (`catalogApi.isBookable(id)`) and interval collisions (`existsOverlap`).
  - If any single equipment item is unavailable, under maintenance, or booked, the entire bundle fails immediately with `409 RESERVATION_CONFLICT` or `409 ASSET_NOT_BOOKABLE`, and no reservation rows are saved.
- **Bundle Grouping & Collective Management:**
  - All reservations in the bundle share a common `bundle_id` (`UUID`).
  - `GET /api/v1/reservations/bundle/{bundleId}`: inspect all reservations in the kit.
  - `POST /api/v1/reservations/bundle/{bundleId}/cancel`: cancel the entire kit collectively in a single call.

---

## 9. Active Reservation Duration Extension

The module supports atomic duration extensions for active equipment reservations (`CONFIRMED` or `CHECKED_OUT`):
- **Endpoint:** `POST /api/v1/reservations/{id}/extend` (`ExtendReservationRequest(newEndTime)`).
- **Concurrency & Pessimistic Locking:** Locks the asset row (`catalogApi.lockForBooking(assetId)`) before running checks to serialize concurrent bookings or extensions.
- **Operational State Verification:** Validates `catalogApi.isBookable(assetId)`; equipment under maintenance or retired cannot be extended.
- **Temporal & Boundary Validations:**
  - Cannot extend expired reservations (`now >= currentEndTime`) or reservations where the 30-minute check-out window has elapsed (`now > startTime + 30m`).
  - Strict alignment to `:00` or `:30` with zero seconds/nanos.
  - Duration limit: total duration from `startTime` to `newEndTime` must be between 30 minutes and 4 hours.
  - Lab hours: within Mon–Fri 08:00–20:00 and on the same calendar day; max 14 days booking horizon.
- **Collision Detection & Rollback:** Overlap check on `[currentEndTime, newEndTime]` ignores the current reservation ID. If the subsequent slot is occupied, the request is rejected with `409 EXTENSION_CONFLICT`, leaving the original reservation untouched.
- **Audit:** Records `EXTEND_RESERVATION` in `AuditService`.



