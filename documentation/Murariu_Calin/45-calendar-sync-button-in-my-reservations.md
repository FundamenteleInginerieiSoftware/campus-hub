# 45 — Move Calendar Sync into My Reservations (#314)

## Part 1: In simple terms

Subscribing to the personal `.ics` calendar feed is a one-time setup, so having **Calendar sync** as a permanent item in the student sidebar menu competed with the everyday pages (`Catalog`, `Projects`, `My reservations`, `My incidents`, `Pickup pass`).

Now:
- **My reservations** has a **Sync to my calendar** button right in the page header next to **Book equipment**, which opens `/calendar-sync`.
- **Calendar sync** is removed from the student sidebar menu, keeping the menu cleaner.
- The `/calendar-sync` route and its top-bar title (`Calendar sync`) remain intact so any existing bookmark or link continues to work.

## Part 2: Technical details

### Changes

| File | Change |
|---|---|
| `frontend/src/pages/MyReservationsPage.tsx` | Added a `Sync to my calendar` button (`variant="default"`, `IconCalendarShare`, `component={Link} to="/calendar-sync"`) in the `PageHeader` actions group next to `Book equipment`. |
| `frontend/src/layout/navigation.ts` | Removed the `Calendar sync` item from `STUDENT_MENU` and added an explicit `pathname.startsWith('/calendar-sync')` branch in `titleFor` so the top bar still displays `Calendar sync` when navigating to `/calendar-sync`. |

### Verification

- `cd frontend && npm run lint` → 0 warnings, 0 errors.
- `cd frontend && npm run build` → TypeScript + Vite production build green.
