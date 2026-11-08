# 17. Active Reservation Duration Extension

**Author:** Murariu Călin (Member 3 - Reservations & Reassignment)  
**Pull Request:** [#213](https://github.com/FundamenteleInginerieiSoftware/campus-hub/pull/213)  

---

## Part 1: In simple terms

Students often begin a laboratory session with an initial estimate of how long they will need equipment, but lab experiments frequently run longer than expected:

1. **Seamless Continuation without Re-booking:**  
   Rather than letting a reservation expire, risking that another student immediately books the device, or having to create a separate subsequent reservation, students can extend their active reservation directly before it concludes.
2. **Safe from Conflicts:**  
   The system guarantees that an extension never steps on someone else's toes. Before granting an extension, the system locks the equipment and verifies that no other student has already reserved the subsequent time slot. If another student is already scheduled, the extension is rejected gracefully and the original reservation remains completely unchanged.
3. **Strict Compliance with Lab Policies:**  
   Extensions cannot be used to bypass laboratory rules. Total duration is capped at 4 hours, extensions must stay within opening hours (08:00–20:00, Monday through Friday), and equipment undergoing repairs or marked retired cannot be extended.

---

## Part 2: Technical details

### 1. Architecture & Execution Flow

```
[ POST /api/v1/reservations/{id}/extend ]
                     │
                     ▼
             ReservationService
  ├── 1. loadOwnedOrAdmin(id) -> 404 IDOR protection
  ├── 2. Status verification (CONFIRMED or CHECKED_OUT only)
  ├── 3. Expiration guard (now < currentEndTime)
  ├── 4. No-show check (if CONFIRMED, now <= startTime + 30m)
  ├── 5. Temporal rules (newEndTime > currentEndTime, :00/:30 alignment, duration <= 4h, Mon-Fri 08:00-20:00, horizon <= 14d)
  ├── 6. Pessimistic lock: catalogApi.lockForBooking(assetId)
  ├── 7. Operational status check: catalogApi.isBookable(assetId)
  ├── 8. Collision detection: reservationRepository.existsOverlap on [currentEndTime, newEndTime]
  ├── 9. Mutate entity: reservation.extendEndTime(newEndTime)
  ├── 10. Persist entity: reservationRepository.save(reservation)
  └── 11. Audit logging: auditService.record("EXTEND_RESERVATION")
```

---

### 2. Business Rules Matrix

| Rule | Implementation Check | Error Code | HTTP Status |
|---|---|---|---|
| **Ownership** | `userId == currentUser.id() \|\| currentUser.isAdmin()` | `RESERVATION_NOT_FOUND` | `404 Not Found` |
| **Status** | `CONFIRMED` or `CHECKED_OUT` | `INVALID_RESERVATION_STATUS_TRANSITION` | `409 Conflict` |
| **Expiry** | `now.isBefore(reservation.getEndTime())` | `RESERVATION_ALREADY_EXPIRED` | `409 Conflict` |
| **Check-out Deadline** | If `CONFIRMED`, `now <= startTime + 30m` | `CHECKOUT_WINDOW_CLOSED` | `409 Conflict` |
| **Time Range** | `newEndTime > reservation.getEndTime()` | `INVALID_TIME_RANGE` | `409 Conflict` |
| **Slot Alignment** | Minutes :00 or :30, seconds & nanos 0 | `SLOT_NOT_ALIGNED` | `409 Conflict` |
| **Maximum Duration** | Total duration `startTime` -> `newEndTime` <= 4h | `INVALID_DURATION` | `409 Conflict` |
| **Opening Hours** | Same day, Mon-Fri, ending at or before 20:00 | `OUTSIDE_OPENING_HOURS` | `409 Conflict` |
| **Booking Horizon** | `newEndTime <= now.plusDays(14)` | `BOOKING_TOO_FAR_AHEAD` | `409 Conflict` |
| **Asset State** | `catalogApi.isBookable(assetId)` | `ASSET_NOT_BOOKABLE` | `409 Conflict` |
| **Collision Check** | `existsOverlap(assetId, oldEnd, newEnd, ACTIVE, id)` | `EXTENSION_CONFLICT` | `409 Conflict` |

---

### 3. REST Endpoint

```
POST /api/v1/reservations/{id}/extend
Content-Type: application/json

{
  "newEndTime": "2026-11-03T16:00:00"
}
```

Response: `200 OK` with updated `ReservationResponse`.
