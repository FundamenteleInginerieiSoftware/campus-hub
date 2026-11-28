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
    Optional<Long> moveToAsset(Long reservationId, Long expectedSourceAssetId, Long newAssetId, String reason);
    Optional<Long> forceCancel(Long reservationId, Long expectedSourceAssetId, String reason);
    Optional<ReservationSummary> findCurrentCheckout(Long assetId);
    UUID bookBundle(Long userId, List<Long> assetIds, LocalDateTime start, LocalDateTime end);
    int cancelUpcomingReservationsForUser(Long userId, String reason);
    Map<Long, Long> usedMinutesByAsset(LocalDateTime from, LocalDateTime to);
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
  ├── 2. ReservationRepository.lockUserQuota + UserApi.findActiveRole (pg_advisory_xact_lock(1, userId) FIRST for all callers incl. admins)
  ├── 3. CatalogApi.lockForBooking (Pessimistic row lock on asset SECOND)
  ├── 4. CatalogApi.isBookable (Operational status check: AVAILABLE)
  ├── 5. ReservationRepository.existsOverlap & ReservationHoldRepository.existsActiveOverlap (Interval collision check)
  ├── 6. Student Quota Verification (count < 2; bypassed by Role.ADMIN)
  ├── 7. Confirm overlapping own holds (FOR UPDATE ORDER BY id ASC) & ReservationRepository.save
  └── 8. AuditService.record (CREATE_RESERVATION)
       │
       ▼
PostgreSQL Table: `reservations`
```

---

## 4. Concurrency & Race-Condition Prevention

All transactional write paths follow one strict **global lock DAG** (`Quota -> Reservation -> Asset -> Transfer -> Hold`, #362) so concurrent bookings, extensions, hold confirmations, transfers, and user-disable cancellations can never form an AB-BA or 3-way deadlock cycle:
1. **Per-User Quota Lock FIRST:** `ReservationRepository.lockUserQuota(userId)` (`pg_advisory_xact_lock(1, userId)`) + `UserApi.findActiveRole(userId)` runs first for all callers (including admins) in `createReservation`, `executeBundleBooking`, `extendReservation`, `createHold`, `confirmHold`, and `acceptTransfer`. When an admin confirms another student's hold, both user quotas are locked in ascending `userId` order.
2. **Reservation Row Lock SECOND:** `findByIdForUpdate` / `findByBundleIdForUpdate` (`ORDER BY id ASC`) / `findUpcomingConfirmedByUserForUpdate` (`ORDER BY id ASC`).
3. **Asset Row Lock THIRD:** `catalogApi.lockForBooking(assetId)` (`SELECT ... FOR UPDATE` on `assets`, sorted ascending for bundles) serializes simultaneous requests for the same asset so the second transaction sees the first one's commit and returns `409 RESERVATION_CONFLICT`.
4. **Transfer / Hold Row Locks LAST:** `ReservationTransferRepository.findByIdForUpdate`, `ReservationHoldRepository.findByIdForUpdate`, and `findActiveOverlappingForUserAndAssetsForUpdate` (`ORDER BY id ASC`).
5. **Optimistic Locking:** The `@Version` column on `Reservation`, `ReservationHold`, and `ReservationTransfer` acts as an additional safety net.

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
- **`frontend/src/components/CalendarBookingView.tsx`**: Interactive Google Calendar timetable with 30-minute intervals (Mon–Fri, 08:00–20:00). Features real-time availability checks via `GET /api/v1/reservations/availability` (suggesting free same-model units sorted same-room first for in-place unit switching when the selected asset conflicts or is non-bookable), RFC 9457 error display (`<ErrorAlert error={availability.error} />`), synchronized week navigation (`handleNextWeek`/`handlePrevWeek`), past-time auto-disabling, asset operational state verification, and strict enforcement of the 20:00 lab closing constraint (durations exceeding 20:00 are dynamically disabled and capped).
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

---

## 10. Peer-to-Peer Reservation Transfer Workflow

The module supports peer-to-peer transfer of upcoming confirmed reservations between students:
- **Initiation:** The owner of a `CONFIRMED` reservation can initiate a transfer to another registered student (`POST /api/v1/reservations/{id}/transfers`). Only one pending transfer is allowed per reservation at a time. Self-transfers and transfers to disabled users are rejected.
- **Recipient Acceptance:** The recipient can accept (`POST /api/v1/reservations/transfers/{transferId}/accept`). The recipient's quota is locked via advisory lock (`lockUserQuota`) to ensure they do not exceed the 2-active-reservations limit. Upon acceptance, ownership is transferred atomically, and the transfer status transitions to `ACCEPTED`.
- **Rejection & Cancellation:** The recipient can reject (`POST /api/v1/reservations/transfers/{transferId}/reject`), or the sender can cancel before acceptance (`POST /api/v1/reservations/transfers/{transferId}/cancel`).
- **Audit & Notification:** Every transfer transition is audited via `AuditService` and both parties receive notifications through `NotificationService`.

---

## 11. Reservation Holds (Temporary Slot Locking)

To prevent checkout collisions during high-traffic lab hours, the module provides a temporary 1-minute slot locking workflow (`reservation_holds` table):
- **Acquire Hold (`POST /api/v1/reservations/holds`):** Temporarily locks the asset slot for 60 seconds. Other students receive `409 SLOT_HELD`. Anti-hoarding rules limit each student to at most 1 active hold.
- **Confirm Hold (`POST /api/v1/reservations/holds/{id}/confirm`):** Converts the active hold into a confirmed reservation atomically under row lock. If 1 minute elapsed without confirmation, returns `409 HOLD_EXPIRED`.
- **Release Hold (`POST /api/v1/reservations/holds/{id}/release`):** Frees the slot immediately if the user cancels or navigates away.
- **Automated Cleanup Job:** `ReservationLifecycleService.sweepExpiredHolds()` runs periodically to mark abandoned holds as `EXPIRED`. Queries filter `status = 'ACTIVE' AND expires_at > now`, so expired holds unblock availability immediately.

---

## 12. Private iCalendar Subscription Feed

Students can generate a private subscription link to sync their bookings with external calendar apps (Google Calendar, Apple Calendar, Outlook):
- **`CalendarTokenService`:** Manages per-user 256-bit secret tokens in `calendar_feeds` using an atomic PostgreSQL `ON CONFLICT (user_id) DO UPDATE` upsert (`CalendarFeedRepository.upsertToken`) so concurrent double-clicks never fail with a unique-constraint 500 (persisting only SHA-256 hashes, auditing `CALENDAR_FEED_CREATED` / `CALENDAR_FEED_REVOKED`), exposed via `GET`/`POST`/`DELETE /api/v1/reservations/me/calendar-feed` (`CalendarFeedSettingsController`). Validates the 43-character base64url pattern before hashing and checks `UserApi.findActiveRole` so disabled accounts lose access immediately.
- **`CalendarFeedService`:** Loads the user's reservations overlapping `[campusNow - 30d, campusNow + 30d]` via `ReservationRepository.findByUserIdOverlappingRange`, keeps visible statuses (`CONFIRMED`, `CHECKED_OUT`, `OVERDUE`, `RETURNED`) so cancelled/no-show bookings disappear from subscribed calendars, caches `CatalogApi.getAsset(assetId)` lookups once per asset (falling back to `Equipment #<id>` if missing), and prefixes `OVERDUE` summaries.
- **`IcsWriter`:** Pure RFC 5545 serializer emitting `VCALENDAR`/`VEVENT` text with strict `CRLF` endings, text escaping, 75-byte UTF-8 safe line folding, and `Europe/Bucharest` → UTC timestamp conversion.
- **`CalendarFeedController`:** Exposes `GET /api/v1/calendar/{token}.ics` returning `text/calendar;charset=UTF-8` with `Cache-Control: no-store` (or `404 CALENDAR_FEED_NOT_FOUND`).




