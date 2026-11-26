# 56 — Waitlist Auto-Promotion Listener & Frontend UI (#59, PR 2)

## Part 1: In simple terms

Cancelling a reservation, cancelling a bundle, marking a booking as no-show, releasing/expiring a hold, returning a checked-out or overdue item, or repairing an asset back to `AVAILABLE` now automatically promotes the first eligible student waiting on that asset in FIFO order. Students can join the waitlist directly from the booking calendar when a slot is taken and view or leave their waitlist entries on **My reservations**.

---

## Part 2: Technical details

| File | Change |
|---|---|
| `SlotFreedEvent.java`, `WaitlistPromotionListener.java`, `WaitlistService.java` | Publishes `SlotFreedEvent(assetId)` from `cancelReservation`, `cancelBundle`, `cancelUpcomingReservationsForUser`, `markNoShow`, unconditional `returnReservation` (including `OVERDUE` returns), `HoldService.releaseHold`, and `HoldService.sweepExpiredHolds`; `AFTER_COMMIT` listener invokes `WaitlistService.processPromotionsForAsset`, which executes one user-quota-locked promotion step per `REQUIRES_NEW` transaction with system `actorId = null` on `PROMOTE_WAITLIST`. |
| `ReservationLifecycleService.java`, `WaitlistEntryRepository.java` | Calls `waitlistService.expireStaleEntries()` and then `promoteWaitingAssets()` (`findAssetIdsWithWaitingEntries` -> `processPromotionsForAsset` per asset, each wrapped in `try/catch`) during `runLifecycleSweep()` so repaired assets (`UNDER_MAINTENANCE -> AVAILABLE`) and missed listener events promote cleanly without skipping no-show/overdue processing. |
| `WaitlistPromotionIT.java` | PostgreSQL `@SpringBootTest` proving cancellation, hold release, hold expiry sweep, overdue return, and repaired-asset lifecycle sweep promote the first active FIFO waiter, disabled waiters are skipped, and concurrent cross-asset promotions never deadlock. |
| `reservations.ts`, `CalendarBookingView.tsx`, `MyReservationsPage.tsx` | Added waitlist API helpers, "Waitlist" button on `RESERVATION_CONFLICT` availability or `RESERVATION_CONFLICT`/`SLOT_HELD` API errors (clearing `waitlistError` on slot/duration/substitute change), and "My Waitlist" card with leave/cancel action. |
