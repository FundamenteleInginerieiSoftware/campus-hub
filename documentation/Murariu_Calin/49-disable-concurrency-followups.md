# 49 — Disable-Cancel Concurrency Follow-ups (#229, #310, #322)

## Part 1: In simple terms

Three small concurrency improvements from PR #310's review (tracked in #322 §2):
1. **Prevent `@Version` rollback during bulk disable-cancel:** When an admin disables a student and cancels their upcoming bookings, a student clicking **Extend** at the exact same moment could bump the booking's version number and cause the entire cancel batch to roll back. Loading the upcoming bookings with `FOR UPDATE` makes both operations wait their turn cleanly.
2. **Avoid deadlock in transfer acceptance:** `acceptTransfer` now locks the reservation row (`findByIdForUpdate`) before acquiring the recipient's quota lock, matching the lock order of bulk cancellation.
3. **Deterministic PostgreSQL lock test:** In `ReservationConcurrencyIT`, replaced `Thread.sleep(200)` with a poll against `pg_stat_activity` + `pg_locks` so Thread 1 disables the account at the exact instant Thread 2 is blocked on `pg_advisory_xact_lock`.

---

## Part 2: Technical details

| File | Change |
|---|---|
| `ReservationRepository.java` | Added `findUpcomingConfirmedByUserForUpdate(userId, cutoff)` with `@Lock(LockModeType.PESSIMISTIC_WRITE)` ordered by `r.id asc`. |
| `ReservationService.java` | Updated `cancelUpcomingReservationsForUser` to call `findUpcomingConfirmedByUserForUpdate`. |
| `TransferService.java` | Updated `acceptTransfer` to load the reservation with `findByIdForUpdate` before `lockUserQuota(userId)`. |
| `ReservationConcurrencyIT.java` | Replaced `Thread.sleep(200)` with `waitUntilBlockedOnUserQuotaLock` polling `pg_stat_activity` joined with `pg_locks`, and added `extendRunningDuringCancelUpcomingDoesNotRollBackCancel()`. |
| Unit tests | Updated `ReservationServiceTest` (`findUpcomingConfirmedByUserForUpdate`) and `TransferServiceTest` (`findByIdForUpdate`). |
