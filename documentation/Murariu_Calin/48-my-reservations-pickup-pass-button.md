# 48 — "Show pickup pass" Button in My Reservations (#322)

## Part 1: In simple terms

When a student arrives at the lab desk to pick up a confirmed booking, they need to show their rotating 6-digit / QR pickup pass (`/pickup-pass`, built in #284 and #318). Adding a **"Show pickup pass"** button directly on every `CONFIRMED` reservation row in **My reservations** gives students a one-click shortcut straight from their booking list without having to hunt for it in the sidebar.

---

## Part 2: Technical details

| File | Change |
|---|---|
| `frontend/src/pages/MyReservationsPage.tsx` | Added a `Show pickup pass` `Button` (`component={Link}`, `to="/pickup-pass"`, `variant="light"`, `color="teal"`, `IconQrcode`) rendered on rows where `r.status === 'CONFIRMED'`. |
| Verification | `cd frontend && npm run lint && npm run build` passes cleanly with zero ESLint or TypeScript errors. |
