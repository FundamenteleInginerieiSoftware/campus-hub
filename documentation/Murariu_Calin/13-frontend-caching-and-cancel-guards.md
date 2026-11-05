# 13 — Frontend Asset Cache Deduplication & Cancellation UI Guards (Murariu Călin)

**Role:** Member 3, Reservations & Auto-Reassignment  
**Date:** 2026-11-05  
**Package:** `frontend/src/pages` & `frontend/src/components`  
**Branch / PR:** `fix/frontend-reservation-cache-and-cancel-guard` (PR #163, Issue #157)  

---

## Part 1: In simple terms

When a student switches between tabs (`All`, `Active`, `Past`) or pages on the **My Reservations** dashboard, the table displays equipment names and serial numbers next to each reservation. Previously:
1. **Redundant API Requests:** Even though `MyReservationsPage` stored resolved equipment details in `assetCache`, the data-fetching `useEffect` did not skip IDs that had already been fetched, sending duplicate `GET /api/v1/assets/{id}` requests on every tab or page change.
2. **Post-Start Cancel Button:** A `CONFIRMED` reservation whose start time had already passed (during the 30-minute grace window before the background job marks it `NO_SHOW`) still showed an active **Cancel** button to students, even though the backend rejects student cancellations once `now >= startTime` (`409 CANCELLATION_NOT_ALLOWED`).
3. **Stale Booking Error Banner:** In `CalendarBookingView`, if a booking submission failed (for example, due to a conflict) and the student selected a different time slot or navigated to another week, the old red error alert stayed on screen.

We fixed all three issues so the UI avoids unnecessary network calls, hides invalid actions proactively, and clears old errors as soon as the student picks a new slot.

---

## Part 2: Technical details

### Files Modified

| File | Change | Description |
|---|---|---|
| `frontend/src/pages/MyReservationsPage.tsx` | `fetchedAssetIdsRef` + `canStudentCancelReservation` | Tracks resolved asset IDs in a `useRef<Set<number>>` without adding `assetCache` to `useEffect` dependencies; hides `Cancel` once `startTime <= now` |
| `frontend/src/components/CalendarBookingView.tsx` | `setBookingError(null)` in slot/duration handlers | Clears stale submission errors whenever the user selects a new slot, changes duration, or navigates weeks |

### Key Implementation Details

1. **Cycle-Free Asset Cache Deduplication (`fetchedAssetIdsRef`):**
   - Adding `assetCache` directly to the `useEffect` dependency array would re-trigger `fetchMyReservations` every time a new asset is cached.
   - Instead, `fetchedAssetIdsRef = useRef<Set<number>>(new Set())` records each asset ID as soon as its `fetchAssetSummary(id)` promise succeeds, filtering `neededAssetIds` in O(1) time before firing `Promise.all`.
2. **Client-Side Start-Time Guard (`canStudentCancelReservation`):**
   - Parses the ISO-8601 local timestamp (`YYYY-MM-DDTHH:mm:ss`) into local date components and verifies `new Date() < startDate` in addition to `r.status === 'CONFIRMED'`.
3. **Automatic Error Reset (`CalendarBookingView`):**
   - Both `selectSlot(day, time)` (used by grid clicks, `handlePrevWeek`, `handleNextWeek`, and `handleToday`) and `handleDurationChange(val)` now call `setBookingError(null)`.
