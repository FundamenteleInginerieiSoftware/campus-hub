# Document 23: Bundle, Transfer & Hold Security Hardening

**Author:** Calin Murariu (Member 3 - Reservations & Reassignment Engine)  
**Date:** 2026-11-09  
**Issue:** #228 (`security(reservation): bundle/transfer/hold follow-ups from the post-merge pass`)

---

## Part 1: In Simple Terms (for non-programmers)

When multiple students book equipment together (bundles), give bookings to classmates (transfers), or temporarily hold equipment while choosing times (holds), subtle security and consistency bugs can arise:

1. **Bundle Privacy & Integrity:** If a student transferred one item from a multi-item package (bundle), the new student could previously view and cancel all items belonging to the original student. We fixed this: individual items in a bundle cannot be transferred away, and cancellation only cancels the items actually owned by the person making the request.
2. **Preventing Spam & Probing in Transfers:** Previously, a student could probe user IDs to see who has an active account or full reservation slots, and repeatedly send transfer offers that spam emails. We restricted transfers exclusively to active students (`Role.STUDENT`) and added database-level guards.
3. **Double-Click Transfer Protection:** Clicking "Transfer" twice fast could create two active transfer offers for the same reservation, corrupting future cancellations. We added a database unique constraint preventing more than one pending offer per booking.
4. **Coordinated Slot Holds:** When creating bundles or extending an existing booking, the system now checks if another student is holding any of the requested slots, preventing race conditions where one user's hold gets overwritten.
5. **Clean Cancellation of Pending Offers:** When a booking is cancelled, force-cancelled due to damaged equipment, moved to another asset, marked as a no-show, or cancelled via bundle, any pending transfer offer is cleanly auto-cancelled, and the recipient is immediately notified.

---

## Part 2: Technical Details (for developers & defense)

### 1. Database Schema & Concurrency Guards

- **Migration `V2026_11_09_1645__reservation_transfers_pending_unique_idx.sql`:**
  ```sql
  CREATE UNIQUE INDEX uq_reservation_transfers_pending 
  ON reservation_transfers(reservation_id) 
  WHERE status = 'PENDING';

  ALTER TABLE reservation_transfers 
      ADD CONSTRAINT fk_reservation_transfers_from_user FOREIGN KEY (from_user_id) REFERENCES users(id),
      ADD CONSTRAINT fk_reservation_transfers_to_user FOREIGN KEY (to_user_id) REFERENCES users(id);
  ```
  Prevents race conditions where simultaneous HTTP requests produce duplicate `PENDING` transfers for the same `reservation_id`, which previously caused `IncorrectResultSizeDataAccessException` when querying `findByReservationIdAndStatus`. Also adds missing FK constraints to `users(id)` per AGENTS.md §5.
- **Row Locking on Transfer Initiation:**
  `initiateTransfer` acquires a pessimistic row lock via `catalogApi.lockForBooking(reservation.getAssetId())` before querying `findByReservationIdAndStatus`. This ensures concurrent double-clicks serialize and return `409 TRANSFER_ALREADY_PENDING` instead of hitting DB unique constraint violations and producing 500 errors.

### 2. Multi-Asset Bundle Protections (#212, #214, #228)

- **Input Validation (`CreateMultiAssetReservationRequest`):**
  Switched to `Set<@NotNull Long> assetIds` and null-safe inspection avoiding `NullPointerException` during `.sorted()` operations.
- **Transfer Restriction:**
  `initiateTransfer` explicitly rejects bundle items with `BUNDLE_TRANSFER_NOT_ALLOWED`:
  ```java
  if (reservation.getBundleId() != null) {
      throw new ConflictException(
              "BUNDLE_TRANSFER_NOT_ALLOWED", "Items part of a bundle cannot be transferred individually");
  }
  ```
- **Safe Bundle Disassociation:**
  `Reservation.transferOwnership(newUserId)` resets `bundleId = null` to guarantee bundle isolation.
- **Per-Item Authorization:**
  `cancelBundle` only cancels items owned by `currentUser.id()` unless caller is admin.
- **Hold Checks on Multi-Asset Booking:**
  `createMultiAssetReservation` executes `existsActiveOverlap` across every requested asset in sorted lock order.

### 3. P2P Reservation Transfer Hardening (#214, #228)

- **Role Verification:**
  `initiateTransfer` validates recipient exists, is active, and possesses role `STUDENT`:
  ```java
  Optional<Role> activeRole = userApi.findActiveRole(recipientId);
  if (activeRole.isEmpty() || activeRole.get() != Role.STUDENT) {
      throw new NotFoundException("USER_NOT_FOUND", "Recipient student not found or inactive: " + recipientId);
  }
  ```
- **Admin-Initiated Transfer Attribution:**
  When an admin initiates a transfer on behalf of a student, `fromUserId` is set to `reservation.getUserId()` (not `currentUser.id()`), ensuring the recipient can properly accept the transfer.
- **Universal Transfer Cascade Cancellation & Actor Attribution:**
  Extracted helper `cancelPendingTransferIfExists(reservationId, now, actorId, auditReason, notificationMessage)` invoked in:
  - `cancelReservation` (attributes audit to `currentUser.id()`)
  - `cancelBundle` (attributes audit to `currentUser.id()`)
  - `checkOutReservation` (attributes audit to `currentUser.id()` to prevent orphaned transfers on early check-out)
  - `moveToAsset` (attributes audit to `null` for system equipment reassignment)
  - `forceCancel` (attributes audit to `null` for system force-cancellation)
  - `markNoShow` (attributes audit to `null` for autonomous no-show sweep)

### 4. Hold & Slot Rule Consistency (#219, #228)

- **Hold Overlap in Availability & Extension:**
  `checkAvailability` uses `findBusyAssetIds` which includes both reservations and active holds. `extendReservation` checks active holds before expanding end time.
- **Reassignment Priority Over Browsing Holds:**
  `isAssetFree` only checks confirmed reservations, ensuring emergency reassignments (`moveToAsset`) are never blocked or force-cancelled by temporary 1-minute browsing holds.
- **`confirmHold` Strict Validation & Student Quota:**
  Re-validates operating hours and future start time (`validateSlotRules`) at confirmation time, preventing elapsed holds from being confirmed into past reservations. Quota is strictly enforced on `hold.getUserId()` whenever the hold owner is a student—even if the confirmation action is performed by an administrator. Confirmation notifications are dispatched to `hold.getUserId()`.

### 5. Architectural Alignment & Decisions

- Updated `docs/decisions.md` clarifying that multi-asset bundles count as 1 lab session slot toward the student's 2-slot active quota (#140, #228).
- Complete test suite passing: 162 unit & integration tests in `com.campus.reservation.service.*`, 820+ tests overall.
