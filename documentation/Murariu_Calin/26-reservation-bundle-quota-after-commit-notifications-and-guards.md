# 26. Reservation Bundle Quota, After-Commit Notifications, and Transfer/Hold/Extend Security Guards

**Author:** Calin Murariu (@Swiorx)  
**Date:** 2026-11-09  
**Branch:** `fix/reservation-bundle-quota-and-edge-cases`  
**Closes:** #240  
**Refs:** #140, #228, #243

---

## Part 1: In simple terms

During thorough end-to-end testing of the reservation workflow against real PostgreSQL 18, our team identified six edge-case discrepancies:

1. **Bundle Quota Accounting:** A student can hold at most 2 active reservations. The team decided that a multi-asset kit/bundle counts as 1 reservation session. However, previous checks counted individual database rows. A student booking a 2-item bundle reached 2 rows and was blocked from booking anything else. Now, the system counts distinct booking sessions (`coalesce(bundle_id, id)`), allowing 1 bundle + 1 single reservation (2 sessions total).
2. **Transactional Notifications (`afterCommit`):** Previously, email notifications were dispatched directly inside database transactions. If an operation later threw an error or rolled back (e.g. invalid cancellation reason), phantom emails were still sent. All notification triggers are now deferred until after the active database transaction commits successfully.
3. **Anti-Probing & Transfer Anti-Spam:** Transfer offers previously returned `409 RESERVATION_LIMIT_REACHED` if the recipient had a full quota, allowing malicious users to probe other students' active bookings. The quota check now executes strictly when the recipient accepts the offer, and a daily limit (max 3 offers per reservation per 24 hours) prevents spam loops.
4. **Hold Cooldown Guard:** Prevents scripts from repeatedly holding and releasing the same slot in an infinite loop by requiring a 2-minute cooldown before the same student can hold that exact slot again.
5. **Bundle Extension Protection:** Individual items in a bundle booking cannot be extended independently (`BUNDLE_EXTEND_NOT_ALLOWED`), preventing kit desynchronization.
6. **Automatic Transfer Cancellation on Extension:** Extending a reservation automatically cancels any pending transfer offer (`cancelPendingTransferIfExists`), preventing recipients from receiving a modified duration.

---

## Part 2: Technical details

### 1. Distinct Session Counting in `ReservationRepository`
- Added query method:
  ```java
  @Query("""
      select count(distinct coalesce(cast(r.bundleId as string), cast(r.id as string)))
      from Reservation r
      where r.userId = :userId
        and r.status in :statuses
      """)
  long countBookingSessionsByUserIdAndStatusIn(
          @Param("userId") Long userId,
          @Param("statuses") Collection<ReservationStatus> statuses);
  ```
- Integrated into `createReservation`, `executeBundleBooking`, `acceptTransfer`, `createHold`, `confirmHold`, and `getMySummary`.

### 2. Post-Commit Notification Dispatcher
- Introduced helper `notifyUserAfterCommit(Long userId, String subject, String body)` leveraging Spring's `TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { void afterCommit() { ... } })`.
- Wired into all mutation workflows (`cancelBundle`, `createBundle`, `extendReservation`, `initiateTransfer`, `acceptTransfer`, `rejectTransfer`, `cancelTransfer`, `confirmHold`).
- Added `@Size(max = 255)` constraint on `cancelBundle` reason parameter in `ReservationController.java` to fail fast with 400 Bad Request instead of 500 DB column overflow.

### 3. Edge-Case Guards & Rate Limits
- In `initiateTransfer`: Added 24-hour rate limit via `countByReservationIdAndRequestedAtAfter(reservationId, dayAgo) >= 3` throwing 409 `TRANSFER_LIMIT_REACHED`. Removed quota probing at initiate time.
- In `createHold`: Added 2-minute cooldown check via `existsByUserIdAndAssetIdAndStartTimeAndEndTimeAndCreatedAtAfter` throwing 409 `HOLD_COOLDOWN_ACTIVE`.
- In `extendReservation`: Added guard checking `reservation.getBundleId() != null` throwing 409 `BUNDLE_EXTEND_NOT_ALLOWED`. Added invocation of `cancelPendingTransferIfExists`.

### Verification
- Full test suite `./gradlew test --tests "com.campus.reservation.*"`: 379 tests passed.
- Full project `./gradlew spotlessApply build`: BUILD SUCCESSFUL.
