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
4. **Hold Cooldown Guard:** Prevents scripts from repeatedly holding and releasing the same slot in an infinite loop. It enforces a 2-minute cooldown on any overlapping slot for the same student and equipment (`startTime < newEnd and endTime > newStart and createdAt > threshold`), closing the bypass where a script could slightly adjust times (e.g. 10:00-11:30 instead of 10:00-12:00) to defeat exact-match cooldowns.
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

### 3. Edge-Case Guards, Rate Limits & Security Considerations
- In `initiateTransfer`:
  - Added 24-hour rate limit via `countByReservationIdAndRequestedAtAfter(reservationId, dayAgo) >= 3` throwing 409 `TRANSFER_LIMIT_REACHED`.
  - Removed quota probing at initiate time: checking recipient quota at initiation leaked whether the target student had 2 active bookings. Now quota is validated upon acceptance.
  - **Design Note on 404 USER_NOT_FOUND:** If `toUserId` does not exist or is not an active student, `userApi.findActiveRole` returns empty, resulting in `NotFoundException("User not found: " + toUserId)`. This is an accepted RESTful design trade-off: standard resource existence checks return 404, while sensitive operational state (reservation count and schedule) is strictly shielded.
- In `createHold`:
  - Enforced a 2-minute cooldown check via `existsRecentHoldOverlap` in `ReservationHoldRepository`:
    ```sql
    select (count(h) > 0) from ReservationHold h
    where h.userId = :userId
      and h.assetId = :assetId
      and h.startTime < :endTime
      and h.endTime > :startTime
      and h.createdAt > :threshold
    ```
    This prevents timing nudges from bypassing the cooldown and is verified with a comprehensive `@DataJpaTest` suite (`ReservationHoldRepositoryTest`).
- In `extendReservation`: Added guard checking `reservation.getBundleId() != null` throwing 409 `BUNDLE_EXTEND_NOT_ALLOWED`. Added invocation of `cancelPendingTransferIfExists`.

### Verification & PIT Mutation Testing
- Full test suite: `./gradlew test --tests "com.campus.reservation.*"` passed (383 tests).
- Full project: `./gradlew spotlessApply build` passed (BUILD SUCCESSFUL, 971 unit & integration tests).
- **PIT Mutation Testing (`./gradlew pitest`):**
  - Project-wide mutation score: 97% killed (1089/1123 mutations), 98% test strength, 96% line coverage.
  - Eliminated all real business rule survivors in `ReservationService`:
    - `bookBundle` slot rules enforcement (`validateSlotRules`).
    - `bookBundle` null element rejection in asset IDs.
    - `bookBundle` 5-item boundary condition.
    - `bookBundle` student vs admin quota evaluation via `userApi.findActiveRole`.
    - `initiateTransfer` row-locking verification via `catalogApi.lockForBooking`.
    - `checkOutReservation` transfer auto-cancellation audit attribution.
