# Equipment Availability Endpoint (Murariu Călin)

**Role:** Member 3, Reservations & Auto-Reassignment  
**Date:** 2026-10-31  
**Package:** `com.campus.reservation.web` & `com.campus.reservation.service`  
**Branch / PR:** `feat/reservation-availability`  
**See also:** `AGENTS.md` §5 (Availability belongs in reservation, not catalog), `docs/api-contract.md` §6

---

## Part 1: In simple terms

When a student browses lab equipment or prepares a booking, they need to know whether an oscilloscope or 3D printer is actually free during their desired lab slot before clicking "Book".

### Why Availability Belongs in Reservation (Not Catalog)
In the database, an asset's operational status is simply `AVAILABLE`, `UNDER_MAINTENANCE`, or `RETIRED`. The catalog module does not know about time slots or student schedules (`AGENTS.md` §5 forbids catalog from depending on reservations). An asset marked `AVAILABLE` might already be booked by another student from 14:00 to 16:00. Therefore, **real-time temporal availability can only be computed by the reservation engine**.

### The Solution: `GET /api/v1/reservations/availability`
We introduced an endpoint that supports two primary use cases:
1. **Specific Equipment Query (`?assetId=10&startTime=...&endTime=...`):**
   Checks whether that exact asset exists, is operationally bookable, and has no overlapping active bookings in that time window.
   If unavailable, returns the exact machine-readable reason (`RESERVATION_CONFLICT` or `ASSET_NOT_BOOKABLE`).
2. **Category-Wide Query (`?categoryId=5&startTime=...&endTime=...`):**
   In a single efficient database query, finds all active bookings overlapping the window across the whole campus (`findBusyAssetIds`), filters the operational assets in that category, and returns the list of all currently free asset IDs.

---

## Part 2: Technical details

### Files Modified & Created

| File | Change | Description |
|---|---|---|
| `backend/src/main/java/com/campus/reservation/web/AvailabilityResponse.java` | New DTO | Response record with `available`, `availableAssetIds`, and `conflictReason` |
| `backend/src/main/java/com/campus/reservation/repository/ReservationRepository.java` | Method added | `findBusyAssetIds(start, end, statuses)` using JPQL `select distinct r.assetId` |
| `backend/src/main/java/com/campus/reservation/service/ReservationService.java` | Logic added | `checkAvailability(assetId, categoryId, start, end)` with full slot validation and O(1) Set lookup |
| `backend/src/main/java/com/campus/reservation/web/ReservationController.java` | Endpoint added | `GET /api/v1/reservations/availability` with Swagger annotations |
| `docs/api-contract.md` | Contract entry | Documents request parameters, response JSON, and status codes |

---

### Data Flow & Execution Order

```
GET /api/v1/reservations/availability?assetId=1&startTime=...&endTime=...
                      │
                      ▼
            ReservationController
                      │
                      ▼
              ReservationService
  ├── 1. Parameter validation (must provide either assetId or categoryId, but not both)
  ├── 2. validateSlotRules (Mon-Fri 08:00-20:00, :00/:30 alignment, 30m-4h duration, max 14 days)
  ├── 3. If assetId != null:
  │      ├── catalogApi.getAsset(id) (404 ASSET_NOT_FOUND if unknown)
  │      ├── catalogApi.isBookable(id) (false -> ASSET_NOT_BOOKABLE)
  │      └── isAssetFree(id, start, end, null) (false -> RESERVATION_CONFLICT)
  └── 4. If categoryId != null:
         ├── catalogApi.findAvailableByCategory(categoryId, -1L)
         ├── reservationRepository.findBusyAssetIds(start, end, ACTIVE_STATUSES)
         └── Return free asset IDs = candidateIds - busyIds
```

---

### Tests & Verification

- **Service Unit Tests (`ReservationServiceTest`):**
  - Specific asset free returns `available: true`
  - Specific asset booked returns `available: false, conflictReason: "RESERVATION_CONFLICT"`
  - Asset under maintenance returns `available: false, conflictReason: "ASSET_NOT_BOOKABLE"`
  - Unknown asset throws `404 NotFoundException`
  - Category sweep correctly filters out busy asset IDs
  - Missing both parameters throws `BusinessRuleException("MISSING_PARAMETER")`, providing both throws `BusinessRuleException("CONFLICTING_PARAMETERS")`
- **Controller Slice Tests (`ReservationControllerTest`):**
  - `GET /api/v1/reservations/availability` returns `200 OK` with JSON
  - Missing required time parameters returns `400 Bad Request`
  - Category query returns `200 OK` with array of available asset IDs
