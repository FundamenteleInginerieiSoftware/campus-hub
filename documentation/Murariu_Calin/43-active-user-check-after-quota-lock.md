# 43 — Active User Re-check After Per-User Quota Lock (#229)

## Part 1: In simple terms

When an admin disables a student's account, the system blocks future logins and cancels their upcoming reservations. However, there was a narrow race window: if a student clicked "Book" right before the admin disabled their account, the booking request had already passed the login-token check and could sit waiting on the student's quota lock while the cancellation ran. Once the lock was released, the booking resumed and saved a brand-new `CONFIRMED` reservation for a disabled account.

To close this gap, every student booking, multi-asset bundle, slot hold, hold confirmation, and transfer acceptance now re-checks that the account is still active *after* acquiring the per-user quota lock. If the account was disabled in the meantime, the request is rejected with `403 USER_DISABLED` and nothing is saved.

## Part 2: Technical details

### Changes

| File | Change |
|---|---|
| `backend/src/main/java/com/campus/reservation/service/ReservationServiceSupport.java` | Added package-private `requireActiveUserAfterLock(UserApi, Long)` that calls `userApi.findActiveRole(userId)` and throws `ForbiddenException("USER_DISABLED", ...)` (403) when empty. |
| `backend/src/main/java/com/campus/reservation/service/ReservationService.java` | Called `requireActiveUserAfterLock(userApi, userId)` immediately after `reservationRepository.lockUserQuota(userId)` in `createReservation` and `executeBundleBooking` (covers both `createMultiAssetReservation` and `bookBundle`). |
| `backend/src/main/java/com/campus/reservation/service/HoldService.java` | Called `requireActiveUserAfterLock(userApi, ...)` immediately after `lockUserQuota` in `createHold` (`userId`) and `confirmHold` (`studentId`). |
| `backend/src/main/java/com/campus/reservation/service/TransferService.java` | Called `requireActiveUserAfterLock(userApi, userId)` immediately after `lockUserQuota(userId)` in `acceptTransfer` so a recipient disabled while waiting on the lock cannot take ownership of a reservation. |

### Why after `lockUserQuota`?

`ReservationService.cancelUpcomingReservationsForUser` acquires `lockUserQuota(userId)` (PostgreSQL transaction-scoped advisory lock key `1`). Checking `userApi.findActiveRole(userId)` *after* `lockUserQuota(userId)` serializes booking writes behind any in-flight cancellation/disable transaction and reads the committed `users.enabled` state under Read Committed isolation.

### Verification

| Test | Scope |
|---|---|
| `ReservationServiceTest` | Unit tests for `createReservation` and `bookBundle` verifying `lockUserQuota` -> `findActiveRole` call order and `403 USER_DISABLED` with zero saves. |
| `ReservationHoldServiceTest` | Unit tests for `createHold` and `confirmHold` verifying `403 USER_DISABLED` after `lockUserQuota`. |
| `TransferServiceTest` | Unit test for `acceptTransfer` verifying recipient disabled while waiting on `lockUserQuota` gets `403 USER_DISABLED`. |
| `ReservationConcurrencyIT.bookingBlockedOnQuotaLockIsRejectedWhenUserIsDisabled` | Two-thread PostgreSQL integration test: Thread 1 holds `lockUserQuota(userId)` and disables the student; Thread 2 blocks in `createReservation`, wakes up after commit, receives `USER_DISABLED`, and leaves `0` active reservations. |
