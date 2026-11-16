# Live notifications (a message on screen the moment a booking is moved): design

**Status:** proposed, 2026-11-10 (written by Matei; Liviu confirms at the next meeting). **Owner:** Liviu (inside his `notification` module). **Needs from teammates:** one line from Matei (prod) and one line from Stefania (mounting the toasts).

**Split of the four new features (10 Nov):** each goes to the member who owns the data it reads: pickup pass → Matei, calendar feed → Calin, live notifications → Liviu (every notification already goes through his `NotificationService`), equipment reliability → Stefania. Proposed until the team agrees.
**Deadline:** before the feature freeze (Mon 7 Dec, W10). Must not slow down M1 (15 Nov) or the W7 deploy.

## 1. Goal

Today a student learns that the reassignment engine moved or cancelled their booking only from an e-mail (prod) or a log line (dev), or by reloading "My reservations". In the demo the Observer pattern does its work and nobody sees it.

With live notifications, the browser of the affected student shows a small message **within about a second**, on whatever page they are:

> **Reservation Reassigned** · Your reservation #12 has been reassigned to asset Oscilloscope DS1054Z #3 due to equipment maintenance.

Success = the demo runs with two browsers side by side: student 2 reports a broken oscilloscope in the left one, and the message pops up for student 1 in the right one, with no reload.

## 2. Decisions (proposed, Liviu confirms)

| # | Decision | Why |
|---|---|---|
| 1 | **Server-Sent Events** (one long HTTP response the server keeps writing to), not WebSocket | We only push server → browser. SSE is plain HTTP: it passes the Vite proxy and CloudFront, needs no new library, and Spring MVC has it built in (`SseEmitter`) |
| 2 | The browser opens the stream with `fetch` and the normal `Authorization` header, not with the browser's `EventSource` | `EventSource` can't send headers, so the 8 h token would have to go in the URL (and into logs). With `fetch` the stream is protected by the same JWT filter as every endpoint: **no new security rule** |
| 3 | v1 is **live only**: no inbox table, no unread counter | The push is the distinct part. A student who is offline still has the e-mail and "My reservations". An inbox is plain CRUD and can come later in the same module (§11) |
| 4 | Every notification the app already sends is pushed (everything that goes through `NotificationService.notifyUser`) | One hook covers the reassignment engine, overdue/no-show jobs, transfers and whatever is added later; no caller changes. Each `NotificationService` adapter (dev log, prod SES) hands the message to `LiveStreams` |
| 5 | The live stream lives **inside** the `notification` module, called directly; no Spring event, no new module | Sending the e-mail/log line and pushing to the browser are the same job ("tell user X"), so they belong together. An event would only be needed to cross a module boundary, and there is none. `messaging/` stays Matei's, for the SQS stretch goal only |
| 6 | Streams live in memory on the one app server | We run one EC2 instance (ADR-002). With two instances a notification could land on the server the browser is not connected to; written down as a limit |
| 7 | The server ends every stream after **10 minutes**; the browser reconnects | Each reconnect passes the JWT filter again, so an expired token or a disabled account loses the stream within 10 minutes |
| 8 | A **heartbeat** comment every 20 s | CloudFront closes an origin connection that is silent for 30 s (default); the heartbeat also lets the server notice closed tabs |
| 9 | At most **5** streams per user; a 6th is **refused**, the older ones are kept | Closing the oldest instead would make six open tabs kick each other out in a loop |

## 3. Ownership: what we build vs. what we ask for

| Who | What |
|---|---|
| Liviu | `notification/service/LiveStreams`; `notification/web/LiveStreamController`, `LiveEvent`; the push call in his `LoggingNotificationService` (dev/test); `frontend/src/api/live.ts`, `frontend/src/live/LiveToasts.tsx`; api-contract section; `docs/modules/notification.md` (or `common.md`, where the notification module is described today); personal docs |
| Matei | One call `liveStreams.send(...)` in his `SesNotificationService` (prod), in his own small PR after Liviu's PR 1. One `SecurityConfig` line if the async-dispatch test in §9 shows it is needed (Liviu reports it, Matei adds it) |
| Stefania | Mount `<LiveToasts />` once in `AppLayout.tsx` (one import + one line), or tell Liviu where she prefers it. She decides whether the look is OK or she wants `@mantine/notifications` instead (`package.json` is hers) |
| Calin | Nothing (`notification` is already in the ArchUnit module list). Optional, later: `MyReservationsPage` can call `subscribeLive(reload)` so the list refreshes itself when a message arrives |

## 4. Architecture

```
Calin's engine / jobs ──notifyUser()──► com.campus.notification (Liviu)
  service/  LoggingNotificationService (dev/test, Liviu)  ─┐
            SesNotificationService     (prod, Matei)      ─┴─► LiveStreams.send(userId, LiveEvent)   (@Async)
            LiveStreams              who is connected (userId -> open streams), send, heartbeat, limits
  web/      LiveStreamController     GET /api/v1/live/stream   (text/event-stream)
            LiveEvent                record sent to the browser
                                              │ one open HTTP response per browser tab
                                              ▼
frontend  src/api/live.ts            connect, parse, reconnect; subscribeLive(listener)
          src/live/LiveToasts.tsx    shows the last messages bottom-right
```

- **Dependencies:** none new. `LiveStreams` needs only a user id; the controller reads it from `CurrentUser` (`common.security`). Calin's ArchUnit rule 2 (notification stays independent of the feature modules) still holds; no AGENTS.md or ArchUnit change.
- The stream holds **no database connection** and no thread while idle (`SseEmitter` uses Servlet async); the JWT check at the start is the only database call.
- `LiveStreams.send` is `@Async`: writing to a slow browser must never hold up Calin's engine. It works because the adapters call it on another bean (Spring's proxy), never from inside `LiveStreams`. In the `test` profile `@Async` runs inline (`AsyncConfig`), so tests need no waiting.
- `app.messaging.sqs.enabled` (the SQS stretch goal) is unrelated and untouched.

## 5. Server rules (`LiveStreams`)

| Constant | Value | Why |
|---|---|---|
| `STREAM_LIFETIME` | 10 min | decision 7 |
| `HEARTBEAT_EVERY` | 20 s | decision 8 |
| `MAX_STREAMS_PER_USER` | 5 | decision 9 |

- `open(userId)`: a 6th stream for the same user → `409 LIVE_STREAM_LIMIT`. Otherwise register the stream; remove it again when it completes, times out or fails.
- `send(userId, event)`: write to every stream of that user and **only** that user. A write that fails (tab closed) removes that stream and nothing else.
- Two threads can write to one stream at the same moment (heartbeat timer + a notification): each write is done while holding that stream's lock.
- Heartbeat: an SSE comment line (`:hb`), which browsers ignore.
- A user with no open stream: `send` does nothing (decision 3).

## 6. Wire format (`docs/api-contract.md` gets a "Live updates (Liviu)" section)

### `GET /api/v1/live/stream` (any logged-in user)

`200`, `Content-Type: text/event-stream`, `Cache-Control: no-store`. The response stays open. `401` without a valid token (the usual JSON). `409 LIVE_STREAM_LIMIT` for a 6th stream.

One notification = one SSE event:

```
event: notification
data: {"type":"NOTIFICATION","title":"Reservation Reassigned","message":"Your reservation #12 has been reassigned to asset Oscilloscope DS1054Z #3 due to equipment maintenance.","at":"2026-11-16T10:42:07"}

```

`LiveEvent { type, title, message, at }`; `type` is always `NOTIFICATION` in v1 (the field leaves room for more kinds without breaking the client). `at` is campus time, ISO without offset, like every time in the API.

## 7. Frontend (Liviu's files; mounted by Stefania)

- `src/api/live.ts`
  - `connectLive(): () => void` opens the stream with `fetch` + `Authorization`, reads the body with a `ReadableStream` reader, splits it into events on blank lines, parses `data:` as JSON and hands each event to the listeners. Returns a function that closes it.
  - Reconnect after the stream ends: wait 1 s, 2 s, 5 s, 15 s, then every 30 s; back to 1 s after a stream that delivered at least one byte. **Stop** (no retry) on `401` (logged out; the normal API calls already send the user to the login page) and on `409 LIVE_STREAM_LIMIT`.
  - `subscribeLive(listener): () => void` for any page that wants to react (returns the unsubscribe function).
- `src/live/LiveToasts.tsx`: on mount `connectLive()` + `subscribeLive`; shows up to 3 Mantine `Notification` cards bottom-right, newest on top, each closes after 8 s or on its ✕; `aria-live="polite"` so screen readers announce them. Renders nothing when there are no messages.

## 8. Limits we accept (written in the personal doc and the report)

- A message sent while the browser is disconnected (reconnecting, laptop asleep) is not replayed.
- A disabled account keeps an already open stream for up to 10 minutes (it receives only its own notifications).
- One app server only (decision 6).
- Dev through the Vite proxy uses HTTP/1.1, where a browser allows 6 connections per site: more than 5 tabs of the app would wait. Prod (CloudFront, HTTP/2) has no such limit; the 5-stream cap keeps dev safe too.

## 9. Testing (AGENTS.md §5)

| Test | Kind | Covers |
|---|---|---|
| `LiveStreamsTest` | plain JUnit with real `SseEmitter`s and a recording stub | only the addressed user receives; a failing stream is dropped and the others still receive; 5 allowed, 6th refused with `LIVE_STREAM_LIMIT`; a completed stream frees its slot; heartbeat reaches every stream; no stream = no error |
| `LoggingNotificationServiceTest` (new tests) | Mockito (`LiveStreams`, fixed `Clock`) | `notifyUser` pushes once to that user; subject → `title`, body → `message`, `at` = campus now |
| `LiveStreamControllerTest` | `@WebMvcTest` | 401 without token; with a user: async started, `text/event-stream` |
| `LiveStreamIT` | `@SpringBootTest` + MockMvc | stream opened as student 1; the real `NotificationService.notifyUser` called for student 1 and for student 2 → the body contains only student 1's; after the stream completes there is **no** `AccessDeniedException` in the async dispatch (if there is: Liviu tells Matei, who adds `.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()` to `SecurityConfig` with a bug-fix comment) |

Manual: two browsers, the demo of §1; then through CloudFront after the W7 deploy (messages must arrive at once, and an idle stream must survive 2 minutes).

## 10. Delivery (one PR each, one personal doc each)

| PR | Branch | Content | Waits on |
|---|---|---|---|
| 1 | `feat/notification-live-stream` | `LiveStreams`, `LiveStreamController`, `LiveEvent`, heartbeat, tests, api-contract | – |
| 2 | `feat/frontend-live-toasts` | `live.ts`, `LiveToasts.tsx` (not mounted yet) | PR 1 |
| 3 | `feat/notification-live-push` | push in `LoggingNotificationService`, `LiveStreamIT` end to end | PR 1 |
| – | Matei's own small PR | the same push call in `SesNotificationService` (prod) | PR 1 |
| – | Stefania's own PR, or a line in PR 2 that she reviews | `<LiveToasts />` in `AppLayout.tsx` | PR 2 |

**Cut line:** PRs 1–3 + Stefania's mount line are the feature locally. Matei's SES line is needed only on AWS: if it is not in by 30 Nov, the live demo runs locally only.

## 11. Out of scope (YAGNI)

An inbox with history and an unread counter (a `notifications` table in the same module, if the team wants it), replay of missed messages, pushing other things than notifications (availability changes, desk updates), WebSocket, several app servers (would need SQS or Redis fan-out), browser/system push notifications when the tab is closed.
