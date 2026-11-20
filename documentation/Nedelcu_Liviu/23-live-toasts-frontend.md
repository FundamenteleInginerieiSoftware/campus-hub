# 23 — Live Notification Client & Toast Stack (Frontend)

**Issue:** Refs [#290](https://github.com/FundamenteleInginerieiSoftware/campus-hub/issues/290) (PR 2 of 3 for Live notifications)  
**Date:** 21 November 2026  
**Author:** Liviu Nedelcu (`@NliviuN`)

---

## Part 1: In Simple Terms

With the server-side SSE endpoint (`GET /api/v1/live/stream`) live on `main` (#317), the browser needs two pieces to show notifications as they arrive:

1. **A stream client (`live.ts`)** that opens the live connection using the user's normal login token, reads incoming text chunks even if a message is split across two network packets, and reconnects automatically after a network drop or the 10-minute server lifetime.
2. **A floating toast stack (`LiveToasts.tsx`)** that sits quietly in the bottom-right corner of the screen and displays up to 3 notification cards (newest on top), each closing automatically after 8 seconds or when the user clicks ✕.

Once Stefania mounts `<LiveToasts />` once in `AppLayout.tsx`, any logged-in student or admin receives live messages on whichever page they are viewing, with no reload.

---

## Part 2: Technical Details

### 1. Files Created
- [`frontend/src/api/live.ts`](../../frontend/src/api/live.ts):
  - `parseSseChunk(buffer)`: normalizes `\r\n`, splits on `\n\n`, keeps any incomplete trailing frame in `rest`, ignores `:` comment lines (`:connected`, `:hb`), and safely `JSON.parse`s `data:` lines into `LiveEvent`.
  - `connectLive()`: opens `GET /api/v1/live/stream` via `fetch` + `Authorization: Bearer <token>` (never putting tokens in URLs), reads `response.body.getReader()` with streaming `TextDecoder`, reconnects with backoff (`1s, 2s, 5s, 15s, 30s`), resets backoff after any stream that delivered bytes, stops permanently on `401` (logged out), and retries after `30s` on `409 LIVE_STREAM_LIMIT` (handles rapid reloads before the 20 s heartbeat drops stale tabs).
  - `subscribeLive(listener)`: maintains a `Set` of subscribers, isolating exceptions per listener.
- [`frontend/src/live/LiveToasts.tsx`](../../frontend/src/live/LiveToasts.tsx):
  - Connects on mount (`connectLive()` + `subscribeLive(...)`) and cleans up stream + all timers on unmount.
  - Displays up to 3 Mantine `Notification` cards bottom-right (`position: fixed`, `zIndex: 400`, `aria-live="polite"`), auto-dismissing each card after 8 seconds. Renders `null` when empty.

### 2. Integration Follow-up
- **Stefania (`AppLayout.tsx`):** Mount `<LiveToasts />` once inside the authenticated layout (one import + one JSX line).

### 3. Verification
- `cd frontend && npm run lint && npm run build`: 0 warnings, 0 errors, production bundle builds cleanly.
