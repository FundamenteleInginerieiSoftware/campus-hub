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
  ├── 5. User Quota Verification (countByUserIdAndStatusIn < 2; bypassed by admins)
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
