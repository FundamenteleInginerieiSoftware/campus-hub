# Reservations desk: earliest bookings first, on every page (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-06
**Branch / PR:** `fix/frontend-desk-sort-asc` (issue #166 point 1, found by Calin)
**See also:** `26-reservations-desk-page.md` (the desk page), Calin's PR #172 (`sortDir` parameter)

---

## Part 1: In simple terms

1. **The desk shows bookings 20 at a time** (one page), with the earliest booking first, because that is the next student at the counter.
2. **The problem:** the server sent the bookings newest first, and the page only re-ordered the 20 it had received. With more than 20 bookings, the earliest ones ended up on the last page, not the first.
3. **What changed on the server:** Calin added an option to ask for "oldest first" (#172).
4. **The fix:** the desk now asks the server for "oldest first" and shows the rows in the order they come. The page's own re-ordering is gone.

**Result:** page 1 always holds the earliest bookings, however many there are. The page looks the same as before.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `frontend/src/api/reservationDesk.ts` | `searchReservations` always sends `sortDir=asc`; Javadoc no longer says "newest first" |
| `frontend/src/pages/ReservationsDeskPage.tsx` | Client-side `.sort(...)` removed, `setPageData(response)` directly; bug-fix comment at the change |

### Why

| Before | After |
|---|---|
| `GET /api/v1/admin/reservations` default = `startTime` descending | Same endpoint with `sortDir=asc` (Calin's #172, documented in `docs/api-contract.md` §3) |
| The page sorted only the 20 rows it received | The database sorts all rows before paging, so every page is in order |

`sortDir` is always `asc` (not an option in `DeskSearch`): the desk never needs another order, so an extra parameter would be unused code.

### How it was tested

| Check | Result |
|---|---|
| `cd frontend && npm ci && npm run lint && npm run build` | lint clean, build OK |
| Backend side | Calin's #172 tests cover `sortDir=asc|desc` and the 400 for a bad value |

No screenshots: the layout did not change, only the order of the rows.
