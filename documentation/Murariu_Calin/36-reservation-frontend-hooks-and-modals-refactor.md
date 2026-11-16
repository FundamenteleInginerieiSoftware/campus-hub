# 36. Reservation Frontend Constants, Slot-Hold Hook, and Modal Refactor

**Author:** Călin Murariu (@Swiorx)  
**Date:** 2026-11-16  
**Branch:** `refactor/reservation-frontend-structure`  
**Refs:** #267

---

## Part 1: In simple terms

Over several feature PRs, `CalendarBookingView.tsx` (~1,100 lines) and `MyReservationsPage.tsx` (~940 lines) had grown large because they mixed page layout with reusable business-rule numbers (opening hours, 14-day booking window, 4-hour max duration, 2 active bookings), the 1-minute slot-hold timer logic, and three inline pop-up dialogs (Cancel, Extend, and Transfer).

This refactor splits those concerns into small, single-purpose pieces under `frontend/src/components/reservation/` without changing any behavior or user-facing flow:
1. One shared place for campus booking rule constants and date/time formatting.
2. One dedicated React hook (`useSlotHold`) for acquiring, timing down, mismatch-checking, and safely releasing the 1-minute slot lock.
3. Three focused modal components (`CancelReservationModal`, `ExtendReservationModal`, `TransferReservationModal`), each managing its own submit/error state only while open.
4. A cleaner `MyReservationsPage` with a shared `reloadReservations()` helper, no dead branches around the Cancel button, and concise comments.

---

## Part 2: Technical details

### 1. Shared booking rules & formatters
- **`frontend/src/components/reservation/bookingRules.ts`**:
  - Centralizes `OPEN_HOUR` (`8`), `CLOSE_HOUR` (`20`), `CLOSE_MINUTES` (`1200`), `OPENING_TIME` (`'08:00'`), `CLOSING_TIME` (`'20:00'`), `DEFAULT_START_TIME` (`'09:00'`), `LAST_START_MINUTES` (`19 * 60 + 30`), `SLOT_STEP_MINUTES` (`30`), `MAX_ADVANCE_DAYS` (`14`), `MAX_DURATION_MINUTES` (`240`), `DEFAULT_DURATION_MINUTES` (`60`), `MAX_ACTIVE_RESERVATIONS` (`2`), `HOLD_DURATION_SECONDS` (`60`), `BOOKING_DURATIONS`, `EXTENSION_STEP_MINUTES`, and `TIME_STEPS`.
  - Eliminates duplicated magic numbers across `CalendarBookingView.tsx`, `useSlotHold.ts`, `ExtendReservationModal.tsx`, and `MyReservationsPage.tsx`.
- **`frontend/src/components/reservation/reservationFormatters.ts`**:
  - Centralizes `MONTH_NAMES`, `formatSlotDate`, and `formatSlotTime` so `CalendarBookingView.tsx`, `MyReservationsPage.tsx`, and all three reservation modals format Bucharest local ISO strings consistently without browser-timezone skew.

### 2. `useSlotHold` hook (`frontend/src/components/reservation/useSlotHold.ts`)
- Extracted from `CalendarBookingView.tsx`:
  - Owns `activeHold`, `holdRemainingSeconds`, `holdLoading`, `holdError`, plus `activeHoldRef`, `deadlineRef`, `isMountedRef`, and `selectionRef` (`{ assetId, startIso, endIso }`).
  - Preserves wall-clock deadline countdown (`deadlineRef.current - Date.now()`) to resist background-tab timer throttling.
  - Preserves unmount cleanup (`releaseHold` in `useEffect` teardown) and in-flight selection/server mismatch release (`HOLD_MISMATCH` error when the server response disagrees with the requested slot).
  - Exposes `handleAcquireHold`, `handleReleaseHold`, `discardActiveHold` (releases any held slot in the background when changing slot, duration, or substitute unit), and `clearActiveHold` (resets state after `confirmHold` or `createReservation` completes).
- `CalendarBookingView.tsx` keeps all UI controls disabled while `holdLoading || bookingLoading` and clears stale `bookingError` when acquiring a hold.

### 3. Modal extraction & cleanup (`MyReservationsPage.tsx`)
- Extracted three conditionally mounted modal components (each owns its local form, loading, and `ErrorAlert` state while mounted):
  - `frontend/src/components/reservation/CancelReservationModal.tsx`
  - `frontend/src/components/reservation/ExtendReservationModal.tsx` (also encapsulates `computeExtensionOptions`)
  - `frontend/src/components/reservation/TransferReservationModal.tsx`
- Added `reloadReservations()` in `MyReservationsPage.tsx` to unify `setError(null); setLoading(true); setReloadToken((t) => t + 1)` across cancel, extend, transfer initiation, and transfer accept/decline handlers.
- Removed dead branches around the `Cancel` action button (`canCancel` already guarantees `!hasStartedInBucharest`) and shortened historical `"Bug fixed"` comment blocks into concise "why" comments.

### Verification
- `cd frontend && npm run lint` — 0 warnings, 0 errors (`oxlint`).
- `cd frontend && npm run build` — `tsc -b && vite build` succeeded cleanly.
