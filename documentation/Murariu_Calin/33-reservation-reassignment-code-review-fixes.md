# 33. Fixes from the #267 code review

**Author:** Calin Murariu (@Swiorx)
**Branch:** `fix/reservation-reassignment-code-review-267`
**PR:** #270 · **Refs:** #267

---

## Part 1: In simple terms

A teammate's code review (#267) found ten small problems in bookings and in the reassignment engine. This PR fixes all of them:

| # | Problem | Now |
|---|---|---|
| 1 | A short database lock conflict while moving a booking made the engine cancel the student's booking | It tries the next substitute; a real bug stops the move and leaves the booking where it was |
| 2 | Clicking another slot while a hold was being created could keep a hold on the wrong slot | Slot picking is blocked while a request runs; a wrong hold is released and the user sees a message |
| 3 | The engine could move a booking into a slot another student was holding | Holds count as busy, except the booking owner's own hold |
| 4 | A bundle booking left the student's holds open | They are confirmed, like for a single booking |
| 5 | Cancelling or checking out a finished booking gave a misleading "wrong time" error | The status is checked first |
| 6 | One failing asset stopped the overdue cascade for all others | Each asset is handled on its own |
| 7 | A desk return at the same moment as the overdue job gave an error | The second one waits and then sees the first one's result |
| 8-10 | My reservations: wrong error banner, slow first load, router state wiped | Fixed |

---

## Part 2: Technical details

| Area | Change |
|---|---|
| `ReassignmentBookingProcessor` | Catches only concurrency conflicts (`ConcurrencyFailureException` tree, SQLSTATE 40P01/55P03/40001) and tries the next candidate; other errors are rethrown and logged once in `ReassignmentService.handle` |
| `ReservationService.isAssetFree` | Counts every active hold. `moveToAsset` uses a private variant that ignores holds of the booking's owner (`r.getUserId()`), not of the logged-in caller |
| Lock order | `findByIdForUpdate` (reservation row) then `lockForBooking` (asset row) in check-out, return, `markOverdue`, extend, transfer. Create, hold and move lock only the asset, so there is no cycle |
| `ReservationLifecycleService` | `try/catch` per overdue asset, logged with the stack trace; `runLifecycleSweep` has no outer transaction, so nothing is marked rollback-only |
| Tests | `ReservationServiceRulesTest` stubs `findByIdForUpdate` where the code now uses it |

**Side effect worth knowing:** Matei's `ProjectService.details` and `ProjectReservationService` call `isAssetFree`, so units other students are holding no longer count as free there. That is the intended behaviour.
