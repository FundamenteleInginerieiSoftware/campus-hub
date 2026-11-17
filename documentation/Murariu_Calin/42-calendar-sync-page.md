# 42. Calendar Sync Page (Frontend)

**Author:** Călin Murariu (@Swiorx)  
**Date:** 2026-11-16  
**Branch:** `feat/frontend-calendar-sync`  
**Refs:** #289

---

## Part 1: In simple terms

Students often want to see their lab equipment bookings next to their university classes in Google Calendar, Apple Calendar, or Outlook. Instead of checking Campus Hub manually every time, the **Calendar sync** page lets a student generate one private `.ics` subscription address and paste it into their calendar app.

Because the server only keeps a one-way cryptographic fingerprint (SHA-256 hash) of the link rather than the link itself, the page guides the student through three clear states:

1. **No link yet:** explains what calendar sync does and provides a **Create calendar link** button.
2. **Just created:** displays the full subscription URL once in a read-only box with a **Copy** button, a **Download .ics** link, and a warning that the link is private and shown only now.
3. **Link exists (after reload):** confirms that `"A calendar link is active"` without exposing the secret URL again, and offers **Make a new link** (replacing the old one) or **Turn off** (revoking it immediately), each behind a confirmation dialog.

Below the status card, short instructions show where to paste the link in Google Calendar, Apple Calendar, and Outlook, along with a note that external calendar apps refresh on their own schedule.

---

## Part 2: Technical details

### Files Created & Modified

| File | Purpose |
|---|---|
| `frontend/src/api/calendarFeed.ts` | API client wrapper (`fetchCalendarFeed`, `createCalendarFeed`, `revokeCalendarFeed`) using `apiFetch` |
| `frontend/src/pages/CalendarSyncPage.tsx` | Three-state Calendar sync UI, Mantine `CopyButton`, `.ics` download link, confirmation modals, and subscribe guide |
| `frontend/src/App.tsx` | Registers `<Route path="calendar-sync" element={<CalendarSyncPage />} />` under authenticated student/admin routes |
| `frontend/src/layout/navigation.ts` | Adds `{ label: 'Calendar sync', path: '/calendar-sync', icon: IconCalendarShare }` under `"Mine"` in `STUDENT_MENU` |

### API Integration (`frontend/src/api/calendarFeed.ts`)

| Function | Endpoint | Request / Response |
|---|---|---|
| `fetchCalendarFeed()` | `GET /api/v1/reservations/me/calendar-feed` | Returns `Promise<{ enabled: boolean }>` (`CalendarFeedStatus`) |
| `createCalendarFeed()` | `POST /api/v1/reservations/me/calendar-feed` | Returns `Promise<{ path: string }>` (`CalendarFeedCreated`) |
| `revokeCalendarFeed()` | `DELETE /api/v1/reservations/me/calendar-feed` | Returns `Promise<void>` (`204 No Content`) |

### Page State & Security Behavior (`CalendarSyncPage.tsx`)

- **Ephemeral URL state:** `createdPath` lives strictly in React component state (never written to `localStorage` or `sessionStorage`). Reloading the page resets `createdPath` to `null` and queries `GET /api/v1/reservations/me/calendar-feed`, transitioning the view to State 3 (`"A calendar link is active"`).
- **Full subscription URL:** built dynamically as `` `${window.location.origin}${createdPath}` `` so it works both in local Vite dev (`localhost:5173` proxy) and in production behind CloudFront.
- **Confirmation modals:** both **Make a new link** and **Turn off** open a Mantine `Modal` (`confirmAction: 'replace' | 'revoke' | null`) warning that the existing link will immediately stop working.
- **Error & loading discipline:** every load and mutation clears `error` before calling the API, clears `loading` / `submitting` in `finally`, and renders errors through the shared `<ErrorAlert error={error} />` component. If the initial status load fails (`enabled === null`), a retry card is shown instead of falsely rendering State 1.

### Verification

- `cd frontend && npm run lint` → 0 warnings, 0 errors (`oxlint`).
- `cd frontend && npm run build` → `tsc -b && vite build` passed cleanly.
