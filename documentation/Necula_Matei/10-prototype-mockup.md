# Prototype mockup (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-10-25
**Branch / PR:** `docs/prototype-mockup`
**See also:** `02-files-explained.md` (what each file does)

---

## Part 1: In simple terms

The backend isn't ready for a full demo yet, so we made a clickable mockup that shows how the finished app will work.

1. **Built one web page** that you open by double-clicking it. It needs no server, database or internet (fonts fall back to system ones).
2. **Filled it with fake data:** 14 pieces of equipment in 3 labs, a few students and one admin, and bookings for the coming days.
3. **Made the student side clickable:** browse the catalog, book a time slot on a day timeline, see "My reservations", cancel, report a broken item with a photo.
4. **Made the admin side clickable:** the reservations desk (hand items out, take them back), the incident queue, the reassignment log and the inventory.
5. **Showed the standout feature:** reporting a booked item moves its future bookings to a matching item, or cancels them if nothing is free, and the students get a notification.
6. **Added a demo script and a request log** at the bottom of the page: each click shows which API call the real app will make and what it answers, including the error codes.

**Result:** the team can show and discuss the whole flow now, before the backend and the React frontend exist. Everything resets when the page reloads.

---

## Part 2: Technical details

### Files

| File | What it is |
|---|---|
| `docs/prototype/campus-hub-prototype.html` | The mockup: one file, plain HTML + CSS + JavaScript, no libraries, no build |

### How it works

| Part | What and why |
|---|---|
| State | One in-memory object `D` (users, categories, rooms, assets, reservations, incidents, reassignment log, notifications, request log) from `seed()`. **Reset data** calls `seed()` again. Nothing is saved, so every demo starts from the same state |
| Clock | Fixed at **Mon 2 Nov 2026, 09:15**, like the fixed `Clock` in our tests, so "today", "past" and the checkout window always behave the same |
| Fake API | Every action goes through `api(method, path, fn)`, which logs the request the real frontend would send (`POST /api/v1/reservations`, …). Errors are shown in the ProblemDetail shape with a `code`, as in `docs/api-contract.md` §4 |
| Login | The 3 dev accounts from the contract (`admin@`, `student1@`, `student2@campus.test` / `password`). Wrong email and wrong password give the same `401 INVALID_CREDENTIALS`. Register always creates a `STUDENT` |
| Booking | Same check order as Calin's booking method: input (`INVALID_TIME_RANGE`, `BOOKING_IN_PAST`, `INVALID_DURATION`, `BOOKING_TOO_FAR_AHEAD`, `OUTSIDE_OPENING_HOURS`), then `ASSET_NOT_BOOKABLE`, then `RESERVATION_CONFLICT`, then `RESERVATION_LIMIT_REACHED` (admins skip it). Overlap = `start < otherEnd && end > otherStart` on `CONFIRMED`, `CHECKED_OUT`, `OVERDUE` |
| Incident | Stefania's flow: `OPEN` → asset `UNDER_MAINTENANCE` → `AssetDamagedEvent`. Admin: start repair / resolve / reject; the asset is `AVAILABLE` again only when no other open incident remains on it |
| Engine | Calin's Strategy order: `SameModelStrategy`, then `SameCategoryStrategy`, same lab room first; first free candidate wins, otherwise `FORCE_CANCELLED`. A moved booking stays `CONFIRMED` and remembers `originalAssetId` |
| Desk | My W9 page: today's pickups (check out from 15 min before to 30 min after the start, else `CHECKOUT_WINDOW_CLOSED`) and items out now (`OVERDUE` ones included) |
| Numbers | 2 active bookings, 14 days ahead, 30 min to 4 h, Mon–Fri 08:00–20:00: the 🟡 proposals in `docs/decisions.md` §5. If the team changes them, change `RULES` at the top of the script |

### Demo data

Rigol DS1054Z no. 1 is the demo asset. Reporting it shows all three engine outcomes at once:

| Booking on Rigol no. 1 | Why | Outcome |
|---|---|---|
| Student One, Tue 3 Nov 10:00–12:00 | Rigol no. 2 is free then | Moved, `SameModelStrategy` |
| Student Three, Wed 4 Nov 09:00–11:00 | Rigol no. 2 is booked, the Tektronix is free | Moved, `SameCategoryStrategy` |
| Student Four, Thu 5 Nov 14:00–16:00 | Rigol no. 2 and the Tektronix are both booked | `FORCE_CANCELLED` |

### How it connects to the team

- It is a **reference, not the frontend.** Stefania's React app in `frontend/` replaces it. Each owner can copy the screens, texts and error messages for their own pages (page list in her guide §5.8).
- Liviu: catalog, inventory and the error format. Calin: booking rules, My reservations, the engine and the log. Stefania: login, incidents, queue. Me: the reservations desk.
- If an endpoint or error code changes in `docs/api-contract.md`, the mockup can drift. It is only kept up to date if we decide to.

### How it was tested

| Check | Result |
|---|---|
| `node --check` on the page's script | Syntax OK |
| Headless Chromium (Playwright) clicking the whole demo script, desktop + phone width (390 px) in dark mode | Every step gives the expected toast; reporting Rigol no. 1 handles 3 bookings and Student One gets the "moved" notification; admin resolve, check out, return, duplicate serial (`409 SERIAL_NUMBER_TAKEN`) work; no console errors; no sideways scrolling after the fix below |
| Node script running the demo story on the page's functions | book 201 → same slot `409 RESERVATION_CONFLICT` → third item `409 RESERVATION_LIMIT_REACHED` → report Rigol no. 1: 201 and the three outcomes in the table above → booking it `409 ASSET_NOT_BOOKABLE` |

### Problems hit

| Problem | Fix |
|---|---|
| The first version was written for a hosted preview that adds `<!doctype>`, `<head>` and a CSS reset itself; opened as a file it would have lacked them | Added the doctype, `<head>` with charset + viewport, and the reset (`body { margin: 0 }`, `[hidden] { display: none !important }`) |
| At phone width the top bar (clock, bell, name, Sign out) didn't wrap and made the page 30 px wider than the screen | `.right` wraps now, and the name is hidden below 520 px (the role tag stays) |
| Selecting your own booking said "overlaps a booking" | It now says "you already booked this slot" |
