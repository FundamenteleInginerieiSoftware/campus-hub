# 48 — "Show pickup pass" Button in My Reservations (#322)

## Part 1: In simple terms

When a student arrives at the lab desk to pick up a confirmed booking, they need to show their rotating 6-digit / QR pickup pass (`/pickup-pass`, built in #284 and #318). Adding a **"Show pickup pass"** button directly on **today's** `CONFIRMED` reservation rows in **My reservations** gives students a one-click shortcut on the day of pickup without tempting them to show a desk code for bookings days in the future (where check-out would be refused).

---

## Part 2: Technical details

| File | Change |
|---|---|
| `frontend/src/pages/MyReservationsPage.tsx` | Added a `Show pickup pass` `Button` (`component={Link}`, `to="/pickup-pass"`, `variant="light"`, `color="teal"`, `IconQrcode`) rendered only when `r.status === 'CONFIRMED'` **and** the booking starts **today** in `Europe/Bucharest` (comparing `parseCampusLocalDateTime(r.startTime)` against `nowInBucharest()`, matching the desk check-out rule in `ReservationService.checkOutReservation`: `start - 15 min .. start + 30 min`). Switched the row actions `Group` to `wrap="wrap"` so the 5 buttons wrap cleanly on narrow screens. |
| Verification | `cd frontend && npm run lint && npm run build` passes cleanly with zero ESLint or TypeScript errors; verified visually on desktop (`1440px`) and narrow (`390px`) with today's `CONFIRMED` row (button shown), a future-day `CONFIRMED` row (button hidden), and a `RETURNED` row (button hidden). |
