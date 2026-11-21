# Live Notifications on Every Page (Vișan Ștefania)

**Role:** Member 4 (frontend lead)
**Date:** 2026-11-21
**Files:** `frontend/src/layout/AppLayout.tsx`, `docs/modules/frontend.md`
**PR:** #345 (Refs #290; Liviu's live notifications, his PRs #317, #339, #340)

---

## Part 1: In simple terms

**The problem:** Liviu built live notifications. When an admin action moves a student's booking (for example, a HIGH incident takes the oscilloscope out of use), the server pushes a message to the student's open tab. He also built the small cards that show the message in the bottom-right corner (`<LiveToasts />`). But a component does nothing until a page actually shows it, and the layout around every page is mine (frontend lead).

**What I did:** I put `<LiveToasts />` **once** in the layout that frames every page after login. It now works on any page the student is on, with no reload. One import and one line.

**Why exactly there:** the layout is only shown to a logged-in user. So the live connection opens right after login and closes by itself on logout. On the login and register pages there is no connection, and none is left open after logout.

---

## Part 2: Technical details

### The change

```tsx
import LiveToasts from '../live/LiveToasts.tsx'
...
      <AppShell.Main>
        <Outlet />
      </AppShell.Main>

      <LiveToasts />
    </AppShell>
```

### How the connection follows the login (Liviu's code, checked)

| Moment | What happens |
|---|---|
| Login | `RequireAuth` lets the user in, `AppLayout` renders, `LiveToasts` mounts → its `useEffect` calls `connectLive()` (`fetch` with `Authorization: Bearer`, the token never goes into the URL) and `subscribeLive(...)` |
| Moving between pages | Only the `<Outlet />` changes; `AppLayout` stays mounted, so the **same** stream stays open (no reconnect per page) |
| Logout | `user` becomes `null`, `AppLayout` returns `null`, `LiveToasts` unmounts → the cleanup calls `off()` and `stop()` (`AbortController.abort()`) and clears the 8 s timers |
| Token expired | the server answers `401`, `connectLive` stops retrying; normal API calls send the user to `/login` |
| Admin disables the user | #340 closes the user's streams; the tab reconnects, gets `401` and stops |

### Choices

| Option | Why not |
|---|---|
| Mount in `App.tsx` or `main.tsx` (outside the routes) | It would also run on `/login`. `connectLive()` returns early without a token, so it would **not** reconnect after login until a full reload |
| Mount in each page | One stream per page change, easy to forget on a new page, and the 5-streams-per-user limit (`409 LIVE_STREAM_LIMIT`) is reached faster |
| After `<AppShell.Main>` (chosen) | `LiveToasts` uses `position: fixed` (bottom-right, `zIndex: 400`), so its place in the tree does not change where it appears; next to the page content is the easiest place to find it |

**React StrictMode (dev only):** `main.tsx` uses `<StrictMode>`, which mounts every component twice in dev. The effect runs, its cleanup aborts the first stream, then it runs again. In the browser's Network tab you see one cancelled `/api/v1/live/stream` request and one open one. This is expected and does not happen in the prod build.

### How to test

1. `cd backend && ./gradlew bootRun`, then `cd frontend && npm run dev`.
2. Browser A (normal window): log in as student 1 and book a unit for tomorrow.
3. Browser B (incognito): log in as student 2 and report a **HIGH** incident on the same unit.
4. In browser A, with no reload, the card "Reservation Reassigned" appears bottom-right and closes after 8 s (or on its ✕).
5. Log out in browser A. In the Network tab, the `/api/v1/live/stream` request ends.

On AWS this works only after Matei adds the same push call in `SesNotificationService` (prod profile).

### Checks

`npm run lint` (oxlint) clean, `npm run build` (tsc + vite) OK. No new logic, so no new tests: the stream client and the cards are tested and reviewed in #339.
