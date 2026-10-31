# 04 — Reservation Check-Out / Return Windows & Autonomous Lifecycle Jobs (Murariu Călin)

**Role:** Member 3, Reservations & Auto-Reassignment  
**Date:** 2026-10-26  
**Package:** `com.campus.reservation`  
**Branch / PR:** `feat/reservation-lifecycle-jobs` (PR #60)  

---

## Part 1: In simple terms

Once a student reserves laboratory equipment, the reservation goes through a physical lifecycle at the lab desk:
1. **Check-Out Window (`[-15 min, +30 min]` around `startTime`):** Lab staff hand out the equipment and mark it `CHECKED_OUT`. Students can pick up their item up to 15 minutes early or up to 30 minutes after the slot begins.
2. **Return (`RETURNED`):** When the student brings the equipment back (`CHECKED_OUT` or `OVERDUE`), the administrator marks it `RETURNED` and records the exact return timestamp.
3. **Automatic No-Show Expiration (`NO_SHOW`):** If a student still hasn't picked up the equipment 30 minutes after `startTime`, a scheduled background job automatically marks the reservation `NO_SHOW` so the equipment is no longer blocked for the rest of the slot.
4. **Automatic Overdue Detection & Preventive Cascade Reassignment (`OVERDUE`):** If a checked-out item is not returned by `endTime`, the background job marks it `OVERDUE`, alerts the student, and checks whether another student has an upcoming reservation on that same asset within the next 60 minutes. If so, it proactively moves the upcoming student's booking to a free same-model substitute asset so they are not left waiting at the lab desk.

---

## Part 2: Technical details

### 1. REST Endpoints (`AdminReservationLifecycleController`)

Desk check-out and return operations live in `AdminReservationController` (documented in [Doc 07](./07-admin-desk-reservations.md), PR #80). This PR provides the autonomous background execution and manual trigger endpoint:

| Method & Path | Access | Controller | Description | Error Codes |
|---|---|---|---|---|
| `POST /api/v1/admin/reservations/jobs/run-lifecycle` | Admin (`ROLE_ADMIN`) | `AdminReservationLifecycleController` | Manually triggers the `NO_SHOW` + `OVERDUE` cascade sweep and returns `LifecycleSweepSummary` | `401 UNAUTHORIZED`, `403 FORBIDDEN` |

---

### 2. Scheduled Sweep & Preventive Cascade (`ReservationLifecycleService`)

- **`@Scheduled(fixedDelayString = "${app.reservation.lifecycle-sweep-ms:300000}", initialDelayString = "${app.reservation.lifecycle-initial-delay-ms:60000}")`** runs every 5 minutes using the shared `Clock` (`Europe/Bucharest`). The sweep method itself is intentionally **not** annotated with `@Transactional`; instead, each reservation state change is executed in its own transaction through the `ReservationService` Spring proxy (`markNoShow`, `markOverdue`, `moveToAsset`). This ensures that an optimistic-lock conflict (`@Version`) on one concurrently edited reservation only skips that single booking without rolling back the entire batch, and notification emails are sent only after that booking's transaction has committed.
- **Step 1 — No-Show Release (`processNoShows`):**
  - Queries `reservationRepository.findConfirmedBeforeCutoff(now.minusMinutes(30))` using a strict `< :cutoff` comparison so a student arriving at exact `startTime + 30m` can still check out.
  - Calls `reservationService.markNoShow(r.getId())` (which transitions to `NO_SHOW` and records `AUTO_NO_SHOW` in `AuditService` inside its own transaction), then notifies the student only if the transition committed (`true`).
- **Step 2 — Overdue Detection & Preventive Cascade (`processOverdueAndCascade`):**
  - Queries `reservationRepository.findCheckedOutPastEnd(now)`.
  - Calls `reservationService.markOverdue(overdue.getId())` (which transitions to `OVERDUE` and records `MARK_OVERDUE` in `AuditService` inside its own transaction), then notifies the late holder.
  - Queries `reservationRepository.findByStatus(ReservationStatus.OVERDUE)` so that all active unreturned equipment is re-evaluated for imminent bookings on every sweep (not only at the moment of status transition), ensuring upcoming bookings 1 hour later are also proactively protected.
  - For each overdue asset, queries `reservationRepository.findUpcomingConfirmedByAsset(overdueAssetId, now)` for bookings starting within the next 60 minutes (`now.plusMinutes(60)`).
  - For each threatened booking, `tryPreventiveMove` searches `catalogApi.findAvailableByModel(...)` and calls `reservationService.moveToAsset(...)` (which acquires a pessimistic row lock via `catalogApi.lockForBooking` and returns `boolean` indicating whether the reservation changed). Only when `moved == true` is the affected student notified.
