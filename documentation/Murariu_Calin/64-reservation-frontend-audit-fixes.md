# 64. Reservation Frontend Action Guards, Late-Evening Calendar Fix & Cancel Bundle UI (#362 PR 3)

**Author:** Călin Murariu ([@Swiorx](https://github.com/Swiorx))  
**Issue:** Refs #362 (§3 Medium frontend, §5 Optional frontend)  
**Modules:** `frontend/` (Calin-owned reservation pages & API bindings)

---

## Part 1: In simple terms

During live browser testing, three rough edges showed up on the reservation screens:

1. **Buttons that could only fail:** In **My reservations**, the **Extend** and **Transfer** buttons still appeared on bundle kit items (which cannot be extended or transferred individually), and **Extend** also appeared on expired slots, `CONFIRMED` bookings whose 30-minute check-out deadline had passed, or bookings already at the 4-hour / 20:00 cap. Clicking them always hit an error banner.
2. **Late-evening "Today" header click:** After 19:30 Bucharest time (when no 30-minute slot remains today), clicking the "Today" day column header in the booking calendar silently jumped back to `09:00` in the past and showed a `BOOKING_IN_PAST` error.
3. **Whole-bundle cancellation:** Students who booked a multi-asset kit could only cancel one asset row at a time from **My reservations**; they now also get a **Cancel bundle** button with a confirmation modal that cancels all remaining confirmed items in one step.

*(Note: migrating `createReservation` in `frontend/src/api/reservations.ts` onto `apiFetch` with custom headers is blocked on Stefania's open PR #369 in `frontend/src/api/client.ts` and will follow once #369 lands on `main`.)*

---

## Part 2: Technical details

| File | Change |
|---|---|
| `frontend/src/api/reservations.ts` | Added `cancelBundle(bundleId: string)` calling `POST /api/v1/reservations/bundle/{bundleId}/cancel` via `apiFetch`. |
| `frontend/src/components/reservation/CancelBundleModal.tsx` | New confirmation modal mirroring `CancelReservationModal` that invokes `cancelBundle`, surfaces errors via `<ErrorAlert />`, and reports released item count back to `MyReservationsPage`. |
| `frontend/src/pages/MyReservationsPage.tsx` | Guarded `canExtend` on `!isBundleItem && !hasEndedInBucharest && !checkOutWindowClosed && hasExtensionRoom`; guarded `canTransfer` on `!isBundleItem`; added **Cancel bundle** action button + `<CancelBundleModal />` on cancellable bundle rows. |
| `frontend/src/components/CalendarBookingView.tsx` | Changed `findFirstValidSlotForDay` to return `string \| null` (returning `null` when no future slot `<= 19:30` remains today), disabled the Today column header (`cursor: not-allowed`, `opacity: 0.5`) when `firstTodaySlot === null`, and guarded `handlePrevWeek` to fall back to `initialSchedule` instead of `09:00` in the past. |

### Verification
- `cd frontend && npm run lint && npm run build` — **0 warnings, 0 errors**.
