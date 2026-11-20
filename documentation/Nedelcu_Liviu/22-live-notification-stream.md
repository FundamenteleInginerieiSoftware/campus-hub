# 22 — Live Notification Stream (Server-Sent Events Backend)

**Issue:** Refs [#290](https://github.com/FundamenteleInginerieiSoftware/campus-hub/issues/290) (PR 1 of 3 for Live notifications)  
**Date:** 20 November 2026  
**Author:** Liviu Nedelcu (`@NliviuN`)

---

## Part 1: In Simple Terms

Previously, when an automatic event happened in the background — such as the reassignment engine moving a student's booking to a substitute oscilloscope — the student only discovered it by checking e-mail or manually reloading "My reservations".

Instead of having every open browser tab ask the server *"anything new?"* every few seconds (polling), **Server-Sent Events (SSE)** works like **a phone call the server keeps open**:
- The browser opens one lightweight HTTP connection (`GET /api/v1/live/stream`) using the student's regular login token.
- Whenever the system has a notification for that student, the server speaks on that open line immediately, and the message arrives in under a second.
- Every 20 seconds, the server whispers a tiny invisible heartbeat (`:hb`) so intermediate proxies (like AWS CloudFront) keep the line open and closed tabs are noticed quickly.
- After 10 minutes, the server hangs up and the browser redials, ensuring expired logins or disabled accounts cannot keep listening indefinitely.

---

## Part 2: Technical Details

### 1. Files Created / Updated
- [`LiveEvent.java`](../../backend/src/main/java/com/campus/notification/web/LiveEvent.java): Immutable SSE payload record `LiveEvent(String type, String title, String message, LocalDateTime at)` with `type = "NOTIFICATION"`.
- [`LiveStreams.java`](../../backend/src/main/java/com/campus/notification/service/LiveStreams.java): `@Component` in-memory registry (`ConcurrentHashMap<Long, List<SseEmitter>>` with `CopyOnWriteArrayList` values) managing per-user open streams, `@Async send(...)`, `@Scheduled(fixedRate = 20_000) heartbeat()`, and thread-safe per-emitter `synchronized (emitter)` writes.
- [`LiveStreamController.java`](../../backend/src/main/java/com/campus/notification/web/LiveStreamController.java): `GET /api/v1/live/stream` (`produces = text/event-stream`), sets `Cache-Control: no-store`, resolves user via `CurrentUser.id()`.
- [`docs/api-contract.md`](../../docs/api-contract.md): Added the **Live updates (Liviu)** section.

### 2. Server Constants & Design Decisions
| Constant | Value | Why |
|---|---|---|
| `STREAM_LIFETIME` | `10 min` | Browser reconnects through the JWT filter, so expired tokens or disabled accounts lose live updates within 10 minutes |
| `HEARTBEAT_EVERY_MS` | `20_000 ms` | AWS CloudFront closes idle origin connections after 30 s; the `:hb` comment keeps live tabs open and detects silently closed tabs |
| `MAX_STREAMS_PER_USER` | `5` | Refusing the 6th tab with `409 LIVE_STREAM_LIMIT` avoids a reconnect kick-loop and stays under HTTP/1.1's 6-connection browser cap in dev |

### 3. Security & Architecture
- **No new `SecurityConfig` rule needed:** Clients open the stream using `fetch` + `Authorization: Bearer <token>` (never putting tokens in query strings). `LiveStreamIT` verified that `asyncDispatch` completes cleanly with `200 OK` and no `AccessDeniedException`.
- **Single-server scope (ADR-002):** Streams live in memory on the single EC2 app instance.
- **Non-blocking push:** `LiveStreams.send` is `@Async`, so a slow browser connection can never stall Calin's reassignment engine.

### 4. Tests
- [`LiveStreamsTest.java`](../../backend/src/test/java/com/campus/notification/service/LiveStreamsTest.java): 9 unit tests covering user isolation, multi-tab fan-out, dropping broken emitters without impacting healthy ones, per-user 5-stream limit (`LIVE_STREAM_LIMIT`), freeing slots on completion, heartbeat fan-out, no-op when offline, and non-interleaved concurrent writes.
- [`LiveStreamControllerTest.java`](../../backend/src/test/java/com/campus/notification/web/LiveStreamControllerTest.java): `@WebMvcTest` verifying `401 UNAUTHORIZED`, async SSE start with `Cache-Control: no-store`, and `409 LIVE_STREAM_LIMIT`.
- [`LiveStreamIT.java`](../../backend/src/test/java/com/campus/notification/LiveStreamIT.java): `@SpringBootTest` + `MockMvc` end-to-end check with real JWT auth, verifying `event:notification` delivery only to the logged-in user and clean `asyncDispatch` completion.
