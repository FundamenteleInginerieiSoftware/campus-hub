# Admin Desk Reservations: Check-Out, Return & Search (Murariu Călin)

**Role:** Member 3, Reservations & Auto-Reassignment  
**Date:** 2026-10-31  
**Package:** `com.campus.reservation.web` & `com.campus.reservation.service`  
**Branch / PR:** `feat/reservation-admin-endpoints`  
**See also:** `AGENTS.md` §1 (Calin owns reservation lifecycle & desk operations), `docs/api-contract.md` §6

---

## Part 1: In simple terms

When a student arrives at the campus laboratory to use reserved equipment (such as an oscilloscope or robotic kit), the lab administrator needs a digital desk interface to hand over the device, verify returns, and oversee active bookings.

### Why Check-Out & Return Matter
A booking merely holds an asset on paper. The check-out operation verifies that the student physically showed up and received the physical device. The return operation marks that the device has been safely returned to the inventory.

### Strict Check-Out Window Rule
To prevent students from picking up devices hours early (disrupting earlier lab sessions) or showing up late when the slot should have been marked as a no-show:
- **Allowed Pickup Window:** From **15 minutes before slot start** until **30 minutes after slot start**.
- If a student arrives too early or too late, the desk rejects the pickup with `CHECKOUT_WINDOW_CLOSED` (409 Conflict).
- If they do not pick up the item within 30 minutes after start time, the automated scheduler cascades it to `NO_SHOW` and frees up the asset.

### Multi-Criteria Admin Search
The lab administrator can query all campus reservations using dynamic filters:
- Filter by reservation status (`CONFIRMED`, `CHECKED_OUT`, `RETURNED`, `OVERDUE`, `CANCELLED`).
- Filter by specific student (`userId`) or equipment item (`assetId`).
- Filter by specific date (e.g. today's schedule for desk dispatch).
- Full pagination (`page`, `size`) sorted chronologically by slot start time.

---

## Part 2: Technical details

### Files Modified & Created

| File | Change | Description |
|---|---|---|
| `backend/src/main/java/com/campus/reservation/repository/ReservationRepository.java` | Modified | Extends `JpaSpecificationExecutor<Reservation>` to support dynamic predicate queries without custom JPQL tricks |
| `backend/src/main/java/com/campus/reservation/service/ReservationService.java` | Added methods | `checkOutReservation(id)`, `returnReservation(id)`, and `getAdminReservations(...)` using Spring Data `Specification` |
| `backend/src/main/java/com/campus/reservation/web/AdminReservationController.java` | Created | REST controller under `/api/v1/admin/reservations` for desk check-out, return, and search |
| `backend/src/test/java/com/campus/reservation/service/ReservationServiceTest.java` | Added tests | 8 unit tests covering check-out window bounds, state exceptions, returns from CHECKED_OUT & OVERDUE, and specification search |
| `backend/src/test/java/com/campus/reservation/web/AdminReservationControllerTest.java` | Created | 5 slice tests verifying HTTP status codes (200, 404, 409) and error payloads |
| `docs/api-contract.md` | Updated | Added `GET /api/v1/admin/reservations` to reservation contract table |
| `documentation/Murariu_Calin/README.md` | Updated | Added entry for Document 07 |

---

### Check-Out Window Logic

```java
LocalDateTime now = LocalDateTime.now(clock);
LocalDateTime windowStart = reservation.getStartTime().minusMinutes(15);
LocalDateTime windowEnd = reservation.getStartTime().plusMinutes(30);

if (now.isBefore(windowStart) || now.isAfter(windowEnd)) {
    throw new BusinessRuleException(
            "CHECKOUT_WINDOW_CLOSED",
            "Check-out is only allowed between 15 minutes before slot start and 30 minutes after slot start");
}
```

### State Transition Matrix

```
[ CONFIRMED ] ──(admin checkOut)──> [ CHECKED_OUT ]
      │                                    │
      │                               (scheduled)
      │                                    │
      ▼                                    ▼
 [ NO_SHOW ]                           [ OVERDUE ]
                                           │
                                    (admin return)
                                           │
                                           ▼
                                      [ RETURNED ]
```

- `checkOut`: valid only from `CONFIRMED`.
- `returnItem`: valid from `CHECKED_OUT` or `OVERDUE`.
- Any invalid state transition throws `InvalidStateTransitionException("INVALID_RESERVATION_STATUS_TRANSITION")` (mapped to HTTP 409 Conflict).

---

### Dynamic Admin Query with Spring Data Specification

Per `AGENTS.md` §5, JPQL queries must avoid `(:param is null or ...)` tricks because they fail or degrade performance on PostgreSQL. Instead, dynamic criteria are composed via JPA `Specification`:

```java
Specification<Reservation> spec = (root, query, cb) -> {
    List<Predicate> predicates = new ArrayList<>();
    if (status != null) predicates.add(cb.equal(root.get("status"), status));
    if (userId != null) predicates.add(cb.equal(root.get("userId"), userId));
    if (assetId != null) predicates.add(cb.equal(root.get("assetId"), assetId));
    if (date != null) {
        predicates.add(cb.lessThan(root.get("startTime"), date.atTime(23, 59, 59, 999999999)));
        predicates.add(cb.greaterThan(root.get("endTime"), date.atStartOfDay()));
    }
    return cb.and(predicates.toArray(new Predicate[0]));
};
```
