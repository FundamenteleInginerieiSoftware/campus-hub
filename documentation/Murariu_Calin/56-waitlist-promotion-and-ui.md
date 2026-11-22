# 56 — Waitlist Auto-Promotion Listener & Frontend UI (#59, PR 2)

## Part 1: In simple terms

Cancelling a reservation, cancelling a bundle, or marking a booking as no-show now automatically promotes the first eligible student waiting on that asset in FIFO order. Students can join the waitlist directly from the booking calendar when a slot is taken and view or leave their waitlist entries on **My reservations**.

---

## Part 2: Technical details

| File | Change |
|---|---|
| `SlotFreedEvent.java`, `WaitlistPromotionListener.java` | Publishes `SlotFreedEvent(assetId)` from `cancelReservation`, `cancelBundle`, `cancelUpcomingReservationsForUser`, and `markNoShow`; `AFTER_COMMIT` + `REQUIRES_NEW` listener invokes `WaitlistService.processPromotionsForAsset`. |
| `ReservationLifecycleService.java` | Calls `waitlistService.expireStaleEntries()` during `runLifecycleSweep()`. |
| `WaitlistPromotionIT.java` | PostgreSQL `@SpringBootTest` proving cancellation automatically promotes the first FIFO waiter into a `CONFIRMED` reservation. |
| `reservations.ts`, `CalendarBookingView.tsx`, `MyReservationsPage.tsx` | Added waitlist API helpers, "Join FIFO waitlist" button on `RESERVATION_CONFLICT`, and "My Waitlist" card with leave/cancel action. |
