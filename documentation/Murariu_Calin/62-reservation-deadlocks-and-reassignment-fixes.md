# 62. Reservation Deadlocks, Stale Transfer Notifications & Transient Concurrency Retry (#362 PR 1)

**Author:** Călin Murariu ([@Swiorx](https://github.com/Swiorx))  
**Issue:** Refs #362 (§1 Critical, §2 High, §3 disabled-admin race, §4 overlapping own-hold lock)  
**Modules:** `reservation/`, `reassignment/`

---

## Part 1: In simple terms

During live multi-user testing, four subtle concurrency problems were discovered when several actions happen in the exact same millisecond:

1. **Three-way database freeze (deadlock):** Booking a new item, extending an existing booking, and disabling a user's account acquired database locks in opposite orders. Under simultaneous requests, PostgreSQL had to abort one transaction with a deadlock error (`40P01`).
2. **Hold confirmation freeze:** Confirming a temporary 1-minute slot hold while a direct booking ran on the same asset also locked the hold and the asset in opposite orders (`Hold -> Asset` vs `Asset -> Hold`).
3. **Notification sent to the old owner after a transfer:** If Student A transferred a booking to Student B at the same moment the equipment broke down or ran overdue, the reassignment engine still emailed Student A (the old owner from its initial read) instead of Student B (the new owner who actually owns the booking).
4. **Accidental permanent cancellation on a temporary lock hiccup:** When the reassignment engine tried to move a booking to a free substitute and hit a brief database lock contention error, it treated the substitute as taken and fell through to force-cancelling the student's reservation instead of retrying.
5. **Disabled admin race & partial-overlap holds:** Disabled administrator accounts could slip past booking/hold/extension checks without waiting behind the user quota lock, and direct bookings only auto-confirmed own holds with exact start/end matches without locking the hold row first.

We resolved all five by introducing one single, strict locking order across the entire reservation system (`Quota -> Reservation -> Asset -> Transfer -> Hold`), returning the real locked owner from reassignment moves and force-cancels, and adding a short retry loop for temporary database lock errors that never falls back to permanent cancellation.

---

## Part 2: Technical details

### 1. Global Lock DAG (`Quota -> Reservation -> Asset -> Transfer -> Hold`)

| Operation | Previous lock order | New lock order (#362 PR 1) |
|---|---|---|
| `ReservationService.createReservation` | `Asset -> Quota -> Hold (unlocked)` | `Quota -> Asset -> Hold (FOR UPDATE ORDER BY id ASC)` |
| `ReservationService.executeBundleBooking` | `Assets (ASC) -> Quota -> Hold (unlocked)` | `Quota -> Assets (ASC) -> Hold (FOR UPDATE ORDER BY id ASC)` |
| `ReservationService.extendReservation` | `Reservation -> Asset -> Transfer` | `Quota (caller) -> Reservation -> Asset -> Transfer` |
| `HoldService.createHold` | `Asset -> Quota` | `Quota -> Asset` |
| `HoldService.confirmHold` | `Hold -> Asset -> Quota` | `Quota (sorted ascending if admin confirms other user's hold) -> Asset -> Hold` |
| `TransferService.acceptTransfer` | `Reservation -> Transfer -> Quota` | `Quota -> Reservation -> Transfer` |
| `ReservationService.cancelUpcomingReservationsForUser` | `Quota -> Reservations (ASC) -> Transfer` | `Quota -> Reservations (ASC) -> Transfer` (unchanged) |

- **Scalar pre-lookup in `HoldService.confirmHold`:** Added `ReservationHoldRepository.findLockTargetById(Long id)` returning a lightweight `HoldLockTarget(assetId, userId)` projection so `confirmHold` can check ownership and acquire `Quota -> Asset` *before* calling `findByIdForUpdate(holdId)`.
- **Disabled-admin guard (`#362 §3`):** `createReservation`, `executeBundleBooking`, `extendReservation`, `createHold`, `confirmHold`, and `acceptTransfer` now acquire `lockUserQuota(...)` and invoke `ReservationServiceSupport.requireActiveUserAfterLock(userApi, ...)` for **all** callers including `Role.ADMIN`. Admins still bypass the student quota count cap (`>= 2`) and hold cooldown/active-hold caps, but cannot bypass the active-account lock barrier. Dead helper `ReservationServiceSupport.isCurrentUserAdmin` was removed.
- **Overlapping own-hold confirmation (`#362 §4`):** Extracted `ReservationServiceSupport.confirmOwnedHoldsForSlots`, backed by `ReservationHoldRepository.findActiveOverlappingForUserAndAssetsForUpdate` (`FOR UPDATE ORDER BY h.id ASC`), matching any overlapping active own hold (`startTime < :end AND endTime > :start`) so a concurrent `releaseHold` serializes cleanly instead of failing with `@Version` conflict.

### 2. Locked Owner Return & Notifications (`#362 §2.1`)

- Changed `ReservationApi.moveToAsset` and `ReservationApi.forceCancel` from `boolean` to `Optional<Long>`, returning `Optional.of(r.getUserId())` read *under* `findByIdForUpdate(reservationId)` (or `Optional.empty()` if the booking was no longer `CONFIRMED` or no longer on `expectedSourceAssetId`).
- Updated `ReassignmentBookingProcessor.ReassignmentExecutionResult` to carry `Long ownerUserId`, so `ReassignmentService.handle` notifies `result.ownerUserId()` instead of stale `booking.userId()`.
- Updated `ReservationLifecycleService.tryPreventiveMove` to notify `movedOwnerId.get()` returned by `moveToAsset` instead of stale `threatened.getUserId()`.

### 3. Bounded Transient Concurrency Retry (`#362 §2.2`)

- In `ReassignmentBookingProcessor.processSingleBooking`, advancing to the next substitute candidate is now strictly reserved for `SubstituteNoLongerFreeException` (direct or wrapped).
- Transient database concurrency errors (`ConcurrencyFailureException` or PostgreSQL SQLStates `40P01`, `55P03`, `40001`) retry the **same** candidate up to `MAX_TRANSIENT_CONCURRENCY_RETRIES = 2` times with brief backoff (`25 ms * (attempt + 1)`). If retries exhaust, the exception is rethrown so `ReassignmentService` logs and skips the booking without ever falling through to `forceCancel`.

### 4. Verification

- **Unit tests:** Updated and expanded `ReservationServiceTest`, `ReservationHoldServiceTest`, `TransferServiceTest`, `ReservationServiceRulesTest`, `ReservationLifecycleServiceTest`, `ReservationLifecycleFailureTest`, `ReassignmentBookingProcessorTest`, and `ReassignmentServiceTest`.
- **PostgreSQL 18 integration tests (`ReservationConcurrencyIT`):**
  - `threeWayCreateCancelUpcomingExtendIsDeadlockFree` (5 rounds of 3-thread race, zero `40P01`).
  - `confirmHoldAndDirectCreateReservationOnSameSlotIsDeadlockFree` (5 rounds of `confirmHold` vs direct `createReservation`, zero `40P01`, exactly 1 `CONFIRMED` booking).
  - `disabledAdminIsRejectedOnBookingHoldConfirmAndExtend` (real advisory-lock barrier test for disabled `ADMIN` across all 4 entry points).
  - `moveToAssetReturnsNewLockedOwnerAfterConcurrentTransferAcceptance` (verifies post-transfer owner is returned).
- **Build:** `cd backend && ./gradlew spotlessApply build` — `BUILD SUCCESSFUL`.
