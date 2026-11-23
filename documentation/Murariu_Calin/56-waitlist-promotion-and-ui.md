# 56 — Waitlist Auto-Promotion Listener & Frontend UI (#59, PR 2)

## Part 1: In simple terms

Cancelling a reservation, cancelling a bundle, or marking a booking as no-show now automatically promotes the first eligible student waiting on that asset in FIFO order. Students can join the waitlist directly from the booking calendar when a slot is taken and view or leave their waitlist entries on **My reservations**.

---

## Part 2: Technical details

| File | Change |
|---|---|
| `SlotFreedEvent.java`, `WaitlistPromotionListener.java`, `WaitlistService.java` | Publishes `SlotFreedEvent(assetId)` from `cancelReservation`, `cancelBundle`, `cancelUpcomingReservationsForUser`, `markNoShow`, early `returnReservation`, `HoldService.releaseHold`, and `HoldService.sweepExpiredHolds`; `AFTER_COMMIT` listener invokes `WaitlistService.processPromotionsForAsset`, which executes one user-quota-locked promotion step per `REQUIRES_NEW` transaction to avoid cross-asset deadlocks. |
| `ReservationLifecycleService.java` | Calls `waitlistService.expireStaleEntries()` inside a `try/catch` during `runLifecycleSweep()` so expiration errors never skip no-show/overdue processing. |
| `WaitlistPromotionIT.java` | PostgreSQL `@SpringBootTest` proving cancellation, hold release, and hold expiry sweep promote the first active FIFO waiter, disabled waiters are skipped, and concurrent cross-asset promotions never deadlock. |
| `reservations.ts`, `CalendarBookingView.tsx`, `MyReservationsPage.tsx` | Added waitlist API helpers, "Waitlist" button on reservation/hold conflicts, and "My Waitlist" card with leave/cancel action. |
